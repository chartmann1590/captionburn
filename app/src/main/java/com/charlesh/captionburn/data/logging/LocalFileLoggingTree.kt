package com.charlesh.captionburn.data.logging

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * Local file-backed logger to preserve the app's offline-only posture.
 */
class LocalFileLoggingTree(
    private val logDir: File,
    private val maxBytes: Long = 1_000_000L,
) : Timber.Tree() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val logFile = File(logDir, "captionburn.log")
    private val oldLogFile = File(logDir, "captionburn.log.1")
    private val lock = Any()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> priority.toString()
        }
        val timestamp = dateFormat.format(Date())
        val throwable = t?.let { "\n${Log.getStackTraceString(it)}" }.orEmpty()
        val line = "$timestamp $level/${tag ?: "CaptionBurn"}: $message$throwable\n"
        append(line)
    }

    fun logCrash(thread: Thread, throwable: Throwable) {
        val crashLine = buildString {
            append("UNCAUGHT on ${thread.name}: ${throwable.message ?: "no message"}\n")
            append(Log.getStackTraceString(throwable))
            append('\n')
        }
        append("${dateFormat.format(Date())} E/CrashHandler: $crashLine")
    }

    private fun append(text: String) {
        synchronized(lock) {
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            rotateIfNeeded()
            logFile.appendText(text)
        }
    }

    private fun rotateIfNeeded() {
        if (logFile.exists() && logFile.length() >= maxBytes) {
            if (oldLogFile.exists()) {
                oldLogFile.delete()
            }
            logFile.renameTo(oldLogFile)
            logFile.delete()
        }
    }
}
