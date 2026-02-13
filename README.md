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

- Supports appending user-specified `libPath` to `java.library.path`.
- Calls `System.loadLibrary(...)` after path update.
- Falls back to absolute-path `System.load(...)`.
- Search paths include both new and old runtime folders.
- Handles `opencv_java4` and `SmartCockpit` in deterministic order.

## Suggested integration

Call the loader before any static initialization path that may touch Ark/OpenCV:

```java
String libPath = "/mnt/vendor/vr/llm_res/llm/libs"; // user-specified runtime lib path
VendorNativeLibLoader.appendToJavaLibraryPath(libPath);

boolean openCvOk = OpenCVLoader.initLocal();
if (openCvOk) {
    System.out.println("OpenCV loaded successfully");
} else {
    System.err.println("OpenCV initLocal returned false");
}

try {
    System.loadLibrary("SmartCockpit");
} catch (UnsatisfiedLinkError e) {
    System.err.println("Native code library failed to load. \n" + e);
    System.err.println("java.library.path: " + System.getProperty("java.library.path"));
}
```

Alternative one-shot call (includes fallback):

```java
boolean ok = VendorNativeLibLoader.ensureLoaded(getApplicationContext(), libPath);
if (!ok) {
    android.util.Log.e("Bootstrap", VendorNativeLibLoader.dumpSearchReport(getApplicationContext()));
}
```

Important:

- Avoid loading Ark/OpenCV from `companion object` static initializers.
- Prefer runtime initialization in `Application.onCreate()` or first Activity
  startup path to ensure fallback logic runs first.

## OpenCV AAR integration checklist (for "cannot find opencv_java4")

If your AAR already contains `opencv.jar` and `libopencv_java4.so` but runtime still
reports "library opencv_java4 not found", verify the following:

1. **AAR native layout is ABI-scoped**
   - `src/main/jniLibs/arm64-v8a/libopencv_java4.so`
   - `src/main/jniLibs/armeabi-v7a/libopencv_java4.so`
   - Include `libc++_shared.so` for the same ABIs (OpenCV often depends on it).
2. **APK ABI filters are compatible with bundled .so files**
   - If APK only builds `arm64-v8a`, but AAR only ships `armeabi-v7a`, load will fail.
3. **Do not rely on static initializer to verify success**
   - `OpenCVLoader.initLocal()` returns `boolean`; it does not guarantee an exception.
4. **Catch `UnsatisfiedLinkError`/`Throwable`, not just `Exception`**
   - Native load failures are `Error` subclasses.

Recommended runtime initialization:

```java
String libPath = "/mnt/vendor/vr/llm_res/llm/libs"; // optional external path
boolean loaded = VendorNativeLibLoader.ensureOpenCvLoaded(getApplicationContext(), libPath);
if (!loaded) {
    Log.e("Bootstrap", VendorNativeLibLoader.dumpOpenCvLoadReport(
            getApplicationContext(), libPath));
}
```

If you still need direct OpenCV API call, always check return value:

```java
boolean ok = OpenCVLoader.initLocal();
if (!ok) {
    Log.e("Bootstrap", "OpenCV initLocal failed");
}
```
