package com.newoether.agora.api.openai

import com.newoether.agora.api.*
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.security.MessageDigest

private const val OPENAI_API_HOST = "api.openai.com"
private const val CACHE_FINGERPRINT_VERSION = "agora-openai-capabilities-v1"

/**
 * The built-in OpenAI provider may point at a user-supplied compatible relay. Keep
 * OpenAI-specific request fields away from those relays because some reject unknown keys.
 */
internal fun isOfficialOpenAiEndpoint(baseUrl: String?): Boolean {
    if (baseUrl.isNullOrBlank()) return true
    return runCatching { URI(baseUrl.trim()).host.equals(OPENAI_API_HOST, ignoreCase = true) }
        .getOrDefault(false)
}

/**
 * Builds a stable, opaque cache-routing key from capability metadata only.
 *
 * User messages, system/memory text, API keys, and conversation ids are deliberately not
 * included. OpenAI still validates the actual prompt prefix before serving cached tokens;
 * this key only keeps requests with the same capability surface on a consistent route.
 */
internal fun openAiPromptCacheKey(config: ProviderConfig): String {
    val capabilityFingerprint = buildString {
        append(CACHE_FINGERPRINT_VERSION).append('\n')
        append("model=").append(config.modelId).append('\n')
        append("code=").append(config.codeExecutionEnabled).append('\n')
        append("search=").append(config.googleSearchEnabled).append('\n')
        append("images=").append(config.includeImages).append('\n')
        config.tools.orEmpty()
            .sortedBy { it.function.name }
            .forEach { tool ->
                append("tool=").append(tool.function.name).append('\n')
                append("description=").append(tool.function.description).append('\n')
                appendCanonicalJson(tool.function.parameters.asJsonObject())
                append('\n')
            }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(capabilityFingerprint.toByteArray(Charsets.UTF_8))
        .toLowerHex()
}

internal fun OpenAiChatRequest.withOfficialPromptCaching(
    config: ProviderConfig
): OpenAiChatRequest = copy(
    promptCacheKey = openAiPromptCacheKey(config)
        .takeIf { isOfficialOpenAiEndpoint(config.baseUrl) }
)

private fun StringBuilder.appendCanonicalJson(element: JsonElement) {
    when (element) {
        is JsonObject -> {
            append('{')
            element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                append(kotlinx.serialization.json.JsonPrimitive(key))
                append(':')
                appendCanonicalJson(value)
            }
            append('}')
        }
        is JsonArray -> {
            append('[')
            element.forEachIndexed { index, value ->
                if (index > 0) append(',')
                appendCanonicalJson(value)
            }
            append(']')
        }
        else -> append(element)
    }
}

private fun ByteArray.toLowerHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        this@toLowerHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}

class OpenAiProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_OPENAI
    override val defaultBaseUrl: String = "https://api.openai.com/v1"

    override fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest {
        val isReasoningModel = config.modelId.startsWith("o1") ||
            config.modelId.startsWith("o3") ||
            config.modelId.startsWith("o4") ||
            config.modelId.startsWith("gpt-5")
        var customized = request.withOfficialPromptCaching(config)
        if (config.thinkingEnabled && isReasoningModel) {
            val effort = ThinkingLevels.openAiEffort(config.thinkingLevel)
            customized = customized.copy(reasoningEffort = effort)
        }
        return customized
    }
    // Reasoning/content parsing uses BaseOpenAiProvider's default (reasoning_content + content).
}
