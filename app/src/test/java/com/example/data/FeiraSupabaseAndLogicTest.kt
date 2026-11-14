package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.media.ProductPhotoHelper
import com.example.data.model.FeiraAnalytics
import com.example.data.model.MarketQuote
import com.example.data.model.ProductComparisonSummary
import com.example.data.model.TrendSeverity
import com.example.data.model.UnifiedPriceItem
import com.example.data.model.formatBrl
import com.example.data.model.generateInviteCode
import com.example.data.model.normalizeProductName
import com.example.data.model.parseBrlToCents
import com.example.data.model.resolvePhotoModel
import com.example.data.supabase.SupabaseConfig
import com.example.data.supabase.SupabaseRealtimeClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeiraSupabaseAndLogicTest {

    @Test
    fun `supabase config rejects placeholders and accepts valid credentials`() {
        assertFalse(SupabaseConfig.isConfigured("https://YOUR_PROJECT_ID.supabase.co", "YOUR_SUPABASE_ANON_KEY"))
        assertFalse(SupabaseConfig.isConfigured("", "sb_publishable_123456"))
        assertFalse(SupabaseConfig.isConfigured("https://abcxyz.supabase.co", ""))
        assertFalse(SupabaseConfig.isConfigured("ftp://abcxyz.supabase.co", "anon-key"))

        assertTrue(SupabaseConfig.isConfigured("https://abcxyz.supabase.co/", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.valid"))
        assertEquals("https://abcxyz.supabase.co", SupabaseConfig.normalizeBaseUrl("https://abcxyz.supabase.co///"))
        assertEquals("https://abcxyz.supabase.co", SupabaseConfig.normalizeBaseUrl("https://abcxyz.supabase.co/rest/v1"))
        assertEquals("https://abcxyz.supabase.co", SupabaseConfig.normalizeBaseUrl("https://abcxyz.supabase.co/auth/v1"))
        assertEquals("https://abcxyz.supabase.co", SupabaseConfig.normalizeBaseUrl("\"https://abcxyz.supabase.co/\""))
        assertEquals(
            "https://abcdefghij.supabase.co",
            SupabaseConfig.normalizeBaseUrl("https://supabase.com/dashboard/project/abcdefghij/settings/api")
        )
    }

    @Test
    fun `supabase auth error mapper translates common GoTrue errors to Portuguese`() {
        val invalidPath = SupabaseConfig.mapAuthErrorMessage(
            404,
            """{"message":"Invalid path specified in request URL"}"""
        )
        assertTrue(invalidPath.contains("SUPABASE_URL"))

        val invalid = SupabaseConfig.mapAuthErrorMessage(
            400,
            """{"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""
        )
        assertEquals("E-mail ou senha incorretos.", invalid)

        val unconfirmed = SupabaseConfig.mapAuthErrorMessage(
            400,
            """{"error_code":"email_not_confirmed","msg":"Email not confirmed"}"""
        )
        assertTrue(unconfirmed.contains("confirmado"))

        val exists = SupabaseConfig.mapAuthErrorMessage(
            422,
            """{"error_code":"user_already_exists","msg":"User already registered"}"""
        )
        assertTrue(exists.contains("cadastrado"))
    }

    @Test
    fun `supabase rest error mapper detects missing schema tables`() {
        val (code, msg) = SupabaseConfig.mapRestErrorMessage(
            404,
            """{"code":"PGRST205","message":"Could not find the table 'public.markets' in the schema cache"}"""
        )
        assertEquals("PGRST205", code)
        assertTrue(msg.contains("supabase_schema.sql"))

        val (_, bucketMsg) = SupabaseConfig.mapRestErrorMessage(
            404,
            """{"statusCode":"404","error":"Bucket not found","message":"Bucket not found"}"""
        )
        assertTrue(bucketMsg.contains(SupabaseConfig.PRODUCT_PHOTOS_BUCKET))
    }

    @Test
    fun `supabase storage public url builder and extractor handle product photo paths`() {
        val objectPath = "user-123/foto-arroz.jpg"
        val publicUrl = SupabaseConfig.publicStorageObjectUrl(
            bucket = SupabaseConfig.PRODUCT_PHOTOS_BUCKET,
            objectPath = objectPath,
            hostBaseUrl = "https://abcxyz.supabase.co/rest/v1"
        )
        assertEquals(
            "https://abcxyz.supabase.co/storage/v1/object/public/product-photos/user-123/foto-arroz.jpg",
            publicUrl
        )
        assertEquals(objectPath, SupabaseConfig.extractStorageObjectPath(publicUrl))
        assertNull(SupabaseConfig.extractStorageObjectPath("https://example.com/other.jpg"))

        assertNull(resolvePhotoModel(""))
        assertEquals(publicUrl, resolvePhotoModel(publicUrl))
        assertTrue(resolvePhotoModel("/data/user/0/app/files/product_photos/test.jpg") is File)
    }

    @Test
    fun `supabase realtime url and phoenix payloads are formatted cleanly`() {
        val wsUrl = SupabaseConfig.realtimeWebSocketUrl(
            hostBaseUrl = "https://abcxyz.supabase.co/rest/v1",
            key = "anon-key-123"
        )
        assertEquals(
            "wss://abcxyz.supabase.co/realtime/v1/websocket?apikey=anon-key-123&vsn=1.0.0",
            wsUrl
        )

        val joinJson = JSONObject(SupabaseRealtimeClient.buildJoinMessage("jwt-abc", ref = "7"))
        assertEquals(SupabaseRealtimeClient.REALTIME_TOPIC, joinJson.getString("topic"))
        assertEquals("phx_join", joinJson.getString("event"))
        assertEquals("7", joinJson.getString("ref"))
        val changes = joinJson.getJSONObject("payload")
            .getJSONObject("config")
            .getJSONArray("postgres_changes")
        assertEquals(4, changes.length())
        assertEquals("price_records", changes.getJSONObject(0).getString("table"))

        val hbJson = JSONObject(SupabaseRealtimeClient.buildHeartbeatMessage("8"))
        assertEquals("phoenix", hbJson.getString("topic"))
        assertEquals("heartbeat", hbJson.getString("event"))

        val insertEvent = SupabaseRealtimeClient.parsePostgresChangeEvent(
            JSONObject(
                """
                {
                  "topic": "realtime:feira-sync",
                  "event": "postgres_changes",
                  "payload": {
                    "data": {
                      "schema": "public",
                      "table": "price_records",
                      "type": "INSERT"
                    }
                  }
                }
                """.trimIndent()
            )
        )
        assertNotNull(insertEvent)
        assertEquals("price_records", insertEvent!!.table)
        assertEquals("INSERT", insertEvent.eventType)

        val deleteEvent = SupabaseRealtimeClient.parsePostgresChangeEvent(
            JSONObject(
                """
                {
                  "topic": "realtime:feira-sync",
                  "event": "postgres_changes",
                  "payload": {
                    "data": {
                      "schema": "public",
                      "table": "markets",
                      "type": "DELETE"
                    }
                  }
                }
                """.trimIndent()
            )
        )
        assertNotNull(deleteEvent)
        assertEquals("markets", deleteEvent!!.table)
        assertEquals("DELETE", deleteEvent.eventType)
    }

    @Test
    fun `product photo helper compresses and stores selected image locally`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val srcFile = File(context.cacheDir, "sample_input.jpg")
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        FileOutputStream(srcFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()

        val result = ProductPhotoHelper.compressAndSaveToAppStorage(
            context = context,
            sourceUri = Uri.fromFile(srcFile),
            tempCameraFile = srcFile
        )
        assertTrue("Expected photo compression to succeed, got: ${result.exceptionOrNull()}", result.isSuccess)
        val savedPath = result.getOrThrow()
        val savedFile = File(savedPath)
        assertTrue(savedFile.exists())
        assertTrue(savedFile.length() > 0L)
        assertFalse(srcFile.exists())
        ProductPhotoHelper.deleteLocalPhoto(savedPath)
        assertFalse(savedFile.exists())
    }

    @Test
    fun `brl parsing normalization and invite code helpers work reliably`() {
        assertEquals(2490L, parseBrlToCents("R$ 24,90"))
        assertEquals(129900L, parseBrlToCents("1.299,00"))
        assertNull(parseBrlToCents("0"))
        assertNull(parseBrlToCents("abc"))
        assertEquals("R$\u00A024,90", formatBrl(2490L))
        assertEquals("cafe torrado extra forte", normalizeProductName("  Café Torrado   Extra Forte "))
        assertTrue(generateInviteCode().matches(Regex("^FJ-[A-Z0-9]{6}$")))
    }

    @Test
    fun `category alternatives finds cheaper products in same category`() {
        val comparisons = listOf(
            sampleComparison("azeite extra virgem", "Azeite Extra Virgem", "Mercearia", 4000L, "Carrefour"),
            sampleComparison("oleo de soja", "Óleo de Soja", "Mercearia", 700L, "Assaí"),
            sampleComparison("arroz tipo 1", "Arroz Tipo 1", "Mercearia", 2500L, "Atacadão"),
            sampleComparison("leite integral", "Leite Integral", "Laticínios", 480L, "Guanabara")
        )

        val alts = FeiraAnalytics.findCategoryAlternatives(
            productName = "Azeite Extra Virgem",
            currentPriceCents = 4000L,
            comparisons = comparisons
        )

        assertEquals(2, alts.size)
        assertEquals("Óleo de Soja", alts[0].productName)
        assertEquals(83, alts[0].estimatedSavingsPercent)
        assertEquals("Arroz Tipo 1", alts[1].productName)
    }

    @Test
    fun `local price trend analysis classifies spike and normal prices accurately`() {
        val records = listOf(
            sampleRecord("1", 3000L, "Atacadão", 1000L, isPromo = false),
            sampleRecord("2", 3100L, "Assaí", 2000L, isPromo = true),
            sampleRecord("3", 4500L, "Carrefour", 3000L, isPromo = false)
        )

        val analysis = FeiraAnalytics.analyzePriceTrend("Azeite", records)
        assertNotNull(analysis)
        assertEquals(TrendSeverity.ALTO, analysis!!.severity)
        assertEquals(48, analysis.vsAveragePercent)
        assertEquals(1500L, analysis.spreadCents)
        assertEquals(1, analysis.promoCount)
        assertEquals("Atacadão", analysis.cheapestMarketName)
    }

    private fun sampleComparison(
        norm: String,
        display: String,
        cat: String,
        price: Long,
        market: String
    ): ProductComparisonSummary {
        val q = MarketQuote(
            marketId = "m1",
            marketName = market,
            priceCents = price,
            isPromo = false,
            recordedAtMillis = 1000L,
            isPendingSync = false
        )
        return ProductComparisonSummary(
            normalizedName = norm,
            displayName = display,
            brand = "",
            category = cat,
            unitLabel = "1 un",
            cheapestQuote = q,
            highestPriceCents = price,
            potentialSavingsCents = 0L,
            quotesByMarket = listOf(q),
            priceDeltaPercent = null,
            hasPendingSync = false
        )
    }

    private fun sampleRecord(
        id: String,
        priceCents: Long,
        market: String,
        time: Long,
        isPromo: Boolean
    ) = UnifiedPriceItem(
        id = id,
        productName = "Azeite",
        normalizedName = "azeite",
        brand = "Gallo",
        category = "Mercearia",
        unitLabel = "500 mL",
        priceCents = priceCents,
        marketId = "m",
        marketName = market,
        isPromo = isPromo,
        recordedAtMillis = time
    )
}
