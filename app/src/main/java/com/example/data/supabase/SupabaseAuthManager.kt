package com.example.data.supabase

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

private val Context.supabaseAuthDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "feira_supabase_auth"
)

data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String,
    val displayName: String,
    val expiresAtEpochSec: Long
) {
    fun isExpiringSoon(nowEpochSec: Long = System.currentTimeMillis() / 1000L): Boolean {
        return expiresAtEpochSec <= nowEpochSec + 60L
    }
}

sealed interface SupabaseAuthState {
    data object Loading : SupabaseAuthState
    data object SignedOut : SupabaseAuthState
    data class SignedIn(val session: SupabaseSession) : SupabaseAuthState
}

sealed interface SignUpOutcome {
    data class SignedIn(val session: SupabaseSession) : SignUpOutcome
    data class ConfirmationRequired(val email: String) : SignUpOutcome
}

class SupabaseAuthManager private constructor(
    context: Context,
    val httpClient: OkHttpClient = SupabaseConfig.createHttpClient()
) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.supabaseAuthDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    private val _authState = MutableStateFlow<SupabaseAuthState>(SupabaseAuthState.Loading)
    val authState: StateFlow<SupabaseAuthState> = _authState.asStateFlow()

    val currentSession: SupabaseSession?
        get() = (_authState.value as? SupabaseAuthState.SignedIn)?.session

    init {
        scope.launch {
            restoreSavedSession()
        }
    }

    private suspend fun restoreSavedSession() {
        val prefs = runCatching { dataStore.data.first() }.getOrNull()
        if (prefs == null) {
            _authState.value = SupabaseAuthState.SignedOut
            return
        }
        val accessToken = prefs[KEY_ACCESS_TOKEN].orEmpty()
        val refreshToken = prefs[KEY_REFRESH_TOKEN].orEmpty()
        val userId = prefs[KEY_USER_ID].orEmpty()
        val email = prefs[KEY_EMAIL].orEmpty()
        val displayName = prefs[KEY_DISPLAY_NAME].orEmpty()
        val expiresAt = prefs[KEY_EXPIRES_AT] ?: 0L

        if (accessToken.isBlank() || userId.isBlank()) {
            _authState.value = SupabaseAuthState.SignedOut
            return
        }

        val saved = SupabaseSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = email,
            displayName = displayName.ifBlank { email.substringBefore('@').ifBlank { "Comprador" } },
            expiresAtEpochSec = expiresAt
        )
        _authState.value = SupabaseAuthState.SignedIn(saved)

        if (saved.isExpiringSoon() && saved.refreshToken.isNotBlank() && SupabaseConfig.isConfigured()) {
            refreshSessionLocked(saved, force = true)
        }
    }

    suspend fun signInWithPassword(rawEmail: String, password: String): Result<SupabaseSession> {
        if (!SupabaseConfig.isConfigured()) {
            return Result.failure(
                IllegalStateException(
                    "Supabase não configurado. Adicione SUPABASE_URL e SUPABASE_ANON_KEY no painel Secrets do AI Studio."
                )
            )
        }
        val email = rawEmail.trim()
        if (email.isEmpty() || !email.contains("@")) {
            return Result.failure(IllegalArgumentException("Informe um e-mail válido."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("A senha deve ter pelo menos 6 caracteres."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }.toString()

                val request = Request.Builder()
                    .url("${SupabaseConfig.baseUrl}/auth/v1/token?grant_type=password")
                    .addHeader("apikey", SupabaseConfig.anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toRequestBody(SupabaseConfig.JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val msg = SupabaseConfig.mapAuthErrorMessage(response.code, body)
                        return@withContext Result.failure(SupabaseHttpException(response.code, null, msg))
                    }
                    val session = parseSessionFromJson(JSONObject(body), fallbackEmail = email)
                        ?: return@withContext Result.failure(
                            IllegalStateException("Resposta inválida do servidor Supabase.")
                        )
                    persistSession(session)
                    _authState.value = SupabaseAuthState.SignedIn(session)
                    Result.success(session)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                Result.failure(IOException("Sem conexão com o Supabase. Verifique sua internet."))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun signUpWithEmail(
        rawName: String,
        rawEmail: String,
        password: String
    ): Result<SignUpOutcome> {
        if (!SupabaseConfig.isConfigured()) {
            return Result.failure(
                IllegalStateException(
                    "Supabase não configurado. Adicione SUPABASE_URL e SUPABASE_ANON_KEY no painel Secrets do AI Studio."
                )
            )
        }
        val name = rawName.trim().take(60)
        val email = rawEmail.trim()
        if (email.isEmpty() || !email.contains("@")) {
            return Result.failure(IllegalArgumentException("Informe um e-mail válido."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("A senha deve ter pelo menos 6 caracteres."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                    if (name.isNotEmpty()) {
                        put("data", JSONObject().apply { put("full_name", name) })
                    }
                }.toString()

                val request = Request.Builder()
                    .url("${SupabaseConfig.baseUrl}/auth/v1/signup")
                    .addHeader("apikey", SupabaseConfig.anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toRequestBody(SupabaseConfig.JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val msg = SupabaseConfig.mapAuthErrorMessage(response.code, body)
                        return@withContext Result.failure(SupabaseHttpException(response.code, null, msg))
                    }
                    val json = JSONObject(body)
                    val session = parseSessionFromJson(
                        json = json,
                        fallbackEmail = email,
                        fallbackName = name
                    )
                    if (session != null) {
                        persistSession(session)
                        _authState.value = SupabaseAuthState.SignedIn(session)
                        Result.success(SignUpOutcome.SignedIn(session))
                    } else {
                        Result.success(SignUpOutcome.ConfirmationRequired(email))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                Result.failure(IOException("Sem conexão com o Supabase. Verifique sua internet."))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): Result<SupabaseSession> {
        val existing = currentSession
            ?: return Result.failure(IllegalStateException("Usuário precisa entrar com o Supabase antes de acessar os dados."))
        if (!forceRefresh && !existing.isExpiringSoon()) {
            return Result.success(existing)
        }
        return refreshSessionLocked(existing, force = forceRefresh)
    }

    private suspend fun refreshSessionLocked(
        expectedSession: SupabaseSession,
        force: Boolean
    ): Result<SupabaseSession> = refreshMutex.withLock {
        val latest = currentSession ?: expectedSession
        if (!force && !latest.isExpiringSoon()) {
            return Result.success(latest)
        }
        if (latest.refreshToken.isBlank()) {
            return Result.failure(IllegalStateException("Sessão expirada. Entre novamente."))
        }
        if (!SupabaseConfig.isConfigured()) {
            return Result.failure(IllegalStateException("Supabase não configurado."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("refresh_token", latest.refreshToken)
                }.toString()

                val request = Request.Builder()
                    .url("${SupabaseConfig.baseUrl}/auth/v1/token?grant_type=refresh_token")
                    .addHeader("apikey", SupabaseConfig.anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toRequestBody(SupabaseConfig.JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (response.code in listOf(400, 401, 403)) {
                            clearPersistedSession()
                            _authState.value = SupabaseAuthState.SignedOut
                        }
                        val msg = SupabaseConfig.mapAuthErrorMessage(response.code, body)
                        return@withContext Result.failure(SupabaseHttpException(response.code, null, msg))
                    }
                    val refreshed = parseSessionFromJson(
                        json = JSONObject(body),
                        fallbackEmail = latest.email,
                        fallbackName = latest.displayName
                    ) ?: return@withContext Result.failure(
                        IllegalStateException("Falha ao renovar sessão Supabase.")
                    )
                    persistSession(refreshed)
                    _authState.value = SupabaseAuthState.SignedIn(refreshed)
                    Result.success(refreshed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Keep cached session while offline so Room queue continues working
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun signOut() {
        val token = currentSession?.accessToken
        withContext(Dispatchers.IO) {
            if (!token.isNullOrBlank() && SupabaseConfig.isConfigured()) {
                runCatching {
                    val request = Request.Builder()
                        .url("${SupabaseConfig.baseUrl}/auth/v1/logout")
                        .addHeader("apikey", SupabaseConfig.anonKey)
                        .addHeader("Authorization", "Bearer $token")
                        .post("".toRequestBody(SupabaseConfig.JSON_MEDIA_TYPE))
                        .build()
                    httpClient.newCall(request).execute().close()
                }
            }
            clearPersistedSession()
        }
        _authState.value = SupabaseAuthState.SignedOut
    }

    private suspend fun persistSession(session: SupabaseSession) {
        dataStore.edit { prefs ->
            prefs[KEY_ACCESS_TOKEN] = session.accessToken
            prefs[KEY_REFRESH_TOKEN] = session.refreshToken
            prefs[KEY_USER_ID] = session.userId
            prefs[KEY_EMAIL] = session.email
            prefs[KEY_DISPLAY_NAME] = session.displayName
            prefs[KEY_EXPIRES_AT] = session.expiresAtEpochSec
        }
    }

    private suspend fun clearPersistedSession() {
        dataStore.edit { it.clear() }
    }

    internal fun parseSessionFromJson(
        json: JSONObject,
        fallbackEmail: String = "",
        fallbackName: String = ""
    ): SupabaseSession? {
        val accessToken = json.optString("access_token")
        if (accessToken.isBlank()) return null

        val userObj = json.optJSONObject("user") ?: return null
        val userId = userObj.optString("id")
        if (userId.isBlank()) return null

        val refreshToken = json.optString("refresh_token")
        val email = userObj.optString("email").ifBlank { fallbackEmail }
        val meta = userObj.optJSONObject("user_metadata")
        val metaName = meta?.optString("full_name")?.ifBlank { meta.optString("name") }.orEmpty()
        val displayName = metaName.ifBlank { fallbackName }.ifBlank {
            email.substringBefore('@').ifBlank { "Comprador" }
        }
        val expiresAt = json.optLong("expires_at", 0L).let { raw ->
            if (raw > 0L) raw else (System.currentTimeMillis() / 1000L) + json.optLong("expires_in", 3600L)
        }

        return SupabaseSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = email,
            displayName = displayName,
            expiresAtEpochSec = expiresAt
        )
    }

    companion object {
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_USER_ID = stringPreferencesKey("user_id")
        private val KEY_EMAIL = stringPreferencesKey("email")
        private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        private val KEY_EXPIRES_AT = longPreferencesKey("expires_at")

        @Volatile
        private var instance: SupabaseAuthManager? = null

        fun getInstance(context: Context): SupabaseAuthManager {
            return instance ?: synchronized(this) {
                instance ?: SupabaseAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
