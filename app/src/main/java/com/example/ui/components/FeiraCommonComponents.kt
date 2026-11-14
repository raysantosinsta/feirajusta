package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddShoppingCart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.model.ProductComparisonSummary
import com.example.data.model.UnifiedPriceItem
import com.example.data.model.formatBrl
import com.example.data.model.resolvePhotoModel
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningContainer
import com.example.ui.theme.JetBrainsMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncStatusPill(
    isOnline: Boolean,
    pendingCount: Int,
    isSyncing: Boolean,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier,
    isRealtimeConnected: Boolean = false
) {
    val bgColor: Color
    val contentColor: Color
    val isLive = isOnline && !isSyncing && pendingCount == 0 && isRealtimeConnected

    val icon = when {
        isSyncing -> Icons.Rounded.CloudSync
        !isOnline -> Icons.Rounded.CloudOff
        pendingCount > 0 -> Icons.Rounded.CloudSync
        else -> Icons.Rounded.CloudDone
    }
    val label = when {
        isSyncing -> "Sincronizando..."
        !isOnline && pendingCount > 0 -> "Offline · $pendingCount pendente(s)"
        !isOnline -> "Modo Offline"
        pendingCount > 0 -> "Sincronizar ($pendingCount)"
        isRealtimeConnected -> "Ao vivo"
        else -> "Sincronizado"
    }

    when {
        !isOnline -> {
            bgColor = AmberWarningContainer
            contentColor = AmberWarning
        }
        pendingCount > 0 -> {
            bgColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        }
        else -> {
            bgColor = MaterialTheme.colorScheme.secondaryContainer
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        }
    }

    val pulseAlpha = if (isLive) {
        val transition = rememberInfiniteTransition(label = "live_pulse")
        val anim by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 950),
                repeatMode = RepeatMode.Reverse
            ),
            label = "live_dot_alpha"
        )
        anim
    } else {
        1f
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = bgColor,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(enabled = isOnline && !isSyncing) { onSyncClick() }
            .testTag("sync_status_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isLive) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = pulseAlpha))
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
fun PriceValueText(
    priceCents: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    isLarge: Boolean = false
) {
    Text(
        text = formatBrl(priceCents),
        style = (if (isLarge) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
            .copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold),
        color = color,
        modifier = modifier
    )
}

@Composable
fun PriceHistoryLineChart(
    records: List<UnifiedPriceItem>,
    modifier: Modifier = Modifier
) {
    val chronological = records.sortedBy { it.recordedAtMillis }
    val lineColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outline
    val pointSurface = MaterialTheme.colorScheme.surface

    if (chronological.isEmpty()) return

    val minCents = chronological.minOf { it.priceCents }
    val maxCents = chronological.maxOf { it.priceCents }
    val dateFmt = SimpleDateFormat("dd/MM", Locale.forLanguageTag("pt-BR"))

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val padX = 20.dp.toPx()
                val padY = 18.dp.toPx()
                val chartW = (size.width - padX * 2).coerceAtLeast(1f)
                val chartH = (size.height - padY * 2).coerceAtLeast(1f)

                // Horizontal reference grid lines
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                for (step in 0..2) {
                    val y = padY + (chartH / 2f) * step
                    drawLine(
                        color = gridColor,
                        start = Offset(padX, y),
                        end = Offset(padX + chartW, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash
                    )
                }

                val range = (maxCents - minCents).coerceAtLeast(1L).toFloat()
                val points = chronological.mapIndexed { idx, item ->
                    val x = if (chronological.size == 1) {
                        padX + chartW / 2f
                    } else {
                        padX + (idx.toFloat() / (chronological.size - 1).toFloat()) * chartW
                    }
                    val normY = if (maxCents == minCents) {
                        0.5f
                    } else {
                        (item.priceCents - minCents).toFloat() / range
                    }
                    val y = padY + chartH * (1f - normY)
                    Offset(x, y)
                }

                if (points.size >= 2) {
                    val linePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x, points[i].y)
                        }
                    }

                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(points.last().x, padY + chartH)
                        lineTo(points.first().x, padY + chartH)
                        close()
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                lineColor.copy(alpha = 0.22f),
                                lineColor.copy(alpha = 0.0f)
                            ),
                            startY = padY,
                            endY = padY + chartH
                        )
                    )

                    drawPath(
                        path = linePath,
                        color = lineColor,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                points.forEach { pt ->
                    drawCircle(
                        color = pointSurface,
                        radius = 6.dp.toPx(),
                        center = pt
                    )
                    drawCircle(
                        color = lineColor,
                        radius = 4.dp.toPx(),
                        center = pt
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${dateFmt.format(Date(chronological.first().recordedAtMillis))} · Mín ${formatBrl(minCents)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Máx ${formatBrl(maxCents)} · ${dateFmt.format(Date(chronological.last().recordedAtMillis))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ProductThumbnail(
    photoRef: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    cornerRadius: Dp = 10.dp,
    onClick: (() -> Unit)? = null
) {
    val model = remember(photoRef) { resolvePhotoModel(photoRef) } ?: return
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun FullScreenPhotoDialog(
    photoRef: String,
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit
) {
    val model = remember(photoRef) { resolvePhotoModel(photoRef) } ?: return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("close_photo_preview_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Fechar visualização",
                            tint = Color.White
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = model,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductComparisonSheet(
    summary: ProductComparisonSummary,
    onDismiss: () -> Unit,
    onAddToShoppingList: () -> Unit
) {
    var enlargedPhoto by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = summary.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = listOfNotNull(
                        summary.brand.takeIf { it.isNotBlank() },
                        summary.unitLabel,
                        summary.category
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val coverModel = remember(summary.coverPhotoUrl) { resolvePhotoModel(summary.coverPhotoUrl) }
            if (coverModel != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clickable {
                            enlargedPhoto = Triple(
                                summary.coverPhotoUrl,
                                summary.displayName,
                                "${summary.cheapestQuote.marketName} · ${formatBrl(summary.cheapestQuote.priceCents)}"
                            )
                        }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = coverModel,
                            contentDescription = "Foto de ${summary.displayName}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ZoomIn,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Ampliar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            if (summary.potentialSavingsCents > 0L) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Economia comprando no mais barato",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        PriceValueText(
                            priceCents = summary.potentialSavingsCents,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                text = "Comparação por estabelecimento",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            summary.quotesByMarket.forEachIndexed { index, quote ->
                val isBest = index == 0
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isBest) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        if (isBest) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (quote.photoUrl.isNotBlank()) {
                                ProductThumbnail(
                                    photoRef = quote.photoUrl,
                                    contentDescription = "Foto no ${quote.marketName}",
                                    size = 42.dp,
                                    cornerRadius = 8.dp,
                                    onClick = {
                                        enlargedPhoto = Triple(
                                            quote.photoUrl,
                                            summary.displayName,
                                            "${quote.marketName} · ${formatBrl(quote.priceCents)}"
                                        )
                                    }
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Storefront,
                                    contentDescription = null,
                                    tint = if (isBest) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = quote.marketName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isBest) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondary,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = "Mais barato",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (quote.isPromo) {
                                        Icon(
                                            imageVector = Icons.Rounded.LocalOffer,
                                            contentDescription = "Promoção",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                if (quote.isPendingSync) {
                                    Text(
                                        text = "Aguardando sincronização offline",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AmberWarning
                                    )
                                }
                            }
                        }

                        PriceValueText(
                            priceCents = quote.priceCents,
                            color = if (isBest) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Button(
                onClick = onAddToShoppingList,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sheet_add_to_list_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Rounded.AddShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Adicionar à lista de compras")
            }
        }
    }

    enlargedPhoto?.let { (url, title, subtitle) ->
        FullScreenPhotoDialog(
            photoRef = url,
            title = title,
            subtitle = subtitle,
            onDismiss = { enlargedPhoto = null }
        )
    }
}
