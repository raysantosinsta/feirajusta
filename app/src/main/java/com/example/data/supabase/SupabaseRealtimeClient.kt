package com.example.data.supabase

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

data class RealtimeTableEvent(
    val table: String,
    val eventType: String
)

class SupabaseRealtimeClient(
    private val authManager: SupabaseAuthManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsHttpClient = authManager.httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _tableEvents = MutableSharedFlow<RealtimeTableEvent>(extraBufferCapacity = 64)
    val tableEvents: SharedFlow<RealtimeTableEvent> = _tableEvents.asSharedFlow()

    private val refCounter = AtomicInteger(1)

    @Volatile
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var shouldRun = false

    @Volatile
    private var isNetworkOnline = true

    @Volatile
    private var reconnectAttempt = 0

    private var connectionLoopJob: Job? = null
    private var heartbeatJob: Job? = null
    private var authObserveJob: Job? = null
    private var lastSentToken: String? = null

    fun start() {
        if (shouldRun) return
        shouldRun = true
        observeAuthTokenChanges()
        ensureConnectionScheduled()
    }

    fun setOnline(online: Boolean) {
        val changed = isNetworkOnline != online
        isNetworkOnline = online
        if (!online) {
            closeSocketOnly()
        } else if (changed && shouldRun) {
            reconnectAttempt = 0
            ensureConnectionScheduled()
        }
    }

    fun reconnectNow() {
        if (!shouldRun || !isNetworkOnline) return
        reconnectAttempt = 0
        if (!_isConnected.value) {
            ensureConnectionScheduled()
        }
    }

    fun stop() {
        shouldRun = false
        authObserveJob?.cancel()
        authObserveJob = null
        connectionLoopJob?.cancel()
        connectionLoopJob = null
        closeSocketOnly()
    }

    private fun observeAuthTokenChanges() {
        authObserveJob?.cancel()
        authObserveJob = scope.launch {
            authManager.authState.collect { state ->
                when (state) {
                    is SupabaseAuthState.SignedIn -> {
                        val token = state.session.accessToken
                        val ws = activeWebSocket
                        if (ws != null && _isConnected.value && token != lastSentToken) {
                            lastSentToken = token
                            ws.send(buildAccessTokenMessage(token, nextRef()))
                        } else if (ws == null && isNetworkOnline && shouldRun) {
                            ensureConnectionScheduled()
                        }
                    }
                    is SupabaseAuthState.SignedOut,
                    is SupabaseAuthState.Loading -> {
                        closeSocketOnly()
                    }
                }
            }
        }
    }

    private fun ensureConnectionScheduled() {
        if (!shouldRun || !isNetworkOnline || !SupabaseConfig.isConfigured()) return
        if (activeWebSocket != null) return
        if (connectionLoopJob?.isActive == true) return

        connectionLoopJob = scope.launch {
            while (isActive && shouldRun && isNetworkOnline && activeWebSocket == null) {
                val sessionResult = authManager.getValidAccessToken(forceRefresh = false)
                val session = sessionResult.getOrNull()
                if (session == null) {
                    delay(3_000L)
                    continue
                }

                openWebSocket(session.accessToken)
                break
            }
        }
    }

    private fun openWebSocket(accessToken: String) {
        val url = SupabaseConfig.realtimeWebSocketUrl()
        val request = Request.Builder().url(url).build()
        lastSentToken = accessToken

        val socket = wsHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (activeWebSocket !== webSocket) return
                reconnectAttempt = 0
                webSocket.send(buildJoinMessage(accessToken, nextRef()))
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (activeWebSocket !== webSocket) return
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeWebSocket !== webSocket) return
                handleDisconnect("closed ($code: $reason)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (activeWebSocket !== webSocket) return
                handleDisconnect(t.localizedMessage ?: "failure")
            }
        })
        activeWebSocket = socket
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val topic = json.optString("topic")
        val event = json.optString("event")
        val payload = json.optJSONObject("payload")

        when {
            event == "phx_reply" && topic == REALTIME_TOPIC -> {
                val status = payload?.optString("status").orEmpty()
                if (status == "ok") {
                    _isConnected.value = true
                    reconnectAttempt = 0
                } else {
                    Log.w(TAG, "Realtime join reply not ok: $text")
                    _isConnected.value = false
                }
            }
            event == "postgres_changes" -> {
                parsePostgresChangeEvent(json)?.let { change ->
                    _tableEvents.tryEmit(change)
                }
            }
            event == "phx_error" || (event == "phx_close" && topic == REALTIME_TOPIC) -> {
                Log.w(TAG, "Realtime channel error/close: $text")
                handleDisconnect(event)
            }
            event == "system" -> {
                val status = payload?.optString("status").orEmpty()
                if (status.equals("error", ignoreCase = true)) {
                    Log.w(TAG, "Realtime system error: $text")
                }
            }
        }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && activeWebSocket === webSocket && shouldRun) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (activeWebSocket === webSocket) {
                    val ok = webSocket.send(buildHeartbeatMessage(nextRef()))
                    if (!ok) {
                        handleDisconnect("heartbeat send failed")
                        break
                    }
                }
            }
        }
    }

    private fun handleDisconnect(reason: String) {
        Log.d(TAG, "Realtime disconnected: $reason")
        closeSocketOnly()
        if (!shouldRun || !isNetworkOnline) return

        connectionLoopJob?.cancel()
        connectionLoopJob = scope.launch {
            val backoffMs = min(30_000L, 2_000L shl min(reconnectAttempt, 4))
            reconnectAttempt++
            delay(backoffMs)
            if (isActive && shouldRun && isNetworkOnline) {
                try {
                    val token = authManager.getValidAccessToken(forceRefresh = false).getOrNull()?.accessToken
                    if (!token.isNullOrBlank()) {
                        openWebSocket(token)
                    } else {
                        ensureConnectionScheduled()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ensureConnectionScheduled()
                }
            }
        }
    }

    private fun closeSocketOnly() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val ws = activeWebSocket
        activeWebSocket = null
        _isConnected.value = false
        if (ws != null) {
            runCatching { ws.close(1000, "closed") }
        }
    }

    private fun nextRef(): String = refCounter.getAndIncrement().toString()

    companion object {
        private const val TAG = "FeiraRealtime"
        const val REALTIME_TOPIC = "realtime:feira-sync"
        private const val HEARTBEAT_INTERVAL_MS = 20_000L

        internal val WATCHED_TABLES = listOf(
            "price_records",
            "markets",
            "shopping_lists",
            "shopping_list_items"
        )

        internal fun buildJoinMessage(accessToken: String, ref: String = "1"): String {
            val postgresChanges = JSONArray().apply {
                WATCHED_TABLES.forEach { table ->
                    put(
                        JSONObject().apply {
                            put("event", "*")
                            put("schema", "public")
                            put("table", table)
                        }
                    )
                }
            }
            val config = JSONObject().apply {
                put("broadcast", JSONObject().put("self", false))
                put("presence", JSONObject().put("key", ""))
                put("postgres_changes", postgresChanges)
            }
            val payload = JSONObject().apply {
                put("config", config)
                put("access_token", accessToken)
            }
            return JSONObject().apply {
                put("topic", REALTIME_TOPIC)
                put("event", "phx_join")
                put("payload", payload)
                put("ref", ref)
            }.toString()
        }

        internal fun buildHeartbeatMessage(ref: String): String {
            return JSONObject().apply {
                put("topic", "phoenix")
                put("event", "heartbeat")
                put("payload", JSONObject())
                put("ref", ref)
            }.toString()
        }

        internal fun buildAccessTokenMessage(accessToken: String, ref: String): String {
            return JSONObject().apply {
                put("topic", REALTIME_TOPIC)
                put("event", "access_token")
                put("payload", JSONObject().put("access_token", accessToken))
                put("ref", ref)
            }.toString()
        }

        internal fun parsePostgresChangeEvent(json: JSONObject): RealtimeTableEvent? {
            if (json.optString("event") != "postgres_changes") return null
            val payload = json.optJSONObject("payload") ?: return null
            val data = payload.optJSONObject("data")
            val table = data?.optString("table")?.takeIf { it.isNotBlank() }
                ?: payload.optString("table").takeIf { it.isNotBlank() }
                ?: return null
            val eventType = data?.optString("type")?.takeIf { it.isNotBlank() }
                ?: payload.optString("eventType").takeIf { it.isNotBlank() }
                ?: "UPDATE"
            return RealtimeTableEvent(table = table, eventType = eventType)
        }
    }
}
