package com.newoether.agora.tool

import android.app.Application
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * Tool that generates images from a text prompt via an OpenAI-compatible
 * `/images/generations` endpoint (BYOK). The decoded image is written to
 * filesDir as `img_<uuid>.jpg` and its path is collected per generation so the
 * GenerationManager can attach it to the correct model message for inline display.
 */
class ImageGenToolProvider(private val app: Application) : ToolProvider {

    private val pendingLock = Any()
    private val pendingByRequest = mutableMapOf<String, MutableList<String>>()

    /** Records an image against exactly one generation. */
    internal fun recordGeneratedImage(ctx: GenerationContext, path: String): Boolean {
        val requestId = ctx.capabilityRequestId?.takeIf(String::isNotBlank) ?: return false
        synchronized(pendingLock) {
            pendingByRequest.getOrPut(requestId, ::mutableListOf).add(path)
        }
        return true
    }

    /** Atomically takes only the images produced by [requestId]. */
    fun drainImages(requestId: String): List<String> = synchronized(pendingLock) {
        pendingByRequest.remove(requestId)?.toList().orEmpty()
    }

    /** Discards any undrained results owned by a completed or cancelled generation. */
    fun clearImages(requestId: String) {
        synchronized(pendingLock) {
            pendingByRequest.remove(requestId)
        }
    }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.imageGenEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "generate_image",
                description = "Generate an image from a text prompt. The generated image is shown to the user automatically — do NOT attempt to embed or describe the raw image data. Use this whenever the user asks to create, draw, paint, or generate a picture.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "prompt" to ToolProperty("string", "A detailed description of the image to generate."),
                        "size" to ToolProperty("string", "Optional image size, e.g. 1024x1024, 1024x1536, or 1536x1024.")
                    ),
                    required = listOf("prompt")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name == "generate_image"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args = try {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        } catch (_: Exception) { emptyMap() }
        val prompt = (args["prompt"] as? JsonPrimitive)?.content
            ?: return err("no_prompt", null)
        val size = (args["size"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: ctx.imageGenSize.ifBlank { "1024x1024" }
        if (ctx.capabilityRequestId.isNullOrBlank()) {
            return err(
                "missing_request_id",
                "Image generation requires a generation-scoped request ID.",
            )
        }

        val apiKey = ctx.imageGenApiKey
        if (apiKey.isBlank()) return err("no_api_key", null)
        val baseUrl = ctx.imageGenBaseUrl.ifBlank { ProviderDefaults.OPENAI_BASE_URL }.trimEnd('/')
        val model = ctx.imageGenModel.ifBlank { "gpt-image-1" }

        return withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("model", model)
                    put("prompt", prompt)
                    put("size", size)
                    put("n", 1)
                }.toString()
                val endpoint = "$baseUrl/images/generations"
                val response = HttpClient.post(
                    endpoint,
                    body,
                    mapOf("Authorization" to "Bearer $apiKey")
                )
                if (response == null) {
                    return@withContext err(
                        "no_response",
                        "Image API request failed (check model, key, base URL: $endpoint)."
                    )
                }

                val parsed = Json { ignoreUnknownKeys = true }
                    .decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(response)
                // Some providers return {"error":{...}} even on non-2xx-handled paths.
                val apiError = parsed["error"]
                if (apiError != null) {
                    val msg = try {
                        val obj = apiError.jsonObject
                        (obj["message"] as? JsonPrimitive)?.content
                            ?: (obj["code"] as? JsonPrimitive)?.content
                            ?: apiError.toString()
                    } catch (_: Exception) {
                        apiError.toString()
                    }
                    return@withContext err("api_error", msg)
                }
                val first = parsed["data"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@withContext err("no_image", "The endpoint returned no image data.")

                val bytes: ByteArray = run {
                    val b64 = (first["b64_json"] as? JsonPrimitive)?.content
                    if (!b64.isNullOrBlank()) {
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    } else {
                        val url = (first["url"] as? JsonPrimitive)?.content
                            ?: return@withContext err("no_image", "No b64_json or url in the response.")
                        HttpClient.getBytes(url)
                            ?: return@withContext err("download_failed", "Failed to download image URL.")
                    }
                }
                if (bytes.isEmpty()) {
                    return@withContext err("empty_image", "Decoded image was empty.")
                }

                val file = File(app.filesDir, "img_${UUID.randomUUID()}.jpg")
                file.outputStream().use { it.write(bytes) }
                if (!file.exists() || file.length() == 0L) {
                    return@withContext err("write_failed", "Failed to write image file.")
                }
                check(recordGeneratedImage(ctx, file.absolutePath)) {
                    "Generation request ID disappeared before recording the image."
                }

                // Keep result small for the model, but explicit so UI can detect success.
                buildJsonObject {
                    put("type", "image_generation")
                    put("status", "ok")
                    put("size", size)
                    put("path", file.absolutePath)
                    put("bytes", bytes.size)
                }.toString()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                DebugLog.e("ImageGenTool", "generate_image failed", e)
                err("generation_error", e.message)
            }
        }
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "image_generation")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}
