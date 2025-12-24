# Android 本地视频全屏循环播放 Activity（Media3 / ExoPlayer）

## 功能
- 全屏播放（沉浸式隐藏状态栏/导航栏）
- 循环播放（单个视频循环）
- 播放本地视频文件（`file://` / `content://` / `res/raw`）
- 支持 mp4 等 Media3/ExoPlayer 支持的多种格式

## 直接运行（默认示例）
项目默认会尝试播放 `app/src/main/res/raw/sample.mp4`。

目前仓库里放了一个 **空的占位文件** `sample.mp4`，请你替换成真实视频（同名覆盖即可）。

## 传入本地视频 Uri 播放
`VideoPlayerActivity` 支持通过 Intent 传入 Uri：

```kotlin
val uri: Uri = /* file:// 或 content:// */
startActivity(VideoPlayerActivity.intent(this, uri))
```

对应 extra key：`VideoPlayerActivity.EXTRA_VIDEO_URI`

> 如果你传 `content://`，通常需要授予读取权限（本实现已添加 `FLAG_GRANT_READ_URI_PERMISSION`）。

