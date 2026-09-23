package com.textgate.ai.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.textgate.ai.security.AppSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Offline deterministic contract tests: no real Gemini API calls, no API keys
 * and no reliance on the state of Google's servers or rate limits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranslationOrchestratorFailoverTest {
    private lateinit var store: ModelAvailabilityStore
    private val now = Instant.parse("2026-09-23T10:00:00Z")
    private val success = GeminiClient.Result.Success("Translated before sending")
    private val highDemand = GeminiClient.Result.Failure.HttpError(503, "High demand")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("textgate_model_availability", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = ModelAvailabilityStore(context)
    }

    @Test
    fun `503 on both large quota models falls back to known working 2_5 Flash`() {
        val tried = mutableListOf<String>()
        val result = TranslationOrchestrator.translateWithFallback(
            store, AppSettingsStore.DEFAULT_MODEL, now
        ) { model, timeout ->
            assertEquals(if (model == "gemini-2.5-flash") 4_500 else 3_000, timeout)
            tried += model
            if (model == "gemini-2.5-flash") success else highDemand
        }
        assertEquals(success, result)
        assertEquals(
            listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash"),
            tried
        )
        assertTrue(store.isUnavailable("gemini-3.5-flash-lite", now))
        assertTrue(store.isUnavailable("gemini-3.1-flash-lite", now))
    }

    @Test
    fun `503 cooldown skips failing models on next translation and retries after expiry`() {
        TranslationOrchestrator.translateWithFallback(
            store, AppSettingsStore.DEFAULT_MODEL, now
        ) { model, _ -> if (model == "gemini-2.5-flash") success else highDemand }

        val withinCooldown = mutableListOf<String>()
        val result = TranslationOrchestrator.translateWithFallback(
            store, AppSettingsStore.DEFAULT_MODEL, now.plusSeconds(30)
        ) { model, _ ->
            withinCooldown += model
            success
        }
        assertEquals(success, result)
        assertEquals(listOf("gemini-2.5-flash"), withinCooldown)

        val afterExpiry = mutableListOf<String>()
        TranslationOrchestrator.translateWithFallback(
            store, AppSettingsStore.DEFAULT_MODEL, now.plusSeconds(181)
        ) { model, _ ->
            afterExpiry += model
            success
        }
        assertEquals(listOf(AppSettingsStore.DEFAULT_MODEL), afterExpiry)
    }

    @Test
    fun `manual selection is honored first while hidden fallback remains automatic`() {
        val tried = mutableListOf<String>()
        val result = TranslationOrchestrator.translateWithFallback(
            store, "gemini-3.7-flash", now
        ) { model, _ ->
            tried += model
            if (model == "gemini-2.5-flash") success else highDemand
        }
        assertEquals(success, result)
        assertEquals(
            listOf(
                "gemini-3.7-flash", "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite", "gemini-2.5-flash"
            ),
            tried
        )
    }

    @Test
    fun `daily 429 skips exhausted model and still uses other available models`() {
        val quota = GeminiClient.Result.Failure.AllKeysExhausted(
            GeminiClient.Result.Failure.QuotaExceeded(
                GeminiClient.Result.Failure.QuotaScope.DAILY, null
            )
        )
        val tried = mutableListOf<String>()
        val result = TranslationOrchestrator.translateWithFallback(
            store, AppSettingsStore.DEFAULT_MODEL, now
        ) { model, _ ->
            tried += model
            if (model == AppSettingsStore.DEFAULT_MODEL) quota else success
        }
        assertEquals(success, result)
        assertEquals(listOf(AppSettingsStore.DEFAULT_MODEL, "gemini-3.1-flash-lite"), tried)
        assertTrue(store.isUnavailable(AppSettingsStore.DEFAULT_MODEL, now.plusSeconds(181)))
    }

    @Test
    fun `unknown 429 uses short cooldown rather than falsely blocking a whole day`() {
        val quota = GeminiClient.Result.Failure.AllKeysExhausted(
            GeminiClient.Result.Failure.QuotaExceeded(
                GeminiClient.Result.Failure.QuotaScope.UNKNOWN, null
            )
        )
        TranslationOrchestrator.translateWithFallback(
            store, AppSettingsStore.DEFAULT_MODEL, now
        ) { model, _ -> if (model == AppSettingsStore.DEFAULT_MODEL) quota else success }
        assertTrue(store.isUnavailable(AppSettingsStore.DEFAULT_MODEL, now.plusSeconds(30)))
        assertFalse(store.isUnavailable(AppSettingsStore.DEFAULT_MODEL, now.plusSeconds(61)))
    }

    @Test
    fun `400 and 403 remain visible instead of hiding request or key bugs`() {
        for (code in listOf(400, 403)) {
            val tried = mutableListOf<String>()
            val result = TranslationOrchestrator.translateWithFallback(
                store, AppSettingsStore.DEFAULT_MODEL, now
            ) { model, _ ->
                tried += model
                GeminiClient.Result.Failure.HttpError(code, "Fix configuration")
            }
            assertEquals(code, (result as GeminiClient.Result.Failure.HttpError).code)
            assertEquals(listOf(AppSettingsStore.DEFAULT_MODEL), tried)
        }
    }

    @Test
    fun `local network failure does not flood all available models`() {
        val tried = mutableListOf<String>()
        val result = TranslationOrchestrator.translateWithFallback(
            store, AppSettingsStore.DEFAULT_MODEL, now
        ) { model, _ ->
            tried += model
            GeminiClient.Result.Failure.NetworkError
        }
        assertTrue(result is GeminiClient.Result.Failure.NetworkError)
        assertEquals(1, tried.size)
    }

    @Test
    fun `hard cap on model attempts prevents serial tries through the entire catalog`() {
        val tried = mutableListOf<String>()
        val result = TranslationOrchestrator.translateWithFallback(
            store, AppSettingsStore.DEFAULT_MODEL, now
        ) { model, _ ->
            tried += model
            highDemand
        }
        assertTrue(result is GeminiClient.Result.Failure.HttpError)
        assertEquals(TranslationOrchestrator.MAX_MODEL_ATTEMPTS, tried.size)
        assertEquals(tried.size, tried.distinct().size)
    }

    @Test
    fun `additional hidden models become available when earlier candidates are cooling down`() {
        TranslationOrchestrator.translateWithFallback(
            store, AppSettingsStore.DEFAULT_MODEL, now
        ) { _, _ -> highDemand }

        val tried = mutableListOf<String>()
        val result = TranslationOrchestrator.translateWithFallback(
            store, AppSettingsStore.DEFAULT_MODEL, now.plusSeconds(10)
        ) { model, _ ->
            tried += model
            success
        }
        assertEquals(success, result)
        assertEquals(listOf("gemini-3.8-flash"), tried)
    }

    @Test
    fun `cooperative 12 second budget prevents starting another model late`() {
        var elapsed = 0L
        val tried = mutableListOf<String>()
        TranslationOrchestrator.translateWithFallback(
            availabilityStore = store,
            requestedModel = AppSettingsStore.DEFAULT_MODEL,
            now = now,
            elapsedMillis = { elapsed },
            attempt = { model, _ ->
                tried += model
                elapsed += 4_000L
                highDemand
            }
        )
        assertEquals(3, tried.size)
    }

    @Test
    fun `short remaining budget is forwarded to network request`() {
        val timeouts = mutableListOf<Int>()
        TranslationOrchestrator.translateWithFallback(
            availabilityStore = store,
            requestedModel = AppSettingsStore.DEFAULT_MODEL,
            now = now,
            elapsedMillis = { when (timeouts.size) {
                0 -> 8_000L
                1 -> 10_500L
                else -> 12_000L
            } },
            attempt = { _, timeout ->
                timeouts += timeout
                highDemand
            }
        )
        assertEquals(listOf(3_000, 1_500), timeouts)
    }

    @Test
    fun `invalid custom model is rejected without falling back silently`() {
        var requests = 0
        val result = TranslationOrchestrator.translateWithFallback(
            store, "models/invalid?key=oops", now
        ) { _, _ ->
            requests++
            success
        }
        assertTrue(result is GeminiClient.Result.Failure.InvalidModel)
        assertEquals(0, requests)
    }

    @Test
    fun `fallback model IDs are deduplicated without changing selected model`() {
        val candidates = TranslationOrchestrator.candidateModels("gemini-3.1-flash-lite")
        assertEquals("gemini-3.1-flash-lite", candidates.first())
        assertEquals(1, candidates.count { it == "gemini-3.1-flash-lite" })
        assertTrue(candidates.contains("gemini-2.5-flash-lite"))
        assertTrue(candidates.contains("gemini-3.8-flash"))
    }
}
