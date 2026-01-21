#include <jni.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <exception>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#ifndef LOGD
#define LOGD(...) \
  do {            \
  } while (0)
#endif

struct Arg {
  int type = 0;
  std::string extra_val;
};

std::vector<Arg> convertArgsCpp(JNIEnv *env, jobjectArray args);
std::string llm_reason(const char *trace_id, const std::vector<Arg> &args);
bool llm_release(const char *biz_id);
bool llm_abort(const char *trace_id);

namespace {
std::atomic<uint64_t> g_active_trace_id_hash{0};
std::mutex g_state_mutex;
std::string g_active_trace_id_str;
bool g_release_in_progress = false;

constexpr std::chrono::milliseconds kAbortWaitTimeout(5000);
constexpr std::chrono::milliseconds kAbortPollInterval(10);

class JniStringUtfChars {
 public:
  JniStringUtfChars(JNIEnv *env, jstring value)
      : env_(env), value_(value), chars_(nullptr) {
    if (env_ != nullptr && value_ != nullptr) {
      chars_ = env_->GetStringUTFChars(value_, nullptr);
    }
  }

  ~JniStringUtfChars() {
    if (env_ != nullptr && value_ != nullptr && chars_ != nullptr) {
      env_->ReleaseStringUTFChars(value_, chars_);
    }
  }

  const char *get() const { return chars_; }
  bool valid() const { return chars_ != nullptr; }

 private:
  JNIEnv *env_;
  jstring value_;
  const char *chars_;
};

void ThrowRuntimeException(JNIEnv *env, const char *message) {
  if (env == nullptr || message == nullptr) {
    return;
  }
  jclass rte = env->FindClass("java/lang/RuntimeException");
  if (rte != nullptr) {
    env->ThrowNew(rte, message);
    env->DeleteLocalRef(rte);
  }
}

uint64_t Fnv1a64(const std::string &value) {
  uint64_t hash = 1469598103934665603ULL;
  for (unsigned char ch : value) {
    hash ^= ch;
    hash *= 1099511628211ULL;
  }
  return hash;
}

uint64_t ComputeTraceIdHash(const std::string &trace_id) {
  if (trace_id.empty()) {
    return 1;
  }

  errno = 0;
  char *end = nullptr;
  unsigned long long parsed =
      std::strtoull(trace_id.c_str(), &end, 10);
  if (errno == 0 && end != nullptr && *end == '\0') {
    uint64_t id = static_cast<uint64_t>(parsed);
    return id == 0 ? 1 : id;
  }

  uint64_t hash = Fnv1a64(trace_id);
  return hash == 0 ? 1 : hash;
}

class TraceIdGuard {
 public:
  explicit TraceIdGuard(const std::string &trace_id)
      : trace_id_(trace_id),
        hash_(ComputeTraceIdHash(trace_id)),
        active_(false) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (g_release_in_progress) {
      return;
    }
    if (!g_active_trace_id_str.empty()) {
      LOGD("trace id override while active: %s -> %s",
           g_active_trace_id_str.c_str(),
           trace_id_.c_str());
    }
    g_active_trace_id_str = trace_id_;
    g_active_trace_id_hash.store(hash_, std::memory_order_release);
    active_ = true;
  }

  ~TraceIdGuard() {
    if (!active_) {
      return;
    }
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (g_active_trace_id_str == trace_id_ &&
        g_active_trace_id_hash.load(std::memory_order_acquire) == hash_) {
      g_active_trace_id_str.clear();
      g_active_trace_id_hash.store(0, std::memory_order_release);
    }
  }

  bool active() const { return active_; }

 private:
  std::string trace_id_;
  uint64_t hash_;
  bool active_;
};

bool AbortActiveTraceIfNeeded() {
  if (g_active_trace_id_hash.load(std::memory_order_acquire) == 0) {
    return false;
  }

  std::string trace_id_copy;
  {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (g_active_trace_id_str.empty()) {
      return false;
    }
    trace_id_copy = g_active_trace_id_str;
  }

  LOGD("interrupting trace id: %s", trace_id_copy.c_str());
  return llm_abort(trace_id_copy.c_str());
}

bool WaitForTraceClear(std::chrono::milliseconds timeout) {
  const auto deadline = std::chrono::steady_clock::now() + timeout;
  while (g_active_trace_id_hash.load(std::memory_order_acquire) != 0) {
    if (std::chrono::steady_clock::now() >= deadline) {
      return false;
    }
    std::this_thread::sleep_for(kAbortPollInterval);
  }
  return true;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_modelbest_ark_ArkLLM_nativeReason(JNIEnv *env,
                                           jobject thiz,
                                           jstring trace_id,
                                           jobjectArray args) {
  if (trace_id == nullptr) {
    ThrowRuntimeException(env, "trace_id is null");
    return nullptr;
  }

  JniStringUtfChars trace_id_chars(env, trace_id);
  if (!trace_id_chars.valid()) {
    return nullptr;
  }

  std::vector<Arg> args_vec;
  try {
    args_vec = convertArgsCpp(env, args);
  } catch (const std::exception &ex) {
    ThrowRuntimeException(env, ex.what());
    return nullptr;
  }

  TraceIdGuard trace_guard(trace_id_chars.get());
  if (!trace_guard.active()) {
    ThrowRuntimeException(env, "model is releasing; inference aborted");
    return nullptr;
  }

  LOGD("begin llm reason --- %zu, %d, %d, %s",
       args_vec.size(),
       args_vec.empty() ? -1 : args_vec[0].type,
       args_vec.size() > 1 ? args_vec[1].type : -1,
       args_vec.size() > 1 ? args_vec[1].extra_val.c_str() : "");

  std::string ret;
  try {
    ret = llm_reason(trace_id_chars.get(), args_vec);
  } catch (const std::exception &ex) {
    ThrowRuntimeException(env, ex.what());
    return nullptr;
  }

  LOGD("end llm reason ret=%s", ret.c_str());
  return env->NewStringUTF(ret.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_modelbest_ark_ArkLLM_nativeRelease(JNIEnv *env,
                                            jobject thiz,
                                            jstring biz_id) {
  if (biz_id == nullptr) {
    ThrowRuntimeException(env, "biz_id is null");
    return JNI_FALSE;
  }

  JniStringUtfChars biz_id_chars(env, biz_id);
  if (!biz_id_chars.valid()) {
    return JNI_FALSE;
  }

  {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    g_release_in_progress = true;
  }

  LOGD("begin llm release");
  const bool interrupted = AbortActiveTraceIfNeeded();
  if (interrupted ||
      g_active_trace_id_hash.load(std::memory_order_acquire) != 0) {
    if (!WaitForTraceClear(kAbortWaitTimeout)) {
      LOGD("timeout waiting for trace id to clear before release");
    }
  }

  bool ret = llm_release(biz_id_chars.get());
  LOGD("end llm release");

  {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    g_release_in_progress = false;
  }

  return static_cast<jboolean>(ret ? JNI_TRUE : JNI_FALSE);
}
