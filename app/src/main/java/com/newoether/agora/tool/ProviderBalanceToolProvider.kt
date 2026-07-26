package com.newoether.agora.tool

import com.newoether.agora.api.ProviderBalanceClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.ProviderKeyBalance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exposes the per-provider balance probes (settings → Provider → 获取账户余额) to
 * the model, so it can answer "how much credit do I have left?" itself.
 *
 * The tool only exists while at least one provider has a runnable probe — an
 * unconfigured app never advertises a tool the model cannot use. The actual
 * request is delegated through [probe] (wired to `ProviderRegistry.fetchBalance`),
 * keeping Base URL / API-key resolution in one place.
 */
class ProviderBalanceToolProvider(
    private val settings: SettingsRepository,
) : ToolProvider {

    /** Set by [com.newoether.agora.viewmodel.GenerationManager]; null = probe unavailable. */
    var probe: (suspend (String) -> List<ProviderKeyBalance>)? = null

    private fun configuredProviders(): List<String> =
        settings.providerBalanceConfigs.value
            .filterValues { it.isRunnable }
            .keys
            .filter { settings.isProviderEnabled(it) }
            .sorted()

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        val providers = configuredProviders()
        if (providers.isEmpty()) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "get_provider_balance",
                    description = "Query the remaining credit / account balance of the user's AI provider accounts. " +
                        "Providers with a balance endpoint configured: ${providers.joinToString(", ")}. " +
                        "Omit \"provider\" to query all of them. Each provider is queried once per stored API " +
                        "key, so the result lists a value per key — balances differ from key to key. The value " +
                        "is the raw field from the provider API — report it as-is, and note that its meaning " +
                        "(remaining credit vs. total usage) depends on the endpoint the user configured.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "provider" to ToolProperty(
                                "string",
                                "Provider name to query, one of: ${providers.joinToString(", ")}. Omit to query all."
                            )
                        ),
                        required = emptyList(),
                    ),
                )
            )
        )
    }

    override fun handles(name: String): Boolean = name == "get_provider_balance"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != "get_provider_balance") return """{"error":"unknown_tool"}"""
        val runProbe = probe
            ?: return buildJsonObject { put("error", "unavailable") }.toString()

        val args = runCatching {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
                arguments.ifBlank { "{}" }
            )
        }.getOrDefault(emptyMap())
        val requested = (args["provider"] as? JsonPrimitive)?.content?.trim().orEmpty()

        val configured = configuredProviders()
        if (configured.isEmpty()) {
            return buildJsonObject { put("error", "not_configured") }.toString()
        }
        val targets = if (requested.isBlank()) configured else {
            // Match case-insensitively: the model tends to echo "openai" for "OpenAI".
            val match = configured.firstOrNull { it.equals(requested, ignoreCase = true) }
                ?: return buildJsonObject {
                    put("error", "unknown_provider")
                    put("requested", requested)
                    put("available", configured.joinToString(", "))
                }.toString()
            listOf(match)
        }

        val results = buildJsonArray {
            targets.forEach { target ->
                val readings = runProbe(target)
                add(buildJsonObject {
                    put("provider", target)
                    settings.providerBalanceConfigs.value[target]?.let { put("field", it.jsonKey) }
                    if (readings.isEmpty()) {
                        put("status", "error")
                        put("error", "not_configured")
                        return@buildJsonObject
                    }
                    put("keys", buildJsonArray {
                        readings.forEach { reading ->
                            add(buildJsonObject {
                                if (reading.keyName.isNotBlank()) put("key", reading.keyName)
                                when (val result = reading.result) {
                                    is ProviderBalanceClient.Result.Success -> {
                                        put("status", "ok")
                                        put("value", result.value)
                                        put("endpoint", result.url)
                                    }
                                    is ProviderBalanceClient.Result.Failure -> {
                                        put("status", "error")
                                        put("error", result.code)
                                        if (result.detail.isNotBlank()) put("message", result.detail)
                                    }
                                }
                            })
                        }
                    })
                })
            }
        }
        return buildJsonObject {
            put("type", "provider_balance")
            put("results", results)
        }.toString()
    }
}
