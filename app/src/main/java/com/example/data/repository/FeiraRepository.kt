package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.FeiraLocalDatabase
import com.example.data.local.PendingPriceDao
import com.example.data.local.PendingPriceEntity
import com.example.data.local.SyncStatus
import com.example.data.media.ProductPhotoHelper
import com.example.data.model.Market
import com.example.data.model.PriceRecord
import com.example.data.model.ShoppingList
import com.example.data.model.ShoppingListItem
import com.example.data.model.generateInviteCode
import com.example.data.model.normalizeProductName
import com.example.data.supabase.SupabaseAuthManager
import com.example.data.supabase.SupabaseConfig
import com.example.data.supabase.SupabaseHttpException
import com.example.data.supabase.SupabaseRealtimeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class FeiraRepository(
    private val authManager: SupabaseAuthManager,
    private val pendingPriceDao: PendingPriceDao? = null,
    private val realtimeClient: SupabaseRealtimeClient = SupabaseRealtimeClient(authManager)
) {
    constructor(context: Context) : this(
        authManager = SupabaseAuthManager.getInstance(context.applicationContext),
        pendingPriceDao = FeiraLocalDatabase.getInstance(context.applicationContext).pendingPriceDao()
    )

    private val httpClient = authManager.httpClient
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshSignals = MutableSharedFlow<String>(extraBufferCapacity = 32)

    val isRealtimeConnected: StateFlow<Boolean> = realtimeClient.isConnected

    init {
        repoScope.launch {
            realtimeClient.tableEvents.collect { evt ->
                val targetScope = when (evt.table) {
                    "price_records" -> SCOPE_PRICES
                    "markets" -> SCOPE_MARKETS
                    "shopping_lists" -> SCOPE_LISTS
                    "shopping_list_items" -> SCOPE_LIST_ITEMS
                    else -> SCOPE_ALL
                }
                refreshSignals.tryEmit(targetScope)
            }
        }
    }

    fun startRealtimeSync() {
        realtimeClient.start()
    }

    fun setOnlineForRealtime(online: Boolean) {
        realtimeClient.setOnline(online)
    }

    fun reconnectRealtimeIfNeeded() {
        realtimeClient.reconnectNow()
    }

    fun stopRealtimeSync() {
        realtimeClient.stop()
        repoScope.cancel()
    }

    fun triggerRefresh(scopeKey: String = SCOPE_ALL) {
        refreshSignals.tryEmit(scopeKey)
    }

    fun observeMarkets(userId: String): Flow<Result<List<Market>>> = pollAndObserve(
        scopeKey = SCOPE_MARKETS,
        pollIntervalMs = 30_000L
    ) {
        fetchMarkets(userId)
    }

    fun observePriceRecords(userId: String): Flow<Result<List<PriceRecord>>> = pollAndObserve(
        scopeKey = SCOPE_PRICES,
        pollIntervalMs = 30_000L
    ) {
        fetchPriceRecords(userId)
    }

    fun observePendingPrices(userId: String): Flow<List<PendingPriceEntity>> {
        return pendingPriceDao?.observePendingForUser(userId) ?: flowOf(emptyList())
    }

    fun observeShoppingLists(userId: String): Flow<Result<List<ShoppingList>>> = pollAndObserve(
        scopeKey = SCOPE_LISTS,
        pollIntervalMs = 20_000L
    ) {
        fetchShoppingLists(userId)
    }

    fun observeListItems(listId: String): Flow<Result<List<ShoppingListItem>>> = pollAndObserve(
        scopeKey = "$SCOPE_LIST_ITEMS:$listId",
        pollIntervalMs = 15_000L
    ) {
        fetchListItems(listId)
    }

    private fun <T> pollAndObserve(
        scopeKey: String,
        pollIntervalMs: Long? = null,
        loader: suspend () -> T
    ): Flow<Result<T>> = flow {
        suspend fun emitAttempt(emitFailure: Boolean) {
            try {
                emit(Result.success(loader()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Fetch error for $scopeKey: ${e.message}")
                if (emitFailure) {
                    emit(Result.failure(e))
                }
            }
        }

        emitAttempt(emitFailure = true)

        val tickerFlow = if (pollIntervalMs != null) {
            flow {
                while (true) {
                    delay(pollIntervalMs)
                    if (!realtimeClient.isConnected.value) {
                        emit(false)
                    }
                }
            }
        } else {
            flowOf()
        }
        val signalFlow = flow {
            refreshSignals.collect { target ->
                if (target == SCOPE_ALL || target == scopeKey ||
                    (scopeKey.startsWith(SCOPE_LIST_ITEMS) && target == SCOPE_LIST_ITEMS)
                ) {
                    emit(true)
                }
            }
        }
        merge(tickerFlow, signalFlow).collect { emitFailure ->
            emitAttempt(emitFailure = emitFailure)
        }
    }

    private suspend fun fetchMarkets(@Suppress("UNUSED_PARAMETER") userId: String): List<Market> {
        val url = "${SupabaseConfig.baseUrl}/rest/v1/markets".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("order", "name.asc")
            .addQueryParameter("limit", "300")
            .build()
            .toString()

        val body = executeSupabaseRequest(method = "GET", url = url)
        val array = JSONArray(body)
        return (0 until array.length()).map { idx ->
            parseMarket(array.getJSONObject(idx))
        }.sortedBy { it.name.lowercase() }
    }

    private suspend fun fetchPriceRecords(@Suppress("UNUSED_PARAMETER") userId: String): List<PriceRecord> {
        val url = "${SupabaseConfig.baseUrl}/rest/v1/price_records".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("order", "recorded_at_millis.desc")
            .addQueryParameter("limit", "500")
            .build()
            .toString()

        val body = executeSupabaseRequest(method = "GET", url = url)
        val array = JSONArray(body)
        return (0 until array.length()).map { idx ->
            parsePriceRecord(array.getJSONObject(idx))
        }.sortedByDescending { it.recordedAtMillis }
    }

    private suspend fun fetchShoppingLists(userId: String): List<ShoppingList> {
        val url = "${SupabaseConfig.baseUrl}/rest/v1/shopping_lists".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("or", "(owner_id.eq.$userId,member_uids.cs.{$userId})")
            .addQueryParameter("order", "updated_at.desc")
            .build()
            .toString()

        val body = executeSupabaseRequest(method = "GET", url = url)
        val array = JSONArray(body)
        return (0 until array.length()).map { idx ->
            parseShoppingList(array.getJSONObject(idx))
        }.sortedByDescending { maxOf(it.updatedAtMillis, it.createdAtMillis) }
    }

    private suspend fun fetchListItems(listId: String): List<ShoppingListItem> {
        val url = "${SupabaseConfig.baseUrl}/rest/v1/shopping_list_items".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("list_id", "eq.$listId")
            .addQueryParameter("order", "checked.asc,product_name.asc")
            .build()
            .toString()

        val body = executeSupabaseRequest(method = "GET", url = url)
        val array = JSONArray(body)
        return (0 until array.length()).map { idx ->
            parseShoppingListItem(array.getJSONObject(idx))
        }.sortedWith(
            compareBy<ShoppingListItem> { it.checked }
                .thenBy { it.productName.lowercase() }
        )
    }

    suspend fun createMarket(name: String, neighborhood: String = ""): Result<String> {
        return try {
            val uid = requireUserId()
            val cleanName = name.trim()
            require(cleanName.isNotEmpty() && cleanName.length <= 80) { "Nome do mercado inválido." }
            val cleanNeighborhood = neighborhood.trim().take(80)

            val payload = JSONObject().apply {
                put("user_id", uid)
                put("name", cleanName)
                put("neighborhood", cleanNeighborhood)
            }.toString()

            val body = executeSupabaseRequest(
                method = "POST",
                url = "${SupabaseConfig.baseUrl}/rest/v1/markets",
                jsonBody = payload,
                preferHeader = "return=representation"
            )
            val createdId = JSONArray(body).optJSONObject(0)?.optString("id").orEmpty()
            refreshSignals.tryEmit(SCOPE_MARKETS)
            Result.success(createdId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteMarket(marketId: String): Result<Unit> {
        return try {
            requireUserId()
            val url = "${SupabaseConfig.baseUrl}/rest/v1/markets".toHttpUrl().newBuilder()
                .addQueryParameter("id", "eq.$marketId")
                .build()
                .toString()
            executeSupabaseRequest(method = "DELETE", url = url)
            refreshSignals.tryEmit(SCOPE_MARKETS)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerPrice(
        productName: String,
        brand: String,
        category: String,
        unitLabel: String,
        priceCents: Long,
        marketId: String,
        marketName: String,
        isPromo: Boolean,
        isOnline: Boolean,
        localPhotoPath: String? = null,
        recordedAtMillis: Long = System.currentTimeMillis()
    ): Result<Boolean> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val cleanName = productName.trim().take(100)
        val cleanBrand = brand.trim().take(60)
        val cleanCategory = category.trim().ifEmpty { "Mercearia" }.take(40)
        val cleanUnit = unitLabel.trim().ifEmpty { "1 un" }.take(30)
        val cleanMarketName = marketName.trim().take(80)
        val normalized = normalizeProductName(cleanName)

        if (cleanName.isEmpty() || normalized.isEmpty() || priceCents <= 0L || cleanMarketName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Preencha produto, mercado e preço válidos."))
        }

        val resolvedMarketId = marketId.ifBlank {
            "mkt_${cleanMarketName.lowercase().replace(Regex("[^a-z0-9]"), "_").take(32)}"
        }

        if (!isOnline && pendingPriceDao != null) {
            queueOfflinePrice(
                uid = uid,
                productName = cleanName,
                normalized = normalized,
                brand = cleanBrand,
                category = cleanCategory,
                unitLabel = cleanUnit,
                priceCents = priceCents,
                marketId = resolvedMarketId,
                marketName = cleanMarketName,
                isPromo = isPromo,
                recordedAtMillis = recordedAtMillis,
                localPhotoPath = localPhotoPath,
                lastError = null
            )
            return Result.success(true)
        }

        return try {
            val uploadedPhotoUrl = if (!localPhotoPath.isNullOrBlank() && File(localPhotoPath).exists()) {
                uploadProductPhoto(uid, localPhotoPath)
            } else {
                ""
            }

            val payload = JSONObject().apply {
                put("user_id", uid)
                put("product_name", cleanName)
                put("normalized_name", normalized)
                put("brand", cleanBrand)
                put("category", cleanCategory)
                put("unit_label", cleanUnit)
                put("price_cents", priceCents)
                put("market_id", resolvedMarketId)
                put("market_name", cleanMarketName)
                put("is_promo", isPromo)
                put("photo_url", uploadedPhotoUrl)
                put("recorded_at_millis", recordedAtMillis)
            }.toString()

            executeSupabaseRequest(
                method = "POST",
                url = "${SupabaseConfig.baseUrl}/rest/v1/price_records",
                jsonBody = payload,
                preferHeader = "return=minimal"
            )
            ProductPhotoHelper.deleteLocalPhoto(localPhotoPath)
            refreshSignals.tryEmit(SCOPE_PRICES)
            Result.success(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val isClientPolicyOrValidation = (e as? SupabaseHttpException)?.statusCode in listOf(400, 401, 403, 404, 422)
            if (!isClientPolicyOrValidation && pendingPriceDao != null) {
                queueOfflinePrice(
                    uid = uid,
                    productName = cleanName,
                    normalized = normalized,
                    brand = cleanBrand,
                    category = cleanCategory,
                    unitLabel = cleanUnit,
                    priceCents = priceCents,
                    marketId = resolvedMarketId,
                    marketName = cleanMarketName,
                    isPromo = isPromo,
                    recordedAtMillis = recordedAtMillis,
                    localPhotoPath = localPhotoPath,
                    lastError = e.localizedMessage
                )
                return Result.success(true)
            }
            Result.failure(e)
        }
    }

    private suspend fun queueOfflinePrice(
        uid: String,
        productName: String,
        normalized: String,
        brand: String,
        category: String,
        unitLabel: String,
        priceCents: Long,
        marketId: String,
        marketName: String,
        isPromo: Boolean,
        recordedAtMillis: Long,
        localPhotoPath: String?,
        lastError: String?
    ) {
        val dao = pendingPriceDao ?: return
        dao.upsert(
            PendingPriceEntity(
                localId = UUID.randomUUID().toString(),
                userId = uid,
                productName = productName,
                normalizedName = normalized,
                brand = brand,
                category = category,
                unitLabel = unitLabel,
                priceCents = priceCents,
                marketId = marketId,
                marketName = marketName,
                isPromo = isPromo,
                recordedAtMillis = recordedAtMillis,
                localPhotoPath = localPhotoPath,
                syncStatus = SyncStatus.PENDING.name,
                lastError = lastError
            )
        )
    }

    suspend fun syncPendingPrices(userId: String): Result<Int> {
        val dao = pendingPriceDao ?: return Result.success(0)
        val pendingItems = dao.getPendingForUser(userId)
        if (pendingItems.isEmpty()) return Result.success(0)

        var syncedCount = 0
        for (item in pendingItems) {
            dao.updateSyncStatus(item.localId, SyncStatus.SYNCING.name, null)
            try {
                val uploadedPhotoUrl = if (!item.localPhotoPath.isNullOrBlank() && File(item.localPhotoPath).exists()) {
                    uploadProductPhoto(userId, item.localPhotoPath)
                } else {
                    ""
                }

                val payload = JSONObject().apply {
                    put("user_id", userId)
                    put("product_name", item.productName)
                    put("normalized_name", item.normalizedName)
                    put("brand", item.brand)
                    put("category", item.category)
                    put("unit_label", item.unitLabel)
                    put("price_cents", item.priceCents)
                    put("market_id", item.marketId)
                    put("market_name", item.marketName)
                    put("is_promo", item.isPromo)
                    put("photo_url", uploadedPhotoUrl)
                    put("recorded_at_millis", item.recordedAtMillis)
                }.toString()

                executeSupabaseRequest(
                    method = "POST",
                    url = "${SupabaseConfig.baseUrl}/rest/v1/price_records",
                    jsonBody = payload,
                    preferHeader = "return=minimal"
                )
                dao.deleteById(item.localId)
                ProductPhotoHelper.deleteLocalPhoto(item.localPhotoPath)
                syncedCount++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dao.updateSyncStatus(item.localId, SyncStatus.FAILED.name, e.localizedMessage)
                return Result.failure(e)
            }
        }
        if (syncedCount > 0) {
            refreshSignals.tryEmit(SCOPE_PRICES)
        }
        return Result.success(syncedCount)
    }

    suspend fun deletePriceRecord(recordId: String, photoUrl: String = ""): Result<Unit> {
        return try {
            requireUserId()
            val url = "${SupabaseConfig.baseUrl}/rest/v1/price_records".toHttpUrl().newBuilder()
                .addQueryParameter("id", "eq.$recordId")
                .build()
                .toString()
            executeSupabaseRequest(method = "DELETE", url = url)

            val objectPath = SupabaseConfig.extractStorageObjectPath(photoUrl)
            if (!objectPath.isNullOrBlank()) {
                runCatching {
                    val storageUrl = "${SupabaseConfig.baseUrl}/storage/v1/object/${SupabaseConfig.PRODUCT_PHOTOS_BUCKET}/$objectPath"
                    executeSupabaseRequest(method = "DELETE", url = storageUrl)
                }
            }

            refreshSignals.tryEmit(SCOPE_PRICES)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun uploadProductPhoto(userId: String, localPhotoPath: String): String {
        if (!SupabaseConfig.isConfigured()) {
            throw IllegalStateException(
                "Supabase não configurado. Adicione SUPABASE_URL e SUPABASE_ANON_KEY no painel Secrets do AI Studio."
            )
        }
        val file = File(localPhotoPath)
        require(file.exists() && file.length() > 0L) {
            "Arquivo local da foto não encontrado para envio."
        }
        val objectPath = "$userId/${UUID.randomUUID()}.jpg"
        val uploadUrl = "${SupabaseConfig.baseUrl}/storage/v1/object/${SupabaseConfig.PRODUCT_PHOTOS_BUCKET}/$objectPath"
        val body = file.asRequestBody(SupabaseConfig.JPEG_MEDIA_TYPE)

        val firstSession = authManager.getValidAccessToken(forceRefresh = false).getOrThrow()
        val firstAttempt = performBinaryUpload(uploadUrl, firstSession.accessToken, body)
        if (firstAttempt.code == 401) {
            val refreshed = authManager.getValidAccessToken(forceRefresh = true).getOrElse {
                throw firstAttempt.toHttpException()
            }
            val retry = performBinaryUpload(uploadUrl, refreshed.accessToken, body)
            if (!retry.isSuccessful) throw retry.toHttpException()
        } else if (!firstAttempt.isSuccessful) {
            throw firstAttempt.toHttpException()
        }

        return SupabaseConfig.publicStorageObjectUrl(
            bucket = SupabaseConfig.PRODUCT_PHOTOS_BUCKET,
            objectPath = objectPath
        )
    }

    suspend fun createShoppingList(title: String): Result<String> {
        return try {
            val uid = requireUserId()
            val cleanTitle = title.trim().take(80)
            require(cleanTitle.isNotEmpty()) { "Informe o nome da lista." }
            val inviteCode = generateInviteCode()

            val payload = JSONObject().apply {
                put("owner_id", uid)
                put("member_uids", JSONArray().apply { put(uid) })
                put("title", cleanTitle)
                put("invite_code", inviteCode)
            }.toString()

            val body = executeSupabaseRequest(
                method = "POST",
                url = "${SupabaseConfig.baseUrl}/rest/v1/shopping_lists",
                jsonBody = payload,
                preferHeader = "return=representation"
            )
            val createdId = JSONArray(body).optJSONObject(0)?.optString("id")
                ?: throw IllegalStateException("Lista criada sem ID retornado.")
            refreshSignals.tryEmit(SCOPE_LISTS)
            Result.success(createdId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinListByInviteCode(rawCode: String): Result<String> {
        return try {
            requireUserId()
            val cleanCode = rawCode.trim().uppercase()
            if (!cleanCode.matches(Regex("^[A-Z0-9\\-]{4,20}$"))) {
                return Result.failure(IllegalArgumentException("Código de convite inválido."))
            }

            val payload = JSONObject().apply {
                put("p_invite_code", cleanCode)
            }.toString()

            val body = executeSupabaseRequest(
                method = "POST",
                url = "${SupabaseConfig.baseUrl}/rest/v1/rpc/join_list_by_invite_code",
                jsonBody = payload
            )
            val listId = body.trim().trim('"')
            if (listId.isBlank()) {
                return Result.failure(IllegalStateException("Não foi possível entrar na lista."))
            }
            refreshSignals.tryEmit(SCOPE_LISTS)
            refreshSignals.tryEmit("$SCOPE_LIST_ITEMS:$listId")
            Result.success(listId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addShoppingListItem(
        listId: String,
        productName: String,
        quantity: Long = 1L,
        unitLabel: String = "1 un"
    ): Result<String> {
        return try {
            val uid = requireUserId()
            val cleanName = productName.trim().take(100)
            require(cleanName.isNotEmpty()) { "Informe o nome do produto." }
            val safeQty = quantity.coerceIn(1L, 999L)
            val cleanUnit = unitLabel.trim().ifEmpty { "1 un" }.take(30)

            val payload = JSONObject().apply {
                put("list_id", listId)
                put("product_name", cleanName)
                put("normalized_name", normalizeProductName(cleanName))
                put("quantity", safeQty)
                put("unit_label", cleanUnit)
                put("checked", false)
                put("added_by_uid", uid)
            }.toString()

            val body = executeSupabaseRequest(
                method = "POST",
                url = "${SupabaseConfig.baseUrl}/rest/v1/shopping_list_items",
                jsonBody = payload,
                preferHeader = "return=representation"
            )
            val itemId = JSONArray(body).optJSONObject(0)?.optString("id").orEmpty()
            refreshSignals.tryEmit("$SCOPE_LIST_ITEMS:$listId")
            Result.success(itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleShoppingListItem(listId: String, item: ShoppingListItem): Result<Unit> {
        return try {
            requireUserId()
            require(item.id.isNotBlank()) { "Item ID vazio." }
            val url = "${SupabaseConfig.baseUrl}/rest/v1/shopping_list_items".toHttpUrl().newBuilder()
                .addQueryParameter("id", "eq.${item.id}")
                .addQueryParameter("list_id", "eq.$listId")
                .build()
                .toString()

            val payload = JSONObject().apply {
                put("checked", !item.checked)
            }.toString()

            executeSupabaseRequest(
                method = "PATCH",
                url = url,
                jsonBody = payload,
                preferHeader = "return=minimal"
            )
            refreshSignals.tryEmit("$SCOPE_LIST_ITEMS:$listId")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteShoppingListItem(listId: String, itemId: String): Result<Unit> {
        return try {
            requireUserId()
            val url = "${SupabaseConfig.baseUrl}/rest/v1/shopping_list_items".toHttpUrl().newBuilder()
                .addQueryParameter("id", "eq.$itemId")
                .addQueryParameter("list_id", "eq.$listId")
                .build()
                .toString()
            executeSupabaseRequest(method = "DELETE", url = url)
            refreshSignals.tryEmit("$SCOPE_LIST_ITEMS:$listId")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun requireUserId(): String {
        return authManager.currentSession?.userId
            ?: throw IllegalStateException("Usuário precisa entrar com o Supabase antes de acessar os dados.")
    }

    private suspend fun executeSupabaseRequest(
        method: String,
        url: String,
        jsonBody: String? = null,
        preferHeader: String? = null
    ): String {
        if (!SupabaseConfig.isConfigured()) {
            throw IllegalStateException(
                "Supabase não configurado. Adicione SUPABASE_URL e SUPABASE_ANON_KEY no painel Secrets do AI Studio."
            )
        }
        val firstSession = authManager.getValidAccessToken(forceRefresh = false).getOrThrow()
        val firstAttempt = performHttp(method, url, firstSession.accessToken, jsonBody, preferHeader)
        if (firstAttempt.code == 401) {
            val refreshed = authManager.getValidAccessToken(forceRefresh = true).getOrElse {
                throw firstAttempt.toHttpException()
            }
            val retry = performHttp(method, url, refreshed.accessToken, jsonBody, preferHeader)
            if (!retry.isSuccessful) throw retry.toHttpException()
            return retry.body
        }
        if (!firstAttempt.isSuccessful) {
            throw firstAttempt.toHttpException()
        }
        return firstAttempt.body
    }

    private data class RawHttpResponse(
        val code: Int,
        val isSuccessful: Boolean,
        val body: String
    ) {
        fun toHttpException(): SupabaseHttpException {
            val (errCode, friendly) = SupabaseConfig.mapRestErrorMessage(code, body)
            return SupabaseHttpException(code, errCode, friendly)
        }
    }

    private suspend fun performHttp(
        method: String,
        url: String,
        accessToken: String,
        jsonBody: String?,
        preferHeader: String?
    ): RawHttpResponse = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Accept", "application/json")
            if (!preferHeader.isNullOrBlank()) {
                builder.addHeader("Prefer", preferHeader)
            }
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                "POST" -> builder.post((jsonBody ?: "{}").toRequestBody(SupabaseConfig.JSON_MEDIA_TYPE))
                "PATCH" -> builder.patch((jsonBody ?: "{}").toRequestBody(SupabaseConfig.JSON_MEDIA_TYPE))
                else -> error("Unsupported HTTP method: $method")
            }
            httpClient.newCall(builder.build()).execute().use { resp ->
                RawHttpResponse(
                    code = resp.code,
                    isSuccessful = resp.isSuccessful,
                    body = resp.body?.string().orEmpty()
                )
            }
        } catch (e: IOException) {
            throw IOException("Sem conexão com o Supabase. Verifique sua internet.", e)
        }
    }

    private suspend fun performBinaryUpload(
        url: String,
        accessToken: String,
        body: RequestBody
    ): RawHttpResponse = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", SupabaseConfig.anonKey)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("x-upsert", "true")
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { resp ->
                RawHttpResponse(
                    code = resp.code,
                    isSuccessful = resp.isSuccessful,
                    body = resp.body?.string().orEmpty()
                )
            }
        } catch (e: IOException) {
            throw IOException("Sem conexão com o Supabase para enviar a foto.", e)
        }
    }

    internal fun parseMarket(obj: JSONObject) = Market(
        id = obj.optString("id"),
        userId = obj.optString("user_id"),
        name = obj.optString("name"),
        neighborhood = obj.optString("neighborhood"),
        createdAtMillis = SupabaseConfig.parseIsoTimestampMillis(obj.optString("created_at")),
        updatedAtMillis = SupabaseConfig.parseIsoTimestampMillis(obj.optString("updated_at"))
    )

    internal fun parsePriceRecord(obj: JSONObject) = PriceRecord(
        id = obj.optString("id"),
        userId = obj.optString("user_id"),
        productName = obj.optString("product_name"),
        normalizedName = obj.optString("normalized_name"),
        brand = obj.optString("brand"),
        category = obj.optString("category", "Mercearia"),
        unitLabel = obj.optString("unit_label", "1 un"),
        priceCents = obj.optLong("price_cents", 0L),
        marketId = obj.optString("market_id"),
        marketName = obj.optString("market_name"),
        isPromo = obj.optBoolean("is_promo", false),
        photoUrl = obj.optString("photo_url"),
        recordedAtMillis = obj.optLong("recorded_at_millis", 0L),
        createdAtMillis = SupabaseConfig.parseIsoTimestampMillis(obj.optString("created_at")),
        updatedAtMillis = SupabaseConfig.parseIsoTimestampMillis(obj.optString("updated_at"))
    )

    internal fun parseShoppingList(obj: JSONObject): ShoppingList {
        val membersArray = obj.optJSONArray("member_uids")
        val members = if (membersArray != null) {
            (0 until membersArray.length()).mapNotNull { i ->
                membersArray.optString(i).takeIf { it.isNotBlank() }
            }
        } else {
            emptyList()
        }
        return ShoppingList(
            id = obj.optString("id"),
            ownerId = obj.optString("owner_id"),
            memberUids = members,
            title = obj.optString("title"),
            inviteCode = obj.optString("invite_code"),
            createdAtMillis = SupabaseConfig.parseIsoTimestampMillis(obj.optString("created_at")),
            updatedAtMillis = SupabaseConfig.parseIsoTimestampMillis(obj.optString("updated_at"))
        )
    }

    internal fun parseShoppingListItem(obj: JSONObject) = ShoppingListItem(
        id = obj.optString("id"),
        listId = obj.optString("list_id"),
        productName = obj.optString("product_name"),
        normalizedName = obj.optString("normalized_name"),
        quantity = obj.optLong("quantity", 1L),
        unitLabel = obj.optString("unit_label", "1 un"),
        checked = obj.optBoolean("checked", false),
        addedByUid = obj.optString("added_by_uid"),
        createdAtMillis = SupabaseConfig.parseIsoTimestampMillis(obj.optString("created_at")),
        updatedAtMillis = SupabaseConfig.parseIsoTimestampMillis(obj.optString("updated_at"))
    )

    companion object {
        private const val TAG = "FeiraRepo"
        private const val SCOPE_ALL = "all"
        private const val SCOPE_MARKETS = "markets"
        private const val SCOPE_PRICES = "prices"
        private const val SCOPE_LISTS = "lists"
        private const val SCOPE_LIST_ITEMS = "list_items"
    }
}
