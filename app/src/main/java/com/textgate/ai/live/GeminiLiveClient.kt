package com.textgate.ai.live

import android.util.Base64
import com.textgate.ai.network.NetworkAllowlist
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around a single Gemini Live API WebSocket session, scoped
 * EXCLUSIVELY to `gemini-3.5-live-translate-preview`'s native audio-to-audio
 * translation mode — never `gemini-3.5-flash-lite` or
 * `gemini-3.1-flash-lite`. See [com.textgate.ai.network.
 * TranslationOrchestrator]'s class doc for why the text-model fallback
 * logic must never touch this client, and this applies symmetrically: this
 * client never falls back to a text model on any error. On a Live-specific
 * limit or failure it only ever reports [ServerEvent.Error] upward for
 * [LiveTranslationService] (and, for the foreground-only Rozmowa screen,
 * its own controller) to surface as a clear message — exactly as required.
 *
 * IMPLEMENTATION NOTE — Gemini Live API wire format: this project's sandbox
 * has no network access to a live Gemini endpoint and no Android
 * compiler/emulator, so the exact JSON message shapes below (`setup` /
 * `realtimeInput` / `serverContent`) are built from the field names the app
 * owner specified directly (`inputAudioTranscription`,
 * `outputAudioTranscription`, `translationConfig`, `targetLanguageCode`)
 * plus this project's best understanding of the general BidiGenerateContent
 * WebSocket protocol shape. BEFORE SHIPPING: verify every message shape
 * below against Google's current Gemini Live API reference (a preview API,
 * more likely than a stable one to have changed field names since) using a
 * real device and a real API key, and adjust ONLY the private
 * buildX/parseX functions in this file if anything differs — nothing
 * outside this file needs to know the wire format, by design.
 *
 * Uses OkHttp for the WebSocket connection itself — see build.gradle.kts
 * for why this is the one deliberate, documented exception to this app's
 * zero-third-party-dependency policy.
 */
class GeminiLiveClient {

    sealed class ServerEvent {
        /** The server accepted [connect]'s setup message; audio may now be
         * sent via [sendAudioChunk]. */
        data object SetupComplete : ServerEvent()

        /** A transcript fragment of the ORIGINAL (source) audio being
         * translated — for on-screen "live transcript of original speech". */
        data class InputTranscript(val text: String) : ServerEvent()

        /** A transcript fragment of the TRANSLATED audio — for on-screen
         * "live transcript of the translation". */
        data class OutputTranscript(val text: String) : ServerEvent()

        /** One chunk of translated, playable PCM16 audio (24kHz mono, per
         * the Live API's documented output format) to hand to an
         * `AudioTrack`. */
        data class AudioChunk(val pcm16: ByteArray) : ServerEvent()

        /** The model finished one translation turn. */
        data object TurnComplete : ServerEvent()

        /** A Live-specific error or limit was reported by the server —
         * NEVER causes a fallback to a text model; the caller shows this
         * message and, if appropriate, offers to retry the Live session. */
        data class Error(val message: String) : ServerEvent()

        /** The socket closed, cleanly or not — [code]/[reason] follow the
         * WebSocket close-frame convention (1000 = normal). */
        data class Closed(val code: Int, val reason: String) : ServerEvent()
    }

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null

    /**
     * Opens a new Live session translating into [targetLanguageCode] (a
     * BCP-47 tag, e.g. "pl", "en-US") and begins delivering [ServerEvent]s
     * to [onEvent] — called on an OkHttp-managed background thread; callers
     * must post to their own main thread before touching any UI or
     * main-thread-only Android API. No thinking/reasoning configuration is
     * ever sent for this model, matching the spec's explicit requirement
     * that Live Translate never carries a `thinkingConfig` the way
     * `gemini-3.5-flash-lite`'s TEXT requests do (see
     * [com.textgate.ai.network.GeminiClient]).
     */
    fun connect(
        apiKey: String,
        model: String,
        targetLanguageCode: String,
        onEvent: (ServerEvent) -> Unit
    ) {
        val httpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        client = httpClient

        // Header form (not a query parameter) would be preferable for the
        // same reason GeminiClient uses a header for the text API — but the
        // Live API's WebSocket handshake is documented as accepting the key
        // via the `key` query parameter, since a WebSocket upgrade request
        // cannot carry a custom header through every proxy/client the same
        // way a plain HTTPS POST can. This is the one exception in this
        // app's whole network layer to "never put a secret in a URL" — a
        // deliberate, narrow one, required by the Live API's own transport.
        val url = "wss://${NetworkAllowlist.GEMINI_HOST}/ws/google.ai.generativelanguage.v1beta." +
            "GenerativeService.BidiGenerateContent?key=$apiKey"

        val request = Request.Builder().url(url).build()

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildSetupMessage(model, targetLanguageCode).toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseServerMessage(text).forEach(onEvent)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    parseServerMessage(bytes.utf8()).forEach(onEvent)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onEvent(ServerEvent.Error(t.message ?: "connection failed"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onEvent(ServerEvent.Closed(code, reason))
                }
            }
        )
    }

    /** Streams one chunk of 16kHz, 16-bit mono PCM microphone audio (the
     * Live API's documented input format) to the active session. A no-op
     * if there is no live socket — [LiveTranslationService] only starts
     * capturing audio after [ServerEvent.SetupComplete], so this should
     * never actually race an unopened connection in practice. */
    fun sendAudioChunk(pcm16: ByteArray) {
        val socket = webSocket ?: return
        socket.send(buildRealtimeAudioMessage(pcm16).toString())
    }

    /** Cleanly closes the session — no further events are delivered after
     * this returns. Safe to call multiple times, or when never connected. */
    fun close() {
        webSocket?.close(1000, "client stop")
        webSocket = null
        try {
            client?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {
            // Best-effort thread-pool cleanup only.
        }
        client = null
    }

    private fun buildSetupMessage(model: String, targetLanguageCode: String): JSONObject {
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("translationConfig", JSONObject().put("targetLanguageCode", targetLanguageCode))
        return JSONObject().put("setup", setup)
    }

    private fun buildRealtimeAudioMessage(pcm16: ByteArray): JSONObject {
        val chunk = JSONObject()
            .put("mimeType", "audio/pcm;rate=16000")
            .put("data", Base64.encodeToString(pcm16, Base64.NO_WRAP))
        val realtimeInput = JSONObject().put("mediaChunks", JSONArray().put(chunk))
        return JSONObject().put("realtimeInput", realtimeInput)
    }

    /** `internal` so this pure parsing logic is directly unit-testable
     * against hand-written server message bodies, the same reasoning as
     * [com.textgate.ai.network.GeminiClient.parseResponse]. Never throws —
     * an unrecognized or malformed message is dropped, not fatal to the
     * session. */
    internal fun parseServerMessage(raw: String): List<ServerEvent> {
        val events = mutableListOf<ServerEvent>()
        try {
            val json = JSONObject(raw)

            if (json.has("setupComplete")) {
                events += ServerEvent.SetupComplete
            }

            json.optJSONObject("serverContent")?.let { serverContent ->
                val inputText = serverContent.optJSONObject("inputTranscription")?.optString("text").orEmpty()
                if (inputText.isNotEmpty()) events += ServerEvent.InputTranscript(inputText)

                val outputText = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty()
                if (outputText.isNotEmpty()) events += ServerEvent.OutputTranscript(outputText)

                serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val data = part.optJSONObject("inlineData")?.optString("data").orEmpty()
                        if (data.isNotEmpty()) {
                            events += ServerEvent.AudioChunk(Base64.decode(data, Base64.NO_WRAP))
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    events += ServerEvent.TurnComplete
                }
            }

            json.optJSONObject("error")?.let { error ->
                events += ServerEvent.Error(error.optString("message", "unknown error"))
            }
        } catch (_: Exception) {
            // Malformed/unrecognized message: ignore rather than tear down
            // the whole session over one bad frame.
        }
        return events
    }
}
