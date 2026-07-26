package com.newoether.agora.viewmodel

import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderBalanceClient
import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.api.openai.DeepSeekProvider
import com.newoether.agora.api.openai.OpenAiProvider
import com.newoether.agora.api.openai.OpenRouterProvider
import com.newoether.agora.api.openai.QwenProvider
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ModelId
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.ConcurrentHashMap

private const val MODEL_CATALOG_SCHEMA_VERSION = 2

/**
 * One API key's balance reading. [keyId]/[keyName] are blank for the single
 * anonymous probe of a keyless provider.
 */
data class ProviderKeyBalance(
    val keyId: String,
    val keyName: String,
    val result: ProviderBalanceClient.Result
)

/**
 * Owns the set of LLM providers — built-in plus user-defined custom OpenAI-compatible
 * ones — and all logic for resolving a model/provider to a concrete [LlmProvider]
 * instance, its effective base URL, and its configured/credentialed status.
 *
 * Extracted from [ChatViewModel] so provider lifecycle (registration, rename, delete,
 * credential reconciliation) and model discovery live in one cohesive place. The live
 * [all] map is shared by reference with the generation pipeline, so it is a
 * [ConcurrentHashMap]: mutated by the sync collectors while read on `Dispatchers.IO`
 * during generation.
 */
class ProviderRegistry(
    private val settings: SettingsRepository,
    localProvider: LocalProvider,
    private val scope: CoroutineScope,
) {
    private val builtInProviders: Map<String, LlmProvider> = mapOf(
        Constants.PROVIDER_GOOGLE to GeminiProvider(),
        Constants.PROVIDER_OPENAI to OpenAiProvider(),
        Constants.PROVIDER_ANTHROPIC to AnthropicProvider(),
        Constants.PROVIDER_DEEPSEEK to DeepSeekProvider(),
        Constants.PROVIDER_QWEN to QwenProvider(),
        Constants.PROVIDER_OLLAMA to OllamaProvider(),
        Constants.PROVIDER_OPEN_ROUTER to OpenRouterProvider(),
        Constants.PROVIDER_LOCAL to localProvider
    )

    // Declared as MutableMap so `in`/`contains` keep Map (containsKey) semantics (KT-18053).
    private val providers: MutableMap<String, LlmProvider> = ConcurrentHashMap(builtInProviders)

    /** Live, thread-safe read view shared with the generation pipeline. */
    val all: Map<String, LlmProvider> get() = providers

    fun isBuiltIn(name: String): Boolean = name in builtInProviders

    fun getInstance(name: String): LlmProvider = providers[name] ?: GeminiProvider()

    fun getEffectiveBaseUrl(providerName: String): String? =
        settings.providerBaseUrls.value[providerName]
            ?: if (!isBuiltIn(providerName)) getInstance(providerName).defaultBaseUrl else null

    fun isConfigured(providerName: String, activeKey: String): Boolean = when {
        providerName == Constants.PROVIDER_UNKNOWN -> false
        providerName == Constants.PROVIDER_LOCAL -> true
        // Ollama is typically keyless; base URL alone is enough.
        providerName == Constants.PROVIDER_OLLAMA -> !getEffectiveBaseUrl(providerName).isNullOrBlank()
        !isBuiltIn(providerName) -> {
            val baseOk = !getEffectiveBaseUrl(providerName).isNullOrBlank()
            if (!baseOk) return false
            // If the user registered any keys for this custom provider, at least one must
            // be enabled. An open/keyless custom endpoint (no keys stored) still works.
            val hasStoredKeys = settings.apiKeys.value.any { it.provider == providerName }
            if (hasStoredKeys) activeKey.isNotBlank() else true
        }
        else -> activeKey.isNotBlank()
    }

    /**
     * Runs the user-configured account-balance probe for [name] — **once per stored
     * API key**, concurrently. Credit is billed per key, so two keys of the same
     * provider routinely hold different balances; a single probe would hide that.
     *
     * Returns an empty list when the provider has no runnable probe (switched off
     * or with an empty path/JSON key). Keyless providers (Ollama, open custom
     * endpoints) get one anonymous reading with a blank key id. Built-in providers
     * fall back to their [LlmProvider.defaultBaseUrl] so the probe works even when
     * the user never overrode the Base URL.
     */
    suspend fun fetchBalances(name: String): List<ProviderKeyBalance> {
        val config = settings.providerBalanceConfigs.value[name] ?: return emptyList()
        if (!config.isRunnable) return emptyList()
        ensureCustomProvidersRegistered()
        val baseUrl = getEffectiveBaseUrl(name) ?: getInstance(name).defaultBaseUrl
        suspend fun probe(apiKey: String) = ProviderBalanceClient.fetch(
            providerName = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            path = config.path,
            jsonKey = config.jsonKey
        )

        val keys = settings.apiKeys.value.filter { it.provider == name }
        if (keys.isEmpty()) return listOf(ProviderKeyBalance("", "", probe("")))
        return coroutineScope {
            keys.map { entry ->
                async { ProviderKeyBalance(entry.id, entry.name, probe(entry.key)) }
            }.awaitAll()
        }
    }

    fun providerForModel(modelId: String): String {
        // Prefixed IDs (e.g. "OpenAI:gpt-4"): extract provider directly
        if (modelId.contains(":")) return ModelId.parse(modelId).providerName
        // Unprefixed IDs: user-registered providers take priority over heuristics
        settings.availableModels.value.forEach { (providerName, models) ->
            if (models.contains(modelId)) return providerName
        }
        // Heuristic fallback for legacy unprefixed IDs
        return ModelId.parse(modelId).providerName
    }

    // ── Custom provider CRUD ──────────────────────────────────
    // Settings persists the config; the callbacks keep the live `providers` map in sync.

    fun addCustom(name: String, baseUrl: String) {
        providers[name] = CustomOpenAiProvider(name, baseUrl)
        settings.addCustomProvider(name, baseUrl) { n, p -> providers[n] = p }
    }

    fun renameCustom(oldName: String, newName: String) {
        val url = settings.providerBaseUrls.value[oldName] ?: return
        providers.remove(oldName)
        providers[newName] = CustomOpenAiProvider(newName, url)
        settings.renameCustomProvider(oldName, newName, { providers.remove(it) }, { n, p -> providers[n] = p })
    }

    fun deleteCustom(name: String) {
        settings.deleteCustomProvider(name) { providers.remove(it) }
    }

    /** Registers any persisted custom provider not yet present in the live map. */
    fun ensureCustomProvidersRegistered() {
        settings.customProviders.value.forEach { config ->
            if (config.name !in providers) {
                providers[config.name] = CustomOpenAiProvider(config.name, settings.providerBaseUrls.value[config.name] ?: "")
            }
        }
    }

    /**
     * Fetches the live model list for a single provider and caches it. Unlike a full
     * sync this carries no global side effects (no snackbar, no syncing flag).
     */
    suspend fun fetchModelsForProvider(name: String): List<String> {
        if (name == Constants.PROVIDER_LOCAL) return emptyList()
        if (!settings.isProviderEnabled(name)) return emptyList()
        ensureCustomProvidersRegistered()
        val provider = providers[name] ?: return emptyList()
        // Only enabled keys participate. Never probe with a blank key — that can hang
        // open endpoints / reverse proxies and leaves stale catalogs when all keys are off.
        val activeKeys = settings.resolveActiveKeys(name)
        val probeKey = activeKeys.firstOrNull() ?: ""
        if (!isConfigured(name, probeKey) || (activeKeys.isEmpty() && name != Constants.PROVIDER_OLLAMA &&
                settings.apiKeys.value.any { it.provider == name })
        ) {
            clearProviderModelCache(name)
            return emptyList()
        }
        val keysToTry = when {
            activeKeys.isNotEmpty() -> activeKeys
            name == Constants.PROVIDER_OLLAMA -> listOf("") // keyless Ollama
            !isBuiltIn(name) && settings.apiKeys.value.none { it.provider == name } -> listOf("") // keyless custom
            else -> {
                clearProviderModelCache(name)
                return emptyList()
            }
        }
        val baseUrl = if (!isBuiltIn(name)) {
            settings.providerBaseUrls.value[name]?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
        } else {
            settings.providerBaseUrls.value[name]
        }

        // Resolve the "/v1" ambiguity ONCE here (config time) and persist the canonical
        // Base URL, so the request hot path uses a single deterministic endpoint instead
        // of trying both forms (and eating a 404) on every call. Custom OpenAI-compatible
        // providers without a version segment are probed /v1-first (the common case).
        val candidates: List<String?> =
            if (!isBuiltIn(name) && baseUrl != null && !com.newoether.agora.api.BaseUrlResolver.hasVersionSegment(baseUrl))
                listOf(com.newoether.agora.api.BaseUrlResolver.withV1(baseUrl), baseUrl)
            else
                listOf(baseUrl)

        // Resolve key id/name for nickname badges on the models page / model menu.
        val keyEntries = settings.apiKeys.value.filter { it.provider == name }
        val keyBySecret = keyEntries.associateBy { it.key }
        val nicknameByKey = keyEntries.associate { it.key to it.name }

        for (candidate in candidates) {
            val merged = linkedMapOf<String, Boolean?>()
            val nicknamesByModel = linkedMapOf<String, LinkedHashSet<String>>()
            // First key that successfully lists a model owns it for requests (no failover).
            val ownerKeyIdByModel = linkedMapOf<String, String>()
            for (key in keysToTry) {
                val raw = try {
                    withTimeout(Constants.MODEL_FETCH_TIMEOUT_MS) {
                        provider.fetchModelCatalog(key, candidate)
                    }
                } catch (_: TimeoutCancellationException) {
                    emptyList()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emptyList()
                }
                val entry = keyBySecret[key]
                val nick = nicknameByKey[key]?.takeIf { it.isNotBlank() }
                    ?: entry?.name
                    ?: key.take(4).ifBlank { null }
                raw.forEach { fetched ->
                    val modelId = fetched.id.removePrefix("models/")
                    val alreadyPresent = merged.containsKey(modelId)
                    val previous = merged[modelId]
                    merged[modelId] = when {
                        !alreadyPresent -> fetched.fast
                        previous == fetched.fast -> previous
                        previous == null -> fetched.fast
                        fetched.fast == null -> previous
                        else -> null // Conflicting explicit declarations remain unknown.
                    }
                    if (!nick.isNullOrBlank()) {
                        nicknamesByModel.getOrPut(modelId) { linkedSetOf() }.add(nick)
                    }
                    // Bind model to the first enabled key that returned it.
                    if (!ownerKeyIdByModel.containsKey(modelId) && entry != null) {
                        ownerKeyIdByModel[modelId] = entry.id
                    }
                }
            }
            if (merged.isEmpty()) continue
            // Persist the working form when it differs from what was stored, so the
            // ambiguity is never re-litigated at request time.
            if (candidate != null && candidate != baseUrl) settings.setProviderBaseUrl(name, candidate)
            val prefixed = merged.keys.map { "$name:$it" }
            settings.saveAvailableModels(name, prefixed)
            settings.saveModelFastSupport(
                provider = name,
                support = merged.mapNotNull { (modelId, fast) ->
                    fast?.let { "$name:$modelId" to it }
                }.toMap()
            )
            settings.saveModelKeyNicknames(
                provider = name,
                nicknames = nicknamesByModel.map { (modelId, nicks) ->
                    "$name:$modelId" to nicks.toList()
                }.toMap()
            )
            settings.saveModelKeyIds(
                provider = name,
                keyIds = ownerKeyIdByModel.map { (modelId, keyId) ->
                    "$name:$modelId" to keyId
                }.toMap()
            )
            return prefixed
        }
        // No catalog from any candidate — drop stale models so the UI doesn't keep
        // showing models for disabled/broken keys.
        clearProviderModelCache(name)
        return emptyList()
    }

    /** Drop cached catalog + ownership for [name] and prune enabled models. */
    private suspend fun clearProviderModelCache(name: String) {
        val existing = settings.getAvailableModels()[name]
        if (!existing.isNullOrEmpty()) {
            settings.saveAvailableModels(name, emptyList())
        }
        settings.saveModelKeyNicknames(name, emptyMap())
        settings.saveModelKeyIds(name, emptyMap())
        settings.saveModelFastSupport(name, emptyMap())
        val allAvailable = settings.getAvailableModels().values.flatten().toSet()
        val newEnabled = settings.enabledModels.value.intersect(allAvailable)
        if (newEnabled != settings.enabledModels.value) {
            settings.setEnabledModels(newEnabled)
        }
        // Drop selected model if it belonged to this provider and is no longer available.
        val selected = settings.selectedModel.value
        if (selected.startsWith("$name:") && selected !in allAvailable) {
            val fallback = newEnabled.firstOrNull()
                ?: allAvailable.firstOrNull()
                ?: Constants.EXAMPLE_MODEL_ID
            settings.setSelectedModel(fallback)
        }
    }

    /** Identity fingerprint of all providers' credentials/URLs — used to skip redundant syncs. */
    fun computeFingerprint(): String = providers.map { (name, _) ->
        val keyIds = settings.activeApiKeyIds.value[name].orEmpty().sorted().joinToString("+")
        val url = settings.providerBaseUrls.value[name] ?: ""
        val enabled = if (settings.isProviderEnabled(name)) "1" else "0"
        "$name|$keyIds|$url|$enabled|catalog=$MODEL_CATALOG_SCHEMA_VERSION"
    }.sorted().joinToString(",").hashCode().toString()

    /** Starts the long-lived collectors that keep the provider map and caches consistent. */
    fun launchSyncJobs() {
        // Sync custom providers into the live map whenever the persisted set changes.
        scope.launch {
            settings.customProviders.collect { custom ->
                providers.keys.filter { !isBuiltIn(it) }.forEach { providers.remove(it) }
                val baseUrls = settings.getProviderBaseUrls()
                custom.forEach { config ->
                    providers[config.name] = CustomOpenAiProvider(config.name, baseUrls[config.name] ?: "")
                }
            }
        }
        // Auto-clear cached available models when a provider loses its credentials /
        // enabled keys (including custom providers that only had a base URL before).
        scope.launch {
            var prevConfigured = emptyMap<String, Boolean>()
            combine(
                settings.apiKeys,
                settings.activeApiKeyIds,
                settings.providerBaseUrls
            ) { keys, activeIds, _ -> Triple(keys, activeIds, Unit) }
                .collect { (keys, activeIds, _) ->
                    // Always evaluate — empty activeIds is a real transition (all keys off).
                    val current = mutableMapOf<String, Boolean>()
                    providers.toMap().forEach { (name, _) ->
                        val enabledIds = activeIds[name].orEmpty()
                        val activeKey = enabledIds.firstNotNullOfOrNull { id -> keys.find { it.id == id }?.key } ?: ""
                        current[name] = isConfigured(name, activeKey)
                    }

                    var changed = false
                    current.forEach { (name, configured) ->
                        val wasConfigured = prevConfigured[name]
                        if (wasConfigured == true && !configured) {
                            val existing = settings.getAvailableModels()[name]
                            if (!existing.isNullOrEmpty()) {
                                clearProviderModelCache(name)
                                changed = true
                            }
                        }
                    }
                    // Also clear when keys exist for a provider but none are enabled, even if
                    // prevConfigured was already false (e.g. first collect after process start
                    // with a stale catalog left from the previous session).
                    if (prevConfigured.isEmpty()) {
                        current.forEach { (name, configured) ->
                            if (!configured) {
                                val existing = settings.getAvailableModels()[name]
                                if (!existing.isNullOrEmpty()) {
                                    clearProviderModelCache(name)
                                    changed = true
                                }
                            }
                        }
                    }
                    prevConfigured = current
                    // fingerprint bump handled by key-id changes; no extra work needed here
                    if (changed) {
                        // already pruned inside clearProviderModelCache
                    }
                }
        }
    }
}
