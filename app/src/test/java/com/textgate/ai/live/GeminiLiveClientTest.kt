package com.textgate.ai.live

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [GeminiLiveClient.parseServerMessage]'s pure JSON parsing —
 * exercised against hand-written message bodies, not a real Gemini Live
 * connection (see that function's own doc comment, and this file's
 * counterpart in GeminiClientTest for the same reasoning applied there).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeminiLiveClientTest {

    private val client = GeminiLiveClient()

    @Test
    fun `setupComplete message produces SetupComplete`() {
        val events = client.parseServerMessage("""{"setupComplete":{}}""")
        assertTrue(events.any { it is GeminiLiveClient.ServerEvent.SetupComplete })
    }

    @Test
    fun `input and output transcription text are both surfaced`() {
        val raw = """
            {"serverContent":{
                "inputTranscription":{"text":"Hello there"},
                "outputTranscription":{"text":"Cześć"}
            }}
        """.trimIndent()
        val events = client.parseServerMessage(raw)
        assertTrue(events.contains(GeminiLiveClient.ServerEvent.InputTranscript("Hello there")))
        assertTrue(events.contains(GeminiLiveClient.ServerEvent.OutputTranscript("Cześć")))
    }

    @Test
    fun `transcription languageCode is surfaced when present`() {
        val raw = """
            {"serverContent":{
                "inputTranscription":{"text":"Hello there","languageCode":"en-US"},
                "outputTranscription":{"text":"Cześć","languageCode":"pl"}
            }}
        """.trimIndent()
        val events = client.parseServerMessage(raw)
        val input = events.filterIsInstance<GeminiLiveClient.ServerEvent.InputTranscript>().single()
        val output = events.filterIsInstance<GeminiLiveClient.ServerEvent.OutputTranscript>().single()
        assertEquals("en-US", input.languageCode)
        assertEquals("pl", output.languageCode)
    }

    @Test
    fun `transcription languageCode is null when absent`() {
        val raw = """{"serverContent":{"inputTranscription":{"text":"Hello there"}}}"""
        val events = client.parseServerMessage(raw)
        val input = events.filterIsInstance<GeminiLiveClient.ServerEvent.InputTranscript>().single()
        assertEquals(null, input.languageCode)
    }

    @Test
    fun `empty transcription text is not surfaced as an event`() {
        val raw = """{"serverContent":{"inputTranscription":{"text":""}}}"""
        val events = client.parseServerMessage(raw)
        assertTrue(events.none { it is GeminiLiveClient.ServerEvent.InputTranscript })
    }

    @Test
    fun `inline audio data is decoded from base64`() {
        val pcmBytes = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val raw = """
            {"serverContent":{"modelTurn":{"parts":[
                {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$encoded"}}
            ]}}}
        """.trimIndent()
        val events = client.parseServerMessage(raw)
        val audioEvent = events.filterIsInstance<GeminiLiveClient.ServerEvent.AudioChunk>().single()
        assertTrue(audioEvent.pcm16.contentEquals(pcmBytes))
    }

    @Test
    fun `turnComplete flag produces TurnComplete`() {
        val events = client.parseServerMessage("""{"serverContent":{"turnComplete":true}}""")
        assertTrue(events.any { it is GeminiLiveClient.ServerEvent.TurnComplete })
    }

    @Test
    fun `turnComplete absent or false produces no TurnComplete event`() {
        val events = client.parseServerMessage("""{"serverContent":{"turnComplete":false}}""")
        assertTrue(events.none { it is GeminiLiveClient.ServerEvent.TurnComplete })
    }

    @Test
    fun `error object is surfaced with its message`() {
        val events = client.parseServerMessage("""{"error":{"code":8,"message":"quota exceeded"}}""")
        val error = events.filterIsInstance<GeminiLiveClient.ServerEvent.Error>().single()
        assertEquals("quota exceeded", error.message)
    }

    @Test
    fun `error object with quota wording is classified as QUOTA`() {
        val events = client.parseServerMessage("""{"error":{"code":8,"message":"RESOURCE_EXHAUSTED: quota exceeded"}}""")
        val error = events.filterIsInstance<GeminiLiveClient.ServerEvent.Error>().single()
        assertEquals(GeminiLiveClient.LiveErrorCategory.QUOTA, error.category)
    }

    // --- classifyLiveError: point 9 of the v2.x request (error
    // classification) — ground-truthed against google-genai's own live.py/
    // errors.py source (see classifyLiveError's own doc comment): real Live
    // errors surface via the WebSocket close code/reason, not an in-band
    // JSON field, so this is what actually needs to be right. ---

    @Test
    fun `classifyLiveError recognizes quota wording`() {
        assertEquals(
            GeminiLiveClient.LiveErrorCategory.QUOTA,
            GeminiLiveClient.classifyLiveError("8 RESOURCE_EXHAUSTED. Quota exceeded for quota metric...")
        )
    }

    @Test
    fun `classifyLiveError recognizes API key wording`() {
        assertEquals(
            GeminiLiveClient.LiveErrorCategory.AUTH,
            GeminiLiveClient.classifyLiveError("API key not valid. Please pass a valid API key.")
        )
    }

    @Test
    fun `classifyLiveError recognizes an HTTP 401 embedded in a connection failure message`() {
        assertEquals(
            GeminiLiveClient.LiveErrorCategory.AUTH,
            GeminiLiveClient.classifyLiveError("SocketException: no message (HTTP 401 Unauthorized)")
        )
    }

    @Test
    fun `classifyLiveError recognizes invalid argument wording`() {
        assertEquals(
            GeminiLiveClient.LiveErrorCategory.CONFIG,
            GeminiLiveClient.classifyLiveError("INVALID_ARGUMENT: unsupported language code")
        )
    }

    @Test
    fun `classifyLiveError falls back to UNKNOWN for an ordinary network close reason`() {
        assertEquals(
            GeminiLiveClient.LiveErrorCategory.UNKNOWN,
            GeminiLiveClient.classifyLiveError("Abnormal closure.")
        )
    }

    @Test
    fun `onClosed event carries a classified category`() {
        // parseServerMessage doesn't cover onClosed (that's a WebSocketListener
        // callback, not a parsed message) — this exercises the classifier the
        // same way LiveTranslationService/ConversationTabController do.
        val category = GeminiLiveClient.classifyLiveError("rate limit exceeded, please slow down")
        assertEquals(GeminiLiveClient.LiveErrorCategory.QUOTA, category)
    }

    @Test
    fun `malformed json never throws, produces no events`() {
        val events = client.parseServerMessage("not json at all {{{")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `unrecognized shape never throws, produces no events`() {
        val events = client.parseServerMessage("""{"somethingElseEntirely":true}""")
        assertTrue(events.isEmpty())
    }

    // --- buildSetupMessage / buildRealtimeAudioMessage: regression coverage
    // for the real, user-hit "stuck at Łączenie forever" bug — a missing
    // responseModalities field, a misplaced translationConfig, and a wrong
    // realtimeInput shape, all confirmed against the actual google-genai
    // Python SDK's request-serialization source. See GeminiLiveClient's own
    // IMPLEMENTATION NOTE for the full story. ---

    @Test
    fun `setup message requests AUDIO response modality`() {
        val setup = client.buildSetupMessage("gemini-3.5-live-translate-preview", "pl")
            .getJSONObject("setup")
        val modalities = setup.getJSONObject("generationConfig").getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))
    }

    @Test
    fun `setup message keeps transcription config at the top level, not nested in generationConfig`() {
        val setup = client.buildSetupMessage("gemini-3.5-live-translate-preview", "pl")
            .getJSONObject("setup")
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        assertTrue(setup.getJSONObject("generationConfig").has("responseModalities"))
        assertTrue(!setup.getJSONObject("generationConfig").has("inputAudioTranscription"))
        assertTrue(!setup.getJSONObject("generationConfig").has("outputAudioTranscription"))
    }

    @Test
    fun `setup message nests translationConfig inside generationConfig, not at the top level`() {
        val setup = client.buildSetupMessage("gemini-3.5-live-translate-preview", "pl")
            .getJSONObject("setup")
        assertTrue(!setup.has("translationConfig"))
        assertTrue(setup.getJSONObject("generationConfig").has("translationConfig"))
    }

    @Test
    fun `setup message carries the model path and target language`() {
        val setup = client.buildSetupMessage("gemini-3.5-live-translate-preview", "pl")
            .getJSONObject("setup")
        assertEquals("models/gemini-3.5-live-translate-preview", setup.getString("model"))
        assertEquals(
            "pl",
            setup.getJSONObject("generationConfig").getJSONObject("translationConfig")
                .getString("targetLanguageCode")
        )
    }

    // --- v2.x additions: echoTargetLanguage and server-side VAD tuning,
    // both confirmed real fields against google-genai's types.py/
    // _live_converters.py (see buildSetupMessage's own doc comment). ---

    @Test
    fun `setup message explicitly disables echoTargetLanguage`() {
        val setup = client.buildSetupMessage("gemini-3.5-live-translate-preview", "pl")
            .getJSONObject("setup")
        val translationConfig = setup.getJSONObject("generationConfig").getJSONObject("translationConfig")
        assertTrue(translationConfig.has("echoTargetLanguage"))
        assertEquals(false, translationConfig.getBoolean("echoTargetLanguage"))
    }

    @Test
    fun `setup message configures realtimeInputConfig as a top-level sibling of generationConfig`() {
        val setup = client.buildSetupMessage("gemini-3.5-live-translate-preview", "pl")
            .getJSONObject("setup")
        assertTrue(setup.has("realtimeInputConfig"))
        assertTrue(!setup.getJSONObject("generationConfig").has("realtimeInputConfig"))
        val silenceMs = setup.getJSONObject("realtimeInputConfig")
            .getJSONObject("automaticActivityDetection")
            .getInt("silenceDurationMs")
        assertTrue("silenceDurationMs should be within the 500-600ms band", silenceMs in 500..600)
    }

    @Test
    fun `realtime audio message uses a single audio object, not a mediaChunks array`() {
        val pcmBytes = byteArrayOf(9, 8, 7, 6)
        val message = client.buildRealtimeAudioMessage(pcmBytes).getJSONObject("realtimeInput")
        assertTrue(message.has("audio"))
        assertTrue(!message.has("mediaChunks"))
        val audio = message.getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        val decoded = Base64.decode(audio.getString("data"), Base64.NO_WRAP)
        assertTrue(decoded.contentEquals(pcmBytes))
    }
}
