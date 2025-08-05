# Fix for OpenCV Library Loading Issue in Android APK

## Problem Description
When an Android APK containing OpenCV native libraries is placed in `/vendor/app/`, the following error occurs:
```
Load libopencv_java4.so using class loader ns vendor-clns-9 (caller=/vendor/app/myjnidemo/app-debug.apk!classes6.dex): dlopen failed: library "libopencv_java4.so" not found
Cannot load library "opencv_java4"
```

This happens even though the `.so` files are present in the APK's `jni/libs` directory.

## Root Cause
The issue occurs because:
1. **Class Loader Namespace Isolation**: Apps in `/vendor/app/` run with restricted class loader namespaces
2. **Library Search Path**: The system cannot locate native libraries in the restricted environment
3. **SELinux Policies**: Additional security restrictions may prevent library loading

## Solutions

### Solution 1: Modify AndroidManifest.xml (Recommended)

Add the following attributes to your `<application>` tag in `AndroidManifest.xml`:

```xml
<application
    android:extractNativeLibs="true"
    android:usesCleartextTraffic="true"
    android:requestLegacyExternalStorage="true">
    <!-- Your existing application content -->
</application>
```

### Solution 2: Update build.gradle for Better Library Packaging

In your app's `build.gradle`:

```gradle
android {
    packagingOptions {
        pickFirst '**/libc++_shared.so'
        pickFirst '**/libopencv_java4.so'
    }
    
    defaultConfig {
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a'
        }
    }
}
```

### Solution 3: Programmatic Library Loading

Create a helper class for loading OpenCV:

```java
public class OpenCVLoader {
    private static final String TAG = "OpenCVLoader";
    
    public static boolean initOpenCV() {
        try {
            // Try loading from APK first
            if (OpenCVLoaderCallback.initDebug()) {
                Log.d(TAG, "OpenCV loaded successfully from APK");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load OpenCV from APK: " + e.getMessage());
        }
        
        try {
            // Fallback: manual library loading
            System.loadLibrary("opencv_java4");
            Log.d(TAG, "OpenCV loaded successfully via System.loadLibrary");
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load OpenCV library: " + e.getMessage());
            return false;
        }
    }
}
```

### Solution 4: Copy Libraries to System Directory

Create a script to copy libraries to the system library path:

```bash
#!/system/bin/sh
# copy_opencv_libs.sh

APK_PATH="/vendor/app/myjnidemo/app-debug.apk"
LIB_DEST="/system/lib64"

# Extract and copy arm64-v8a libraries
unzip -j "$APK_PATH" "lib/arm64-v8a/libopencv_java4.so" -d "$LIB_DEST"
chmod 644 "$LIB_DEST/libopencv_java4.so"

# For 32-bit support
LIB_DEST_32="/system/lib"
unzip -j "$APK_PATH" "lib/armeabi-v7a/libopencv_java4.so" -d "$LIB_DEST_32"
chmod 644 "$LIB_DEST_32/libopencv_java4.so"

# Set SELinux context
chcon u:object_r:system_lib_file:s0 "$LIB_DEST/libopencv_java4.so"
chcon u:object_r:system_lib_file:s0 "$LIB_DEST_32/libopencv_java4.so"
```

### Solution 5: SELinux Policy Update

Add SELinux policies to allow library loading:

```
# File: sepolicy/vendor/app_domain.te
allow untrusted_app vendor_app_file:file { read open getattr execute };
allow untrusted_app vendor_app_file:dir { read open search };
```

## Implementation Steps

### For Development Environment:

1. **Update AndroidManifest.xml** with `android:extractNativeLibs="true"`
2. **Modify build.gradle** to include proper packaging options
3. **Implement OpenCVLoader** helper class
4. **Test on target device** before deploying to `/vendor/app/`

### For Production Deployment:

1. **Copy the script** `copy_opencv_libs.sh` to the target device
2. **Execute as root** to copy libraries to system directories
3. **Update SELinux policies** if necessary
4. **Verify library loading** with `ldd` or similar tools

## Verification Commands

```bash
# Check if library exists in APK
unzip -l /vendor/app/myjnidemo/app-debug.apk | grep opencv

# Check system library paths
ls -la /system/lib*/libopencv*

# Verify library dependencies
ldd /system/lib64/libopencv_java4.so

# Check SELinux context
ls -Z /system/lib64/libopencv_java4.so
```

## Alternative Approaches

### Option A: Use OpenCV Manager
Instead of bundling libraries, use OpenCV Manager for dynamic loading:

```java
private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
    @Override
    public void onManagerConnected(int status) {
        switch (status) {
            case LoaderCallbackInterface.SUCCESS:
                Log.d(TAG, "OpenCV loaded successfully");
                // Initialize OpenCV dependent code
                break;
            default:
                super.onManagerConnected(status);
                break;
        }
    }
};
```

### Option B: Custom Library Path
Set custom library search paths in native code:

```cpp
#include <dlfcn.h>

void* loadOpenCVLibrary() {
    const char* paths[] = {
        "/system/lib64/libopencv_java4.so",
        "/vendor/lib64/libopencv_java4.so",
        "./lib/arm64-v8a/libopencv_java4.so"
    };
    
    for (int i = 0; i < sizeof(paths)/sizeof(paths[0]); i++) {
        void* handle = dlopen(paths[i], RTLD_LAZY);
        if (handle) {
            return handle;
        }
    }
    return nullptr;
}
```

## Troubleshooting

1. **Check ABI compatibility**: Ensure the `.so` files match the device architecture
2. **Verify permissions**: Libraries must have proper read/execute permissions
3. **Check dependencies**: Use `readelf -d` to verify library dependencies
4. **Monitor logs**: Use `adb logcat` to see detailed error messages
5. **Test extraction**: Verify `android:extractNativeLibs="true"` actually extracts libraries

## Best Practices

1. **Always test** library loading in the actual deployment environment
2. **Include multiple ABIs** (arm64-v8a, armeabi-v7a) for broader compatibility
3. **Use gradual fallback** strategies in your loading code
4. **Monitor performance** impact of library extraction vs. direct loading
5. **Keep libraries updated** and check for security vulnerabilities