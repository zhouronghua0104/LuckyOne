#include "sentinel_pipeline.h"

#include <algorithm>
#include <stdexcept>

namespace sentinel {

FramePipeline::FramePipeline(std::string video_path,
                             std::size_t window_size,
                             FrameProvider frame_provider,
                             InferenceFn inference_fn)
    : video_path_(std::move(video_path)),
      window_size_(window_size),
      frame_provider_(std::move(frame_provider)),
      inference_fn_(std::move(inference_fn)) {
  if (window_size_ == 0) {
    throw std::invalid_argument("window_size must be > 0");
  }
  if (!frame_provider_) {
    throw std::invalid_argument("frame_provider must be set");
  }
  if (!inference_fn_) {
    throw std::invalid_argument("inference_fn must be set");
  }
}

void FramePipeline::Start() {
  consumer_thread_ = std::thread(&FramePipeline::InferenceWorker, this);
  producer_thread_ = std::thread(&FramePipeline::ProducerWorker, this);

  producer_thread_.join();
  consumer_thread_.join();
}

void FramePipeline::ProducerWorker() {
  Frame frame;
  while (frame_provider_(frame)) {
    std::unique_lock<std::mutex> lock(mutex_);
    frame_queue_.push(frame);
    if (frame_queue_.size() >= window_size_) {
      NotifyInferenceAndWait(lock);
    }
  }

  {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!frame_queue_.empty()) {
      NotifyInferenceAndWait(lock);
    }
    stop_ = true;
    cv_batch_ready_.notify_one();
  }
}

void FramePipeline::InferenceWorker() {
  while (true) {
    std::unique_lock<std::mutex> lock(mutex_);
    cv_batch_ready_.wait(lock, [this] { return batch_ready_ || stop_; });

    if (!batch_ready_ && stop_) {
      return;
    }

    batch_ready_ = false;
    std::vector<Frame> batch = DrainBatchLocked();
    lock.unlock();

    if (!batch.empty()) {
      inference_fn_(batch);
    }

    lock.lock();
    batch_done_ = true;
    cv_batch_done_.notify_one();
  }
}

void FramePipeline::NotifyInferenceAndWait(std::unique_lock<std::mutex>& lock) {
  batch_done_ = false;
  batch_ready_ = true;
  cv_batch_ready_.notify_one();
  cv_batch_done_.wait(lock, [this] { return batch_done_; });
}

std::vector<Frame> FramePipeline::DrainBatchLocked() {
  std::vector<Frame> batch;
  const std::size_t batch_size =
      std::min(window_size_, frame_queue_.size());
  batch.reserve(batch_size);

  for (std::size_t i = 0; i < batch_size; ++i) {
    batch.push_back(frame_queue_.front());
    frame_queue_.pop();
  }

  return batch;
}

}  // namespace sentinel
