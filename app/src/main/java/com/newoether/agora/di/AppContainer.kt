package com.newoether.agora.di

import android.app.Application
import android.content.Context
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.sandbox.ProotSandboxManagerFactory
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ChatViewModelFactory

/**
 * Centralized dependency container (manual DI).
 *
 * Replaces the ad-hoc dependency creation previously spread across
 * MainActivity (ChatDatabase.build, ChatViewModelFactory instantiation).
 * All shared dependencies are created once and reused.
 *
 * This is a stepping stone toward a full DI framework (Hilt/Koin);
 * for a single-module project it provides sufficient decoupling and
 * testability without annotation processing overhead.
 */
class AppContainer(private val appContext: Context) {
    private val application = appContext.applicationContext as Application

    /** App-lifetime scope that backs the shared settings StateFlows. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    // ── Data Layer ────────────────────────────────────────────

    val settingsManager: SettingsManager by lazy { SettingsManager(appContext) }
    val memoryManager: MemoryManager by lazy { MemoryManager(appContext) }
    val database: ChatDatabase by lazy { ChatDatabase.build(appContext) }
    val chatDao: ChatDao by lazy { database.chatDao() }

    // ── Repositories ──────────────────────────────────────────

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(chatDao)
    }
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsManager, appScope)
    }

    // ── Sandbox ───────────────────────────────────────────────
    // Was reflection-based while a play flavor shipped a no-op stub instead of
    // proot; with the flavors gone this is a plain construction.

    val sandboxManagerFactory: SandboxManagerFactory? by lazy {
        try {
            ProotSandboxManagerFactory(appContext)
        } catch (e: Exception) {
            com.newoether.agora.util.DebugLog.e("AppContainer", "sandbox factory init failed", e)
            null
        }
    }

    // ── Auto Backup ───────────────────────────────────────────

    val autoBackupManager: AutoBackupManager by lazy {
        AutoBackupManager(appContext, settingsManager, chatDao, memoryManager)
    }

    // ── ViewModel Factory ─────────────────────────────────────

    fun chatViewModelFactory(): ChatViewModelFactory =
        ChatViewModelFactory(
            application, chatDao, settingsManager, memoryManager, appContext, sandboxManagerFactory,
            autoBackupManager, conversationRepository, settingsRepository
        )
}
