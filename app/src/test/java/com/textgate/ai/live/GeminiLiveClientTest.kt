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
    fun `malformed json never throws, produces no events`() {
        val events = client.parseServerMessage("not json at all {{{")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `unrecognized shape never throws, produces no events`() {
        val events = client.parseServerMessage("""{"somethingElseEntirely":true}""")
        assertTrue(events.isEmpty())
    }
}
