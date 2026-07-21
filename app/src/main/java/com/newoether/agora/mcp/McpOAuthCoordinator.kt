package com.newoether.agora.mcp

import android.content.Context
import com.newoether.agora.data.McpOAuthState
import com.newoether.agora.data.McpServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

private const val TOKEN_REFRESH_LEEWAY_MS = 60_000L

/** Coordinates OAuth authorization jobs and serializes token refresh per server. */
internal class McpOAuthCoordinator(
    private val scope: CoroutineScope,
    private val oauthClient: McpOAuthClient,
    private val currentConfig: (String) -> McpServerConfig?,
    private val persistConfig: suspend (McpServerConfig) -> Unit,
    private val updateStatus: (String, McpStatus) -> Unit,
) {
    private val authorizationJobs = ConcurrentHashMap<String, Job>()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        authorizationJobs.remove(config.id)?.cancel()
        val job = scope.launch {
            updateStatus(config.id, McpStatus.Authorizing)
            try {
                authorize(currentConfig(config.id) ?: config, context.applicationContext)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateStatus(config.id, McpStatus.Error.from(error, "OAuth authorization failed"))
            }
        }
        authorizationJobs[config.id] = job
        job.invokeOnCompletion { authorizationJobs.remove(config.id, job) }
    }

    fun cancelAuthorization(serverId: String) {
        authorizationJobs.remove(serverId)?.cancel()
        updateStatus(serverId, McpStatus.NeedsAuthorization)
    }

    fun forget(serverId: String) {
        authorizationJobs.remove(serverId)?.cancel()
        refreshLocks.remove(serverId)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        persistConfig(config.copy(oauth = null))
    }

    suspend fun ensureFreshToken(configInput: McpServerConfig): McpServerConfig {
        return refreshLocks.getOrPut(configInput.id) { Mutex() }.withLock {
            val config = currentConfig(configInput.id) ?: configInput
            val oauth = config.oauth ?: return@withLock config
            if (!oauth.enabled || oauth.refreshToken.isNullOrBlank()) return@withLock config
            val expired = oauth.expiresAt > 0L &&
                System.currentTimeMillis() >= oauth.expiresAt - TOKEN_REFRESH_LEEWAY_MS
            if (!oauth.accessToken.isNullOrBlank() && !expired) return@withLock config

            val tokenEndpoint = oauth.tokenEndpoint ?: return@withLock config
            val clientId = oauth.clientId ?: return@withLock config
            runCatching {
                oauthClient.refreshToken(
                    tokenEndpoint = tokenEndpoint,
                    clientId = clientId,
                    clientSecret = oauth.clientSecret,
                    refreshToken = oauth.refreshToken,
                    resource = McpOAuthClient.canonicalResource(config.url),
                    scope = oauth.scope,
                )
            }.fold(
                onSuccess = { token ->
                    val updated = config.copy(
                        oauth = oauth.copy(
                            accessToken = token.accessToken,
                            refreshToken = token.refreshToken ?: oauth.refreshToken,
                            expiresAt = token.expiresIn.toExpiryMillis(),
                            scope = token.scope ?: oauth.scope,
                        )
                    )
                    persistConfig(updated)
                    updated
                },
                onFailure = { config },
            )
        }
    }

    suspend fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        if (!looksUnauthorized(error)) return false
        if (config.oauth?.enabled == true) return true
        if (config.bearerToken.isNotBlank() ||
            config.headers.keys.any { it.equals("Authorization", ignoreCase = true) }
        ) return false
        return runCatching { oauthClient.discoverProtectedResource(config.url) }.isSuccess
    }

    private suspend fun authorize(config: McpServerConfig, context: Context) {
        require(config.url.isNotBlank()) { "Server URL is required for OAuth" }
        val protectedResource = oauthClient.discoverProtectedResource(config.url)
        val issuer = protectedResource.authorizationServers.firstOrNull()
            ?: error("Protected resource did not declare an authorization server")
        val metadata = oauthClient.discoverAuthorizationServer(issuer)
        val authorizationEndpoint = metadata.authorizationEndpoint
            ?: error("Authorization server is missing authorization_endpoint")
        val tokenEndpoint = metadata.tokenEndpoint
            ?: error("Authorization server is missing token_endpoint")
        val existing = config.oauth
        val scopeValue = existing?.scope
            ?: protectedResource.scopesSupported?.joinToString(" ")
            ?: metadata.scopesSupported?.joinToString(" ")

        var clientId = existing?.clientId
        var clientSecret = existing?.clientSecret
        if (clientId.isNullOrBlank()) {
            val registrationEndpoint = metadata.registrationEndpoint
                ?: error("Authorization server does not support dynamic registration; configure client_id")
            oauthClient.registerClient(registrationEndpoint, config.name, scopeValue).also { registration ->
                clientId = registration.clientId
                clientSecret = registration.clientSecret
            }
        }
        val resolvedClientId = requireNotNull(clientId)
        val pkce = oauthClient.generatePkce()
        val state = oauthClient.generateState()
        val resource = McpOAuthClient.canonicalResource(config.url)
        val pending = config.copy(
            oauth = (existing ?: McpOAuthState()).copy(
                enabled = true,
                clientId = resolvedClientId,
                clientSecret = clientSecret,
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                registrationEndpoint = metadata.registrationEndpoint,
                scope = scopeValue,
            )
        )
        persistConfig(pending)

        val authorizationUrl = oauthClient.buildAuthorizationUrl(
            authorizationEndpoint = authorizationEndpoint,
            clientId = resolvedClientId,
            pkce = pkce,
            state = state,
            scope = scopeValue,
            resource = resource,
        )
        val callback = awaitCallbackAndLaunchBrowser(context, authorizationUrl, state)
            ?: error("OAuth authorization timed out")
        callback.error?.let { error("Authorization failed: $it") }
        val code = callback.code ?: error("Authorization response did not contain a code")
        val token = oauthClient.exchangeCode(
            tokenEndpoint = tokenEndpoint,
            clientId = resolvedClientId,
            clientSecret = clientSecret,
            code = code,
            codeVerifier = pkce.verifier,
            resource = resource,
        )
        persistConfig(
            pending.copy(
                oauth = requireNotNull(pending.oauth).copy(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    expiresAt = token.expiresIn.toExpiryMillis(),
                    scope = token.scope ?: scopeValue,
                )
            )
        )
    }

    private suspend fun awaitCallbackAndLaunchBrowser(
        context: Context,
        authorizationUrl: String,
        state: String,
    ): McpOAuthCallback? = coroutineScope {
        val subscribed = CompletableDeferred<Unit>()
        val callback = async {
            withTimeoutOrNull(5.minutes) {
                McpOAuthCallbackBus.events
                    .onSubscription { subscribed.complete(Unit) }
                    .first { it.state == state }
            }
        }
        subscribed.await()
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            launchMcpAuthorization(context, authorizationUrl)
        }
        callback.await()
    }

    private fun looksUnauthorized(error: Throwable): Boolean {
        val text = generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString(" ")
            .lowercase()
        return listOf("401", "unauthorized", "invalid_token", "invalid access token", "missing or invalid")
            .any(text::contains)
    }

    private fun Long?.toExpiryMillis(): Long =
        if (this != null && this > 0L) System.currentTimeMillis() + this * 1000L else 0L
}
