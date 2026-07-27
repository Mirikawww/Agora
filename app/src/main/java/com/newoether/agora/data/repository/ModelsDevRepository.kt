package com.newoether.agora.data.repository

import android.content.Context
import com.newoether.agora.api.HttpClient
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

@Serializable
data class ModelCapabilities(
    val reasoning: Boolean = false,
    val fast: Boolean = false,
    /**
     * Model supports function/tool calling (models.dev `tool_call`).
     *
     * Defaults to `true`: the catalog is a best-effort overlay, and a cache miss or an
     * unlisted model must not silently strip tools from a model that in fact supports them.
     * Only an explicit `"tool_call": false` turns the gate off.
     */
    val tools: Boolean = true,
    /**
     * Real context window in **tokens** (models.dev `limit.context`), 0 when unknown.
     *
     * Distinct from the `maxContextWindow` setting, which is a *message count* used to trim
     * history. Anything reasoning about token budgets must use this field.
     */
    val contextTokens: Int = 0,
)

private const val MODELS_DEV_CATALOG_URL = "https://models.dev/catalog.json"
// v5: added `tools` (tool_call) and `contextTokens` (limit.context) to ModelCapabilities.
private const val MODELS_DEV_CACHE_FILE = "models_dev_capabilities_v5.json"
private const val MODELS_DEV_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
private const val ALL_PROVIDERS = "*"

private val modelsDevJson = Json { ignoreUnknownKeys = true }

private object ModelsDevFeatureCache {
    val loadMutex = Mutex()

    @Volatile
    var capabilities: Map<String, ModelCapabilities> = emptyMap()

    @Volatile
    var updatedAt: Long = 0L
}

internal fun normalizeModelsDevIdentifier(value: String): String =
    value.lowercase().filter(Char::isLetterOrDigit)

internal fun parseModelsDevCapabilities(raw: String): Map<String, ModelCapabilities> {
    val root = modelsDevJson.parseToJsonElement(raw).jsonObject
    val providers = root["providers"] as? JsonObject ?: return emptyMap()
    return buildMap {
        providers.forEach providerLoop@ { (_, providerElement) ->
            val provider = providerElement as? JsonObject ?: return@providerLoop
            val models = provider["models"] as? JsonObject ?: return@providerLoop
            models.forEach modelLoop@ { (modelId, modelElement) ->
                val model = modelElement as? JsonObject ?: return@modelLoop
                val reasoning = model["reasoning"]?.jsonPrimitive?.booleanOrNull == true
                val experimental = model["experimental"] as? JsonObject
                val modes = experimental?.get("modes") as? JsonObject
                val fast = modes?.containsKey("fast") == true
                // Absent `tool_call` means "unknown", which must stay permissive (see
                // ModelCapabilities.tools). Only an explicit false records a restriction.
                val tools = model["tool_call"]?.jsonPrimitive?.booleanOrNull != false
                val contextTokens = (model["limit"] as? JsonObject)
                    ?.get("context")?.jsonPrimitive?.intOrNull ?: 0
                val capabilities = ModelCapabilities(reasoning, fast, tools, contextTokens)
                // Nothing worth caching when every field is at its default.
                if (!reasoning && !fast && tools && contextTokens == 0) return@modelLoop
                val displayName = model["name"]?.jsonPrimitive?.content.orEmpty()
                val aliases = buildSet {
                    add(modelId.lowercase())
                    add(normalizeModelsDevIdentifier(modelId))
                    add(normalizeModelsDevIdentifier(modelId.substringAfterLast('/')))
                    if (displayName.isNotBlank()) add(normalizeModelsDevIdentifier(displayName))
                }.filter { it.isNotBlank() }
                aliases.forEach { alias ->
                    val key = ModelsDevRepository.key(ALL_PROVIDERS, alias)
                    val previous = get(key)
                    put(
                        key,
                        if (previous == null) capabilities else ModelCapabilities(
                            reasoning = previous.reasoning || capabilities.reasoning,
                            fast = previous.fast || capabilities.fast,
                            // AND, unlike the OR above: aliases collide across providers, and a
                            // model listed anywhere as tool-incapable must not be re-enabled by a
                            // permissive sibling entry.
                            tools = previous.tools && capabilities.tools,
                            // 0 means unknown, so any real figure wins over it.
                            contextTokens = maxOf(previous.contextTokens, capabilities.contextTokens)
                        )
                    )
                }
            }
        }
    }
}

internal fun findModelsDevCapabilities(
    capabilities: Map<String, ModelCapabilities>,
    apiModelName: String,
    providerFast: Boolean? = null,
): ModelCapabilities {
    val candidates = listOf(
        apiModelName.lowercase(),
        normalizeModelsDevIdentifier(apiModelName),
        normalizeModelsDevIdentifier(apiModelName.substringAfterLast('/')),
    ).distinct().filter { it.isNotBlank() }
    val catalogCapabilities = candidates.firstNotNullOfOrNull { candidate ->
        capabilities[ModelsDevRepository.key(ALL_PROVIDERS, candidate)]
    } ?: ModelCapabilities()
    return catalogCapabilities.copy(fast = providerFast ?: catalogCapabilities.fast)
}

class ModelsDevRepository(context: Context) {
    private val cacheFile = File(context.filesDir, MODELS_DEV_CACHE_FILE)
    private val _capabilities = MutableStateFlow(ModelsDevFeatureCache.capabilities)
    val capabilities: StateFlow<Map<String, ModelCapabilities>> = _capabilities.asStateFlow()

    suspend fun loadAndRefresh() = withContext(Dispatchers.IO) {
        ModelsDevFeatureCache.loadMutex.withLock {
            if (ModelsDevFeatureCache.capabilities.isNotEmpty()) {
                _capabilities.value = ModelsDevFeatureCache.capabilities
            }

            if (ModelsDevFeatureCache.capabilities.isEmpty()) {
                runCatching {
                    if (cacheFile.isFile) {
                        val cached: Map<String, ModelCapabilities> =
                            modelsDevJson.decodeFromString(cacheFile.readText())
                        ModelsDevFeatureCache.capabilities = cached
                        ModelsDevFeatureCache.updatedAt = cacheFile.lastModified()
                        _capabilities.value = cached
                    }
                }.onFailure { DebugLog.d("ModelsDev", "Capability cache read failed", it) }
            }

            val cacheIsFresh = ModelsDevFeatureCache.capabilities.isNotEmpty() &&
                System.currentTimeMillis() - ModelsDevFeatureCache.updatedAt < MODELS_DEV_CACHE_TTL_MS
            if (cacheIsFresh) return@withLock

            runCatching {
                val raw = HttpClient.fetchModels(MODELS_DEV_CATALOG_URL) ?: return@runCatching
                val parsed = parseModelsDevCapabilities(raw)
                if (parsed.isNotEmpty()) {
                    ModelsDevFeatureCache.capabilities = parsed
                    ModelsDevFeatureCache.updatedAt = System.currentTimeMillis()
                    _capabilities.value = parsed
                    cacheFile.writeText(modelsDevJson.encodeToString(parsed))
                }
            }.onFailure { DebugLog.d("ModelsDev", "Catalog refresh failed", it) }
        }
    }

    fun capabilitiesFor(modelId: String, providerFast: Boolean? = null): ModelCapabilities {
        val parsed = ModelId.parse(modelId)
        return capabilitiesFor(parsed.providerName, parsed.apiModelName, providerFast)
    }

    fun capabilitiesFor(
        providerName: String,
        apiModelName: String,
        providerFast: Boolean? = null,
    ): ModelCapabilities {
        return findModelsDevCapabilities(
            capabilities = _capabilities.value,
            apiModelName = apiModelName,
            providerFast = providerFast,
        )
    }

    companion object {
        internal fun key(providerId: String, modelId: String): String =
            "${providerId.lowercase()}:${modelId.lowercase()}"
    }
}
