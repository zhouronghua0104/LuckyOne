#include <jni.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace sentinel {

struct Frame {
  int64_t index = 0;
  std::vector<uint8_t> data;
};

// Placeholder frame extractor. Replace with FFmpeg or media decoder.
class FrameExtractor {
 public:
  explicit FrameExtractor(std::string video_path)
      : video_path_(std::move(video_path)) {}

  bool Next(Frame& out_frame) {
    if (index_ >= kFakeFrameCount) {
      return false;
    }
    out_frame.index = index_++;
    out_frame.data.clear();
    return true;
  }

 private:
  static constexpr int64_t kFakeFrameCount = 120;
  std::string video_path_;
  int64_t index_ = 0;
};

// Placeholder inference engine. Replace with real model invocation.
class InferenceEngine {
 public:
  void Infer(const std::vector<Frame>& batch) {
    (void)batch;
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
};

class FrameWindowQueue {
 public:
  explicit FrameWindowQueue(size_t window_size)
      : window_size_(window_size == 0 ? 1 : window_size) {}

  void Push(Frame&& frame) {
    std::unique_lock<std::mutex> lock(mu_);
    cv_producer_.wait(lock, [&] { return !paused_ || stop_requested_; });
    if (stop_requested_) {
      return;
    }
    queue_.push(std::move(frame));
    if (queue_.size() >= window_size_) {
      paused_ = true;
      cv_consumer_.notify_one();
    }
  }

  std::vector<Frame> TakeWindow() {
    std::unique_lock<std::mutex> lock(mu_);
    cv_consumer_.wait(lock, [&] { return paused_ || done_ || stop_requested_; });
    if (stop_requested_) {
      return {};
    }
    if (!paused_ && done_ && queue_.empty()) {
      return {};
    }
    const size_t count = std::min(queue_.size(), window_size_);
    std::vector<Frame> batch;
    batch.reserve(count);
    for (size_t i = 0; i < count; ++i) {
      batch.push_back(std::move(queue_.front()));
      queue_.pop();
    }
    paused_ = false;
    cv_producer_.notify_one();
    return batch;
  }

  void MarkDone() {
    std::lock_guard<std::mutex> lock(mu_);
    done_ = true;
    if (!queue_.empty()) {
      paused_ = true;
      cv_consumer_.notify_one();
    } else {
      cv_consumer_.notify_all();
    }
  }

  void Stop() {
    std::lock_guard<std::mutex> lock(mu_);
    stop_requested_ = true;
    paused_ = false;
    done_ = true;
    cv_producer_.notify_all();
    cv_consumer_.notify_all();
  }

 private:
  size_t window_size_;
  std::mutex mu_;
  std::condition_variable cv_producer_;
  std::condition_variable cv_consumer_;
  std::queue<Frame> queue_;
  bool paused_ = false;
  bool done_ = false;
  bool stop_requested_ = false;
};

class SentinelPipeline {
 public:
  explicit SentinelPipeline(size_t window_size)
      : queue_(window_size) {}

  void Process(const std::string& video_path) {
    stop_requested_.store(false);
    producer_thread_ =
        std::thread(&SentinelPipeline::ProducerLoop, this, video_path);
    consumer_thread_ =
        std::thread(&SentinelPipeline::ConsumerLoop, this);
    producer_thread_.join();
    consumer_thread_.join();
  }

  void Stop() {
    stop_requested_.store(true);
    queue_.Stop();
  }

 private:
  void ProducerLoop(const std::string& video_path) {
    FrameExtractor extractor(video_path);
    Frame frame;
    while (!stop_requested_.load() && extractor.Next(frame)) {
      queue_.Push(Frame{frame});
    }
    queue_.MarkDone();
  }

  void ConsumerLoop() {
    InferenceEngine engine;
    while (!stop_requested_.load()) {
      std::vector<Frame> batch = queue_.TakeWindow();
      if (batch.empty()) {
        break;
      }
      engine.Infer(batch);
    }
  }

  std::atomic<bool> stop_requested_{false};
  FrameWindowQueue queue_;
  std::thread producer_thread_;
  std::thread consumer_thread_;
};

}  // namespace sentinel

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_sentinel_SentinelNative_nativeCreateProcessor(
    JNIEnv* /*env*/, jobject /*thiz*/, jint window_size) {
  auto* pipeline = new sentinel::SentinelPipeline(
      static_cast<size_t>(window_size));
  return reinterpret_cast<jlong>(pipeline);
}

JNIEXPORT void JNICALL
Java_com_example_sentinel_SentinelNative_nativeProcessVideo(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring video_path) {
  auto* pipeline =
      reinterpret_cast<sentinel::SentinelPipeline*>(handle);
  if (!pipeline || !video_path) {
    return;
  }
  const char* c_path = env->GetStringUTFChars(video_path, nullptr);
  if (!c_path) {
    return;
  }
  pipeline->Process(std::string(c_path));
  env->ReleaseStringUTFChars(video_path, c_path);
}

JNIEXPORT void JNICALL
Java_com_example_sentinel_SentinelNative_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* pipeline =
      reinterpret_cast<sentinel::SentinelPipeline*>(handle);
  if (pipeline) {
    pipeline->Stop();
  }
}

JNIEXPORT void JNICALL
Java_com_example_sentinel_SentinelNative_nativeRelease(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* pipeline =
      reinterpret_cast<sentinel::SentinelPipeline*>(handle);
  delete pipeline;
}

}  // extern "C"
