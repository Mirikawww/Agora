package com.newoether.agora.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Provider → enabled API-key IDs.
 *
 * Wire format evolved from `Map<String, String>` (single active id) to
 * `Map<String, List<String>>` (multi-enable). [decode] / [Serializer] accept both.
 */
object ActiveApiKeyIds {
    fun decode(json: Json, raw: String): Map<String, List<String>> {
        if (raw.isBlank() || raw == "{}") return emptyMap()
        return try {
            json.decodeFromString(Serializer, raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun encode(json: Json, value: Map<String, List<String>>): String =
        json.encodeToString(Serializer, value)

    object Serializer : KSerializer<Map<String, List<String>>> {
        override val descriptor = buildClassSerialDescriptor("ActiveApiKeyIds")

        override fun serialize(encoder: Encoder, value: Map<String, List<String>>) {
            val jsonEncoder = encoder as? JsonEncoder
                ?: error("ActiveApiKeyIds.Serializer requires JsonEncoder")
            jsonEncoder.encodeJsonElement(
                JsonObject(
                    value.mapValues { (_, ids) ->
                        JsonArray(ids.map { JsonPrimitive(it) })
                    }
                )
            )
        }

        override fun deserialize(decoder: Decoder): Map<String, List<String>> {
            val jsonDecoder = decoder as? JsonDecoder
                ?: error("ActiveApiKeyIds.Serializer requires JsonDecoder")
            return parseElement(jsonDecoder.decodeJsonElement())
        }

        fun parseElement(element: JsonElement): Map<String, List<String>> {
            val obj = element as? JsonObject ?: return emptyMap()
            return buildMap {
                for ((provider, node) in obj) {
                    val ids = when (node) {
                        is JsonArray -> node.mapNotNull { it.jsonPrimitive.contentOrNull }
                        is JsonPrimitive -> node.contentOrNull?.let { listOf(it) } ?: emptyList()
                        else -> emptyList()
                    }
                    if (ids.isNotEmpty()) put(provider, ids)
                }
            }
        }
    }
}
