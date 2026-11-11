package com.biometricpass.app.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Debug-only diagnostic logger.
 *
 * Security invariants:
 * 1. Emits nothing unless the application is debuggable (`FLAG_DEBUGGABLE`).
 * 2. Stripped out of Release bytecode by R8 (`-assumenosideeffects` in `proguard-rules.pro`).
 * 3. Never logs exception messages or stack traces — only safe context strings and exception types.
 */
object AppLog {

    @Volatile
    private var debugEnabled: Boolean = false

    fun init(context: Context) {
        debugEnabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun d(tag: String, message: String) {
        if (debugEnabled) {
            Log.d(tag, message)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (debugEnabled) {
            val suffix = throwable?.let { " (${it.javaClass.simpleName})" }.orEmpty()
            Log.w(tag, message + suffix)
        }
    }
}
