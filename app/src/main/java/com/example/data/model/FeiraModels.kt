package com.example.data.model

import java.io.File
import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale

data class Market(
    val id: String = "",
    val userId: String = "",
    val name: String = "",
    val neighborhood: String = "",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)

data class PriceRecord(
    val id: String = "",
    val userId: String = "",
    val productName: String = "",
    val normalizedName: String = "",
    val brand: String = "",
    val category: String = "Mercearia",
    val unitLabel: String = "1 un",
    val priceCents: Long = 0L,
    val marketId: String = "",
    val marketName: String = "",
    val isPromo: Boolean = false,
    val photoUrl: String = "",
    val recordedAtMillis: Long = 0L,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)

data class ShoppingList(
    val id: String = "",
    val ownerId: String = "",
    val memberUids: List<String> = emptyList(),
    val title: String = "",
    val inviteCode: String = "",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)

data class ShoppingListItem(
    val id: String = "",
    val listId: String = "",
    val productName: String = "",
    val normalizedName: String = "",
    val quantity: Long = 1L,
    val unitLabel: String = "1 un",
    val checked: Boolean = false,
    val addedByUid: String = "",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)

data class UnifiedPriceItem(
    val id: String,
    val userId: String = "",
    val productName: String,
    val normalizedName: String,
    val brand: String,
    val category: String,
    val unitLabel: String,
    val priceCents: Long,
    val marketId: String,
    val marketName: String,
    val isPromo: Boolean,
    val photoUrl: String = "",
    val recordedAtMillis: Long,
    val isPendingSync: Boolean = false,
    val syncError: String? = null
)

data class MarketQuote(
    val marketId: String,
    val marketName: String,
    val priceCents: Long,
    val isPromo: Boolean,
    val photoUrl: String = "",
    val recordedAtMillis: Long,
    val isPendingSync: Boolean
)

data class ProductComparisonSummary(
    val normalizedName: String,
    val displayName: String,
    val brand: String,
    val category: String,
    val unitLabel: String,
    val cheapestQuote: MarketQuote,
    val highestPriceCents: Long,
    val potentialSavingsCents: Long,
    val quotesByMarket: List<MarketQuote>,
    val priceDeltaPercent: Double?,
    val hasPendingSync: Boolean,
    val coverPhotoUrl: String = ""
)

data class MarketTotalQuote(
    val marketName: String,
    val totalCents: Long,
    val coveredItemsCount: Int,
    val totalItemsCount: Int
)

data class ListItemBestDeal(
    val item: ShoppingListItem,
    val bestMarketName: String?,
    val bestUnitPriceCents: Long?,
    val highestUnitPriceCents: Long?
)

data class BasketOptimizationSummary(
    val itemDeals: List<ListItemBestDeal>,
    val marketTotals: List<MarketTotalQuote>,
    val splitStrategyTotalCents: Long,
    val bestSingleMarket: MarketTotalQuote?,
    val splitSavingsOverBestSingleCents: Long
)

data class ProductQuickTemplate(
    val normalizedName: String,
    val productName: String,
    val brand: String,
    val category: String,
    val unitLabel: String,
    val lastPriceCents: Long,
    val lastMarketName: String
)

data class CategoryAlternativeOption(
    val productName: String,
    val brand: String,
    val category: String,
    val unitLabel: String,
    val cheapestPriceCents: Long,
    val cheapestMarketName: String,
    val estimatedSavingsPercent: Int
)

enum class TrendSeverity {
    NORMAL,
    ATENCAO,
    ALTO
}

data class PriceTrendAnalysis(
    val severity: TrendSeverity,
    val vsAveragePercent: Int,
    val spreadCents: Long,
    val minPriceCents: Long,
    val maxPriceCents: Long,
    val averagePriceCents: Long,
    val latestPriceCents: Long,
    val cheapestMarketName: String,
    val promoCount: Int,
    val summaryText: String
)

object FeiraCategories {
    val all = listOf(
        "Todos",
        "Mercearia",
        "Laticínios",
        "Hortifrúti",
        "Açougue",
        "Bebidas",
        "Limpeza",
        "Higiene",
        "Padaria"
    )
    val editable = all.drop(1)
    val units = listOf("1 un", "1 kg", "500 g", "5 kg", "1 L", "2 L", "350 mL", "pct")
}

private val brLocale = Locale.forLanguageTag("pt-BR")

fun formatBrl(priceCents: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(brLocale)
    return formatter.format(priceCents / 100.0)
}

fun parseBrlToCents(raw: String): Long? {
    val cleaned = raw
        .replace("R$", "", ignoreCase = true)
        .replace(" ", "")
        .trim()
    if (cleaned.isEmpty()) return null
    val normalized = if (cleaned.contains(",") && cleaned.contains(".")) {
        cleaned.replace(".", "").replace(",", ".")
    } else {
        cleaned.replace(",", ".")
    }
    val value = normalized.toDoubleOrNull() ?: return null
    val cents = kotlin.math.round(value * 100.0).toLong()
    return if (cents > 0L && cents <= 10_000_000L) cents else null
}

fun normalizeProductName(raw: String): String {
    val decomposed = Normalizer.normalize(raw.trim().lowercase(brLocale), Normalizer.Form.NFD)
    val withoutAccents = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    return withoutAccents.replace(Regex("\\s+"), " ").trim()
}

fun generateInviteCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val suffix = (1..6).map { chars.random() }.joinToString("")
    return "FJ-$suffix"
}

fun resolvePhotoModel(photoRef: String?): Any? {
    val clean = photoRef?.trim().orEmpty()
    if (clean.isEmpty()) return null
    return if (clean.startsWith("http://", ignoreCase = true) ||
        clean.startsWith("https://", ignoreCase = true) ||
        clean.startsWith("content://", ignoreCase = true) ||
        clean.startsWith("file://", ignoreCase = true)
    ) {
        clean
    } else {
        File(clean)
    }
}
