package com.crescent.myjnidemo;

import android.util.Log;
import org.opencv.android.OpenCVLoaderCallback;

/**
 * Helper class for loading OpenCV libraries with fallback mechanisms
 * Specifically designed to handle loading issues in /vendor/app/ environment
 */
public class OpenCVLoader {
    private static final String TAG = "OpenCVLoader";
    private static boolean sOpenCVInitialized = false;

    /**
     * Initialize OpenCV with multiple fallback strategies
     * @return true if OpenCV was successfully loaded, false otherwise
     */
    public static boolean initOpenCV() {
        if (sOpenCVInitialized) {
            Log.d(TAG, "OpenCV already initialized");
            return true;
        }

        // Strategy 1: Try OpenCV Manager approach
        if (tryOpenCVManager()) {
            sOpenCVInitialized = true;
            return true;
        }

        // Strategy 2: Try direct library loading
        if (tryDirectLibraryLoading()) {
            sOpenCVInitialized = true;
            return true;
        }

        // Strategy 3: Try system library path
        if (trySystemLibraryPath()) {
            sOpenCVInitialized = true;
            return true;
        }

        Log.e(TAG, "Failed to load OpenCV library with all strategies");
        return false;
    }

    /**
     * Try loading OpenCV using OpenCV Manager
     */
    private static boolean tryOpenCVManager() {
        try {
            Log.d(TAG, "Attempting OpenCV Manager loading...");
            // Note: This requires OpenCV Manager to be installed on the device
            if (OpenCVLoaderCallback.initDebug()) {
                Log.d(TAG, "OpenCV loaded successfully via OpenCV Manager");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "OpenCV Manager loading failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Try direct library loading using System.loadLibrary
     */
    private static boolean tryDirectLibraryLoading() {
        try {
            Log.d(TAG, "Attempting direct library loading...");
            System.loadLibrary("opencv_java4");
            Log.d(TAG, "OpenCV loaded successfully via System.loadLibrary");
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Direct library loading failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Try loading from system library paths
     */
    private static boolean trySystemLibraryPath() {
        String[] libraryPaths = {
            "/system/lib64/libopencv_java4.so",
            "/system/lib/libopencv_java4.so",
            "/vendor/lib64/libopencv_java4.so",
            "/vendor/lib/libopencv_java4.so"
        };

        for (String path : libraryPaths) {
            try {
                Log.d(TAG, "Attempting to load from: " + path);
                System.load(path);
                Log.d(TAG, "OpenCV loaded successfully from: " + path);
                return true;
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "Failed to load from " + path + ": " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Check if OpenCV is initialized
     */
    public static boolean isInitialized() {
        return sOpenCVInitialized;
    }

    /**
     * Force re-initialization (useful for testing)
     */
    public static void reset() {
        sOpenCVInitialized = false;
    }

    /**
     * Get detailed library information for debugging
     */
    public static String getLibraryInfo() {
        StringBuilder info = new StringBuilder();
        info.append("OpenCV Library Information:\n");
        info.append("Initialized: ").append(sOpenCVInitialized).append("\n");
        
        // Check library paths
        String[] paths = {
            "/system/lib64/libopencv_java4.so",
            "/system/lib/libopencv_java4.so",
            "/vendor/lib64/libopencv_java4.so",
            "/vendor/lib/libopencv_java4.so"
        };
        
        info.append("Library path check:\n");
        for (String path : paths) {
            java.io.File file = new java.io.File(path);
            info.append("  ").append(path).append(": ")
                .append(file.exists() ? "EXISTS" : "NOT FOUND").append("\n");
        }
        
        return info.toString();
    }
}