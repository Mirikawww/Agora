package com.newoether.agora.util

import android.content.Context

object DebugLog {
    @Volatile
    var forceEnabled = false
    @Volatile
    private var enabled = true

    fun init(context: Context) {
        enabled = forceEnabled || (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val active: Boolean get() = forceEnabled || enabled

    /** Public because the inline lazy [d] overload below is compiled into its callers. */
    @PublishedApi
    internal val isActive: Boolean get() = active

    fun d(tag: String, msg: String) { if (active) android.util.Log.d(tag, msg) }
    fun d(tag: String, msg: String, tr: Throwable) { if (active) android.util.Log.d(tag, msg, tr) }

    /**
     * Lazy variant for messages whose construction is itself expensive.
     *
     * Diagnostics that estimate schema tokens or serialize a tool surface must not run in release
     * builds, where the resulting string is discarded. `inline` keeps the lambda allocation-free.
     */
    inline fun d(tag: String, message: () -> String) {
        if (isActive) android.util.Log.d(tag, message())
    }

    fun e(tag: String, msg: String) { if (active) android.util.Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) { if (active) android.util.Log.e(tag, msg, tr) }
    fun w(tag: String, msg: String) { if (active) android.util.Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable) { if (active) android.util.Log.w(tag, msg, tr) }
}
