# Vendor runtime library path migration fixes

This branch adds targeted fixes for two crashes seen after moving runtime
libraries from:

- old: `/data/vendor_de/svw/llm/libs`
- new: `/mnt/vendor/vr/llm_res/llm/libs`

## 1) dumpsys crash in `SentryService.dump`

File:

- `app/src/main/java/com/crescent/myjnidemo/service/SentryService.kt`

Fix summary:

- Argument normalization no longer relies on unsafe array casts.
- Primitive arrays (`IntArray`, `LongArray`, etc.) are converted safely.
- This prevents `ClassCastException: int[] cannot be cast to Integer[]`.

## 2) Native library load fallback for vendor app namespace

File:

- `app/src/main/java/com/crescent/myjnidemo/runtime/VendorNativeLibLoader.java`

Fix summary:

- Tries `System.loadLibrary(...)` first.
- Falls back to absolute-path `System.load(...)`.
- Search paths include both new and old runtime folders.
- Handles `opencv_java4` and `SmartCockpit` in deterministic order.

## Suggested integration

Call the loader before any static initialization path that may touch Ark/OpenCV:

```kotlin
val ok = VendorNativeLibLoader.ensureLoaded(applicationContext)
if (!ok) {
    android.util.Log.e("Bootstrap", VendorNativeLibLoader.dumpSearchReport(applicationContext))
}
```

Important:

- Avoid loading Ark/OpenCV from `companion object` static initializers.
- Prefer runtime initialization in `Application.onCreate()` or first Activity
  startup path to ensure fallback logic runs first.
