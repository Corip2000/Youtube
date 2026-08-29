package ru.corip.shortsoffline

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Вход в Google по OAuth 2.0 + PKCE через системный браузер.
 * Play Services не нужны, client secret тоже — для Android-клиента его не бывает.
 */
class Auth(context: Context, private val store: Store) {

    private val app = context.applicationContext
    private val service = AuthorizationService(app)

    private val config = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token"),
    )

    private var state: AuthState = store.authState
        ?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
        ?: AuthState(config)

    val isSignedIn: Boolean get() = state.isAuthorized

    fun buildIntent(): Intent {
        val clientId = store.clientId
        require(clientId.isNotBlank()) { "Сначала вставь OAuth client ID." }
        val request = AuthorizationRequest.Builder(
            config,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse("${app.packageName}:/oauth2redirect"),
        )
            .setScope("https://www.googleapis.com/auth/youtube.readonly")
            .setPrompt("consent")
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Меняет код авторизации на токены и сохраняет их. */
    suspend fun handleResult(data: Intent?): Result<Unit> {
        val intent = data ?: return Result.failure(ApiError("Вход отменён."))
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)
        state.update(response, error)
        if (response == null) {
            return Result.failure(ApiError(error?.errorDescription ?: "Вход не удался."))
        }
        return runCatching {
            suspendCancellableCoroutine { cont ->
                service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, ex ->
                    state.update(tokens, ex)
                    persist()
                    if (tokens != null) cont.resume(Unit)
                    else cont.resumeWithException(ApiError(ex?.errorDescription ?: "Токен не выдан."))
                }
            }
        }
    }

    /** Действующий access token, при необходимости обновлённый. */
    suspend fun accessToken(): String? {
        if (!state.isAuthorized) return null
        return runCatching {
            suspendCancellableCoroutine<String?> { cont ->
                state.performActionWithFreshTokens(service) { token, _, ex ->
                    persist()
                    if (ex != null) cont.resume(null) else cont.resume(token)
                }
            }
        }.getOrNull()
    }

    fun signOut() {
        state = AuthState(config)
        store.authState = null
    }

    private fun persist() {
        store.authState = state.jsonSerializeString()
    }

    fun dispose() = service.dispose()
}
