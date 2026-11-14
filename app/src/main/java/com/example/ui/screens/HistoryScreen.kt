package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.model.FeiraAnalytics
import com.example.data.model.ProductComparisonSummary
import com.example.data.model.TrendSeverity
import com.example.data.model.UnifiedPriceItem
import com.example.data.model.formatBrl
import com.example.ui.components.FullScreenPhotoDialog
import com.example.ui.components.PriceHistoryLineChart
import com.example.ui.components.PriceValueText
import com.example.ui.components.ProductThumbnail
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningContainer
import com.example.ui.viewmodel.DataUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    pricesState: DataUiState<List<UnifiedPriceItem>>,
    comparisons: List<ProductComparisonSummary>,
    initialProductNormalized: String?,
    onDeleteRecord: (recordId: String, photoUrl: String) -> Unit,
    currentUserId: String = "",
    isRefreshing: Boolean = false,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedNormalized by rememberSaveable { mutableStateOf(initialProductNormalized ?: "") }
    var previewPhoto by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    LaunchedEffect(initialProductNormalized, comparisons) {
        if (!initialProductNormalized.isNullOrBlank()) {
            selectedNormalized = initialProductNormalized
        } else if (selectedNormalized.isBlank() && comparisons.isNotEmpty()) {
            selectedNormalized = comparisons.first().normalizedName
        }
    }

    val allRecords = (pricesState as? DataUiState.Success)?.data.orEmpty()
    val productRecords = remember(allRecords, selectedNormalized) {
        allRecords.filter { it.normalizedName == selectedNormalized }
            .sortedByDescending { it.recordedAtMillis }
    }
    val recordsWithPhotos = remember(productRecords) {
        productRecords.filter { it.photoUrl.isNotBlank() }
    }
    val selectedSummary = comparisons.firstOrNull { it.normalizedName == selectedNormalized }
    val trendAnalysis = remember(productRecords, selectedSummary) {
        selectedSummary?.let { FeiraAnalytics.analyzePriceTrend(it.displayName, productRecords) }
    }
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")) }

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
                Text("Histórico & Tendências", style = MaterialTheme.typography.headlineSmall)
            }

            if (comparisons.isEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ShowChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text("Sem histórico ainda", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Registre preços na aba Registrar ao longo do tempo para acompanhar a variação e detectar aumentos.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        comparisons.forEach { summary ->
                            FilterChip(
                                selected = summary.normalizedName == selectedNormalized,
                                onClick = { selectedNormalized = summary.normalizedName },
                                label = { Text(summary.displayName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }

                if (productRecords.isNotEmpty() && selectedSummary != null) {
                    val minCents = productRecords.minOf { it.priceCents }
                    val avgCents = productRecords.map { it.priceCents }.average().toLong()
                    val latestCents = productRecords.first().priceCents

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HistoryKpiCard("Mínimo", minCents, Modifier.weight(1f), highlight = true)
                            HistoryKpiCard("Média", avgCents, Modifier.weight(1f))
                            HistoryKpiCard("Último", latestCents, Modifier.weight(1f))
                        }
                    }

                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Curva de variação — ${selectedSummary.displayName}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                PriceHistoryLineChart(records = productRecords)
                            }
                        }
                    }

                    if (trendAnalysis != null) {
                        item {
                            val containerColor = when (trendAnalysis.severity) {
                                TrendSeverity.ALTO -> MaterialTheme.colorScheme.tertiaryContainer
                                TrendSeverity.ATENCAO -> AmberWarningContainer
                                TrendSeverity.NORMAL -> MaterialTheme.colorScheme.secondaryContainer
                            }
                            val textColor = when (trendAnalysis.severity) {
                                TrendSeverity.ALTO -> MaterialTheme.colorScheme.onTertiaryContainer
                                TrendSeverity.ATENCAO -> AmberWarning
                                TrendSeverity.NORMAL -> MaterialTheme.colorScheme.onSecondaryContainer
                            }
                            val badgeLabel = when {
                                trendAnalysis.severity == TrendSeverity.ALTO ->
                                    "Alta atípica (+${trendAnalysis.vsAveragePercent}%)"
                                trendAnalysis.severity == TrendSeverity.ATENCAO ->
                                    "Atenção (+${trendAnalysis.vsAveragePercent}%)"
                                trendAnalysis.vsAveragePercent <= -5 ->
                                    "Em queda (${trendAnalysis.vsAveragePercent}%)"
                                else -> "Preço normal"
                            }

                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.testTag("statistical_trend_card")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
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
                                                Icons.AutoMirrored.Rounded.TrendingUp,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "Análise estatística de variação",
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                        }
                                        Surface(
                                            color = containerColor,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = badgeLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = textColor,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    Surface(
                                        color = containerColor.copy(alpha = 0.65f),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            text = trendAnalysis.summaryText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = textColor,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp)
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Amplitude: ${formatBrl(trendAnalysis.spreadCents)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Em oferta: ${trendAnalysis.promoCount}x",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (recordsWithPhotos.isNotEmpty()) {
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
                                    Text(
                                        text = "Fotos registradas (${recordsWithPhotos.size})",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        recordsWithPhotos.forEach { rec ->
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                ProductThumbnail(
                                                    photoRef = rec.photoUrl,
                                                    contentDescription = "Foto no ${rec.marketName}",
                                                    size = 72.dp,
                                                    cornerRadius = 10.dp,
                                                    onClick = {
                                                        previewPhoto = Triple(
                                                            rec.photoUrl,
                                                            rec.productName,
                                                            "${rec.marketName} · ${formatBrl(rec.priceCents)} · ${dateFmt.format(Date(rec.recordedAtMillis))}"
                                                        )
                                                    }
                                                )
                                                Text(
                                                    text = rec.marketName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            text = "Registros cronológicos (${productRecords.size})",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    items(productRecords, key = { it.id }) { rec ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (rec.photoUrl.isNotBlank()) {
                                    ProductThumbnail(
                                        photoRef = rec.photoUrl,
                                        contentDescription = "Foto de ${rec.productName} no ${rec.marketName}",
                                        size = 44.dp,
                                        cornerRadius = 8.dp,
                                        onClick = {
                                            previewPhoto = Triple(
                                                rec.photoUrl,
                                                rec.productName,
                                                "${rec.marketName} · ${formatBrl(rec.priceCents)} · ${dateFmt.format(Date(rec.recordedAtMillis))}"
                                            )
                                        }
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(rec.marketName, style = MaterialTheme.typography.titleSmall)
                                        if (rec.isPromo) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = RoundedCornerShape(50)
                                            ) {
                                                Text(
                                                    text = "Oferta",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    val isOwn = rec.userId.isBlank() || rec.userId == currentUserId
                                    val originSuffix = if (isOwn) "" else " · Comunidade"
                                    Text(
                                        text = "${dateFmt.format(Date(rec.recordedAtMillis))}$originSuffix",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                PriceValueText(priceCents = rec.priceCents)

                                if (!rec.isPendingSync && (rec.userId.isBlank() || rec.userId == currentUserId)) {
                                    IconButton(
                                        onClick = { onDeleteRecord(rec.id, rec.photoUrl) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Excluir registro",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    previewPhoto?.let { (url, title, subtitle) ->
        FullScreenPhotoDialog(
            photoRef = url,
            title = title,
            subtitle = subtitle,
            onDismiss = { previewPhoto = null }
        )
    }
}

@Composable
private fun HistoryKpiCard(
    label: String,
    priceCents: Long,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlight) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (highlight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatBrl(priceCents),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (highlight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
