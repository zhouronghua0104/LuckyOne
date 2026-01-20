import queue
import threading
from typing import Callable, Iterable, List, Optional, TypeVar


Frame = TypeVar("Frame")


def iter_video_frames(video_path: str) -> Iterable[Frame]:
    """
    Placeholder for frame extraction.
    Replace with OpenCV, FFmpeg bindings, or your decoder of choice.
    """
    raise NotImplementedError("Provide a real frame iterator")


def run_inference(frames: List[Frame]) -> None:
    """
    Placeholder for model inference.
    Replace with actual inference logic.
    """
    raise NotImplementedError("Provide a real inference function")


class FramePipeline:
    """
    Producer-consumer pipeline for "extract while infer".

    Producer:
      - Extracts frames and puts them into a bounded queue.
      - When the queue reaches window_size, it notifies the consumer
        and blocks until inference is done.

    Consumer:
      - Waits for a notification.
      - Drains up to window_size frames from the queue.
      - Runs a single inference batch.
      - Notifies producer to continue.
    """

    def __init__(
        self,
        video_path: str,
        window_size: int,
        frame_iter_fn: Callable[[str], Iterable[Frame]] = iter_video_frames,
        inference_fn: Callable[[List[Frame]], None] = run_inference,
    ) -> None:
        if window_size <= 0:
            raise ValueError("window_size must be > 0")

        self.video_path = video_path
        self.window_size = window_size
        self.frame_iter_fn = frame_iter_fn
        self.inference_fn = inference_fn

        self.frame_queue: "queue.Queue[Frame]" = queue.Queue(maxsize=window_size)
        self.batch_ready = threading.Event()
        self.batch_done = threading.Event()
        self.stop_event = threading.Event()

        self._producer_thread: Optional[threading.Thread] = None
        self._consumer_thread: Optional[threading.Thread] = None

    def start(self) -> None:
        self._consumer_thread = threading.Thread(
            target=self._inference_worker, name="inference-worker"
        )
        self._producer_thread = threading.Thread(
            target=self._producer_worker, name="frame-producer"
        )

        self._consumer_thread.start()
        self._producer_thread.start()

        self._producer_thread.join()
        self._consumer_thread.join()

    def _producer_worker(self) -> None:
        for frame in self.frame_iter_fn(self.video_path):
            self.frame_queue.put(frame)
            if self.frame_queue.qsize() >= self.window_size:
                self._notify_inference_and_wait()

        if not self.frame_queue.empty():
            self._notify_inference_and_wait()

        self.stop_event.set()
        self.batch_ready.set()  # wake consumer to exit

    def _notify_inference_and_wait(self) -> None:
        self.batch_done.clear()
        self.batch_ready.set()
        self.batch_done.wait()

    def _inference_worker(self) -> None:
        while True:
            self.batch_ready.wait()
            self.batch_ready.clear()

            if self.stop_event.is_set() and self.frame_queue.empty():
                return

            batch = self._drain_batch()
            if batch:
                self.inference_fn(batch)

            self.batch_done.set()

    def _drain_batch(self) -> List[Frame]:
        batch: List[Frame] = []
        batch_size = min(self.window_size, self.frame_queue.qsize())
        for _ in range(batch_size):
            batch.append(self.frame_queue.get())
        return batch


def main() -> None:
    # Example usage:
    # pipeline = FramePipeline("path/to/video.mp4", window_size=16,
    #                          frame_iter_fn=iter_video_frames,
    #                          inference_fn=run_inference)
    # pipeline.start()
    pass


if __name__ == "__main__":
    main()
