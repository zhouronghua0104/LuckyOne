package com.crescent.myjnidemo.runtime;

import android.content.Context;
import android.util.Log;

import java.io.File;
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
        return ensureLoaded(null, Collections.emptyList());
    }

    public static synchronized boolean ensureLoaded(Context context) {
        return ensureLoaded(context, Collections.emptyList());
    }

    public static synchronized boolean ensureLoaded(Context context, List<String> extraSearchDirs) {
        if (INITIALIZED.get()) {
            return true;
        }

        List<String> searchDirs = buildSearchDirs(context, extraSearchDirs);
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

    public static String dumpSearchReport() {
        return dumpSearchReport(null, Collections.emptyList());
    }

    public static String dumpSearchReport(Context context) {
        return dumpSearchReport(context, Collections.emptyList());
    }

    public static String dumpSearchReport(Context context, List<String> extraSearchDirs) {
        List<String> dirs = buildSearchDirs(context, extraSearchDirs);
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

    private static List<String> buildSearchDirs(Context context, List<String> extraSearchDirs) {
        LinkedHashSet<String> dirs = new LinkedHashSet<>();

        if (context != null
                && context.getApplicationInfo() != null
                && context.getApplicationInfo().nativeLibraryDir != null) {
            String nativeDir = context.getApplicationInfo().nativeLibraryDir.trim();
            if (!nativeDir.isEmpty()) {
                dirs.add(nativeDir);
            }
        }

        dirs.addAll(BUILTIN_SEARCH_DIRS);

        if (extraSearchDirs != null) {
            for (String dir : extraSearchDirs) {
                if (dir == null) {
                    continue;
                }
                String trimmed = dir.trim();
                if (!trimmed.isEmpty()) {
                    dirs.add(trimmed);
                }
            }
        }
        return new ArrayList<>(dirs);
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
