// See header for overview.

#include "android_ffmpeg_uri_io.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

// FFmpeg error macros are optional here; callbacks just return AVERROR(errno)/-1.
extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/error.h>
}

#define LOG_TAG "FFmpegUriIO"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static inline bool clearException(JNIEnv* env, const char* where) {
    if (!env->ExceptionCheck()) return false;
    LOGE("%s: Java exception occurred", where);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

static std::string JStringToString(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// 尝试拿到 Application(Context)，避免要求调用方显式传 Context（适配 “只给 Uri 字符串” 的场景）
// 如果你工程里已有全局 Context，请改成直接使用全局 Context（更稳更推荐）。
static jobject getApplicationContext(JNIEnv* env) {
    jclass atCls = env->FindClass("android/app/ActivityThread");
    if (!atCls) {
        clearException(env, "FindClass(ActivityThread)");
        return nullptr;
    }
    jmethodID curApp = env->GetStaticMethodID(
            atCls, "currentApplication", "()Landroid/app/Application;");
    if (!curApp) {
        clearException(env, "GetStaticMethodID(currentApplication)");
        env->DeleteLocalRef(atCls);
        return nullptr;
    }
    jobject app = env->CallStaticObjectMethod(atCls, curApp);
    clearException(env, "CallStaticObjectMethod(currentApplication)");
    env->DeleteLocalRef(atCls);
    return app; // local ref
}

void closePfd(JNIEnv* env, jobject pfd) {
    if (!env || !pfd) return;
    jclass pfdCls = env->GetObjectClass(pfd);
    if (!pfdCls) {
        clearException(env, "GetObjectClass(ParcelFileDescriptor)");
        return;
    }
    jmethodID closeMid = env->GetMethodID(pfdCls, "close", "()V");
    if (!closeMid) {
        clearException(env, "GetMethodID(ParcelFileDescriptor.close)");
        env->DeleteLocalRef(pfdCls);
        return;
    }
    env->CallVoidMethod(pfd, closeMid);
    clearException(env, "CallVoidMethod(ParcelFileDescriptor.close)");
    env->DeleteLocalRef(pfdCls);
}

jobject openUriReadFd(JNIEnv* env, jstring video_uri, int& outFd) {
    outFd = -1;
    if (!env || !video_uri) return nullptr;

    // 1) Context (Application)
    jobject ctx = getApplicationContext(env);
    if (!ctx) {
        LOGE("openUriReadFd: application context is null");
        return nullptr;
    }

    // 2) ContentResolver
    jclass ctxCls = env->GetObjectClass(ctx);
    if (!ctxCls) {
        clearException(env, "GetObjectClass(Context)");
        env->DeleteLocalRef(ctx);
        return nullptr;
    }
    jmethodID getCrMid = env->GetMethodID(
            ctxCls, "getContentResolver", "()Landroid/content/ContentResolver;");
    if (!getCrMid) {
        clearException(env, "GetMethodID(Context.getContentResolver)");
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }
    jobject cr = env->CallObjectMethod(ctx, getCrMid);
    if (clearException(env, "CallObjectMethod(getContentResolver)") || !cr) {
        LOGE("openUriReadFd: ContentResolver is null");
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }

    // 3) Uri.parse(video_uri)
    jclass uriCls = env->FindClass("android/net/Uri");
    if (!uriCls) {
        clearException(env, "FindClass(Uri)");
        env->DeleteLocalRef(cr);
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }
    jmethodID parseMid = env->GetStaticMethodID(
            uriCls, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    if (!parseMid) {
        clearException(env, "GetStaticMethodID(Uri.parse)");
        env->DeleteLocalRef(uriCls);
        env->DeleteLocalRef(cr);
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }
    jobject uriObj = env->CallStaticObjectMethod(uriCls, parseMid, video_uri);
    if (clearException(env, "CallStaticObjectMethod(Uri.parse)") || !uriObj) {
        LOGE("openUriReadFd: uriObj is null, uri=%s", JStringToString(env, video_uri).c_str());
        env->DeleteLocalRef(uriCls);
        env->DeleteLocalRef(cr);
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }

    // 4) ContentResolver.openFileDescriptor(uri, "r")
    jclass crCls = env->GetObjectClass(cr);
    if (!crCls) {
        clearException(env, "GetObjectClass(ContentResolver)");
        env->DeleteLocalRef(uriObj);
        env->DeleteLocalRef(uriCls);
        env->DeleteLocalRef(cr);
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }
    jmethodID ofdMid = env->GetMethodID(
            crCls,
            "openFileDescriptor",
            "(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;");
    if (!ofdMid) {
        clearException(env, "GetMethodID(ContentResolver.openFileDescriptor)");
        env->DeleteLocalRef(crCls);
        env->DeleteLocalRef(uriObj);
        env->DeleteLocalRef(uriCls);
        env->DeleteLocalRef(cr);
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }
    jstring mode = env->NewStringUTF("r");
    jobject pfd = env->CallObjectMethod(cr, ofdMid, uriObj, mode);
    env->DeleteLocalRef(mode);
    if (clearException(env, "CallObjectMethod(openFileDescriptor)") || !pfd) {
        LOGE("openUriReadFd: openFileDescriptor returned null");
        env->DeleteLocalRef(crCls);
        env->DeleteLocalRef(uriObj);
        env->DeleteLocalRef(uriCls);
        env->DeleteLocalRef(cr);
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }

    // 5) pfd.getFd()
    jclass pfdCls = env->GetObjectClass(pfd);
    if (!pfdCls) {
        clearException(env, "GetObjectClass(ParcelFileDescriptor)");
        closePfd(env, pfd);
        env->DeleteLocalRef(pfd);
        env->DeleteLocalRef(crCls);
        env->DeleteLocalRef(uriObj);
        env->DeleteLocalRef(uriCls);
        env->DeleteLocalRef(cr);
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }
    jmethodID getFdMid = env->GetMethodID(pfdCls, "getFd", "()I");
    if (!getFdMid) {
        clearException(env, "GetMethodID(ParcelFileDescriptor.getFd)");
        env->DeleteLocalRef(pfdCls);
        closePfd(env, pfd);
        env->DeleteLocalRef(pfd);
        env->DeleteLocalRef(crCls);
        env->DeleteLocalRef(uriObj);
        env->DeleteLocalRef(uriCls);
        env->DeleteLocalRef(cr);
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }
    jint javaFd = env->CallIntMethod(pfd, getFdMid);
    if (clearException(env, "CallIntMethod(ParcelFileDescriptor.getFd)") || javaFd < 0) {
        LOGE("openUriReadFd: getFd invalid: %d", (int)javaFd);
        env->DeleteLocalRef(pfdCls);
        closePfd(env, pfd);
        env->DeleteLocalRef(pfd);
        env->DeleteLocalRef(crCls);
        env->DeleteLocalRef(uriObj);
        env->DeleteLocalRef(uriCls);
        env->DeleteLocalRef(cr);
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }

    // 6) 复制一份 native fd（避免 pfd 关闭导致 fd 失效；同时保证 >=3，避免你的 fd<=0 判断误杀）
    int nativeFd = -1;
#ifdef F_DUPFD_CLOEXEC
    nativeFd = fcntl((int)javaFd, F_DUPFD_CLOEXEC, 3);
#endif
    if (nativeFd < 0) {
        nativeFd = dup((int)javaFd);
        if (nativeFd >= 0) {
            // best-effort CLOEXEC
            (void)fcntl(nativeFd, F_SETFD, FD_CLOEXEC);
        }
    }
    if (nativeFd < 0) {
        LOGE("openUriReadFd: dup/fcntl failed, errno=%d", errno);
        env->DeleteLocalRef(pfdCls);
        closePfd(env, pfd);
        env->DeleteLocalRef(pfd);
        env->DeleteLocalRef(crCls);
        env->DeleteLocalRef(uriObj);
        env->DeleteLocalRef(uriCls);
        env->DeleteLocalRef(cr);
        env->DeleteLocalRef(ctxCls);
        env->DeleteLocalRef(ctx);
        return nullptr;
    }
    outFd = nativeFd;

    // cleanup local refs (keep pfd for caller)
    env->DeleteLocalRef(pfdCls);
    env->DeleteLocalRef(crCls);
    env->DeleteLocalRef(uriObj);
    env->DeleteLocalRef(uriCls);
    env->DeleteLocalRef(cr);
    env->DeleteLocalRef(ctxCls);
    env->DeleteLocalRef(ctx);

    LOGI("openUriReadFd ok: outFd=%d", outFd);
    return pfd; // local ref; caller should close & delete local/global as needed
}

int readFn(void* opaque, uint8_t* buf, int buf_size) {
    if (!opaque || !buf || buf_size <= 0) return AVERROR(EINVAL);
    int fd = (int)(intptr_t)opaque;

    // 对于 content:// 对应的 fd，这里用 read() 即可；FFmpeg 会通过 seekFn 调整 offset
    ssize_t n = ::read(fd, buf, (size_t)buf_size);
    if (n == 0) return 0; // EOF
    if (n < 0) {
        int e = errno;
        // EINTR 重试
        if (e == EINTR) return readFn(opaque, buf, buf_size);
        return AVERROR(e);
    }
    return (int)n;
}

static int64_t fdSize(int fd) {
    struct stat st {};
    if (fstat(fd, &st) != 0) return -1;
    // 对部分 provider 可能不是常规文件，st_size 可能为 0；但多数媒体文件可用
    return (int64_t)st.st_size;
}

int64_t seekFn(void* opaque, int64_t offset, int whence) {
    if (!opaque) return AVERROR(EINVAL);
    int fd = (int)(intptr_t)opaque;

    if (whence == AVSEEK_SIZE) {
        int64_t size = fdSize(fd);
        if (size >= 0) return size;
        return AVERROR(errno);
    }

    // 去掉 AVSEEK_FORCE 等 flag
    int wh = whence & ~AVSEEK_FORCE;

    off64_t res = lseek64(fd, (off64_t)offset, wh);
    if (res < 0) {
        int e = errno;
        // 对不支持 seek 的 fd（例如 pipe），返回 -1 让 FFmpeg 走不可 seek 路径
        return AVERROR(e);
    }
    return (int64_t)res;
}

/*
用法示例（对应你给的片段）：

    int fd = -1;
    jobject pfd = openUriReadFd(env, video_uri, fd);
    if (pfd == nullptr || fd < 0) {
        LOGE("openUriReadFd fail");
        return env->NewStringUTF("");
    }

    uint8_t* ioBuf = (uint8_t*)av_malloc(32768);
    AVIOContext* avio = avio_alloc_context(
        ioBuf, 32768, 0,
        (void*)(intptr_t)fd,   // opaque: fd
        readFn, nullptr, seekFn);
    if (!avio) goto cleanup;

    AVFormatContext* fmt = avformat_alloc_context();
    fmt->pb = avio;
    fmt->flags |= AVFMT_FLAG_CUSTOM_IO;

    if (avformat_open_input(&fmt, nullptr, nullptr, nullptr) < 0) goto cleanup;

cleanup:
    if (fmt) avformat_close_input(&fmt);
    if (avio) {
        av_freep(&avio->buffer);
        avio_context_free(&avio);
    }
    if (fd >= 0) close(fd);
    if (pfd) { closePfd(env, pfd); env->DeleteLocalRef(pfd); }

*/

