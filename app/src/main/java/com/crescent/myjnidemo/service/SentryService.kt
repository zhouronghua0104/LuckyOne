package com.crescent.myjnidemo.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.Locale

/**
 * Sentry monitoring service.
 *
 * Key fix:
 * - dumpsys argument parsing no longer relies on array casts, avoiding
 *   primitive-array vs boxed-array ClassCastException.
 */
class SentryService : Service() {

    private val serviceBinder = SentryServiceBinder()

    @Volatile
    private var batchTestRunning = false
    private var batchTestStartTimeMs: Long? = null

    override fun onBind(intent: Intent?): IBinder = serviceBinder

    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        super.dump(fd, writer, args)
        val out = writer ?: return
        val safeArgs = normalizeDumpArgs(args)

        if (safeArgs.isEmpty()) {
            dumpStatus(out)
            return
        }

        when (safeArgs.first().lowercase(Locale.US)) {
            "bt" -> handleBatchTestCommand(out, safeArgs.drop(1))
            "stop" -> stopBatchTest(out)
            "help", "-h", "--help" -> dumpHelp(out)
            else -> {
                out.println("Unknown command: ${safeArgs.first()}")
                dumpHelp(out)
            }
        }
    }

    /**
     * Converts heterogeneous inputs into a normalized string argument list.
     * This avoids risky array cast behavior.
     */
    internal fun normalizeDumpArgs(rawArgs: Any?): List<String> {
        val values: List<String> = when (rawArgs) {
            null -> emptyList()
            is Array<*> -> rawArgs.mapNotNull { it?.toString() }
            is IntArray -> rawArgs.map(Int::toString)          // int[] -> ["1","2",...]
            is LongArray -> rawArgs.map(Long::toString)
            is ShortArray -> rawArgs.map(Short::toString)
            is ByteArray -> rawArgs.map(Byte::toString)
            is BooleanArray -> rawArgs.map(Boolean::toString)
            is FloatArray -> rawArgs.map(Float::toString)
            is DoubleArray -> rawArgs.map(Double::toString)
            is CharArray -> rawArgs.map(Char::toString)
            is Iterable<*> -> rawArgs.mapNotNull { it?.toString() }
            else -> listOf(rawArgs.toString())
        }
        return values.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun dumpStatus(out: PrintWriter) {
        out.println("SentryService status:")
        out.println("  batchTestRunning: $batchTestRunning")
        if (batchTestRunning) {
            val start = batchTestStartTimeMs
            if (start != null) {
                out.println("  batchTestStartTimeMs: $start")
            }
        }
        out.println("  Use: dumpsys activity service com.crescent.myjnidemo/.service.SentryService [bt|stop|help]")
    }

    private fun dumpHelp(out: PrintWriter) {
        out.println("SentryService dumpsys commands:")
        out.println("  (no args)   Show current status")
        out.println("  bt          Start batch test")
        out.println("  stop        Stop batch test")
        out.println("  help        Show this help")
    }

    private fun handleBatchTestCommand(out: PrintWriter, args: List<String>) {
        if (batchTestRunning) {
            out.println("Batch test already running.")
            return
        }
        batchTestRunning = true
        batchTestStartTimeMs = System.currentTimeMillis()
        out.println("Batch test started.")
        if (args.isNotEmpty()) {
            out.println("Ignored args: ${args.joinToString(",")}")
        }
    }

    private fun stopBatchTest(out: PrintWriter) {
        if (!batchTestRunning) {
            out.println("Batch test is not running.")
            return
        }
        batchTestRunning = false
        out.println("Batch test stopped.")
    }

    inner class SentryServiceBinder : Binder()
}
