package com.newoether.agora.sandbox

/**
 * Factory for creating [SandboxManager] instances. The single implementation is
 * [ProotSandboxManagerFactory]; there used to be a no-op stub behind a `play`
 * product flavor, which no longer exists.
 */
interface SandboxManagerFactory {
    /** Create a new SandboxManager instance. */
    fun create(): SandboxManager

    /** Whether the sandbox feature is available in this build. */
    fun isAvailable(): Boolean
}
