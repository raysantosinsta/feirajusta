package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.PendingPriceEntity
import com.example.data.model.BasketOptimizationSummary
import com.example.data.model.CategoryAlternativeOption
import com.example.data.model.FeiraAnalytics
import com.example.data.model.ListItemBestDeal
import com.example.data.model.Market
import com.example.data.model.MarketQuote
import com.example.data.model.MarketTotalQuote
import com.example.data.model.PriceRecord
import com.example.data.model.ProductComparisonSummary
import com.example.data.model.ProductQuickTemplate
import com.example.data.model.ShoppingList
import com.example.data.model.ShoppingListItem
import com.example.data.model.UnifiedPriceItem
import com.example.data.network.ConnectivityObserver
import com.example.data.repository.FeiraRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DataUiState<out T> {
    data object Loading : DataUiState<Nothing>
    data class Success<T>(val data: T) : DataUiState<T>
    data class Error(val message: String) : DataUiState<Nothing>
}

data class PriceSpikeItem(
    val normalizedName: String,
    val displayName: String,
    val marketName: String,
    val latestPriceCents: Long,
    val previousAverageCents: Long,
    val jumpPercent: Int
)

class FeiraViewModel(
    private val repository: FeiraRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val currentUserId: String
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivityObserver.isOnline
    val isRealtimeConnected: StateFlow<Boolean> = repository.isRealtimeConnected

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val marketsState: StateFlow<DataUiState<List<Market>>> = repository.observeMarkets(currentUserId)
        .map { result ->
            result.fold(
                onSuccess = { DataUiState.Success(it) },
                onFailure = { DataUiState.Error(it.localizedMessage ?: "Erro ao carregar mercados.") }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DataUiState.Loading)

    private val remotePricesState: StateFlow<DataUiState<List<PriceRecord>>> =
        repository.observePriceRecords(currentUserId)
            .map { result ->
                result.fold(
                    onSuccess = { DataUiState.Success(it) },
                    onFailure = { DataUiState.Error(it.localizedMessage ?: "Erro ao carregar preços.") }
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DataUiState.Loading)

    val pendingPrices: StateFlow<List<PendingPriceEntity>> =
        repository.observePendingPrices(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val unifiedPricesState: StateFlow<DataUiState<List<UnifiedPriceItem>>> =
        combine(remotePricesState, pendingPrices) { remoteState, pending ->
            when (remoteState) {
                is DataUiState.Loading -> {
                    if (pending.isNotEmpty()) {
                        DataUiState.Success(pending.map { it.toUnified() })
                    } else {
                        DataUiState.Loading
                    }
                }
                is DataUiState.Error -> {
                    if (pending.isNotEmpty()) {
                        DataUiState.Success(pending.map { it.toUnified() })
                    } else {
                        remoteState
                    }
                }
                is DataUiState.Success -> {
                    val remoteItems = remoteState.data.map { it.toUnified() }
                    val pendingItems = pending.map { it.toUnified() }
                    DataUiState.Success((pendingItems + remoteItems).sortedByDescending { it.recordedAtMillis })
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DataUiState.Loading)

    val comparisonSummaries: StateFlow<List<ProductComparisonSummary>> = unifiedPricesState
        .map { state ->
            val records = (state as? DataUiState.Success)?.data ?: emptyList()
            buildComparisons(records)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val quickProductTemplates: StateFlow<List<ProductQuickTemplate>> = comparisonSummaries
        .map { summaries -> FeiraAnalytics.buildProductQuickTemplates(summaries) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val priceSpikeAlerts: StateFlow<List<PriceSpikeItem>> = unifiedPricesState
        .map { state ->
            val records = (state as? DataUiState.Success)?.data ?: emptyList()
            detectPriceSpikes(records)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val shoppingListsState: StateFlow<DataUiState<List<ShoppingList>>> =
        repository.observeShoppingLists(currentUserId)
            .map { result ->
                result.fold(
                    onSuccess = { DataUiState.Success(it) },
                    onFailure = { DataUiState.Error(it.localizedMessage ?: "Erro ao carregar listas.") }
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DataUiState.Loading)

    private val _selectedListId = MutableStateFlow<String?>(null)
    val selectedListId: StateFlow<String?> = combine(shoppingListsState, _selectedListId) { state, selected ->
        val lists = (state as? DataUiState.Success)?.data.orEmpty()
        if (selected != null && lists.any { it.id == selected }) selected else lists.firstOrNull()?.id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeListItemsState: StateFlow<DataUiState<List<ShoppingListItem>>> = selectedListId
        .flatMapLatest { listId ->
            if (listId == null) {
                flowOf(DataUiState.Success(emptyList()))
            } else {
                repository.observeListItems(listId)
                    .map { result ->
                        result.fold(
                            onSuccess = { DataUiState.Success(it) },
                            onFailure = { DataUiState.Error(it.localizedMessage ?: "Erro ao carregar itens da lista.") }
                        )
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DataUiState.Success(emptyList()))

    val basketOptimization: StateFlow<BasketOptimizationSummary?> =
        combine(activeListItemsState, comparisonSummaries) { itemsState, comparisons ->
            val items = (itemsState as? DataUiState.Success)?.data ?: return@combine null
            if (items.isEmpty()) return@combine null
            computeBasketOptimization(items, comparisons)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    init {
        repository.startRealtimeSync()

        viewModelScope.launch {
            isOnline.collect { online ->
                repository.setOnlineForRealtime(online)
            }
        }

        viewModelScope.launch {
            combine(isOnline, pendingPrices) { online, pending -> online to pending.size }
                .collect { (online, pendingCount) ->
                    if (online) {
                        if (pendingCount > 0 && !_isSyncing.value) {
                            syncPendingPricesNow()
                        } else {
                            repository.triggerRefresh()
                        }
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopRealtimeSync()
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun selectList(listId: String) {
        _selectedListId.value = listId
    }

    fun onAppResumed() {
        if (isOnline.value) {
            repository.reconnectRealtimeIfNeeded()
            repository.triggerRefresh()
            if (pendingPrices.value.isNotEmpty()) {
                syncPendingPricesNow()
            }
        }
    }

    fun retryRefresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            if (isOnline.value) {
                repository.reconnectRealtimeIfNeeded()
            }
            repository.triggerRefresh()
            syncPendingPricesNow()
            delay(450L)
            _isRefreshing.value = false
        }
    }

    fun findCategoryAlternatives(
        productName: String,
        currentPriceCents: Long?
    ): List<CategoryAlternativeOption> {
        return FeiraAnalytics.findCategoryAlternatives(
            productName = productName,
            currentPriceCents = currentPriceCents,
            comparisons = comparisonSummaries.value
        )
    }

    fun syncPendingPricesNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            val result = repository.syncPendingPrices(currentUserId)
            repository.triggerRefresh()
            _isSyncing.value = false
            result.onSuccess { count ->
                if (count > 0) {
                    _statusBanner.value = "$count preço(s) sincronizado(s) com o Supabase."
                }
            }.onFailure { err ->
                Log.w(TAG, "Sync failed", err)
                _statusBanner.value = "Falha na sincronização: ${err.localizedMessage ?: "tente novamente"}"
            }
        }
    }

    fun createMarket(name: String, neighborhood: String, onDone: (Result<String>) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.createMarket(name, neighborhood)
            result.onFailure { _statusBanner.value = it.localizedMessage ?: "Erro ao salvar mercado." }
            onDone(result)
        }
    }

    fun registerPrice(
        productName: String,
        brand: String,
        category: String,
        unitLabel: String,
        priceCents: Long,
        marketId: String,
        marketName: String,
        isPromo: Boolean,
        localPhotoPath: String? = null,
        onDone: (Boolean, String?) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.registerPrice(
                productName = productName,
                brand = brand,
                category = category,
                unitLabel = unitLabel,
                priceCents = priceCents,
                marketId = marketId,
                marketName = marketName,
                isPromo = isPromo,
                isOnline = isOnline.value,
                localPhotoPath = localPhotoPath
            )
            result.onSuccess { savedOffline ->
                _statusBanner.value = if (savedOffline) {
                    "Salvo offline na fila local — será sincronizado ao conectar."
                } else {
                    "Preço registrado e compartilhado na comparação."
                }
                onDone(true, null)
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Erro ao registrar preço."
                _statusBanner.value = msg
                onDone(false, msg)
            }
        }
    }

    fun deletePriceRecord(recordId: String, photoUrl: String = "") {
        viewModelScope.launch {
            repository.deletePriceRecord(recordId, photoUrl)
                .onFailure { _statusBanner.value = it.localizedMessage ?: "Erro ao remover preço." }
        }
    }

    fun createShoppingList(title: String, onDone: (Result<String>) -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.createShoppingList(title)
            res.onSuccess { id ->
                _selectedListId.value = id
                _statusBanner.value = "Lista criada! Compartilhe o código com sua família."
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Erro ao criar lista."
            }
            onDone(res)
        }
    }

    fun joinListByCode(code: String, onDone: (Result<String>) -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.joinListByInviteCode(code)
            res.onSuccess { id ->
                _selectedListId.value = id
                _statusBanner.value = "Você entrou na lista familiar!"
            }.onFailure { err ->
                _statusBanner.value = err.localizedMessage ?: "Código inválido ou lista indisponível."
            }
            onDone(res)
        }
    }

    fun addItemToActiveList(productName: String, quantity: Long, unitLabel: String) {
        val listId = selectedListId.value ?: run {
            _statusBanner.value = "Crie ou selecione uma lista de compras primeiro."
            return
        }
        viewModelScope.launch {
            repository.addShoppingListItem(listId, productName, quantity, unitLabel)
                .onFailure { _statusBanner.value = it.localizedMessage ?: "Erro ao adicionar item." }
        }
    }

    fun toggleListItem(item: ShoppingListItem) {
        val listId = selectedListId.value ?: return
        viewModelScope.launch {
            repository.toggleShoppingListItem(listId, item)
                .onFailure { _statusBanner.value = it.localizedMessage ?: "Erro ao atualizar item." }
        }
    }

    fun deleteListItem(itemId: String) {
        val listId = selectedListId.value ?: return
        viewModelScope.launch {
            repository.deleteShoppingListItem(listId, itemId)
                .onFailure { _statusBanner.value = it.localizedMessage ?: "Erro ao remover item." }
        }
    }

    private fun buildComparisons(records: List<UnifiedPriceItem>): List<ProductComparisonSummary> {
        if (records.isEmpty()) return emptyList()
        return records.groupBy { it.normalizedName }
            .map { (normalized, group) ->
                val sortedChrono = group.sortedByDescending { it.recordedAtMillis }
                val latestSample = sortedChrono.first()

                val latestByMarket = sortedChrono
                    .groupBy { it.marketName.trim().lowercase() }
                    .values
                    .map { marketRecords ->
                        val newest = marketRecords.first()
                        val newestWithPhoto = marketRecords.firstOrNull { it.photoUrl.isNotBlank() }?.photoUrl.orEmpty()
                        MarketQuote(
                            marketId = newest.marketId,
                            marketName = newest.marketName,
                            priceCents = newest.priceCents,
                            isPromo = newest.isPromo,
                            photoUrl = newestWithPhoto,
                            recordedAtMillis = newest.recordedAtMillis,
                            isPendingSync = newest.isPendingSync
                        )
                    }
                    .sortedBy { it.priceCents }

                val cheapest = latestByMarket.first()
                val highest = latestByMarket.maxOf { it.priceCents }
                val deltaPct = if (sortedChrono.size >= 2) {
                    val newest = sortedChrono[0].priceCents.toDouble()
                    val prev = sortedChrono[1].priceCents.toDouble()
                    if (prev > 0) ((newest - prev) / prev) * 100.0 else null
                } else null
                val coverPhoto = sortedChrono.firstOrNull { it.photoUrl.isNotBlank() }?.photoUrl.orEmpty()

                ProductComparisonSummary(
                    normalizedName = normalized,
                    displayName = latestSample.productName,
                    brand = latestSample.brand,
                    category = latestSample.category,
                    unitLabel = latestSample.unitLabel,
                    cheapestQuote = cheapest,
                    highestPriceCents = highest,
                    potentialSavingsCents = (highest - cheapest.priceCents).coerceAtLeast(0L),
                    quotesByMarket = latestByMarket,
                    priceDeltaPercent = deltaPct,
                    hasPendingSync = group.any { it.isPendingSync },
                    coverPhotoUrl = coverPhoto
                )
            }
            .sortedByDescending { it.quotesByMarket.maxOfOrNull { q -> q.recordedAtMillis } ?: 0L }
    }

    private fun detectPriceSpikes(records: List<UnifiedPriceItem>): List<PriceSpikeItem> {
        return records.groupBy { it.normalizedName }.mapNotNull { (norm, group) ->
            val chrono = group.sortedBy { it.recordedAtMillis }
            if (chrono.size < 2) return@mapNotNull null
            val latest = chrono.last()
            val prevAvg = chrono.dropLast(1).map { it.priceCents }.average().toLong()
            if (prevAvg <= 0L) return@mapNotNull null
            val jumpPct = (((latest.priceCents - prevAvg).toDouble() / prevAvg.toDouble()) * 100.0).toInt()
            if (jumpPct >= 15) {
                PriceSpikeItem(
                    normalizedName = norm,
                    displayName = latest.productName,
                    marketName = latest.marketName,
                    latestPriceCents = latest.priceCents,
                    previousAverageCents = prevAvg,
                    jumpPercent = jumpPct
                )
            } else {
                null
            }
        }.sortedByDescending { it.jumpPercent }
    }

    private fun computeBasketOptimization(
        items: List<ShoppingListItem>,
        comparisons: List<ProductComparisonSummary>
    ): BasketOptimizationSummary {
        val byNorm = comparisons.associateBy { it.normalizedName }
        val itemDeals = items.map { item ->
            val match = byNorm[item.normalizedName]
            ListItemBestDeal(
                item = item,
                bestMarketName = match?.cheapestQuote?.marketName,
                bestUnitPriceCents = match?.cheapestQuote?.priceCents,
                highestUnitPriceCents = match?.highestPriceCents
            )
        }

        val allMarkets = comparisons.flatMap { it.quotesByMarket.map { q -> q.marketName } }.distinct()
        val marketTotals = allMarkets.mapNotNull { market ->
            var sum = 0L
            var covered = 0
            items.forEach { item ->
                val quote = byNorm[item.normalizedName]?.quotesByMarket
                    ?.firstOrNull { it.marketName.equals(market, ignoreCase = true) }
                if (quote != null) {
                    sum += quote.priceCents * item.quantity
                    covered++
                }
            }
            if (covered == 0) null else MarketTotalQuote(market, sum, covered, items.size)
        }.sortedWith(compareByDescending<MarketTotalQuote> { it.coveredItemsCount }.thenBy { it.totalCents })

        val splitTotal = itemDeals.sumOf { (it.bestUnitPriceCents ?: 0L) * it.item.quantity }
        val bestSingle = marketTotals.firstOrNull { it.coveredItemsCount == items.size } ?: marketTotals.firstOrNull()
        val splitSavings = if (bestSingle != null && bestSingle.coveredItemsCount == items.size) {
            (bestSingle.totalCents - splitTotal).coerceAtLeast(0L)
        } else {
            0L
        }

        return BasketOptimizationSummary(
            itemDeals = itemDeals,
            marketTotals = marketTotals,
            splitStrategyTotalCents = splitTotal,
            bestSingleMarket = bestSingle,
            splitSavingsOverBestSingleCents = splitSavings
        )
    }

    private fun PriceRecord.toUnified() = UnifiedPriceItem(
        id = id,
        userId = userId,
        productName = productName,
        normalizedName = normalizedName,
        brand = brand,
        category = category,
        unitLabel = unitLabel,
        priceCents = priceCents,
        marketId = marketId,
        marketName = marketName,
        isPromo = isPromo,
        photoUrl = photoUrl,
        recordedAtMillis = recordedAtMillis,
        isPendingSync = false
    )

    private fun PendingPriceEntity.toUnified() = UnifiedPriceItem(
        id = localId,
        userId = userId,
        productName = productName,
        normalizedName = normalizedName,
        brand = brand,
        category = category,
        unitLabel = unitLabel,
        priceCents = priceCents,
        marketId = marketId,
        marketName = marketName,
        isPromo = isPromo,
        photoUrl = localPhotoPath.orEmpty(),
        recordedAtMillis = recordedAtMillis,
        isPendingSync = true,
        syncError = lastError
    )

    private companion object {
        const val TAG = "FeiraVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
