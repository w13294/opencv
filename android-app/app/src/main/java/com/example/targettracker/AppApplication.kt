package com.example.targettracker

import android.app.Application
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局异常捕获 — 把崩溃堆栈写入文件, 方便在没有电脑/adb 时定位问题
 * 崩溃日志路径: Android/data/com.example.targettracker/files/crash.log
 */
class AppApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(thread, throwable)
            // 交给系统默认处理, 让进程正常退出
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    private fun writeCrash(thread: Thread, throwable: Throwable?) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "crash.log")
            FileWriter(file, true).use { w ->
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                w.appendLine("==== CRASH @ $ts ====")
                w.appendLine("SDK=${Build.VERSION.SDK_INT} MODEL=${Build.MODEL} DEVICE=${Build.DEVICE} BRAND=${Build.BRAND}")
                w.appendLine("thread=${thread.name}")
                val t = throwable ?: Exception("null throwable")
                w.appendLine(t.javaClass.name + ": " + (t.message ?: "no message"))
                t.stackTrace.forEach { w.appendLine("    at $it") }
                var cause = t.cause
                while (cause != null) {
                    w.appendLine("Caused by: " + cause.javaClass.name + ": " + (cause.message ?: "no message"))
                    cause.stackTrace.forEach { w.appendLine("    at $it") }
                    cause = cause.cause
                }
                w.appendLine("====================")
            }
        } catch (_: Exception) {
            // ignore
        }
    }
}
