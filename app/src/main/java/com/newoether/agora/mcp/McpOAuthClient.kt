package com.newoether.agora.mcp

import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OAuth 2.1 client for MCP protected-resource discovery, PKCE, DCR and token refresh. */
internal class McpOAuthClient(private val httpClient: OkHttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Serializable
    data class ProtectedResourceMetadata(
        val resource: String? = null,
        @SerialName("authorization_servers") val authorizationServers: List<String> = emptyList(),
        @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
    )

    @Serializable
    data class AuthorizationServerMetadata(
        val issuer: String? = null,
        @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
        @SerialName("token_endpoint") val tokenEndpoint: String? = null,
        @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
        @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
    )

    @Serializable
    private data class ClientRegistrationRequest(
        @SerialName("client_name") val clientName: String,
        @SerialName("redirect_uris") val redirectUris: List<String>,
        @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
        @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
        @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
        val scope: String? = null,
    )

    @Serializable
    data class ClientRegistrationResponse(
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String? = null,
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") val expiresIn: Long? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val scope: String? = null,
    )

    data class Pkce(val verifier: String, val challenge: String)

    suspend fun discoverProtectedResource(serverUrl: String): ProtectedResourceMetadata {
        val candidates = buildList {
            probeResourceMetadataUrl(serverUrl)?.let(::add)
            addAll(wellKnownProtectedResourceUrls(serverUrl))
        }.distinct()
        for (url in candidates) {
            val metadata = runCatching { getJson<ProtectedResourceMetadata>(url) }.getOrNull()
            if (metadata != null && metadata.authorizationServers.isNotEmpty()) return metadata
        }
        error("Unable to discover OAuth protected-resource metadata")
    }

    suspend fun discoverAuthorizationServer(issuer: String): AuthorizationServerMetadata {
        for (url in wellKnownAuthorizationServerUrls(issuer)) {
            val metadata = runCatching { getJson<AuthorizationServerMetadata>(url) }.getOrNull()
            if (metadata?.authorizationEndpoint != null && metadata.tokenEndpoint != null) return metadata
        }
        error("Unable to discover authorization-server metadata for $issuer")
    }

    suspend fun registerClient(
        registrationEndpoint: String,
        clientName: String,
        scope: String?,
    ): ClientRegistrationResponse {
        val body = json.encodeToString(
            ClientRegistrationRequest.serializer(),
            ClientRegistrationRequest(
                clientName = clientName.ifBlank { "Agora" },
                redirectUris = listOf(MCP_OAUTH_REDIRECT_URI),
                scope = scope,
            ),
        )
        return json.decodeFromString(
            execute(
                Request.Builder()
                    .url(registrationEndpoint)
                    .header("Accept", "application/json")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
        )
    }

    fun generatePkce(): Pkce {
        val verifier = base64Url(ByteArray(32).also(SecureRandom()::nextBytes))
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return Pkce(verifier, challenge)
    }

    fun generateState(): String = base64Url(ByteArray(16).also(SecureRandom()::nextBytes))

    fun buildAuthorizationUrl(
        authorizationEndpoint: String,
        clientId: String,
        pkce: Pkce,
        state: String,
        scope: String?,
        resource: String,
    ): String {
        val base = authorizationEndpoint.toHttpUrlOrNull()
            ?: error("Invalid authorization endpoint: $authorizationEndpoint")
        return base.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", MCP_OAUTH_REDIRECT_URI)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("resource", resource)
            .apply { if (!scope.isNullOrBlank()) addQueryParameter("scope", scope) }
            .build()
            .toString()
    }

    suspend fun exchangeCode(
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String?,
        code: String,
        codeVerifier: String,
        resource: String,
    ): TokenResponse = postToken(
        tokenEndpoint,
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", MCP_OAUTH_REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .add("resource", resource)
            .apply { if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret) }
            .build(),
    )

    suspend fun refreshToken(
        tokenEndpoint: String,
        clientId: String,
        clientSecret: String?,
        refreshToken: String,
        resource: String,
        scope: String?,
    ): TokenResponse = postToken(
        tokenEndpoint,
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .add("resource", resource)
            .apply {
                if (!clientSecret.isNullOrBlank()) add("client_secret", clientSecret)
                if (!scope.isNullOrBlank()) add("scope", scope)
            }
            .build(),
    )

    private suspend fun postToken(endpoint: String, body: FormBody): TokenResponse =
        json.decodeFromString(
            execute(
                Request.Builder().url(endpoint).header("Accept", "application/json").post(body).build()
            )
        )

    private suspend fun probeResourceMetadataUrl(serverUrl: String): String? {
        val request = Request.Builder()
            .url(serverUrl)
            .header("Accept", "application/json, text/event-stream")
            .get()
            .build()
        return runCatching {
            executeRaw(request).use { response ->
                if (response.code != 401) return null
                response.header("WWW-Authenticate")
                    ?.let { Regex("resource_metadata=\"([^\"]+)\"").find(it) }
                    ?.groupValues
                    ?.getOrNull(1)
            }
        }.getOrNull()
    }

    private fun wellKnownProtectedResourceUrls(serverUrl: String): List<String> =
        serverUrl.toHttpUrlOrNull()?.let { url ->
            val origin = "${url.scheme}://${url.host}${portSuffix(url)}"
            val path = url.encodedPath.trimEnd('/')
            buildList {
                if (path.isNotEmpty() && path != "/") add("$origin/.well-known/oauth-protected-resource$path")
                add("$origin/.well-known/oauth-protected-resource")
            }.distinct()
        }.orEmpty()

    private fun wellKnownAuthorizationServerUrls(issuer: String): List<String> =
        issuer.toHttpUrlOrNull()?.let { url ->
            val origin = "${url.scheme}://${url.host}${portSuffix(url)}"
            val path = url.encodedPath.trimEnd('/')
            buildList {
                if (path.isNotEmpty() && path != "/") {
                    add("$origin/.well-known/oauth-authorization-server$path")
                    add("$origin/.well-known/openid-configuration$path")
                    add("$origin$path/.well-known/openid-configuration")
                }
                add("$origin/.well-known/oauth-authorization-server")
                add("$origin/.well-known/openid-configuration")
            }.distinct()
        }.orEmpty()

    private fun portSuffix(url: HttpUrl): String =
        if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"

    private suspend inline fun <reified T> getJson(url: String): T = json.decodeFromString(
        execute(Request.Builder().url(url).header("Accept", "application/json").get().build())
    )

    private suspend fun execute(request: Request): String = executeRaw(request).use { response ->
        val body = response.body.string()
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code} for ${request.url}: ${body.take(300)}")
        }
        body
    }

    private suspend fun executeRaw(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response)
                else response.close()
            }
        })
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun base64Url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        fun canonicalResource(serverUrl: String): String =
            serverUrl.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString() ?: serverUrl
    }
}
