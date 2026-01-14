// Cross-app shared video URI -> fd -> FFmpeg AVIO callbacks
// 用于：A 应用发送视频 Uri 字符串，B 应用通过 ContentResolver 打开 pfd，拿到 fd 后用 FFmpeg 解析。
//
// 说明：
// - openUriReadFd() 会通过 ContentResolver.openFileDescriptor(uri, "r") 打开 ParcelFileDescriptor
// - 为避免 Java pfd 关闭影响 native 侧读写，内部会把 Java fd 复制一份（fcntl(F_DUPFD_CLOEXEC, 3) / dup）
// - 返回的 pfd 需要调用方在 cleanup 中 close（或你也可以立刻 close，只要保留复制出的 outFd）
//
// 依赖：
// - Android framework 类：android.net.Uri / android.content.ContentResolver / android.os.ParcelFileDescriptor
// - NDK：unistd/fcntl 等
// - FFmpeg：avio_alloc_context 需要 readFn/seekFn 回调

#pragma once

#include <jni.h>
#include <cstdint>

// 获取共享视频文件 Uri 对应的文件句柄（ParcelFileDescriptor），并输出可用于 native 侧读取的 fd（复制后的 fd）
// 返回值：成功返回 ParcelFileDescriptor 对象（jobject），失败返回 nullptr
// 注意：outFd 可能为 0（理论上，如果 0/1/2 被关闭），调用方判断建议用 outFd < 0
jobject openUriReadFd(JNIEnv* env, jstring video_uri, int& outFd);

// FFmpeg AVIO read callback: opaque 里放 (void*)(intptr_t)fd
int readFn(void* opaque, uint8_t* buf, int buf_size);

// FFmpeg AVIO seek callback: opaque 里放 (void*)(intptr_t)fd
int64_t seekFn(void* opaque, int64_t offset, int whence);

// 安全关闭 ParcelFileDescriptor（忽略异常）
void closePfd(JNIEnv* env, jobject pfd);

