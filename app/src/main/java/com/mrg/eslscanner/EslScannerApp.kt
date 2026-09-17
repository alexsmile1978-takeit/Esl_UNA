package com.mrg.eslscanner

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures crashes that normal try/catch(Exception) can't — Errors like
 * NoClassDefFoundError, VerifyError, ExceptionInInitializerError, which can
 * happen when a library (e.g. ojdbc8) references JVM classes missing on
 * Android. Writes the full stack trace to SharedPreferences so it survives
 * the crash and can be shown on the next launch (see MainActivity).
 */
class EslScannerApp : Application() {

    companion object {
        const val PREFS_NAME = "crash_log"
        const val KEY_LAST_CRASH = "last_crash"
    }

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, sw.toString())
                    .apply()
            } catch (ignored: Throwable) {
                // don't let logging itself crash the crash handler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
