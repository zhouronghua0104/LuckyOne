#pragma once

#include <condition_variable>
#include <cstddef>
#include <functional>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <vector>

namespace sentinel {

struct Frame {
  int index = 0;
};

using FrameProvider = std::function<bool(Frame& frame)>;
using InferenceFn = std::function<void(const std::vector<Frame>& batch)>;

class FramePipeline {
 public:
  FramePipeline(std::string video_path,
                std::size_t window_size,
                FrameProvider frame_provider,
                InferenceFn inference_fn);

  void Start();

 private:
  void ProducerWorker();
  void InferenceWorker();
  void NotifyInferenceAndWait(std::unique_lock<std::mutex>& lock);
  std::vector<Frame> DrainBatchLocked();

  std::string video_path_;
  std::size_t window_size_;
  FrameProvider frame_provider_;
  InferenceFn inference_fn_;

  std::queue<Frame> frame_queue_;
  std::mutex mutex_;
  std::condition_variable cv_batch_ready_;
  std::condition_variable cv_batch_done_;
  bool batch_ready_ = false;
  bool batch_done_ = false;
  bool stop_ = false;

  std::thread producer_thread_;
  std::thread consumer_thread_;
};

}  // namespace sentinel
