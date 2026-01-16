#include <algorithm>
#include <cmath>
#include <chrono>
#include <string>
#include <utility>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/frame.h>
#include <libavutil/hwcontext.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

// ---- Project-provided utilities (expected to exist in your codebase) ----
// Keep these as declarations so this file can be dropped into your project.
struct HWDecodeContext {
  AVBufferRef *hw_device_ctx = nullptr;
  AVPixelFormat hw_pix_fmt = AV_PIX_FMT_NONE;
};

static std::string ff_err(int errnum);
static int setup_hw_decode(AVCodecContext *codec_ctx,
                           const AVCodec *decoder,
                           HWDecodeContext *hw,
                           AVHWDeviceType type);
static int save_cropped_tiles(AVFrame *frame,
                              const char *output_dir,
                              double target_time_sec,
                              int extracted_index,
                              int crop_rows,
                              int crop_cols,
                              const char *suffix);

// Logging macros expected in your project. Replace if needed.
#ifndef LOGI
#define LOGI(...) ((void)0)
#endif
#ifndef LOGD
#define LOGD(...) ((void)0)
#endif
#ifndef LOGE
#define LOGE(...) ((void)0)
#endif

static inline int64_t seconds_to_stream_pts(double sec, AVRational tb) {
  // Convert seconds to stream time_base PTS (round to nearest).
  // Use AV_TIME_BASE as an intermediate to preserve precision.
  const int64_t us = (int64_t) llround(sec * (double) AV_TIME_BASE);
  return av_rescale_q(us, AVRational{1, AV_TIME_BASE}, tb);
}

static inline double pts_to_seconds(int64_t pts, AVRational tb) {
  return (pts == AV_NOPTS_VALUE) ? NAN : (double) pts * av_q2d(tb);
}

static inline int64_t best_effort_ts(const AVFrame *f) {
  // Prefer best-effort timestamp to match ffmpeg/filters behavior.
  int64_t ts = av_frame_get_best_effort_timestamp(const_cast<AVFrame *>(f));
  if (ts == AV_NOPTS_VALUE) ts = f->pts;
  return ts;
}

static inline AVPixelFormat normalize_j_pix_fmt(AVPixelFormat fmt, int &is_full_range) {
  // swscale warns for deprecated YUVJ* formats. Map them to YUV* and carry range separately.
  switch (fmt) {
    case AV_PIX_FMT_YUVJ420P: is_full_range = 1; return AV_PIX_FMT_YUV420P;
    case AV_PIX_FMT_YUVJ422P: is_full_range = 1; return AV_PIX_FMT_YUV422P;
    case AV_PIX_FMT_YUVJ444P: is_full_range = 1; return AV_PIX_FMT_YUV444P;
    case AV_PIX_FMT_YUVJ440P: is_full_range = 1; return AV_PIX_FMT_YUV440P;
    default: return fmt;
  }
}

static inline const int *pick_sws_coeffs(AVColorSpace cs) {
  // Minimal mapping for common color spaces; fall back to default.
  switch (cs) {
    case AVCOL_SPC_BT709:      return sws_getCoefficients(SWS_CS_ITU709);
    case AVCOL_SPC_BT470BG:   return sws_getCoefficients(SWS_CS_ITU601);
    case AVCOL_SPC_SMPTE170M: return sws_getCoefficients(SWS_CS_SMPTE170M);
    case AVCOL_SPC_SMPTE240M: return sws_getCoefficients(SWS_CS_SMPTE240M);
    case AVCOL_SPC_BT2020_NCL:return sws_getCoefficients(SWS_CS_BT2020);
    default:                  return sws_getCoefficients(SWS_CS_DEFAULT);
  }
}

/**
 * API 抽帧版本（对齐 ffmpeg CLI: -vf fps=3 的“时间轴取样 + 丢帧/复制帧”行为）
 *
 * 核心变化（相对你原先“每秒 seek + 容差匹配”逻辑）：
 * - 只在 start_time 处 seek 一次，然后连续解码前进（更接近 CLI 滤镜链）
 * - 用 best_effort_timestamp 计算 frame_time，避免 pts 不稳定导致差异
 * - 维护 next_target_time（start + n*interval），每个 target 只输出 1 张
 * - 在 [prev, cur] 两帧时间区间内，用“就近”策略选择最接近 target 的那一帧（接近 fps filter 默认 round=near）
 * - EOF 时（若还有剩余 target），可选择重复最后一帧以补齐（更像 CFR 转换的 hold-last 行为）
 */
static std::string extract_video_with_time_range(
        AVFormatContext *fmt_ctx,
        std::string output_dir,
        const std::vector<std::pair<double, double>> &sectionList,
        double frameInterval,
        int clipRowCnt,
        int clipColumnCnt,
        bool use_soft_decode = false) {
  std::string result;

  AVCodecContext *codec_ctx = nullptr;
  const AVCodec *codec = nullptr;
  AVPacket *packet = nullptr;
  AVFrame *frame = nullptr;
  AVFrame *sw_frame = nullptr;

  // Two output frames to allow "prev vs cur" selection safely.
  AVFrame *out_cur = nullptr;
  AVFrame *out_prev = nullptr;
  SwsContext *sws_ctx = nullptr;

  HWDecodeContext hw{};
  bool hw_enabled = false;

  int video_stream_index = -1;
  int output_seq = 0;      // output index used for file naming (stable, no gaps due to skip)
  int saved_ok = 0;        // successfully saved outputs

  auto start_processing_time = std::chrono::high_resolution_clock::now();

  int crop_rows = clipRowCnt > 0 ? clipRowCnt : 1;
  int crop_cols = clipColumnCnt > 0 ? clipColumnCnt : 1;

  auto ensure_out_frame = [&](AVFrame *&dst, int w, int h) -> int {
    if (!dst) dst = av_frame_alloc();
    if (!dst) return AVERROR(ENOMEM);
    // Use non-deprecated format; range is handled explicitly in swscale.
    if (dst->format != AV_PIX_FMT_YUV420P || dst->width != w || dst->height != h || !dst->buf[0]) {
      av_frame_unref(dst);
      dst->format = AV_PIX_FMT_YUV420P;
      dst->width = w;
      dst->height = h;
      // This output is typically encoded to JPEG; mark full range to avoid "range incorrect" issues downstream.
      dst->color_range = AVCOL_RANGE_JPEG;
      int r = av_frame_get_buffer(dst, 32);
      if (r < 0) return r;
    }
    return 0;
  };

  auto open_software_decoder = [&]() -> int {
    codec = avcodec_find_decoder(fmt_ctx->streams[video_stream_index]->codecpar->codec_id);
    if (!codec) return AVERROR_DECODER_NOT_FOUND;
    codec_ctx = avcodec_alloc_context3(codec);
    if (!codec_ctx) return AVERROR(ENOMEM);
    int ret = avcodec_parameters_to_context(codec_ctx, fmt_ctx->streams[video_stream_index]->codecpar);
    if (ret < 0) return ret;
    return avcodec_open2(codec_ctx, codec, nullptr);
  };

  auto try_open_hw_decoder = [&](const char *decoder_name, AVHWDeviceType type) -> bool {
    const AVCodec *c = avcodec_find_decoder_by_name(decoder_name);
    if (!c) return false;
    AVCodecContext *cctx = avcodec_alloc_context3(c);
    if (!cctx) return false;
    int ret = avcodec_parameters_to_context(cctx, fmt_ctx->streams[video_stream_index]->codecpar);
    if (ret < 0) { avcodec_free_context(&cctx); return false; }
    ret = setup_hw_decode(cctx, c, &hw, type);
    if (ret < 0) { avcodec_free_context(&cctx); return false; }
    ret = avcodec_open2(cctx, c, nullptr);
    if (ret < 0) { avcodec_free_context(&cctx); return false; }
    codec_ctx = cctx;
    codec = c;
    hw_enabled = true;
    return true;
  };

  auto try_open_hwaccel_with_default_decoder = [&](AVHWDeviceType type) -> bool {
    const AVCodec *c = avcodec_find_decoder(fmt_ctx->streams[video_stream_index]->codecpar->codec_id);
    if (!c) return false;
    AVCodecContext *cctx = avcodec_alloc_context3(c);
    if (!cctx) return false;
    int ret = avcodec_parameters_to_context(cctx, fmt_ctx->streams[video_stream_index]->codecpar);
    if (ret < 0) { avcodec_free_context(&cctx); return false; }
    ret = setup_hw_decode(cctx, c, &hw, type);
    if (ret < 0) { avcodec_free_context(&cctx); return false; }
    ret = avcodec_open2(cctx, c, nullptr);
    if (ret < 0) { avcodec_free_context(&cctx); return false; }
    codec_ctx = cctx;
    codec = c;
    hw_enabled = true;
    return true;
  };

  auto cleanup_all = [&]() {
    if (sws_ctx) sws_freeContext(sws_ctx);
    if (out_cur) av_frame_free(&out_cur);
    if (out_prev) av_frame_free(&out_prev);
    if (sw_frame) av_frame_free(&sw_frame);
    if (frame) av_frame_free(&frame);
    if (packet) av_packet_free(&packet);
    if (codec_ctx) avcodec_free_context(&codec_ctx);
    if (hw.hw_device_ctx) av_buffer_unref(&hw.hw_device_ctx);
    if (fmt_ctx) avformat_close_input(&fmt_ctx);
  };

  try {
    for (const auto &section: sectionList) {
      double start_time = section.first;
      double end_time = section.second;

      if (start_time >= 0.0 && end_time >= 0.0 && end_time <= start_time) {
        result = "Error: Invalid time range. End time must be greater than start time and both must be non-negative.";
        cleanup_all();
        return result;
      }
      if (start_time < 0.0) start_time = 0.0;

      int ret = avformat_find_stream_info(fmt_ctx, nullptr);
      if (ret < 0) { result = "Error: Could not find stream info"; cleanup_all(); return result; }

      video_stream_index = -1;
      for (unsigned int i = 0; i < fmt_ctx->nb_streams; i++) {
        if (fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
          video_stream_index = (int) i;
          break;
        }
      }
      if (video_stream_index == -1) { result = "Error: No video stream found"; cleanup_all(); return result; }

      AVStream *video_stream = fmt_ctx->streams[video_stream_index];
      const AVRational time_base = video_stream->time_base;

      const double duration_sec = (fmt_ctx->duration > 0) ? ((double) fmt_ctx->duration / AV_TIME_BASE) : -1.0;
      if (duration_sec > 0 && start_time >= duration_sec) {
        LOGI("start_time %.3f >= duration %.3f, skip", start_time, duration_sec);
        continue;
      }
      if (duration_sec > 0 && (end_time < 0.0 || end_time > duration_sec)) end_time = duration_sec;
      if (end_time < 0.0) {
        // Unknown duration: keep original end_time (caller-provided); if still <0, abort.
        result = "Error: Unknown duration and invalid end_time";
        cleanup_all();
        return result;
      }

      // Decoder selection.
      if (!use_soft_decode) {
        const AVCodecID cid = video_stream->codecpar->codec_id;
        bool ok = false;
        if (cid == AV_CODEC_ID_H264) {
          ok = try_open_hw_decoder("h264_mediacodec", AV_HWDEVICE_TYPE_MEDIACODEC) ||
               try_open_hw_decoder("h264_cuvid", AV_HWDEVICE_TYPE_CUDA) ||
               try_open_hw_decoder("h264_qsv", AV_HWDEVICE_TYPE_QSV) ||
               try_open_hwaccel_with_default_decoder(AV_HWDEVICE_TYPE_VAAPI);
        } else if (cid == AV_CODEC_ID_HEVC) {
          ok = try_open_hw_decoder("hevc_mediacodec", AV_HWDEVICE_TYPE_MEDIACODEC) ||
               try_open_hw_decoder("hevc_cuvid", AV_HWDEVICE_TYPE_CUDA) ||
               try_open_hw_decoder("hevc_qsv", AV_HWDEVICE_TYPE_QSV) ||
               try_open_hwaccel_with_default_decoder(AV_HWDEVICE_TYPE_VAAPI);
        } else {
          ok = try_open_hwaccel_with_default_decoder(AV_HWDEVICE_TYPE_MEDIACODEC) ||
               try_open_hwaccel_with_default_decoder(AV_HWDEVICE_TYPE_VAAPI);
        }
        if (!ok) {
          LOGE("HW decode unavailable; fallback to software decode");
          hw_enabled = false;
          if (codec_ctx) avcodec_free_context(&codec_ctx);
          if (hw.hw_device_ctx) av_buffer_unref(&hw.hw_device_ctx);
          ret = open_software_decoder();
          if (ret < 0) { result = "Error: Could not open software decoder: " + ff_err(ret); cleanup_all(); return result; }
        }
      } else {
        ret = open_software_decoder();
        if (ret < 0) { result = "Error: Could not open codec: " + ff_err(ret); cleanup_all(); return result; }
      }

      packet = av_packet_alloc();
      frame = av_frame_alloc();
      if (!packet || !frame) { result = "Error: Could not allocate packet/frame"; cleanup_all(); return result; }

      // Interval and target timestamps.
      double interval = frameInterval;
      if (!(interval > 0.0) || !std::isfinite(interval)) interval = 1.0 / 3.0;

      // Build targets from start_time to end_time inclusive (like common CLI expectation).
      std::vector<double> targets;
      {
        const double eps = 1e-6;
        const int max_n = (int) std::floor((end_time - start_time) / interval + eps) + 1;
        targets.reserve(std::max(0, max_n));
        for (int i = 0; i < max_n; i++) {
          targets.push_back(start_time + (double) i * interval);
        }
        // Ensure last target is not beyond end_time by too much due to FP drift.
        while (!targets.empty() && targets.back() > end_time + 1e-3) targets.pop_back();
      }

      LOGI("Time range: %.3f - %.3f, interval=%.6f, targets=%d",
           start_time, end_time, interval, (int) targets.size());

      // Seek once to start_time (stream time_base).
      auto seek_and_flush = [&](double sec) -> int {
        const int64_t pts = seconds_to_stream_pts(sec, time_base);
        int r = av_seek_frame(fmt_ctx, video_stream_index, pts, AVSEEK_FLAG_BACKWARD);
        if (r < 0) return r;
        avcodec_flush_buffers(codec_ctx);
        return 0;
      };

      ret = seek_and_flush(start_time);
      if (ret < 0) {
        result = "Error: av_seek_frame failed: " + ff_err(ret);
        cleanup_all();
        return result;
      }

      // Decode forward and pick frames on a continuous timeline.
      int target_idx = 0;
      double prev_time = NAN;
      double cur_time = NAN;
      bool have_prev = false;
      bool did_runtime_fallback = false;

      auto fallback_to_software = [&](double resume_sec) -> int {
        // Runtime fallback: HW decoder opened successfully but failed during decode.
        // Close HW decoder, open software decoder, then seek near the next target and continue.
        LOGE("HW decode runtime failed -> fallback to software at %.3fs", resume_sec);

        if (codec_ctx) avcodec_free_context(&codec_ctx);
        if (hw.hw_device_ctx) av_buffer_unref(&hw.hw_device_ctx);
        hw.hw_pix_fmt = AV_PIX_FMT_NONE;
        hw_enabled = false;
        codec = nullptr;

        int r = open_software_decoder();
        if (r < 0) return r;

        // Re-seek to resume point to rebuild decoder state.
        r = seek_and_flush(resume_sec);
        if (r < 0) return r;

        // Pixel format/range may differ after fallback.
        if (sws_ctx) { sws_freeContext(sws_ctx); sws_ctx = nullptr; }

        // Reset prev/cur window after seek so we don't mix old HW frames with SW frames.
        have_prev = false;
        prev_time = NAN;
        cur_time = NAN;

        return 0;
      };

      auto convert_to_out = [&](AVFrame *src, AVFrame *&dst) -> int {
        // Derive src range; if the decoder didn't fill it, infer from deprecated YUVJ* formats.
        int src_full = (src->color_range == AVCOL_RANGE_JPEG) ? 1 : 0;
        AVPixelFormat src_fmt = normalize_j_pix_fmt((AVPixelFormat) src->format, src_full);
        const int src_range = src_full;
        const int dst_range = 1; // full-range output for JPEG pipeline
        const int *coeffs = pick_sws_coeffs(src->colorspace);

        sws_ctx = sws_getCachedContext(
                sws_ctx,
                src->width, src->height, src_fmt,
                src->width, src->height, AV_PIX_FMT_YUV420P,
                SWS_BILINEAR, nullptr, nullptr, nullptr
        );
        if (!sws_ctx) return AVERROR(EINVAL);

        // Explicitly set colorspace + range to avoid swscale "deprecated pixel format" warnings
        // and to keep behavior closer to ffmpeg CLI (which tracks range through filter graph).
        if (sws_setColorspaceDetails(sws_ctx,
                                    coeffs, src_range,
                                    coeffs, dst_range,
                                    0, 1 << 16, 1 << 16) < 0) {
          // Not fatal; continue with defaults.
        }

        int eo = ensure_out_frame(dst, src->width, src->height);
        if (eo < 0) return eo;
        sws_scale(sws_ctx, src->data, src->linesize, 0, src->height, dst->data, dst->linesize);
        return 0;
      };

      auto save_one = [&](AVFrame *to_save, double target_time, int out_index, const char *suffix) -> bool {
        int saved = save_cropped_tiles(to_save,
                                       output_dir.c_str(),
                                       target_time,
                                       out_index,
                                       crop_rows,
                                       crop_cols,
                                       suffix);
        if (saved > 0) {
          saved_ok += 1;
          return true;
        }
        return false;
      };

      auto process_decoded_frame = [&](AVFrame *decoded) {
        // Hardware -> CPU transfer if needed.
        AVFrame *src = decoded;
        if (hw_enabled && hw.hw_pix_fmt != AV_PIX_FMT_NONE && decoded->format == hw.hw_pix_fmt) {
          if (!sw_frame) sw_frame = av_frame_alloc();
          if (!sw_frame) return;
          av_frame_unref(sw_frame);
          int tr = av_hwframe_transfer_data(sw_frame, decoded, 0);
          if (tr < 0) {
            LOGE("av_hwframe_transfer_data failed: %s", ff_err(tr).c_str());
            return;
          }
          src = sw_frame;
        }

        const int64_t ts = best_effort_ts(src);
        cur_time = pts_to_seconds(ts, time_base);
        if (!std::isfinite(cur_time)) return;

        // Ignore frames strictly before start_time (allow small epsilon).
        if (cur_time + 1e-6 < start_time) return;

        // Convert to current output buffer.
        if (convert_to_out(src, out_cur) < 0) return;

        if (!have_prev) {
          // Initialize prev buffer with current.
          std::swap(out_prev, out_cur);
          prev_time = cur_time;
          have_prev = true;

          // Fallback: if targets are <= first decoded frame time, output using the first frame.
          // This prevents "middle index misalignment" when seek lands after the first target time.
          while (target_idx < (int) targets.size() && targets[target_idx] <= end_time + 1e-6) {
            const double t = targets[target_idx];
            if (t <= prev_time + 1e-6) {
              const int out_index = output_seq++;
              if (!save_one(out_prev, t, out_index, "")) {
                // retry with fallback suffix for debugging
                (void) save_one(out_prev, t, out_index, "_fallback");
              }
              target_idx++;
              continue;
            }
            break;
          }
          return;
        }

        // We have a prev frame (prev_time/out_prev) and a cur frame (cur_time/out_cur).
        if (cur_time < prev_time) {
          // Non-monotonic timestamps can happen. Keep the later of the two as "prev".
          std::swap(out_prev, out_cur);
          prev_time = cur_time;
          return;
        }

        // Emit targets using "near" selection, and fallback for targets earlier than prev_time.
        while (target_idx < (int) targets.size() && targets[target_idx] <= end_time + 1e-6) {
          const double t = targets[target_idx];
          const int out_index = output_seq++;

          // If target is earlier than our current window start (can happen due to seek/ts jitter),
          // fallback to prev frame (hold-first/hold-prev) instead of skipping.
          if (t + 1e-6 < prev_time) {
            if (!save_one(out_prev, t, out_index, "")) {
              (void) save_one(out_prev, t, out_index, "_fallback");
            }
            target_idx++;
            continue;
          }
          if (t > cur_time + 1e-6) break;

          // Choose closer frame between prev and cur.
          const double d_prev = std::fabs(t - prev_time);
          const double d_cur = std::fabs(cur_time - t);
          AVFrame *chosen = (d_cur < d_prev) ? out_cur : out_prev;
          if (!save_one(chosen, t, out_index, "")) {
            // Fallback: try the other neighbor frame.
            AVFrame *alt = (chosen == out_prev) ? out_cur : out_prev;
            (void) save_one(alt, t, out_index, "_fallback");
          }
          target_idx++;
        }

        // Shift window: current becomes previous for next iteration.
        std::swap(out_prev, out_cur);
        prev_time = cur_time;
      };

      auto send_packet_with_drain = [&](AVPacket *pkt) -> int {
        // Correct FFmpeg decode API usage: EAGAIN means "drain then retry", not a fatal error.
        int r = avcodec_send_packet(codec_ctx, pkt);
        if (r != AVERROR(EAGAIN)) return r;

        while (true) {
          int rr = avcodec_receive_frame(codec_ctx, frame);
          if (rr == AVERROR(EAGAIN) || rr == AVERROR_EOF) break;
          if (rr < 0) break;
          // We don't want to emit frames here, only drain to unblock send.
          av_frame_unref(frame);
        }
        return avcodec_send_packet(codec_ctx, pkt);
      };

      // Normal decode loop.
restart_decode:
      while ((ret = av_read_frame(fmt_ctx, packet)) >= 0) {
        if (packet->stream_index != video_stream_index) {
          av_packet_unref(packet);
          continue;
        }

        ret = send_packet_with_drain(packet);
        av_packet_unref(packet);
        if (ret < 0) {
          // Runtime HW failure (e.g., MediaCodec). Try once to fallback to software.
          if (hw_enabled && !use_soft_decode && !did_runtime_fallback) {
            did_runtime_fallback = true;
            const double resume_sec =
                    (target_idx < (int) targets.size())
                    ? std::max(start_time, targets[target_idx] - interval)
                    : std::max(start_time, prev_time);
            const int fr = fallback_to_software(resume_sec);
            if (fr < 0) {
              result = "Error: fallback to software decoder failed: " + ff_err(fr);
              cleanup_all();
              return result;
            }
            // Resume decoding from the new decoder + seek position.
            goto restart_decode;
          }
          continue;
        }

        while (true) {
          ret = avcodec_receive_frame(codec_ctx, frame);
          if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
          if (ret < 0) {
            if (hw_enabled && !use_soft_decode && !did_runtime_fallback) {
              did_runtime_fallback = true;
              const double resume_sec =
                      (target_idx < (int) targets.size())
                      ? std::max(start_time, targets[target_idx] - interval)
                      : std::max(start_time, prev_time);
              const int fr = fallback_to_software(resume_sec);
              if (fr < 0) {
                result = "Error: fallback to software decoder failed: " + ff_err(fr);
                cleanup_all();
                return result;
              }
              goto restart_decode;
            }
            break;
          }
          process_decoded_frame(frame);
          av_frame_unref(frame);

          if (target_idx >= (int) targets.size()) break;
        }
        if (target_idx >= (int) targets.size()) break;
      }

      // Flush decoder.
      avcodec_send_packet(codec_ctx, nullptr);
      while (true) {
        ret = avcodec_receive_frame(codec_ctx, frame);
        if (ret == AVERROR_EOF || ret == AVERROR(EAGAIN)) break;
        if (ret < 0) {
          if (hw_enabled && !use_soft_decode && !did_runtime_fallback) {
            did_runtime_fallback = true;
            const double resume_sec =
                    (target_idx < (int) targets.size())
                    ? std::max(start_time, targets[target_idx] - interval)
                    : std::max(start_time, prev_time);
            const int fr = fallback_to_software(resume_sec);
            if (fr < 0) {
              result = "Error: fallback to software decoder failed: " + ff_err(fr);
              cleanup_all();
              return result;
            }
            goto restart_decode;
          }
          break;
        }
        process_decoded_frame(frame);
        av_frame_unref(frame);
        if (target_idx >= (int) targets.size()) break;
      }

      // If still missing targets, optionally duplicate last frame (hold-last) like CFR conversion.
      if (have_prev) {
        while (target_idx < (int) targets.size()) {
          const double t = targets[target_idx];
          if (t > end_time + 1e-6) break;
          const int out_index = output_seq++;
          if (!save_one(out_prev, t, out_index, "")) {
            (void) save_one(out_prev, t, out_index, "_fallback");
          }
          target_idx++;
        }
      }

      auto end_processing_time = std::chrono::high_resolution_clock::now();
      auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_processing_time - start_processing_time);
      double elapsed_seconds = duration_ms.count() / 1000.0;

      result = "Frame extraction completed!\n";
      result += "Time range: " + std::to_string(start_time) + " - " + std::to_string(end_time) + " seconds\n";
      result += "Expected frames: " + std::to_string((int) targets.size()) + "\n";
      result += "Frames saved: " + std::to_string(saved_ok) + "\n";
      result += "Processing time: " + std::to_string(elapsed_seconds) + " seconds\n";
      result += "Output directory: " + output_dir;
    }
  } catch (...) {
    auto end_processing_time = std::chrono::high_resolution_clock::now();
    auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_processing_time - start_processing_time);
    double elapsed_seconds = duration_ms.count() / 1000.0;
    result = "Error: Exception occurred during frame extraction\n";
    result += "Processing time before error: " + std::to_string(elapsed_seconds) + " seconds\n";
    result += "Frames saved before error: " + std::to_string(saved_ok);
  }

  cleanup_all();
  return result;
}

