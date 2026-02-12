package com.crescent.myjnidemo.runtime;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vendor runtime native-library loader.
 *
 * Context:
 * - Runtime libraries moved from /data/vendor_de/svw/llm/libs
 *   to /mnt/vendor/vr/llm_res/llm/libs.
 * - In vendor-app namespace, System.loadLibrary may fail to resolve .so files.
 *
 * Strategy:
 * 1) Try System.loadLibrary first.
 * 2) Fallback to System.load with absolute candidate paths.
 */
public final class VendorNativeLibLoader {

    private static final String TAG = "VendorNativeLibLoader";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    // Dependency order: base runtime first, business libraries after.
    private static final List<String> PRELOAD_LIBRARIES =
            Collections.unmodifiableList(Arrays.asList("c++_shared"));
    private static final List<String> REQUIRED_LIBRARIES =
            Collections.unmodifiableList(Arrays.asList("opencv_java4", "SmartCockpit"));

    private static final List<String> BUILTIN_SEARCH_DIRS = Collections.unmodifiableList(Arrays.asList(
            "/mnt/vendor/vr/llm_res/llm/libs", // New location (preferred)
            "/data/vendor_de/svw/llm/libs",    // Old location (compatibility)
            "/vendor/lib64",
            "/vendor/lib",
            "/system/lib64",
            "/system/lib"
    ));

    private VendorNativeLibLoader() {
    }

    public static synchronized boolean ensureLoaded() {
        return ensureLoaded(null, null, Collections.emptyList());
    }

    public static synchronized boolean ensureLoaded(Context context) {
        return ensureLoaded(context, null, Collections.emptyList());
    }

    /**
     * Load runtime libraries with a user-specified library directory.
     *
     * <p>Order:
     * 1) Append libPath to java.library.path
     * 2) Call System.loadLibrary(...)
     * 3) Fallback to absolute-path loading when needed
     */
    public static synchronized boolean ensureLoaded(Context context, String libPath) {
        return ensureLoaded(context, libPath, Collections.emptyList());
    }

    public static synchronized boolean ensureLoaded(Context context, List<String> extraSearchDirs) {
        return ensureLoaded(context, null, extraSearchDirs);
    }

    public static synchronized boolean ensureLoaded(
            Context context, String libPath, List<String> extraSearchDirs) {
        String normalizedLibPath = normalizeDir(libPath);
        if (normalizedLibPath != null) {
            appendToJavaLibraryPath(normalizedLibPath);
        }

        if (INITIALIZED.get()) {
            return true;
        }

        List<String> searchDirs = buildSearchDirs(context, normalizedLibPath, extraSearchDirs);
        boolean success = true;

        for (String lib : PRELOAD_LIBRARIES) {
            loadSingleLibrary(lib, searchDirs);
        }
        for (String lib : REQUIRED_LIBRARIES) {
            boolean loaded = loadSingleLibrary(lib, searchDirs);
            if (!loaded) {
                success = false;
            }
        }

        if (success) {
            INITIALIZED.set(true);
        }
        return success;
    }

    /**
     * Appends a directory into java.library.path.
     * Returns true when path is valid and appended/already present.
     */
    public static synchronized boolean appendToJavaLibraryPath(String libPath) {
        String normalized = normalizeDir(libPath);
        if (normalized == null) {
            Log.w(TAG, "appendToJavaLibraryPath ignored: empty libPath");
            return false;
        }

        String current = System.getProperty("java.library.path", "");
        String separator = File.pathSeparator;
        List<String> paths = splitPathList(current, separator);
        if (paths.contains(normalized)) {
            Log.i(TAG, "java.library.path already contains: " + normalized);
            return true;
        }

        String updated = current == null || current.isEmpty()
                ? normalized
                : current + separator + normalized;
        System.setProperty("java.library.path", updated);
        resetClassLoaderPathCacheBestEffort();
        Log.i(TAG, "java.library.path updated: " + updated);
        return true;
    }

    public static String dumpSearchReport() {
        return dumpSearchReport(null, Collections.emptyList());
    }

    public static String dumpSearchReport(Context context) {
        return dumpSearchReport(context, Collections.emptyList());
    }

    public static String dumpSearchReport(Context context, List<String> extraSearchDirs) {
        List<String> dirs = buildSearchDirs(context, null, extraSearchDirs);
        StringBuilder report = new StringBuilder();
        report.append("VendorNativeLibLoader search report\n");
        report.append("java.library.path=").append(System.getProperty("java.library.path")).append('\n');
        for (String dir : dirs) {
            File folder = new File(dir);
            report.append(" - ").append(dir).append(" : ")
                    .append(folder.exists() ? "EXISTS" : "MISSING")
                    .append('\n');
        }
        return report.toString();
    }

    private static List<String> buildSearchDirs(
            Context context, String preferredLibPath, List<String> extraSearchDirs) {
        LinkedHashSet<String> dirs = new LinkedHashSet<>();

        if (context != null
                && context.getApplicationInfo() != null
                && context.getApplicationInfo().nativeLibraryDir != null) {
            String nativeDir = context.getApplicationInfo().nativeLibraryDir.trim();
            if (!nativeDir.isEmpty()) {
                dirs.add(nativeDir);
            }
        }

        if (preferredLibPath != null) {
            dirs.add(preferredLibPath);
        }

        dirs.addAll(BUILTIN_SEARCH_DIRS);

        if (extraSearchDirs != null) {
            for (String dir : extraSearchDirs) {
                String normalized = normalizeDir(dir);
                if (normalized == null) {
                    continue;
                }
                dirs.add(normalized);
            }
        }
        return new ArrayList<>(dirs);
    }

    private static String normalizeDir(String dir) {
        if (dir == null) {
            return null;
        }
        String trimmed = dir.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> splitPathList(String pathList, String separator) {
        if (pathList == null || pathList.isEmpty()) {
            return new ArrayList<>();
        }
        String[] split = pathList.split(java.util.regex.Pattern.quote(separator));
        List<String> result = new ArrayList<>(split.length);
        for (String item : split) {
            String normalized = normalizeDir(item);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static void resetClassLoaderPathCacheBestEffort() {
        // JVMs may cache java.library.path internally. On Android this may be a no-op.
        String[] cacheFields = {"sys_paths", "usr_paths"};
        for (String fieldName : cacheFields) {
            try {
                Field field = ClassLoader.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(null, null);
            } catch (Throwable ignore) {
                // Ignore: field may not exist on Android ART.
            }
        }
    }

    private static boolean loadSingleLibrary(String libName, List<String> searchDirs) {
        try {
            System.loadLibrary(libName);
            Log.i(TAG, "Loaded by name: " + libName);
            return true;
        } catch (UnsatisfiedLinkError nameError) {
            Log.w(TAG, "loadLibrary failed for " + libName + ": " + nameError.getMessage());
        }

        String fileName = "lib" + libName + ".so";
        for (String dir : searchDirs) {
            File candidate = new File(dir, fileName);
            if (!candidate.exists()) {
                continue;
            }
            try {
                System.load(candidate.getAbsolutePath());
                Log.i(TAG, "Loaded by absolute path: " + candidate.getAbsolutePath());
                return true;
            } catch (UnsatisfiedLinkError pathError) {
                Log.w(TAG, "load path failed: " + candidate.getAbsolutePath()
                        + ", reason=" + pathError.getMessage());
            } catch (SecurityException securityError) {
                Log.w(TAG, "no permission for: " + candidate.getAbsolutePath()
                        + ", reason=" + securityError.getMessage());
            }
        }

        Log.e(TAG, "Failed to load " + libName + " from all search paths");
        return false;
    }
}
