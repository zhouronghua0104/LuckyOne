// Reference implementation for ffmpeg fps-aligned extraction.
// This file mirrors the extraction logic shown in the request and focuses on
// aligning with ffmpeg CLI behavior for `-vf fps=3`.
// NOTE: This snippet expects project headers to provide FFmpeg types,
// HWDecodeContext, logging macros, and helper functions.

/**
 * Video frame extraction.
 * Notes: Extract frames from video and save to output_dir.
 * Interval frameInterval, start_time, end_time.
 *
 * API extraction version (aligns with ffmpeg CLI: -vf fps=3 timeline
 * sampling + drop/duplicate behavior).
 *
 * Key changes (relative to a "per-second seek + tolerance match" logic):
 * - Seek once at start_time, then decode forward (closer to CLI filter chain).
 * - Use best_effort_timestamp to compute frame time (avoid PTS jitter).
 * - Maintain a global fps grid (anchored at 0) and output one frame per target.
 * - Use "nearest" selection with round=near (ties go to later frame).
 * - EOF uses fps filter eof_action=round (no hold-last duplication).
 *
 * @param fmt_ctx Video AVFormatContext
 * @param output_dir Output directory
 * @param start_time Start time, <0 uses 0
 * @param end_time End time, <0 uses full duration
 * @param frameInterval Frame sampling interval (seconds)
 * @param clipRowCnt Crop rows
 * @param clipColumnCnt Crop columns
 * @param use_soft_decode true=software decode; false=try HW decode (fallback to SW)
 * @author zhouronghua
 * @time 2025/12/29 16:38
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
    // save frame counter
    int saved_seq = 0;
    int output_seq = 0;      // output index used for file naming (stable, no gaps due to skip)
    int saved_ok = 0;        // successfully saved outputs

    auto start_processing_time = std::chrono::high_resolution_clock::now();

    LOGI("Starting frame extraction (N frames per second) ");
    int crop_rows = clipRowCnt > 0 ? clipRowCnt : 1;
    int crop_cols = clipColumnCnt > 0 ? clipColumnCnt : 1;
    LOGI("clip rows=%d col=%d", crop_rows, crop_cols);

    auto ensure_out_frame = [&](AVFrame *&dst, int w, int h) -> int {
        if (!dst) dst = av_frame_alloc();
        if (!dst) return AVERROR(ENOMEM);
        // Use non-deprecated format; range is handled explicitly in swscale.
        if (dst->format != AV_PIX_FMT_YUV420P || dst->width != w || dst->height != h ||
            !dst->buf[0]) {
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
        int ret = avcodec_parameters_to_context(codec_ctx,
                                                fmt_ctx->streams[video_stream_index]->codecpar);
        if (ret < 0) return ret;
        return avcodec_open2(codec_ctx, codec, nullptr);
    };

    auto try_open_hw_decoder = [&](const char *decoder_name, AVHWDeviceType type) -> bool {
        const AVCodec *c = avcodec_find_decoder_by_name(decoder_name);
        if (!c) {
            LOGD("hw_decoder 1 find decoder %s fail", decoder_name);
            return false;
        }

        AVCodecContext *cctx = avcodec_alloc_context3(c);
        if (!cctx) {
            LOGD("hw_decoder 2 %s alloc context3  fail", decoder_name);
            return false;
        }

        int ret = avcodec_parameters_to_context(cctx,
                                                fmt_ctx->streams[video_stream_index]->codecpar);
        if (ret < 0) {
            LOGD("hw_decoder 3 %s parameters to context fail", decoder_name);
            avcodec_free_context(&cctx);
            return false;
        }

        ret = setup_hw_decode(cctx, c, &hw, type);
        if (ret < 0) {
            LOGD("hw_decoder 4 %s setup hw decode fail", decoder_name);
            avcodec_free_context(&cctx);
            return false;
        }

        ret = avcodec_open2(cctx, c, nullptr);
        if (ret < 0) {
            LOGD("hw_decoder 5 %s open2 fail", decoder_name);
            avcodec_free_context(&cctx);
            return false;
        }

        codec_ctx = cctx;
        codec = c;
        hw_enabled = true;
        LOGI("HW decode enabled: decoder=%s, hw_type=%d, hw_pix_fmt=%d", decoder_name, (int) type,
             (int) hw.hw_pix_fmt);
        return true;
    };

    auto try_open_hwaccel_with_default_decoder = [&](AVHWDeviceType type) -> bool {
        const AVCodec *c = avcodec_find_decoder(
                fmt_ctx->streams[video_stream_index]->codecpar->codec_id);
        if (!c) return false;
        AVCodecContext *cctx = avcodec_alloc_context3(c);
        if (!cctx) return false;
        int ret = avcodec_parameters_to_context(cctx,
                                                fmt_ctx->streams[video_stream_index]->codecpar);
        if (ret < 0) {
            avcodec_free_context(&cctx);
            return false;
        }
        ret = setup_hw_decode(cctx, c, &hw, type);
        if (ret < 0) {
            avcodec_free_context(&cctx);
            return false;
        }
        ret = avcodec_open2(cctx, c, nullptr);
        if (ret < 0) {
            avcodec_free_context(&cctx);
            return false;
        }
        codec_ctx = cctx;
        codec = c;
        hw_enabled = true;
        LOGI("HW accel enabled with default decoder: codec=%s, hw_type=%d, hw_pix_fmt=%d", c->name,
             (int) type, (int) hw.hw_pix_fmt);
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
            if (ret < 0) {
                result = "Error: Could not find stream info";
                cleanup_all();
                return result;
            }

            video_stream_index = -1;
            for (unsigned int i = 0; i < fmt_ctx->nb_streams; i++) {
                if (fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                    video_stream_index = (int) i;
                    break;
                }
            }
            if (video_stream_index == -1) {
                result = "Error: No video stream found";
                cleanup_all();
                return result;
            }

            AVStream *video_stream = fmt_ctx->streams[video_stream_index];
            const AVRational time_base = video_stream->time_base;

            const double duration_sec = (fmt_ctx->duration > 0) ? ((double) fmt_ctx->duration /
                                                                   AV_TIME_BASE) : -1.0;
            if (duration_sec > 0 && start_time >= duration_sec) {
                LOGI("start_time %.3f >= duration %.3f, skip", start_time, duration_sec);
                continue;
            }
            if (duration_sec > 0 && (end_time < 0.0 || end_time > duration_sec))
                end_time = duration_sec;
            if (end_time < 0.0) {
                // Unknown duration: keep original end_time (caller-provided); if still <0, abort.
                result = "Error: Unknown duration and invalid end_time";
                cleanup_all();
                return result;
            }

            // Decoder selection.
            if (!use_soft_decode) {
                // Prefer hardware decode when available.
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
                    // Other codecs: try MediaCodec first, then VAAPI as a fallback.
                    ok = try_open_hwaccel_with_default_decoder(AV_HWDEVICE_TYPE_MEDIACODEC) ||
                         try_open_hwaccel_with_default_decoder(AV_HWDEVICE_TYPE_VAAPI);
                }
                if (!ok) {
                    LOGE("HW decode unavailable; fallback to software decode");
                    hw_enabled = false;
                    if (codec_ctx) avcodec_free_context(&codec_ctx);
                    if (hw.hw_device_ctx) av_buffer_unref(&hw.hw_device_ctx);
                    ret = open_software_decoder();
                    if (ret < 0) {
                        result = "Error: Could not open software decoder: " + ff_err(ret);
                        cleanup_all();
                        return result;
                    }
                }
            } else {
                // Use software decoder as requested.
                ret = open_software_decoder();
                if (ret < 0) {
                    result = "Error: Could not open codec: " + ff_err(ret);
                    cleanup_all();
                    return result;
                }
            }

            packet = av_packet_alloc();
            frame = av_frame_alloc();
            if (!packet || !frame) {
                result = "Error: Could not allocate packet/frame";
                cleanup_all();
                return result;
            }

            // Interval and fps grid (align with ffmpeg fps filter).
            double interval = frameInterval;
            if (interval <= 0.0 || !std::isfinite(interval)) interval = 1.0 / 3.0;
            const double fps_value = 1.0 / interval;
            AVRational fps_q = av_d2q(fps_value, 100000);
            if (fps_q.num <= 0 || fps_q.den <= 0) {
                fps_q = av_make_q(3, 1);
            }
            const AVRational out_tb = av_inv_q(fps_q);
            const AVRounding round_mode = AV_ROUND_NEAR_INF; // round=near
            const int64_t start_us = (int64_t) llround(start_time * AV_TIME_BASE);
            const int64_t end_us = (int64_t) llround(end_time * AV_TIME_BASE);
            const int64_t start_out_pts = (start_us > 0)
                                          ? av_rescale_q_rnd(start_us, AV_TIME_BASE_Q, out_tb,
                                                            AV_ROUND_UP)
                                          : 0;
            const int64_t end_out_pts = av_rescale_q_rnd(end_us, AV_TIME_BASE_Q, out_tb,
                                                        AV_ROUND_DOWN);
            const int64_t interval_us = av_rescale_q_rnd(1, out_tb, AV_TIME_BASE_Q, round_mode);
            int64_t next_out_pts = start_out_pts;
            const int64_t expected_frames =
                    (end_out_pts >= start_out_pts) ? (end_out_pts - start_out_pts + 1) : 0;
            const int64_t kTimeEpsUs = 1;

            auto out_pts_to_us = [&](int64_t out_pts) -> int64_t {
                return av_rescale_q_rnd(out_pts, out_tb, AV_TIME_BASE_Q, round_mode);
            };
            auto us_to_seconds = [&](int64_t us) -> double {
                return (double) us / (double) AV_TIME_BASE;
            };

            LOGI("Time range: %.3f - %.3f, interval=%.6f, fps=%.6f, targets=%lld",
                 start_time, end_time, interval, fps_value, (long long) expected_frames);

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
            int64_t prev_ts_us = AV_NOPTS_VALUE;
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
                if (sws_ctx) {
                    sws_freeContext(sws_ctx);
                    sws_ctx = nullptr;
                }

                // Reset prev/cur window after seek so we don't mix old HW frames with SW frames.
                have_prev = false;
                prev_ts_us = AV_NOPTS_VALUE;

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
                sws_scale(sws_ctx, src->data, src->linesize, 0, src->height, dst->data,
                          dst->linesize);
                return 0;
            };

            // save frame to jpeg
            auto save_one = [&](AVFrame *to_save,
                                double target_time,
                                int out_index,
                                int &save_counter,
                                const char *suffix) -> bool {
                int saved = save_cropped_tiles(to_save,
                                               output_dir.c_str(),
                                               target_time,
                                               save_counter,
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
                if (next_out_pts > end_out_pts) return;

                // Hardware -> CPU transfer if needed.
                AVFrame *src = decoded;
                if (hw_enabled && hw.hw_pix_fmt != AV_PIX_FMT_NONE &&
                    decoded->format == hw.hw_pix_fmt) {
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
                if (ts == AV_NOPTS_VALUE) return;
                const int64_t cur_ts_us = av_rescale_q_rnd(ts, time_base, AV_TIME_BASE_Q,
                                                          round_mode);

                // Convert to current output buffer.
                if (convert_to_out(src, out_cur) < 0) return;

                if (!have_prev) {
                    // Initialize prev buffer with current.
                    std::swap(out_prev, out_cur);
                    prev_ts_us = cur_ts_us;
                    have_prev = true;

                    // If targets are <= first decoded frame time, output using the first frame.
                    while (next_out_pts <= end_out_pts) {
                        const int64_t target_us = out_pts_to_us(next_out_pts);
                        if (target_us + kTimeEpsUs < start_us) {
                            next_out_pts++;
                            continue;
                        }
                        if (target_us > prev_ts_us + kTimeEpsUs) break;
                        const double target_time = us_to_seconds(target_us);
                        const int out_index = output_seq++;
                        if (!save_one(out_prev, target_time, out_index, saved_seq, "")) {
                            // Retry with fallback suffix for debugging.
                            (void) save_one(out_prev, target_time, out_index, saved_seq,
                                            "_fallback");
                        }
                        next_out_pts++;
                    }
                    return;
                }

                if (cur_ts_us < prev_ts_us) {
                    // Non-monotonic timestamps can happen. Keep the later frame as "prev".
                    return;
                }

                auto abs_diff = [](int64_t a, int64_t b) -> int64_t {
                    return (a >= b) ? (a - b) : (b - a);
                };

                // We have a prev frame (prev_ts_us/out_prev) and a cur frame (cur_ts_us/out_cur).
                while (next_out_pts <= end_out_pts) {
                    const int64_t target_us = out_pts_to_us(next_out_pts);
                    if (target_us + kTimeEpsUs < start_us) {
                        next_out_pts++;
                        continue;
                    }
                    if (target_us > cur_ts_us + kTimeEpsUs) break;

                    // If target is earlier than our current window start, fallback to prev frame.
                    if (target_us + kTimeEpsUs < prev_ts_us) {
                        const double target_time = us_to_seconds(target_us);
                        const int out_index = output_seq++;
                        if (!save_one(out_prev, target_time, out_index, saved_seq, "")) {
                            (void) save_one(out_prev, target_time, out_index, saved_seq,
                                            "_fallback");
                        }
                        next_out_pts++;
                        continue;
                    }

                    // Choose closer frame between prev and cur (round=near, ties -> cur).
                    const int64_t d_prev = abs_diff(target_us, prev_ts_us);
                    const int64_t d_cur = abs_diff(cur_ts_us, target_us);
                    AVFrame *chosen = (d_cur <= d_prev) ? out_cur : out_prev;
                    const double target_time = us_to_seconds(target_us);
                    const int out_index = output_seq++;
                    if (!save_one(chosen, target_time, out_index, saved_seq, "")) {
                        // Fallback: try the other neighbor frame.
                        AVFrame *alt = (chosen == out_prev) ? out_cur : out_prev;
                        (void) save_one(alt, target_time, out_index, saved_seq, "_fallback");
                    }
                    next_out_pts++;
                }

                // Shift window: current becomes previous for next iteration.
                std::swap(out_prev, out_cur);
                prev_ts_us = cur_ts_us;
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
            while (next_out_pts <= end_out_pts && (ret = av_read_frame(fmt_ctx, packet)) >= 0) {
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
                        const double resume_sec = (next_out_pts <= end_out_pts)
                                ? std::max(start_time,
                                           us_to_seconds(
                                                   std::max<int64_t>(
                                                           start_us,
                                                           out_pts_to_us(next_out_pts) -
                                                           interval_us)))
                                : (have_prev
                                   ? std::max(start_time,
                                              us_to_seconds(std::max<int64_t>(start_us,
                                                                              prev_ts_us)))
                                   : start_time);
                        // FIX: hardware decode failed, fallback to software decode.
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
                            const double resume_sec = (next_out_pts <= end_out_pts)
                                    ? std::max(start_time,
                                               us_to_seconds(
                                                       std::max<int64_t>(
                                                               start_us,
                                                               out_pts_to_us(next_out_pts) -
                                                               interval_us)))
                                    : (have_prev
                                       ? std::max(start_time,
                                                  us_to_seconds(std::max<int64_t>(start_us,
                                                                                  prev_ts_us)))
                                       : start_time);
                            const int fr = fallback_to_software(resume_sec);
                            if (fr < 0) {
                                result =
                                        "Error: fallback to software decoder failed: " +
                                        ff_err(fr);
                                cleanup_all();
                                return result;
                            }
                            goto restart_decode;
                        }
                        break;
                    }
                    process_decoded_frame(frame);
                    av_frame_unref(frame);

                    if (next_out_pts > end_out_pts) break;
                }
                if (next_out_pts > end_out_pts) break;
            }

            // Flush decoder.
            if (next_out_pts <= end_out_pts) {
                avcodec_send_packet(codec_ctx, nullptr);
                while (next_out_pts <= end_out_pts) {
                    ret = avcodec_receive_frame(codec_ctx, frame);
                    if (ret == AVERROR_EOF || ret == AVERROR(EAGAIN)) break;
                    if (ret < 0) {
                        if (hw_enabled && !use_soft_decode && !did_runtime_fallback) {
                            did_runtime_fallback = true;
                            const double resume_sec = (next_out_pts <= end_out_pts)
                                    ? std::max(start_time,
                                               us_to_seconds(
                                                       std::max<int64_t>(
                                                               start_us,
                                                               out_pts_to_us(next_out_pts) -
                                                               interval_us)))
                                    : (have_prev
                                       ? std::max(start_time,
                                                  us_to_seconds(std::max<int64_t>(start_us,
                                                                                  prev_ts_us)))
                                       : start_time);
                            const int fr = fallback_to_software(resume_sec);
                            if (fr < 0) {
                                result = "Error: fallback to software decoder failed: " +
                                         ff_err(fr);
                                cleanup_all();
                                return result;
                            }
                            goto restart_decode;
                        }
                        break;
                    }
                    process_decoded_frame(frame);
                    av_frame_unref(frame);
                    if (next_out_pts > end_out_pts) break;
                }
            }

            // EOF handling: match fps filter default eof_action=round (no hold-last duplication).
            if (have_prev && next_out_pts <= end_out_pts) {
                const int64_t last_out_pts = av_rescale_q_rnd(prev_ts_us, AV_TIME_BASE_Q, out_tb,
                                                             round_mode);
                if (last_out_pts >= next_out_pts && last_out_pts <= end_out_pts) {
                    const int64_t target_us = out_pts_to_us(last_out_pts);
                    if (target_us + kTimeEpsUs >= start_us) {
                        const double target_time = us_to_seconds(target_us);
                        const int out_index = output_seq++;
                        if (!save_one(out_prev, target_time, out_index, saved_seq, "")) {
                            (void) save_one(out_prev, target_time, out_index, saved_seq,
                                            "_fallback");
                        }
                    }
                    next_out_pts = last_out_pts + 1;
                }
            }

            auto end_processing_time = std::chrono::high_resolution_clock::now();
            auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    end_processing_time - start_processing_time);
            double elapsed_seconds = duration_ms.count() / 1000.0;

            result = "Frame extraction completed!\n";
            result +=
                    "Time range: " + std::to_string(start_time) + " - " +
                    std::to_string(end_time) +
                    " seconds\n";
            result += "Expected frames: " + std::to_string((long long) expected_frames) + "\n";
            result += "Frames saved: " + std::to_string(saved_seq) + "\n";
            result += "Processing time: " + std::to_string(elapsed_seconds) + " seconds\n";
            result += "Output directory: " + output_dir;
            LOGI("Frame extraction completed. Extracted %lld frames from %.3f-%.3f second range in %.3f seconds",
                 (long long) expected_frames, start_time, end_time, elapsed_seconds);
        }
    } catch (...) {
        auto end_processing_time = std::chrono::high_resolution_clock::now();
        auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                end_processing_time - start_processing_time);
        double elapsed_seconds = duration_ms.count() / 1000.0;
        result = "Error: Exception occurred during frame extraction\n";
        result += "Processing time before error: " + std::to_string(elapsed_seconds) + " seconds\n";
        result += "Frames saved before error: " + std::to_string(saved_seq);
        LOGE("Exception during frame extraction after %.3f seconds", elapsed_seconds);
    }

    cleanup_all();
    return result;
}
