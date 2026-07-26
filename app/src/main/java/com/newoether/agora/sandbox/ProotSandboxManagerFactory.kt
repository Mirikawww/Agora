package com.newoether.agora.sandbox

import android.content.Context

class ProotSandboxManagerFactory(private val context: Context) : SandboxManagerFactory {
    override fun create(): SandboxManager = ProotSandboxManager(context)
    override fun isAvailable(): Boolean = true
}
