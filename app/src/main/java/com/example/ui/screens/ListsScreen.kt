package com.example.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BasketOptimizationSummary
import com.example.data.model.CategoryAlternativeOption
import com.example.data.model.ListItemBestDeal
import com.example.data.model.ShoppingList
import com.example.data.model.ShoppingListItem
import com.example.data.model.formatBrl
import com.example.ui.components.PriceValueText
import com.example.ui.theme.JetBrainsMono
import com.example.ui.viewmodel.DataUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    listsState: DataUiState<List<ShoppingList>>,
    selectedListId: String?,
    itemsState: DataUiState<List<ShoppingListItem>>,
    basketOptimization: BasketOptimizationSummary?,
    onFindAlternatives: (String, Long?) -> List<CategoryAlternativeOption>,
    onSelectList: (String) -> Unit,
    onCreateList: (String) -> Unit,
    onJoinList: (String) -> Unit,
    onAddItem: (String, Long, String) -> Unit,
    onToggleItem: (ShoppingListItem) -> Unit,
    onDeleteItem: (String) -> Unit,
    onRetry: () -> Unit = {},
    isRefreshing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCreateListDialog by remember { mutableStateOf(false) }
    var showJoinListDialog by remember { mutableStateOf(false) }
    var showAddItemDialog by remember { mutableStateOf(false) }
    var alternativesTarget by remember { mutableStateOf<Pair<String, List<CategoryAlternativeOption>>?>(null) }

    val lists = (listsState as? DataUiState.Success)?.data.orEmpty()
    val activeList = lists.firstOrNull { it.id == selectedListId }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Listas da família",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { showJoinListDialog = true },
                            modifier = Modifier.testTag("join_list_button")
                        ) {
                            Icon(Icons.Rounded.GroupAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Entrar")
                        }
                        Button(
                            onClick = { showCreateListDialog = true },
                            modifier = Modifier.testTag("create_list_button")
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Nova")
                        }
                    }
                }
            }

            if (lists.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        lists.forEach { list ->
                            FilterChip(
                                selected = list.id == activeList?.id,
                                onClick = { onSelectList(list.id) },
                                label = { Text("${list.title} (${list.memberUids.size})") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }
            }

            when (listsState) {
                is DataUiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
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
                                    text = listsState.message,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                OutlinedButton(
                                    onClick = onRetry,
                                    modifier = Modifier.testTag("retry_lists_button")
                                ) {
                                    Text("Tentar novamente")
                                }
                            }
                        }
                    }
                }
                is DataUiState.Success -> {
                    if (activeList == null) {
                        item {
                            EmptyListsCard(
                                onCreateList = { showCreateListDialog = true },
                                onJoinList = { showJoinListDialog = true }
                            )
                        }
                    } else {
                        item {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Código familiar: ${activeList.inviteCode}",
                                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${activeList.memberUids.size} participante(s) sincronizados",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val itemsText = (itemsState as? DataUiState.Success)?.data
                                                ?.joinToString("\n") { item ->
                                                    val mark = if (item.checked) "[x]" else "[ ]"
                                                    "$mark ${item.quantity}x ${item.productName} (${item.unitLabel})"
                                                }.orEmpty()
                                            val shareBody = buildString {
                                                append("Lista \"${activeList.title}\" no FeiraJusta!\n")
                                                append("Entre pelo código familiar: ${activeList.inviteCode}\n")
                                                if (itemsText.isNotBlank()) {
                                                    append("\nItens:\n$itemsText")
                                                }
                                            }
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareBody)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Compartilhar lista"))
                                        },
                                        modifier = Modifier.testTag("share_list_button")
                                    ) {
                                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Compartilhar")
                                    }
                                }
                            }
                        }

                        if (basketOptimization != null && basketOptimization.marketTotals.isNotEmpty()) {
                            item {
                                BasketOptimizationCard(optimization = basketOptimization)
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val count = (itemsState as? DataUiState.Success)?.data?.size ?: 0
                                Text(
                                    text = "Itens ($count)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Button(
                                    onClick = { showAddItemDialog = true },
                                    modifier = Modifier.testTag("add_list_item_button")
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Adicionar item")
                                }
                            }
                        }

                        when (itemsState) {
                            is DataUiState.Loading -> {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) { CircularProgressIndicator() }
                                }
                            }
                            is DataUiState.Error -> {
                                item {
                                    Text(
                                        text = itemsState.message,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            is DataUiState.Success -> {
                                val deals = basketOptimization?.itemDeals ?: itemsState.data.map {
                                    ListItemBestDeal(it, null, null, null)
                                }
                                if (deals.isEmpty()) {
                                    item {
                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                        ) {
                                            Text(
                                                text = "Lista vazia. Adicione itens acima ou envie diretamente da aba Comparar.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp)
                                            )
                                        }
                                    }
                                } else {
                                    items(deals, key = { it.item.id }) { deal ->
                                        ShoppingListItemRow(
                                            deal = deal,
                                            onToggle = { onToggleItem(deal.item) },
                                            onDelete = { onDeleteItem(deal.item.id) },
                                            onCompareAlternatives = {
                                                val alts = onFindAlternatives(
                                                    deal.item.productName,
                                                    deal.bestUnitPriceCents
                                                )
                                                alternativesTarget = deal.item.productName to alts
                                            }
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

    if (showCreateListDialog) {
        SimpleInputDialog(
            title = "Criar lista compartilhada",
            label = "Nome da lista",
            placeholder = "Ex.: Compra do mês",
            confirmLabel = "Criar",
            onDismiss = { showCreateListDialog = false },
            onConfirm = { title ->
                onCreateList(title)
                showCreateListDialog = false
            }
        )
    }

    if (showJoinListDialog) {
        SimpleInputDialog(
            title = "Entrar em lista familiar",
            label = "Código do convite",
            placeholder = "Ex.: FJ-A2B3C4",
            confirmLabel = "Entrar",
            onDismiss = { showJoinListDialog = false },
            onConfirm = { code ->
                onJoinList(code)
                showJoinListDialog = false
            }
        )
    }

    if (showAddItemDialog) {
        AddListItemDialog(
            onDismiss = { showAddItemDialog = false },
            onConfirm = { name, qty, unit ->
                onAddItem(name, qty, unit)
                showAddItemDialog = false
            }
        )
    }

    alternativesTarget?.let { (targetName, alternatives) ->
        CategoryAlternativesBottomSheet(
            productName = targetName,
            alternatives = alternatives,
            onAddAlternativeToList = { alt ->
                onAddItem(alt.productName, 1L, alt.unitLabel)
                alternativesTarget = null
            },
            onDismiss = { alternativesTarget = null }
        )
    }
}

@Composable
private fun BasketOptimizationCard(optimization: BasketOptimizationSummary) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Otimização da compra",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            val bestSingle = optimization.bestSingleMarket
            if (bestSingle != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Melhor mercado único: ${bestSingle.marketName} (${bestSingle.coveredItemsCount}/${bestSingle.totalItemsCount} itens)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    PriceValueText(
                        priceCents = bestSingle.totalCents,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            if (optimization.splitStrategyTotalCents > 0L) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (optimization.splitSavingsOverBestSingleCents > 0L) {
                            "Dividindo nos mais baratos (economize ${formatBrl(optimization.splitSavingsOverBestSingleCents)})"
                        } else {
                            "Total pelos menores preços registrados"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    PriceValueText(
                        priceCents = optimization.splitStrategyTotalCents,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ShoppingListItemRow(
    deal: ListItemBestDeal,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onCompareAlternatives: () -> Unit
) {
    val item = deal.item
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (item.checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (item.checked) "Desmarcar item" else "Marcar item",
                        tint = if (item.checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${item.quantity}x ${item.productName}",
                        style = MaterialTheme.typography.titleSmall.copy(
                            textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None
                        ),
                        color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val dealText = if (deal.bestMarketName != null && deal.bestUnitPriceCents != null) {
                        "Mais barato: ${deal.bestMarketName} (${formatBrl(deal.bestUnitPriceCents)})"
                    } else {
                        "${item.unitLabel} · Sem preço comparado ainda"
                    }
                    Text(
                        text = dealText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (deal.bestMarketName != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Remover item",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onCompareAlternatives,
                    modifier = Modifier.testTag("suggest_substitute_${item.normalizedName}")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Alternativas na categoria")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryAlternativesBottomSheet(
    productName: String,
    alternatives: List<CategoryAlternativeOption>,
    onAddAlternativeToList: (CategoryAlternativeOption) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Text(
                    text = "Mais baratos que $productName",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            if (alternatives.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = "Nenhuma opção mais barata nesta categoria foi registrada ainda. Cadastre mais produtos na aba Registrar para comparar alternativas automaticamente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                alternatives.forEach { alt ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (alt.brand.isNotBlank()) "${alt.productName} · ${alt.brand}" else alt.productName,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (alt.estimatedSavingsPercent > 0) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = "-${alt.estimatedSavingsPercent}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${alt.category} (${alt.unitLabel}) · ${formatBrl(alt.cheapestPriceCents)} no ${alt.cheapestMarketName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { onAddAlternativeToList(alt) }) {
                                    Text("Adicionar alternativa à lista")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyListsCard(
    onCreateList: () -> Unit,
    onJoinList: () -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ShoppingCart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(36.dp)
            )
            Text("Nenhuma lista criada ainda", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Crie uma lista compartilhada ou digite o código de convite de um familiar para planejar a feira juntos.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onJoinList) { Text("Entrar com código") }
                Button(onClick = onCreateList) { Text("Criar lista") }
            }
        }
    }
}

@Composable
private fun SimpleInputDialog(
    title: String,
    label: String,
    placeholder: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }, enabled = value.trim().isNotEmpty()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun AddListItemDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Long, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf("1 un") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar item à lista") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Produto") },
                    placeholder = { Text("Ex.: Arroz Tipo 1, Leite Integral") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it.filter { c -> c.isDigit() } },
                        label = { Text("Qtd.") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Unidade") },
                        singleLine = true,
                        modifier = Modifier.weight(1.5f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val qty = quantity.toLongOrNull()?.coerceIn(1L, 999L) ?: 1L
                    onConfirm(name, qty, unit.ifBlank { "1 un" })
                },
                enabled = name.trim().isNotEmpty()
            ) {
                Text("Adicionar")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
