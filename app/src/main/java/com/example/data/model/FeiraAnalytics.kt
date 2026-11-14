package com.example.data.model

import kotlin.math.roundToInt

object FeiraAnalytics {

    fun buildProductQuickTemplates(
        comparisons: List<ProductComparisonSummary>
    ): List<ProductQuickTemplate> {
        return comparisons.map { summary ->
            val newestQuote = summary.quotesByMarket.maxByOrNull { it.recordedAtMillis } ?: summary.cheapestQuote
            ProductQuickTemplate(
                normalizedName = summary.normalizedName,
                productName = summary.displayName,
                brand = summary.brand,
                category = summary.category,
                unitLabel = summary.unitLabel,
                lastPriceCents = newestQuote.priceCents,
                lastMarketName = newestQuote.marketName
            )
        }
    }

    fun findCategoryAlternatives(
        productName: String,
        currentPriceCents: Long?,
        comparisons: List<ProductComparisonSummary>
    ): List<CategoryAlternativeOption> {
        if (comparisons.isEmpty()) return emptyList()
        val targetNorm = normalizeProductName(productName)
        val matchedProduct = comparisons.firstOrNull { it.normalizedName == targetNorm }
        val targetCategory = matchedProduct?.category ?: inferCategoryFromName(targetNorm)
        val referencePrice = currentPriceCents ?: matchedProduct?.cheapestQuote?.priceCents

        val sameCategoryCandidates = if (targetCategory != null) {
            comparisons.filter {
                it.normalizedName != targetNorm &&
                    it.category.equals(targetCategory, ignoreCase = true)
            }
        } else {
            emptyList()
        }

        val pool = sameCategoryCandidates.ifEmpty {
            comparisons.filter { it.normalizedName != targetNorm }
        }

        val filtered = if (referencePrice != null && referencePrice > 0L) {
            pool.filter { it.cheapestQuote.priceCents < referencePrice }
        } else {
            pool
        }

        return filtered
            .sortedBy { it.cheapestQuote.priceCents }
            .take(5)
            .map { candidate ->
                val savingsPct = if (referencePrice != null && referencePrice > 0L) {
                    (((referencePrice - candidate.cheapestQuote.priceCents).toDouble() / referencePrice.toDouble()) * 100.0)
                        .roundToInt()
                        .coerceIn(1, 95)
                } else {
                    0
                }
                CategoryAlternativeOption(
                    productName = candidate.displayName,
                    brand = candidate.brand,
                    category = candidate.category,
                    unitLabel = candidate.unitLabel,
                    cheapestPriceCents = candidate.cheapestQuote.priceCents,
                    cheapestMarketName = candidate.cheapestQuote.marketName,
                    estimatedSavingsPercent = savingsPct
                )
            }
    }

    fun analyzePriceTrend(
        productName: String,
        records: List<UnifiedPriceItem>
    ): PriceTrendAnalysis? {
        if (records.isEmpty()) return null
        val chrono = records.sortedBy { it.recordedAtMillis }
        val latest = chrono.last()
        val minRec = chrono.minBy { it.priceCents }
        val maxRec = chrono.maxBy { it.priceCents }
        val avgAll = chrono.map { it.priceCents }.average().toLong()
        val spread = (maxRec.priceCents - minRec.priceCents).coerceAtLeast(0L)
        val promoCount = chrono.count { it.isPromo }

        if (chrono.size == 1) {
            return PriceTrendAnalysis(
                severity = TrendSeverity.NORMAL,
                vsAveragePercent = 0,
                spreadCents = 0L,
                minPriceCents = minRec.priceCents,
                maxPriceCents = maxRec.priceCents,
                averagePriceCents = avgAll,
                latestPriceCents = latest.priceCents,
                cheapestMarketName = minRec.marketName,
                promoCount = promoCount,
                summaryText = "Há apenas 1 registro para $productName (${formatBrl(latest.priceCents)} no ${latest.marketName}). " +
                    "Cadastre novos preços ao longo do tempo para acompanhar variações e detectar altas."
            )
        }

        val previousAverage = chrono.dropLast(1).map { it.priceCents }.average()
        val deltaPct = if (previousAverage > 0.0) {
            (((latest.priceCents - previousAverage) / previousAverage) * 100.0).roundToInt()
        } else {
            0
        }

        val severity = when {
            deltaPct >= 15 -> TrendSeverity.ALTO
            deltaPct >= 5 -> TrendSeverity.ATENCAO
            else -> TrendSeverity.NORMAL
        }

        val summary = when {
            severity == TrendSeverity.ALTO ->
                "O registro mais recente (${formatBrl(latest.priceCents)} no ${latest.marketName}) está +$deltaPct% " +
                    "acima da média anterior (${formatBrl(previousAverage.toLong())}). Vale priorizar ${minRec.marketName}, " +
                    "onde já apareceu por ${formatBrl(minRec.priceCents)}."
            severity == TrendSeverity.ATENCAO ->
                "Leve alta de +$deltaPct% frente à média anterior (${formatBrl(previousAverage.toLong())}). " +
                    "A diferença entre o menor (${formatBrl(minRec.priceCents)}) e o maior preço já chega a ${formatBrl(spread)}."
            deltaPct <= -5 ->
                "Boa oportunidade: o último valor (${formatBrl(latest.priceCents)} no ${latest.marketName}) está ${-deltaPct}% " +
                    "abaixo da média anterior (${formatBrl(previousAverage.toLong())})."
            else ->
                "Comportamento estável em relação à média anterior (${formatBrl(previousAverage.toLong())}). " +
                    "Melhor mercado registrado até agora: ${minRec.marketName} (${formatBrl(minRec.priceCents)})."
        }

        return PriceTrendAnalysis(
            severity = severity,
            vsAveragePercent = deltaPct,
            spreadCents = spread,
            minPriceCents = minRec.priceCents,
            maxPriceCents = maxRec.priceCents,
            averagePriceCents = avgAll,
            latestPriceCents = latest.priceCents,
            cheapestMarketName = minRec.marketName,
            promoCount = promoCount,
            summaryText = summary
        )
    }

    private fun inferCategoryFromName(normalized: String): String? {
        return when {
            listOf("leite", "queijo", "manteiga", "iogurte", "requeijao").any { normalized.contains(it) } -> "Laticínios"
            listOf("banana", "tomate", "maca", "alface", "batata", "cebola").any { normalized.contains(it) } -> "Hortifrúti"
            listOf("frango", "carne", "alcatra", "patinho", "peixe", "linguica").any { normalized.contains(it) } -> "Açougue"
            listOf("suco", "refrigerante", "agua", "cerveja").any { normalized.contains(it) } -> "Bebidas"
            listOf("sabao", "detergente", "agua sanitaria", "desinfetante").any { normalized.contains(it) } -> "Limpeza"
            listOf("papel higienico", "sabonete", "shampoo", "creme dental").any { normalized.contains(it) } -> "Higiene"
            listOf("pao", "bolo", "bisnaguinha", "torrada").any { normalized.contains(it) } -> "Padaria"
            else -> null
        }
    }
}
