package com.newoether.agora.util

/**
 * Always-on, lazily-built latency probes for the pre-request critical path.
 *
 * These deliberately survive release builds: the pre-request stalls they exist to catch only
 * reproduce with a user's real connector set, which a debuggable build rarely has. Every message
 * is built inside an inline lambda, so an enabled probe costs a few integer subtractions and a
 * string concat, and a disabled one costs nothing at all.
 *
 * Use [DebugLog] instead for verbose diagnostics, and for anything whose message is expensive to
 * construct (token estimates, serialized schemas).
 */
object TimingLog {
    const val TAG = "AgoraTiming"

    @Volatile
    var enabled = true

    inline fun mark(message: () -> String) {
        if (enabled) android.util.Log.d(TAG, message())
    }

    /** Convenience for the dominant shape: "<step> took <n>ms". */
    inline fun since(startMs: Long, step: () -> String) {
        if (enabled) {
            android.util.Log.d(TAG, "${step()} took ${System.currentTimeMillis() - startMs}ms")
        }
    }
}
