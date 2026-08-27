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
 * IMPLEMENTATION NOTE - Gemini Live API wire format: this project's sandbox
 * has no network access to a live Gemini endpoint and no Android
 * compiler/emulator, so this can never be end-to-end verified here. It HAS
 * been checked, though — first against Google's official "live-translate"
 * doc page and a real-world Gemini API forum field report, and then, most
 * authoritatively, against the actual request-serialization source code of
 * Google's own official `google-genai` Python SDK (`_live_converters.py`,
 * `_LiveConnectConfig_to_mldev`, package version 2.20.0) — installed and
 * read directly in this sandbox with `pip install google-genai`, since that
 * source IS the wire format, not a description of it, and settles any
 * disagreement between the doc page and the forum report below. Corrected
 * three real bugs so far:
 *
 * - setup.generationConfig.responseModalities: ["AUDIO"] was MISSING
 *   entirely before the first fix — every official example includes it,
 *   and its absence is a confirmed cause of a Live session that opens the
 *   socket, sends setup, and then never receives setupComplete — i.e.
 *   "stuck on Łączenie forever" with no error surfaced, since the server
 *   has no way to tell the client what went wrong about a field that was
 *   never sent at all. Added; confirmed correct (nested inside
 *   generationConfig) by the SDK source (`setv(..., ['setup',
 *   'generationConfig', 'responseModalities'], ...)`).
 * - setup.generationConfig.translationConfig was WRONGLY placed at the TOP
 *   LEVEL of setup (a sibling of generationConfig) before the second fix.
 *   The SDK source is unambiguous: `setv(..., ['setup', 'generationConfig',
 *   'translationConfig'], ...)` — nested inside generationConfig, alongside
 *   responseModalities. This is very likely the real, primary cause of the
 *   "stuck connecting" reports: translationConfig is what actually tells
 *   the server this is a *translation* session at all, and it was never
 *   where the server would look for it. Fixed.
 * - inputAudioTranscription / outputAudioTranscription are correctly kept
 *   at the TOP LEVEL of setup (siblings of generationConfig), NOT nested
 *   inside generationConfig — confirmed by the same SDK source
 *   (`setv(..., ['setup', 'inputAudioTranscription'], ...)`, no
 *   generationConfig in that path), matching a forum field report from
 *   another developer that documented the opposite placement (which
 *   Google's own doc-page example shows) being rejected outright
 *   (WebSocket close 1007). This file already had it right; no change.
 * - realtimeInput.mediaChunks: [...] (an array) was replaced with
 *   realtimeInput.audio (a single {data, mimeType} object) in an earlier
 *   fix. The SDK source confirms BOTH shapes are real and accepted
 *   (`_LiveClientRealtimeInput_to_mldev` handles `media_chunks` ->
 *   `mediaChunks` and `audio` -> `audio` as two independent optional
 *   fields) — `audio` is simply the current, simpler, single-chunk form
 *   this class already moved to. No further change needed.
 *
 * - v2.x follow-up (routing/latency/error-handling request): added
 *   generationConfig.translationConfig.echoTargetLanguage=false (stops
 *   Gemini parroting speech already in the target language — confirmed
 *   real field, previously left at its undocumented server default),
 *   setup.realtimeInputConfig.automaticActivityDetection.silenceDurationMs
 *   (confirmed real, top-level sibling of generationConfig — tunes how
 *   long Gemini's own server-side VAD waits for silence before it commits
 *   end-of-speech; no client-side VAD was added), and parsing of
 *   serverContent.{input,output}Transcription.languageCode (confirmed
 *   real — the one genuine "what language did Gemini detect" signal,
 *   since no sourceLanguageCode request field exists anywhere in
 *   TranslationConfig/LiveConnectConfig). All three confirmed the same
 *   way: reading google-genai's types.py/_live_converters.py directly,
 *   never guessed. See classifyLiveError's own doc for the accompanying,
 *   separately-sourced finding about how Live errors actually surface
 *   (WebSocket close code/reason, not an in-band error field).
 *
 * If a future server response still hangs at setup after this fix, check
 * first whether Google has since changed this preview model's schema again
 * (preview APIs are the most likely to move) — re-installing `google-genai`
 * and re-reading `_live_converters.py` is the fastest way to get ground
 * truth again, faster than trusting a doc page or forum post. Nothing
 * outside this file needs to know the wire format, by design — adjust only
 * the private buildX/parseX functions here if anything differs.
 *
 * Uses OkHttp for the WebSocket connection itself — see build.gradle.kts
 * for why this is the one deliberate, documented exception to this app's
 * zero-third-party-dependency policy.
 */
class GeminiLiveClient {

    companion object {
        /** Explicit rather than relied-upon default — see [connect]'s doc.
         * Bounds only the initial TCP+TLS connect, not the session's life
         * (see the read-timeout comment in [connect] for why that's kept
         * unbounded instead). */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** How long [connect] waits for [ServerEvent.SetupComplete] before
         * giving up on its own, regardless of what OkHttp/the OS/the
         * network are doing underneath. See [connect]'s doc. */
        private const val SETUP_TIMEOUT_MS = 20_000L

        /** `realtimeInputConfig.automaticActivityDetection.
         * silenceDurationMs` sent in [buildSetupMessage] — see that
         * function's doc comment for the full reasoning. Within the
         * 500-600ms band this was deliberately tuned to. */
        private const val VAD_SILENCE_DURATION_MS = 550

        /**
         * Classifies a Live-specific error/close-reason string into a
         * [LiveErrorCategory] the callers ([LiveTranslationService],
         * [com.textgate.ai.conversation.ConversationTabController], via the
         * shared `liveErrorMessageRes()` helper) use to decide whether to
         * reconnect with backoff or fail immediately with a specific
         * message.
         *
         * IMPLEMENTATION NOTE, ground-truthed the same way as this class's
         * wire format (see the class doc's IMPLEMENTATION NOTE): read
         * `google-genai`'s own `live.py`/`errors.py` source directly.
         * `LiveServerMessage` does NOT model an in-band JSON `error` field
         * at all — its real fields are `setupComplete`, `serverContent`,
         * `toolCall`, `toolCallCancellation`, `usageMetadata`, `goAway`,
         * `sessionResumptionUpdate`, `voiceActivityDetectionSignal`,
         * `voiceActivity`; the shared Pydantic base model the whole SDK
         * uses is even configured `extra='forbid'`, meaning an unmodeled
         * top-level key would be REJECTED, not silently accepted. Instead,
         * the SDK's own `_receive()` catches `ConnectionClosed` and calls
         * `errors.APIError.raise_error(close_code, close_reason, None)` —
         * i.e. real Live API errors surface via the WEBSOCKET CLOSE
         * CODE/REASON, exactly what this class already reports as
         * [ServerEvent.Closed]. There is no documented, stable numeric
         * code-to-category mapping for Live's close codes the way the
         * plain REST API has gRPC-style status codes (see
         * [com.textgate.ai.network.GeminiClient.classifyQuotaText] for
         * that REST-side equivalent) — inventing one would violate this
         * project's own "don't guess an unverified API detail" rule — so
         * this classifies by matching known text fragments in the
         * close/error [text] instead, the same proven text-matching
         * approach already used for the text model's quota detection,
         * applied here to the one signal Live actually gives us. The
         * `error.optJSONObject("error")` parse kept in [parseServerMessage]
         * is a harmless defensive fallback only, in case a future API
         * revision or intermediary ever does send one; its message is
         * classified through this same function.
         */
        internal fun classifyLiveError(text: String): LiveErrorCategory {
            val t = text.lowercase()
            return when {
                t.contains("resource_exhausted") || t.contains("quota") ||
                    t.contains("rate limit") || t.contains("rate_limit") ||
                    t.contains(" 429") ->
                    LiveErrorCategory.QUOTA
                t.contains("unauthenticated") || t.contains("api key") ||
                    t.contains("api_key") || t.contains("permission_denied") ||
                    t.contains(" 401") || t.contains(" 403") ->
                    LiveErrorCategory.AUTH
                t.contains("invalid_argument") || t.contains("invalid argument") ||
                    t.contains("unsupported") || t.contains("failed_precondition") ->
                    LiveErrorCategory.CONFIG
                else -> LiveErrorCategory.UNKNOWN
            }
        }
    }

    /** See [classifyLiveError]'s doc for how/why this is derived from a
     * close code's reason text rather than a structured field. NETWORK and
     * UNKNOWN are both treated identically by every caller (reconnect with
     * the existing backoff) — kept as two separate values only so a
     * genuinely unrecognized message is never silently folded into
     * "definitely network", which it isn't confirmed to be. */
    enum class LiveErrorCategory { NETWORK, QUOTA, AUTH, CONFIG, UNKNOWN }

    sealed class ServerEvent {
        /** The server accepted [connect]'s setup message; audio may now be
         * sent via [sendAudioChunk]. */
        data object SetupComplete : ServerEvent()

        /** A transcript fragment of the ORIGINAL (source) audio being
         * translated — for on-screen "live transcript of original speech".
         * [languageCode] is the server's own BCP-47 detected-language tag
         * for this fragment (`serverContent.inputTranscription.
         * languageCode` — confirmed real via `Transcription.language_code`
         * in the `google-genai` SDK), null if the server didn't include one
         * for this particular fragment. This is the ONLY real signal of
         * what ambient language Gemini detected — there is no
         * `sourceLanguageCode` request field to force it (confirmed: no
         * such field exists anywhere in `TranslationConfig`/
         * `LiveConnectConfig`), so the UI shows this as "Wykryto: X"
         * rather than offering a picker that would silently do nothing. */
        data class InputTranscript(val text: String, val languageCode: String? = null) : ServerEvent()

        /** A transcript fragment of the TRANSLATED audio — for on-screen
         * "live transcript of the translation". [languageCode] mirrors
         * [InputTranscript.languageCode] but for the output side. */
        data class OutputTranscript(val text: String, val languageCode: String? = null) : ServerEvent()

        /** One chunk of translated, playable PCM16 audio (24kHz mono, per
         * the Live API's documented output format) to hand to an
         * `AudioTrack`. */
        data class AudioChunk(val pcm16: ByteArray) : ServerEvent()

        /** The model finished one translation turn. */
        data object TurnComplete : ServerEvent()

        /** A Live-specific error or limit was reported by the server —
         * NEVER causes a fallback to a text model; the caller shows a
         * message picked from [category] and, if appropriate, offers to
         * retry the Live session. */
        data class Error(val message: String, val category: LiveErrorCategory) : ServerEvent()

        /** The socket closed, cleanly or not — [code]/[reason] follow the
         * WebSocket close-frame convention (1000 = normal). [category] is
         * [classifyLiveError] applied to [reason] — see that function's doc
         * for why this, not [code], is what actually carries the signal. */
        data class Closed(val code: Int, val reason: String, val category: LiveErrorCategory) : ServerEvent()
    }

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null

    /** Guards against a handshake that neither completes nor ever calls
     * back — see [connect]'s doc for why this exists. `Handler` (main
     * looper) rather than a raw `Timer`/executor purely because it's
     * already a transitive Android dependency and gives free
     * cancellation via [android.os.Handler.removeCallbacks]; the actual
     * callback this posts still only ever touches [onEvent] and the
     * socket, same as every other event path in this class. */
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var setupTimeoutRunnable: Runnable? = null

    private fun cancelSetupTimeout() {
        setupTimeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
        setupTimeoutRunnable = null
    }

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
     *
     * Two defenses against a session that hangs instead of failing loud
     * (added after a real report of the UI being stuck on "Łączenie"
     * indefinitely, with neither [ServerEvent.SetupComplete] nor
     * [ServerEvent.Error] ever arriving — which OkHttp's own defaults
     * should not allow, but evidently something on some networks does):
     * an explicit (bounded) connect timeout on the [OkHttpClient] itself
     * for the initial TCP+TLS handshake, AND an app-level watchdog
     * ([setupTimeoutRunnable]) that force-closes the socket and reports
     * [ServerEvent.Error] if [ServerEvent.SetupComplete] hasn't arrived
     * within [SETUP_TIMEOUT_MS] of this call — independent of whatever
     * OkHttp/the OS/the network in between is actually doing, so the UI
     * can never again get stuck showing "connecting" with no way out.
     */
    fun connect(
        apiKey: String,
        model: String,
        targetLanguageCode: String,
        onEvent: (ServerEvent) -> Unit
    ) {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // NOT a short readTimeout: once the session is open, a real
            // Live conversation has natural silences (someone pausing
            // between sentences) far longer than any short read timeout,
            // and OkHttp's read timeout applies to the socket for the
            // whole connection's life, not just the initial handshake — a
            // short value here would silently kill a perfectly healthy
            // session the first time nobody spoke for a few seconds.
            // Liveness is [pingInterval]'s job, not this. 0 = no timeout.
            .readTimeout(0, TimeUnit.MILLISECONDS)
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

        val timeoutRunnable = Runnable {
            onEvent(
                ServerEvent.Error(
                    "Brak odpowiedzi serwera przez ${SETUP_TIMEOUT_MS / 1000}s podczas łączenia " +
                        "(setup timeout) — sprawdź połączenie sieciowe.",
                    LiveErrorCategory.NETWORK
                )
            )
            webSocket?.close(1000, "setup timeout")
            webSocket = null
        }
        setupTimeoutRunnable = timeoutRunnable
        timeoutHandler.postDelayed(timeoutRunnable, SETUP_TIMEOUT_MS)

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildSetupMessage(model, targetLanguageCode).toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val events = parseServerMessage(text)
                    if (events.any { it is ServerEvent.SetupComplete }) cancelSetupTimeout()
                    events.forEach(onEvent)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val events = parseServerMessage(bytes.utf8())
                    if (events.any { it is ServerEvent.SetupComplete }) cancelSetupTimeout()
                    events.forEach(onEvent)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    cancelSetupTimeout()
                    // t.message alone is frequently unhelpful/null for
                    // connection-level failures (e.g. plain "Software
                    // caused connection abort"); the exception's class name
                    // plus, when the failure happened during the HTTP
                    // Upgrade itself, the response's status code/message
                    // are what actually distinguish "DNS failure" from
                    // "TLS failure" from "server rejected the upgrade" —
                    // exactly the detail needed to diagnose a hang without
                    // needing a full device log.
                    val exceptionDetail = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
                    val responseDetail = response?.let { " (HTTP ${it.code} ${it.message})" }.orEmpty()
                    // classifyLiveError on the FULL combined message (not
                    // just responseDetail) so an HTTP status embedded there
                    // (e.g. "(HTTP 401 Unauthorized)" for a bad API key
                    // rejected at the WebSocket upgrade handshake itself)
                    // is still picked up even though it's Kotlin's own
                    // exception/response text, not Gemini's own error
                    // wording.
                    val fullMessage = "$exceptionDetail$responseDetail"
                    onEvent(ServerEvent.Error(fullMessage, classifyLiveError(fullMessage)))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    cancelSetupTimeout()
                    onEvent(ServerEvent.Closed(code, reason, classifyLiveError(reason)))
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
        cancelSetupTimeout()
        webSocket?.close(1000, "client stop")
        webSocket = null
        try {
            client?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {
            // Best-effort thread-pool cleanup only.
        }
        client = null
    }

    /** `internal` (not `private`) solely so [GeminiLiveClientTest] can
     * assert on the exact request shape directly — the same reasoning as
     * [parseServerMessage], applied to construction instead of parsing,
     * specifically because this class has already shipped one real,
     * user-hit bug in this exact shape (see the IMPLEMENTATION NOTE). */
    internal fun buildSetupMessage(model: String, targetLanguageCode: String): JSONObject {
        // responseModalities tells the server this session wants audio back
        // (not text) — see this class's IMPLEMENTATION NOTE: omitting it
        // entirely was the confirmed cause of a setup that is sent but never
        // answered with setupComplete.
        //
        // translationConfig is nested INSIDE generationConfig (a sibling of
        // responseModalities), NOT at the top level of setup — confirmed
        // straight from Google's own official `google-genai` Python SDK
        // source (_live_converters.py's _LiveConnectConfig_to_mldev:
        // `setv(parent_object, ['setup', 'generationConfig',
        // 'translationConfig'], ...)`), which is the ground truth for what
        // the real API actually accepts, more reliable than any doc page or
        // forum post. This class had it at the top level of setup before —
        // a second real bug in the same setup message as the
        // responseModalities one, and the more likely actual cause of a
        // session that never leaves "connecting": the server has nothing to
        // configure a *translation* session with if translationConfig isn't
        // where it's expected.
        // echoTargetLanguage: confirmed real (TranslationConfig.
        // echo_target_language in google-genai's types.py, wire path
        // generationConfig.translationConfig.echoTargetLanguage) —
        // "Optional. If true, the model will generate audio when the
        // target language is spoken, essentially it will parrot the
        // input. If false, we will not produce audio for the target
        // language." The server default is undocumented (None) and
        // evidently parrots back speech that's already in the target
        // language on at least some sessions — a real reported symptom
        // ("Gemini powtarza zdanie, które już jest w języku docelowym").
        // Explicit false here rather than relying on that default — this
        // one function serves both Na żywo and Rozmowa (see class doc), so
        // one change covers both.
        val translationConfig = JSONObject()
            .put("targetLanguageCode", targetLanguageCode)
            .put("echoTargetLanguage", false)
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("translationConfig", translationConfig)
        // realtimeInputConfig.automaticActivityDetection.silenceDurationMs:
        // confirmed real (AutomaticActivityDetection.silence_duration_ms in
        // google-genai's types.py — "The required duration of detected
        // non-speech ... before end-of-speech is committed. The larger
        // this value, the longer speech gaps can be ... but this will
        // increase the model's latency.") realtimeInputConfig is a
        // TOP-LEVEL sibling of generationConfig inside setup, NOT nested
        // inside it — confirmed via _live_converters.py: `setv(...,
        // ['setup', 'realtimeInputConfig'], ...)` is a separate call from
        // the generationConfig one. VAD_SILENCE_DURATION_MS (550) sits in
        // the 500-600ms band this was deliberately tuned to: short enough
        // to start translating soon after a sentence genuinely ends,
        // without being so low it risks cutting off a natural mid-sentence
        // pause the way an aggressive 100-200ms value would. No local
        // client-side VAD/silence-trimming is added anywhere in this
        // class — this only configures Gemini's OWN server-side detector,
        // which already exists and runs regardless.
        val realtimeInputConfig = JSONObject()
            .put(
                "automaticActivityDetection",
                JSONObject().put("silenceDurationMs", VAD_SILENCE_DURATION_MS)
            )
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", generationConfig)
            .put("realtimeInputConfig", realtimeInputConfig)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup)
    }

    /** `internal` for the same reason as [buildSetupMessage]. */
    internal fun buildRealtimeAudioMessage(pcm16: ByteArray): JSONObject {
        // realtimeInput.audio is a single {data, mimeType} object, not an
        // array — see this class's IMPLEMENTATION NOTE.
        val audio = JSONObject()
            .put("data", Base64.encodeToString(pcm16, Base64.NO_WRAP))
            .put("mimeType", "audio/pcm;rate=16000")
        val realtimeInput = JSONObject().put("audio", audio)
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
                serverContent.optJSONObject("inputTranscription")?.let { input ->
                    val inputText = input.optString("text").orEmpty()
                    if (inputText.isNotEmpty()) {
                        val rawLanguageCode = input.optString("languageCode")
                        val languageCode: String? = if (rawLanguageCode.isEmpty()) null else rawLanguageCode
                        events += ServerEvent.InputTranscript(inputText, languageCode)
                    }
                }

                serverContent.optJSONObject("outputTranscription")?.let { output ->
                    val outputText = output.optString("text").orEmpty()
                    if (outputText.isNotEmpty()) {
                        val rawLanguageCode = output.optString("languageCode")
                        val languageCode: String? = if (rawLanguageCode.isEmpty()) null else rawLanguageCode
                        events += ServerEvent.OutputTranscript(outputText, languageCode)
                    }
                }

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
                // Defensive fallback only — see classifyLiveError's doc for
                // why real Live errors are not actually expected to arrive
                // this way.
                val message = error.optString("message", "unknown error")
                events += ServerEvent.Error(message, classifyLiveError(message))
            }
        } catch (_: Exception) {
            // Malformed/unrecognized message: ignore rather than tear down
            // the whole session over one bad frame.
        }
        return events
    }
}
