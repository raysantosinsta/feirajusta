package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddShoppingCart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.FeiraCategories
import com.example.data.model.Market
import com.example.data.model.ProductComparisonSummary
import com.example.data.model.UnifiedPriceItem
import com.example.data.model.formatBrl
import com.example.ui.components.FullScreenPhotoDialog
import com.example.ui.components.PriceValueText
import com.example.ui.components.ProductComparisonSheet
import com.example.ui.components.ProductThumbnail
import com.example.ui.theme.AmberWarning
import com.example.ui.viewmodel.DataUiState
import com.example.ui.viewmodel.PriceSpikeItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(
    pricesState: DataUiState<List<UnifiedPriceItem>>,
    marketsState: DataUiState<List<Market>>,
    comparisons: List<ProductComparisonSummary>,
    spikeAlerts: List<PriceSpikeItem>,
    onNavigateToScanner: () -> Unit,
    onAddMarket: (String, String) -> Unit,
    onAddProductToList: (String, String) -> Unit,
    onInspectHistory: (String) -> Unit,
    onRetry: () -> Unit = {},
    isRefreshing: Boolean = false,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("Todos") }
    var selectedSummary by remember { mutableStateOf<ProductComparisonSummary?>(null) }
    var previewPhoto by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var showAddMarketDialog by remember { mutableStateOf(false) }

    val filteredComparisons = remember(comparisons, searchQuery, selectedCategory) {
        comparisons.filter { item ->
            val matchesCat = selectedCategory == "Todos" || item.category.equals(selectedCategory, ignoreCase = true)
            val matchesQuery = searchQuery.isBlank() ||
                item.displayName.contains(searchQuery, ignoreCase = true) ||
                item.brand.contains(searchQuery, ignoreCase = true) ||
                item.quotesByMarket.any { it.marketName.contains(searchQuery, ignoreCase = true) }
            matchesCat && matchesQuery
        }
    }

    val markets = (marketsState as? DataUiState.Success)?.data.orEmpty()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRetry,
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar produto, marca ou mercado...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Limpar busca")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("compare_search_input")
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FeiraCategories.all.forEach { category ->
                        val selected = selectedCategory == category
                        FilterChip(
                            selected = selected,
                            onClick = { selectedCategory = category },
                            label = { Text(category) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.testTag("category_chip_${category.lowercase()}")
                        )
                    }
                }
            }

            // Smart Price Spike Alert Strip
            item {
                AnimatedVisibility(visible = spikeAlerts.isNotEmpty()) {
                    val topSpike = spikeAlerts.firstOrNull()
                    if (topSpike != null) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInspectHistory(topSpike.normalizedName) }
                                .testTag("price_spike_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                                    contentDescription = "Alerta de alta",
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Possível aumento em ${topSpike.displayName}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Text(
                                        text = "+${topSpike.jumpPercent}% no ${topSpike.marketName} (${formatBrl(topSpike.latestPriceCents)} vs média ${formatBrl(topSpike.previousAverageCents)})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Markets Quick Bar
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Storefront,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Mercados (${markets.size})",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            TextButton(
                                onClick = { showAddMarketDialog = true },
                                modifier = Modifier.testTag("add_market_button")
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Novo mercado")
                            }
                        }

                        if (markets.isEmpty()) {
                            Text(
                                text = "Cadastre os mercados que você frequenta para comparar ofertas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                markets.forEach { market ->
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = if (market.neighborhood.isNotBlank()) {
                                                "${market.name} · ${market.neighborhood}"
                                            } else {
                                                market.name
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            when (pricesState) {
                is DataUiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is DataUiState.Error -> {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = pricesState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                OutlinedButton(
                                    onClick = onRetry,
                                    modifier = Modifier.testTag("retry_compare_button")
                                ) {
                                    Text("Tentar novamente")
                                }
                            }
                        }
                    }
                }
                is DataUiState.Success -> {
                    if (filteredComparisons.isEmpty()) {
                        item {
                            EmptyCompareCard(
                                hasSearch = searchQuery.isNotBlank() || selectedCategory != "Todos",
                                onOpenScanner = onNavigateToScanner
                            )
                        }
                    } else {
                        items(filteredComparisons, key = { it.normalizedName }) { summary ->
                            ProductComparisonCard(
                                summary = summary,
                                onClick = { selectedSummary = summary },
                                onPhotoClick = {
                                    if (summary.coverPhotoUrl.isNotBlank()) {
                                        previewPhoto = Triple(
                                            summary.coverPhotoUrl,
                                            summary.displayName,
                                            "${summary.cheapestQuote.marketName} · ${formatBrl(summary.cheapestQuote.priceCents)}"
                                        )
                                    }
                                },
                                onAddToList = {
                                    onAddProductToList(summary.displayName, summary.unitLabel)
                                }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    selectedSummary?.let { summary ->
        ProductComparisonSheet(
            summary = summary,
            onDismiss = { selectedSummary = null },
            onAddToShoppingList = {
                onAddProductToList(summary.displayName, summary.unitLabel)
                selectedSummary = null
            }
        )
    }

    previewPhoto?.let { (url, title, subtitle) ->
        FullScreenPhotoDialog(
            photoRef = url,
            title = title,
            subtitle = subtitle,
            onDismiss = { previewPhoto = null }
        )
    }

    if (showAddMarketDialog) {
        AddMarketDialog(
            onDismiss = { showAddMarketDialog = false },
            onConfirm = { name, neighborhood ->
                onAddMarket(name, neighborhood)
                showAddMarketDialog = false
            }
        )
    }
}

@Composable
private fun ProductComparisonCard(
    summary: ProductComparisonSummary,
    onClick: () -> Unit,
    onPhotoClick: () -> Unit,
    onAddToList: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("product_card_${summary.normalizedName}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (summary.coverPhotoUrl.isNotBlank()) {
                    ProductThumbnail(
                        photoRef = summary.coverPhotoUrl,
                        contentDescription = "Foto de ${summary.displayName}",
                        size = 58.dp,
                        cornerRadius = 10.dp,
                        onClick = onPhotoClick
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = summary.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (summary.hasPendingSync) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmberWarning,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = listOfNotNull(
                            summary.brand.takeIf { it.isNotBlank() },
                            summary.unitLabel,
                            summary.category
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    PriceValueText(
                        priceCents = summary.cheapestQuote.priceCents,
                        color = MaterialTheme.colorScheme.secondary,
                        isLarge = true
                    )
                    Text(
                        text = "no ${summary.cheapestQuote.marketName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val badgeText = when {
                    summary.quotesByMarket.size > 1 && summary.potentialSavingsCents > 0L ->
                        "Economize ${formatBrl(summary.potentialSavingsCents)} (${summary.quotesByMarket.size} mercados)"
                    summary.quotesByMarket.size > 1 ->
                        "${summary.quotesByMarket.size} mercados comparados"
                    else ->
                        "1 mercado registrado"
                }

                Surface(
                    color = if (summary.potentialSavingsCents > 0L) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (summary.potentialSavingsCents > 0L) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                IconButton(
                    onClick = onAddToList,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("quick_add_list_${summary.normalizedName}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddShoppingCart,
                        contentDescription = "Adicionar à lista de compras",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCompareCard(
    hasSearch: Boolean,
    onOpenScanner: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.AddShoppingCart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = if (hasSearch) "Nenhum produto encontrado" else "Comece comparando preços",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (hasSearch) {
                    "Tente limpar os filtros ou registrar um novo preço para este item."
                } else {
                    "Use o formulário rápido com sugestões dos seus produtos salvos para registrar preços, mesmo sem internet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onOpenScanner,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("empty_open_scanner_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Registrar primeiro preço")
            }
        }
    }
}

@Composable
private fun AddMarketDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var neighborhood by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo supermercado") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do estabelecimento") },
                    placeholder = { Text("Ex.: Assaí, Carrefour, Mercado Central") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("market_name_input")
                )
                OutlinedTextField(
                    value = neighborhood,
                    onValueChange = { neighborhood = it },
                    label = { Text("Bairro (opcional)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("market_neighborhood_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, neighborhood) },
                enabled = name.trim().isNotEmpty(),
                modifier = Modifier.testTag("confirm_add_market_button")
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
