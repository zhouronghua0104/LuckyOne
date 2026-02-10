#include <jni.h>

#include <atomic>
#include <chrono>
#include <exception>
#include <memory>
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
using TraceIdPtr = std::shared_ptr<const std::string>;

TraceIdPtr g_active_trace_id;
std::atomic<bool> g_processing{false};
std::atomic<bool> g_release_in_progress{false};

constexpr std::chrono::milliseconds kAbortWaitTimeout(5000);
constexpr std::chrono::milliseconds kAbortPollInterval(10);
constexpr std::chrono::milliseconds kProcessingWaitTimeout(3000);
constexpr std::chrono::milliseconds kProcessingPollInterval(500);

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

TraceIdPtr LoadActiveTraceId() {
  return std::atomic_load_explicit(&g_active_trace_id,
                                   std::memory_order_acquire);
}

void StoreActiveTraceId(const TraceIdPtr &trace_id) {
  std::atomic_store_explicit(&g_active_trace_id, trace_id,
                             std::memory_order_release);
}

class TraceIdGuard {
 public:
  explicit TraceIdGuard(const std::string &trace_id) : active_(false) {
    if (g_release_in_progress.load(std::memory_order_acquire)) {
      return;
    }

    trace_id_ptr_ = std::make_shared<std::string>(trace_id);
    TraceIdPtr previous = LoadActiveTraceId();
    if (previous) {
      LOGD("trace id override while active: %s -> %s",
           previous->c_str(),
           trace_id.c_str());
    }
    StoreActiveTraceId(trace_id_ptr_);
    active_ = true;
  }

  ~TraceIdGuard() {
    if (!active_) {
      return;
    }
    TraceIdPtr expected = trace_id_ptr_;
    std::atomic_compare_exchange_strong_explicit(
        &g_active_trace_id, &expected, TraceIdPtr(),
        std::memory_order_acq_rel, std::memory_order_acquire);
  }

  bool active() const { return active_; }

 private:
  TraceIdPtr trace_id_ptr_;
  bool active_;
};

class ProcessingGuard {
 public:
  ProcessingGuard() {
    g_processing.store(true, std::memory_order_release);
  }

  ~ProcessingGuard() {
    g_processing.store(false, std::memory_order_release);
  }
};

bool AbortActiveTraceIfNeeded() {
  TraceIdPtr trace_id = LoadActiveTraceId();
  if (!trace_id) {
    return false;
  }

  LOGD("interrupting trace id: %s", trace_id->c_str());
  return llm_abort(trace_id->c_str());
}

bool WaitForTraceClear(std::chrono::milliseconds timeout) {
  const auto deadline = std::chrono::steady_clock::now() + timeout;
  while (LoadActiveTraceId()) {
    if (std::chrono::steady_clock::now() >= deadline) {
      return false;
    }
    std::this_thread::sleep_for(kAbortPollInterval);
  }
  return true;
}

bool WaitForProcessingRelease(std::chrono::milliseconds timeout,
                              std::chrono::milliseconds poll_interval) {
  if (!g_processing.load(std::memory_order_acquire)) {
    return true;
  }

  const auto deadline = std::chrono::steady_clock::now() + timeout;
  while (g_processing.load(std::memory_order_acquire)) {
    if (std::chrono::steady_clock::now() >= deadline) {
      return false;
    }
    std::this_thread::sleep_for(poll_interval);
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

  ProcessingGuard processing_guard;

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

  g_release_in_progress.store(true, std::memory_order_release);

  LOGD("begin llm release");
  const bool interrupted = AbortActiveTraceIfNeeded();
  if (interrupted || LoadActiveTraceId()) {
    if (!WaitForTraceClear(kAbortWaitTimeout)) {
      LOGD("timeout waiting for trace id to clear before release");
    }
  }

  if (!WaitForProcessingRelease(kProcessingWaitTimeout,
                                kProcessingPollInterval)) {
    LOGD("timeout waiting for inference processing release");
  }

  bool ret = llm_release(biz_id_chars.get());
  LOGD("end llm release");

  g_release_in_progress.store(false, std::memory_order_release);

  return static_cast<jboolean>(ret ? JNI_TRUE : JNI_FALSE);
}
