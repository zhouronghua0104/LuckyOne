package com.crescent.myjnidemo.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.Locale

/**
 * <pre>
 * author : zhouronghua
 * e-mail : zhouronghua1@modelbest.com
 * time : 2026/01/05
 * desc : 哨兵监听服务
 * version: 1.0
 * </pre>
 */
class SentryService : Service() {
    protected val TAG = javaClass.simpleName

    /**
     * 绑定对象
     */
    protected var serviceBinder = SentryServiceBinder()

    @Volatile
    private var batchTestRunning = false
    private var batchTestStartTimeMs: Long? = null

    /**
     * 获取服务远程绑定对象
     *
     * @return 服务Binder对象
     * @author zhouronghua
     * @time 2025/7/12 17:04
     */
    override fun onBind(intent: Intent?): IBinder? {
        return serviceBinder
    }

    /**
     * 接收dumpsys指令
     *
     * @param writer 执行结果输出到控制台
     * @param args 传入参数
     * @author zhouronghua
     * @time 2026/2/9 16:11
     */
    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        super.dump(fd, writer, args)
        val out = writer ?: return
        val safeArgs = args
            ?.mapNotNull { it?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        if (safeArgs.isEmpty()) {
            dumpStatus(out)
            return
        }

        when (safeArgs[0].lowercase(Locale.US)) {
            "bt" -> handleBatchTestCommand(out, safeArgs.drop(1))
            "help", "-h", "--help" -> dumpHelp(out)
            else -> {
                out.println("Unknown command: ${safeArgs[0]}")
                dumpHelp(out)
            }
        }
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
        out.println("  Use: dumpsys activity service com.crescent.myjnidemo bt")
    }

    private fun dumpHelp(out: PrintWriter) {
        out.println("SentryService dumpsys commands:")
        out.println("  (no args)   Show current status")
        out.println("  bt          Start batch test")
        out.println("  help        Show this help")
    }

    private fun handleBatchTestCommand(out: PrintWriter, args: List<String>) {
        if (batchTestRunning) {
            out.println("Batch test already running.")
            return
        }
        batchTestRunning = true
        batchTestStartTimeMs = System.currentTimeMillis()
        // TODO: hook into real batch test implementation.
        out.println("Batch test started.")
        if (args.isNotEmpty()) {
            out.println("Ignored args: ${args.joinToString(",")}")
        }
    }

    inner class SentryServiceBinder : Binder()
}
