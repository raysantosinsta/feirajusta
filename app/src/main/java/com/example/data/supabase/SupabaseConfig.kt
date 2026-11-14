package com.example.data.supabase

import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class SupabaseHttpException(
    val statusCode: Int,
    val errorCode: String?,
    override val message: String
) : Exception(message)

object SupabaseConfig {
    const val PRODUCT_PHOTOS_BUCKET = "product-photos"
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()

    val baseUrl: String
        get() = normalizeBaseUrl(BuildConfig.SUPABASE_URL)

    val anonKey: String
        get() = BuildConfig.SUPABASE_ANON_KEY.trim().trim('"', '\'')

    fun isConfigured(
        url: String = BuildConfig.SUPABASE_URL,
        key: String = BuildConfig.SUPABASE_ANON_KEY
    ): Boolean {
        val cleanUrl = normalizeBaseUrl(url)
        val cleanKey = key.trim().trim('"', '\'')
        if (cleanUrl.isEmpty() || cleanKey.isEmpty()) return false
        if (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("http://")) return false
        if (cleanUrl.contains("YOUR_", ignoreCase = true) || cleanUrl.contains("MY_", ignoreCase = true)) {
            return false
        }
        if (cleanKey.startsWith("YOUR_", ignoreCase = true) || cleanKey.startsWith("MY_", ignoreCase = true)) {
            return false
        }
        return true
    }

    fun normalizeBaseUrl(rawUrl: String): String {
        var cleaned = rawUrl.trim().trim('"', '\'').trim()
        if (cleaned.isEmpty()) return ""

        if (!cleaned.contains("://") && cleaned.contains(".")) {
            cleaned = "https://$cleaned"
        }

        val parsed = runCatching { URI(cleaned) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase(Locale.ROOT)
        val host = parsed?.host?.lowercase(Locale.ROOT)
        val port = parsed?.port ?: -1
        val path = parsed?.path.orEmpty()

        if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
            // If the user pasted the Supabase Dashboard URL instead of the API URL,
            // e.g. https://supabase.com/dashboard/project/<ref>/settings/api -> https://<ref>.supabase.co
            if (host == "supabase.com" || host == "app.supabase.com") {
                val match = Regex("^/(?:dashboard/)?project/([a-z0-9]{10,30})(?:/|$)").find(path)
                val projectRef = match?.groupValues?.getOrNull(1)
                if (!projectRef.isNullOrBlank()) {
                    return "https://$projectRef.supabase.co"
                }
            }

            // For *.supabase.co / *.supabase.in, always use only scheme://host[:port]
            // so pasted suffixes like /rest/v1 or /auth/v1 never cause "Invalid path specified in request URL".
            if (host.endsWith(".supabase.co") || host.endsWith(".supabase.in")) {
                val portSuffix = if (port > 0) ":$port" else ""
                return "$scheme://$host$portSuffix"
            }

            // For custom domains, strip accidentally appended Supabase service paths.
            val cleanPath = path
                .replace(Regex("/(?:auth|rest|storage|realtime|functions|graphql)/v1.*$"), "")
                .trimEnd('/')
            val portSuffix = if (port > 0) ":$port" else ""
            return "$scheme://$host$portSuffix$cleanPath"
        }

        return cleaned.trimEnd('/')
    }

    fun publicStorageObjectUrl(
        bucket: String = PRODUCT_PHOTOS_BUCKET,
        objectPath: String,
        hostBaseUrl: String = baseUrl
    ): String {
        val cleanBase = normalizeBaseUrl(hostBaseUrl)
        val cleanPath = objectPath.trimStart('/')
        return "$cleanBase/storage/v1/object/public/$bucket/$cleanPath"
    }

    fun extractStorageObjectPath(
        publicUrl: String,
        bucket: String = PRODUCT_PHOTOS_BUCKET
    ): String? {
        val marker = "/storage/v1/object/public/$bucket/"
        val idx = publicUrl.indexOf(marker)
        if (idx < 0) return null
        return publicUrl.substring(idx + marker.length).substringBefore('?').takeIf { it.isNotBlank() }
    }

    fun realtimeWebSocketUrl(
        hostBaseUrl: String = baseUrl,
        key: String = anonKey
    ): String {
        val cleanBase = normalizeBaseUrl(hostBaseUrl)
        val wsBase = when {
            cleanBase.startsWith("https://") -> "wss://" + cleanBase.removePrefix("https://")
            cleanBase.startsWith("http://") -> "ws://" + cleanBase.removePrefix("http://")
            else -> cleanBase
        }
        val cleanKey = key.trim().trim('"', '\'')
        val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
        return "$wsBase/realtime/v1/websocket?apikey=$encodedKey&vsn=1.0.0"
    }

    fun createHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
                redactHeader("apikey")
            }
            builder.addInterceptor(logging)
        }
        return builder.build()
    }

    fun mapAuthErrorMessage(statusCode: Int, body: String): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val errorCode = parsed?.optString("error_code")
            ?.ifBlank { parsed.optString("error") }
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val rawMsg = parsed?.optString("msg")
            ?.ifBlank { parsed.optString("error_description") }
            ?.ifBlank { parsed.optString("message") }
            .orEmpty()
        val lowerMsg = rawMsg.lowercase(Locale.ROOT)

        return when {
            lowerMsg.contains("invalid path specified in request url") ->
                "A URL configurada em SUPABASE_URL possui um caminho inválido. Use apenas https://SEU_PROJETO.supabase.co (sem /rest/v1 ou /auth/v1) no painel Secrets."
            errorCode == "invalid_credentials" || lowerMsg.contains("invalid login credentials") ->
                "E-mail ou senha incorretos."
            errorCode == "email_not_confirmed" || lowerMsg.contains("email not confirmed") ->
                "Seu e-mail ainda não foi confirmado. Verifique sua caixa de entrada e spam."
            errorCode == "user_already_exists" || lowerMsg.contains("already registered") ->
                "Este e-mail já está cadastrado. Tente entrar com sua senha."
            errorCode == "weak_password" || lowerMsg.contains("at least 6 characters") ->
                "A senha precisa ter pelo menos 6 caracteres."
            errorCode == "validation_failed" && lowerMsg.contains("email") ->
                "Informe um endereço de e-mail válido."
            errorCode == "over_email_send_rate_limit" || statusCode == 429 ->
                "Muitas tentativas seguidas. Aguarde alguns instantes e tente novamente."
            errorCode == "signup_disabled" ->
                "Cadastro de novos usuários desativado neste projeto Supabase."
            rawMsg.isNotBlank() -> rawMsg
            else -> "Falha na autenticação Supabase (HTTP $statusCode)."
        }
    }

    fun mapRestErrorMessage(statusCode: Int, body: String): Pair<String?, String> {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val code = parsed?.optString("code")?.takeIf { it.isNotBlank() }
            ?: parsed?.optString("error")?.takeIf { it.isNotBlank() }
        val rawMessage = parsed?.optString("message")?.takeIf { it.isNotBlank() }
            ?: parsed?.optString("hint")?.takeIf { it.isNotBlank() }
            ?: parsed?.optString("details")?.takeIf { it.isNotBlank() }
            ?: parsed?.optString("error")?.takeIf { it.isNotBlank() }
            ?: body.take(180)
        val lower = rawMessage.lowercase(Locale.ROOT)

        val friendly = when {
            lower.contains("invalid path specified in request url") ->
                "A URL configurada em SUPABASE_URL possui um caminho inválido. Use apenas https://SEU_PROJETO.supabase.co."
            code == "PGRST205" || code == "42P01" || lower.contains("could not find the table") ->
                "Tabela não encontrada na API do Supabase (${code ?: "HTTP $statusCode"}: $rawMessage). " +
                    "Execute supabase_schema.sql no SQL Editor ou, se já criou as tabelas, rode: " +
                    "notify pgrst, 'reload schema'; e toque em Tentar novamente."
            lower.contains("bucket not found") ->
                "Bucket '$PRODUCT_PHOTOS_BUCKET' não encontrado no Supabase Storage. Execute o script supabase_schema.sql atualizado no SQL Editor."
            lower.contains("exceeded the maximum allowed size") ->
                "A imagem excedeu o limite máximo permitido pelo bucket ($PRODUCT_PHOTOS_BUCKET)."
            code == "42501" || statusCode == 403 || lower.contains("row-level security") ->
                "Sem permissão no Supabase para esta operação. Verifique as políticas RLS em supabase_schema.sql."
            statusCode == 401 ->
                "Sessão Supabase expirada. Entre novamente."
            rawMessage.isNotBlank() -> rawMessage
            else -> "Erro no Supabase (HTTP $statusCode)."
        }
        return code to friendly
    }

    fun parseIsoTimestampMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.trim()
            .replace("Z", "")
            .replace(Regex("[+-]\\d{2}:?\\d{2}$"), "")
            .substringBefore('.')
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(cleaned)?.time ?: 0L
        }.getOrDefault(0L)
    }
}
