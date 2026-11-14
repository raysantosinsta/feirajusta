package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.local.PendingPriceEntity
import com.example.data.media.ProductPhotoHelper
import com.example.data.model.FeiraCategories
import com.example.data.model.Market
import com.example.data.model.ProductQuickTemplate
import com.example.data.model.formatBrl
import com.example.data.model.parseBrlToCents
import com.example.data.model.resolvePhotoModel
import com.example.ui.components.FullScreenPhotoDialog
import com.example.ui.components.ProductThumbnail
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningContainer
import com.example.ui.theme.JetBrainsMono
import com.example.ui.viewmodel.DataUiState
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ScannerScreen(
    isOnline: Boolean,
    isSyncing: Boolean,
    marketsState: DataUiState<List<Market>>,
    pendingPrices: List<PendingPriceEntity>,
    quickTemplates: List<ProductQuickTemplate>,
    onRegisterPrice: (
        productName: String,
        brand: String,
        category: String,
        unitLabel: String,
        priceCents: Long,
        marketId: String,
        marketName: String,
        isPromo: Boolean,
        localPhotoPath: String?,
        onDone: (Boolean, String?) -> Unit
    ) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val markets = (marketsState as? DataUiState.Success)?.data.orEmpty()

    var productName by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Mercearia") }
    var unitLabel by rememberSaveable { mutableStateOf("1 un") }
    var priceText by rememberSaveable { mutableStateOf("") }
    var selectedMarketId by rememberSaveable { mutableStateOf("") }
    var manualMarketName by rememberSaveable { mutableStateOf("") }
    var isPromo by rememberSaveable { mutableStateOf(false) }
    var localPhotoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var isProcessingPhoto by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    var showPhotoZoom by remember { mutableStateOf(false) }
    var pendingCameraUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    var selectedTemplateNote by rememberSaveable { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }

    fun importPhotoFromUri(uri: Uri, tempCameraFile: File? = null) {
        scope.launch {
            isProcessingPhoto = true
            photoError = null
            val previousPath = localPhotoPath
            val res = ProductPhotoHelper.compressAndSaveToAppStorage(context, uri, tempCameraFile)
            isProcessingPhoto = false
            res.onSuccess { savedPath ->
                if (!previousPath.isNullOrBlank() && previousPath != savedPath) {
                    ProductPhotoHelper.deleteLocalPhoto(previousPath)
                }
                localPhotoPath = savedPath
            }.onFailure { err ->
                photoError = err.localizedMessage ?: "Não foi possível preparar a foto."
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            importPhotoFromUri(uri)
        }
    }

    val documentImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importPhotoFromUri(uri)
        }
    }

    fun launchGalleryPicker() {
        photoError = null
        runCatching {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }.onFailure {
            runCatching {
                documentImageLauncher.launch(arrayOf("image/*"))
            }.onFailure { err ->
                photoError = "Não foi possível abrir a galeria neste aparelho: ${err.localizedMessage ?: "tente a câmera"}"
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uriStr = pendingCameraUriString
        val filePath = pendingCameraFilePath
        pendingCameraUriString = null
        pendingCameraFilePath = null
        val tempFile = filePath?.let { File(it) }
        if (success && !uriStr.isNullOrBlank()) {
            importPhotoFromUri(Uri.parse(uriStr), tempFile)
        } else if (tempFile != null) {
            runCatching { tempFile.delete() }
        }
    }

    fun launchCameraCapture() {
        photoError = null
        runCatching {
            val (uri, file) = ProductPhotoHelper.createCameraCaptureTarget(context)
            pendingCameraUriString = uri.toString()
            pendingCameraFilePath = file.absolutePath
            cameraLauncher.launch(uri)
        }.onFailure { err ->
            pendingCameraUriString = null
            pendingCameraFilePath = null
            photoError = "Não foi possível abrir a câmera: ${err.localizedMessage ?: "tente escolher da Galeria"}"
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            photoError = "Permissão de câmera negada. Você pode escolher uma foto pela Galeria."
        }
    }

    val matchingTemplates = remember(quickTemplates, productName) {
        val query = productName.trim()
        if (query.isEmpty()) {
            quickTemplates.take(10)
        } else {
            quickTemplates.filter {
                it.productName.contains(query, ignoreCase = true) ||
                    it.brand.contains(query, ignoreCase = true)
            }.take(8)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cadastro Rápido de Preços", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = if (isOnline) {
                            "Toque em um produto já salvo para preencher em segundos ou cadastre um novo item."
                        } else {
                            "Sem internet: os preços cadastrados ficam salvos no aparelho e sobem automaticamente."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick Suggestions from Saved Products
            if (matchingTemplates.isNotEmpty()) {
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
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Sugestões dos produtos salvos",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                matchingTemplates.forEach { tpl ->
                                    SuggestionChip(
                                        onClick = {
                                            productName = tpl.productName
                                            brand = tpl.brand
                                            if (tpl.category in FeiraCategories.editable) {
                                                category = tpl.category
                                            }
                                            if (tpl.unitLabel.isNotBlank()) {
                                                unitLabel = tpl.unitLabel
                                            }
                                            selectedTemplateNote =
                                                "Último registro: ${formatBrl(tpl.lastPriceCents)} no ${tpl.lastMarketName}"
                                        },
                                        label = {
                                            val labelSuffix = if (tpl.unitLabel.isNotBlank()) " · ${tpl.unitLabel}" else ""
                                            Text("${tpl.productName}$labelSuffix")
                                        },
                                        modifier = Modifier.testTag("quick_template_${tpl.normalizedName}")
                                    )
                                }
                            }

                            if (selectedTemplateNote != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = selectedTemplateNote.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Registration Form
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Dados do preço", style = MaterialTheme.typography.titleSmall)

                        OutlinedTextField(
                            value = productName,
                            onValueChange = {
                                productName = it
                                selectedTemplateNote = null
                            },
                            label = { Text("Produto *") },
                            placeholder = { Text("Ex.: Arroz Branco Tipo 1") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("price_product_input")
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = brand,
                                onValueChange = { brand = it },
                                label = { Text("Marca") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = priceText,
                                onValueChange = { priceText = it },
                                label = { Text("Preço (R$) *") },
                                placeholder = { Text("12,90") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("price_value_input")
                            )
                        }

                        Text("Categoria", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FeiraCategories.editable.forEach { cat ->
                                FilterChip(
                                    selected = category == cat,
                                    onClick = { category = cat },
                                    label = { Text(cat) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }

                        Text("Unidade / Embalagem", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FeiraCategories.units.forEach { u ->
                                FilterChip(
                                    selected = unitLabel == u,
                                    onClick = { unitLabel = u },
                                    label = { Text(u) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }

                        if (markets.isNotEmpty()) {
                            Text("Selecionar mercado cadastrado", style = MaterialTheme.typography.labelMedium)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                markets.forEach { m ->
                                    val selected = selectedMarketId == m.id
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            selectedMarketId = m.id
                                            manualMarketName = m.name
                                        },
                                        label = { Text(m.name) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                            selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                                        )
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = manualMarketName,
                            onValueChange = {
                                manualMarketName = it
                                selectedMarketId = ""
                            },
                            label = { Text("Supermercado *") },
                            placeholder = { Text("Ex.: Assaí Atacadista") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("price_market_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Preço promocional / oferta", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = isPromo, onCheckedChange = { isPromo = it })
                        }

                        // Product Photo Picker / Camera Section
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Foto do produto (opcional)",
                                    style = MaterialTheme.typography.labelLarge
                                )

                                val previewModel = remember(localPhotoPath) { resolvePhotoModel(localPhotoPath) }
                                if (isProcessingPhoto) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Text(
                                            text = "Preparando e comprimindo foto...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else if (previewModel != null && localPhotoPath != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(150.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { showPhotoZoom = true }
                                    ) {
                                        AsyncImage(
                                            model = previewModel,
                                            contentDescription = "Prévia da foto do produto",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Surface(
                                            color = Color.Black.copy(alpha = 0.6f),
                                            shape = RoundedCornerShape(50),
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    Icons.Rounded.ZoomIn,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = if (isOnline) "Enviar ao bucket" else "Salva offline",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = {
                                                ProductPhotoHelper.deleteLocalPhoto(localPhotoPath)
                                                localPhotoPath = null
                                            },
                                            modifier = Modifier.testTag("remove_photo_button")
                                        ) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Remover foto")
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(
                                                onClick = { launchGalleryPicker() }
                                            ) {
                                                Text("Galeria")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    val granted = ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.CAMERA
                                                    ) == PackageManager.PERMISSION_GRANTED
                                                    if (granted) launchCameraCapture()
                                                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                                }
                                            ) {
                                                Text("Câmera")
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { launchGalleryPicker() },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("pick_gallery_photo_button")
                                        ) {
                                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Galeria")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val granted = ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.CAMERA
                                                ) == PackageManager.PERMISSION_GRANTED
                                                if (granted) launchCameraCapture()
                                                else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("capture_camera_photo_button")
                                        ) {
                                            Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Câmera")
                                        }
                                    }
                                }

                                if (photoError != null) {
                                    Text(
                                        text = photoError.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }

                        if (formError != null) {
                            Text(
                                text = formError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }

                        Button(
                            onClick = {
                                val parsedCents = parseBrlToCents(priceText)
                                when {
                                    productName.trim().isEmpty() -> formError = "Informe o nome do produto."
                                    parsedCents == null -> formError = "Informe um preço válido em Reais (ex.: 14,90)."
                                    manualMarketName.trim().isEmpty() -> formError = "Informe o supermercado."
                                    isProcessingPhoto -> formError = "Aguarde a foto terminar de ser processada."
                                    isSubmitting -> Unit
                                    else -> {
                                        formError = null
                                        isSubmitting = true
                                        onRegisterPrice(
                                            productName,
                                            brand,
                                            category,
                                            unitLabel,
                                            parsedCents,
                                            selectedMarketId,
                                            manualMarketName,
                                            isPromo,
                                            localPhotoPath
                                        ) { saved, errMessage ->
                                            isSubmitting = false
                                            if (saved) {
                                                productName = ""
                                                brand = ""
                                                priceText = ""
                                                isPromo = false
                                                localPhotoPath = null
                                                photoError = null
                                                selectedTemplateNote = null
                                            } else if (!errMessage.isNullOrBlank()) {
                                                formError = errMessage
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isProcessingPhoto && !isSubmitting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("save_price_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isOnline) MaterialTheme.colorScheme.primary else AmberWarning,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (localPhotoPath != null && isOnline) "Enviando foto e preço..." else "Salvando...")
                            } else {
                                Icon(
                                    imageVector = if (isOnline) Icons.Rounded.Save else Icons.Rounded.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(if (isOnline) "Registrar preço" else "Salvar offline na fila")
                            }
                        }
                    }
                }
            }

            // Offline Pending Queue
            item {
                AnimatedVisibility(visible = pendingPrices.isNotEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = AmberWarningContainer,
                        border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Fila offline (${pendingPrices.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AmberWarning,
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedButton(
                                    onClick = onSyncNow,
                                    enabled = isOnline && !isSyncing,
                                    modifier = Modifier.testTag("manual_sync_button")
                                ) {
                                    Icon(Icons.Rounded.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isSyncing) "Enviando..." else "Sincronizar")
                                }
                            }
                            Text(
                                text = "Estes registros e suas fotos já aparecem no app e subirão para o Supabase assim que houver internet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberWarning
                            )
                        }
                    }
                }
            }

            items(pendingPrices, key = { it.localId }) { pending ->
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
                        if (!pending.localPhotoPath.isNullOrBlank()) {
                            ProductThumbnail(
                                photoRef = pending.localPhotoPath,
                                contentDescription = "Foto offline de ${pending.productName}",
                                size = 44.dp,
                                cornerRadius = 8.dp
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pending.productName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${pending.marketName} · ${pending.syncStatus}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formatBrl(pending.priceCents),
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono),
                            color = AmberWarning
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showPhotoZoom && !localPhotoPath.isNullOrBlank()) {
        FullScreenPhotoDialog(
            photoRef = localPhotoPath.orEmpty(),
            title = productName.ifBlank { "Foto do produto" },
            subtitle = manualMarketName.takeIf { it.isNotBlank() },
            onDismiss = { showPhotoZoom = false }
        )
    }
}
