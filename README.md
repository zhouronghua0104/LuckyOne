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

try {
    // Load OpenCV first
    OpenCVLoader.initLocal();
    System.out.println("OpenCV loaded successfully");
} catch (Exception e) {
    System.err.println("OpenCV failed to load: " + e);
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
