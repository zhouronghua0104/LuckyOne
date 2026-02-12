package com.crescent.myjnidemo.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
object VendorNativeLibLoader {

    private const val TAG = "VendorNativeLibLoader"

    private val initialized = AtomicBoolean(false)

    // Dependency order: base runtime first, business libraries after.
    private val preloadLibraries = listOf("c++_shared")
    private val requiredLibraries = listOf("opencv_java4", "SmartCockpit")

    private val builtinSearchDirs = listOf(
        "/mnt/vendor/vr/llm_res/llm/libs", // New location (preferred)
        "/data/vendor_de/svw/llm/libs",    // Old location (compatibility)
        "/vendor/lib64",
        "/vendor/lib",
        "/system/lib64",
        "/system/lib"
    )

    @JvmStatic
    @Synchronized
    fun ensureLoaded(context: Context? = null, extraSearchDirs: List<String> = emptyList()): Boolean {
        if (initialized.get()) {
            return true
        }

        val searchDirs = buildSearchDirs(context, extraSearchDirs)
        var success = true

        for (lib in preloadLibraries) {
            loadSingleLibrary(lib, searchDirs)
        }
        for (lib in requiredLibraries) {
            val loaded = loadSingleLibrary(lib, searchDirs)
            if (!loaded) {
                success = false
            }
        }

        if (success) {
            initialized.set(true)
        }
        return success
    }

    @JvmStatic
    fun dumpSearchReport(context: Context? = null, extraSearchDirs: List<String> = emptyList()): String {
        val dirs = buildSearchDirs(context, extraSearchDirs)
        val report = StringBuilder()
        report.append("VendorNativeLibLoader search report\n")
        report.append("java.library.path=").append(System.getProperty("java.library.path")).append('\n')
        for (dir in dirs) {
            val folder = File(dir)
            report.append(" - ").append(dir).append(" : ")
                .append(if (folder.exists()) "EXISTS" else "MISSING")
                .append('\n')
        }
        return report.toString()
    }

    private fun buildSearchDirs(context: Context?, extraSearchDirs: List<String>): List<String> {
        val dirs = LinkedHashSet<String>()
        context?.applicationInfo?.nativeLibraryDir
            ?.takeIf { it.isNotBlank() }
            ?.let { dirs.add(it) }
        builtinSearchDirs.forEach { dirs.add(it) }
        extraSearchDirs.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { dirs.add(it) }
        return dirs.toList()
    }

    private fun loadSingleLibrary(libName: String, searchDirs: List<String>): Boolean {
        try {
            System.loadLibrary(libName)
            Log.i(TAG, "Loaded by name: $libName")
            return true
        } catch (nameError: UnsatisfiedLinkError) {
            Log.w(TAG, "loadLibrary failed for $libName: ${nameError.message}")
        }

        val fileName = "lib$libName.so"
        for (dir in searchDirs) {
            val candidate = File(dir, fileName)
            if (!candidate.exists()) {
                continue
            }
            try {
                System.load(candidate.absolutePath)
                Log.i(TAG, "Loaded by absolute path: ${candidate.absolutePath}")
                return true
            } catch (pathError: UnsatisfiedLinkError) {
                Log.w(TAG, "load path failed: ${candidate.absolutePath}, reason=${pathError.message}")
            } catch (securityError: SecurityException) {
                Log.w(TAG, "no permission for: ${candidate.absolutePath}, reason=${securityError.message}")
            }
        }

        Log.e(TAG, "Failed to load $libName from all search paths")
        return false
    }
}
