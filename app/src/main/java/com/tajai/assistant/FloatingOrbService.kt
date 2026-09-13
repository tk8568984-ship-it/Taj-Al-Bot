package com.tajai.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * The always-on agent. Once started, it:
 *  - shows a small draggable orb over every app
 *  - continuously listens for speech (restarts itself after each utterance)
 *  - sends what you said (+ a screenshot, if a MediaProjection session is active) to Gemini
 *  - speaks the reply back and executes any action tags TAJ decided on
 *
 * Start/stop this service = the ONLY two times any Android permission dialog should appear
 * (assuming the 4 one-time grants from MainActivity are already done).
 */
class FloatingOrbService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "FloatingOrbService"
        private const val CHANNEL_ID = "taj_ai_channel"
        private const val NOTIF_ID = 1001

        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        var instance: FloatingOrbService? = null

        fun start(context: Context, projectionResultCode: Int = 0, projectionData: Intent? = null) {
            val intent = Intent(context, FloatingOrbService::class.java)
            intent.putExtra(EXTRA_PROJECTION_RESULT_CODE, projectionResultCode)
            intent.putExtra(EXTRA_PROJECTION_DATA, projectionData)
            ContextCompatStartForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOrbService::class.java))
        }

        private fun ContextCompatStartForegroundService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var orbView: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var sleeping = false
    private var listening = false
    private var muted = false

    fun setMuted(value: Boolean) {
        muted = value
    }

    // Screen capture (optional — only active if the user granted it once at start)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this) { status ->
            try {
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("bn", "BD")
                    selectFemaleVoice()
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            try { AgentState.update(AgentStatus.SPEAKING) } catch (e: Throwable) {
                                Log.e(TAG, "onStart listener failed", e)
                            }
                        }
                        override fun onDone(utteranceId: String?) {
                            try { if (!sleeping) AgentState.update(AgentStatus.ONLINE_IDLE) } catch (e: Throwable) {
                                Log.e(TAG, "onDone listener failed", e)
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            try { if (!sleeping) AgentState.update(AgentStatus.ONLINE_IDLE) } catch (e: Throwable) {
                                Log.e(TAG, "onError listener failed", e)
                            }
                        }
                    })
                }
            } catch (e: Throwable) {
                Log.e(TAG, "TTS init callback failed", e)
            }
        }
        createNotificationChannel()
    }

    /**
     * Picks a female Bengali (bn-BD, falling back to any bn-*) voice from whatever
     * the phone's TTS engine has installed. Not every phone ships a Bangladeshi
     * female voice out of the box — if none is found, this falls back to the
     * default voice for the language (which may be male on some devices).
     */
    private fun selectFemaleVoice() {
        try {
            val engine = tts ?: return
            val voices = engine.voices ?: return
            val candidates = voices.filter { it.locale.language == "bn" }
            val femaleBD = candidates.firstOrNull {
                it.locale.country.equals("BD", ignoreCase = true) &&
                    it.name.contains("female", ignoreCase = true)
            }
            val femaleAny = candidates.firstOrNull { it.name.contains("female", ignoreCase = true) }
            val bdAny = candidates.firstOrNull { it.locale.country.equals("BD", ignoreCase = true) }
            val chosen = femaleBD ?: femaleAny ?: bdAny ?: candidates.firstOrNull()
            if (chosen != null) {
                engine.voice = chosen
                Log.i(TAG, "TTS voice selected: ${chosen.name} (${chosen.locale})")
            } else {
                Log.w(TAG, "No Bengali voice found on this device's TTS engine")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "selectFemaleVoice failed — continuing with default voice", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        showOrb()

        val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
        if (resultCode != 0 && data != null) {
            setupScreenCapture(resultCode, data)
        }

        AgentState.update(AgentStatus.ONLINE_IDLE)
        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopListening()
        tts?.shutdown()
        removeOrb()
        virtualDisplay?.release()
        mediaProjection?.stop()
        AgentState.update(AgentStatus.OFFLINE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- Orb overlay ----------------

    private fun showOrb() {
        if (orbView != null) return
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.floating_orb, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 300

        // Make it draggable, and a single tap toggles manual listening (useful if
        // continuous background listening ever gets stopped by the OS to save battery).
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    moved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (sleeping) exitSleepMode() else startListening()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
        orbView = view
    }

    private fun removeOrb() {
        orbView?.let { windowManager.removeView(it) }
        orbView = null
    }

    // ---------------- Voice loop ----------------

    private fun startListening() {
        if (sleeping || listening) return
        try {
            listening = true
            AgentState.update(AgentStatus.LISTENING)
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(this)
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // Give the user more room to pause mid-sentence before the recognizer
            // decides speech is "done" and cuts them off.
            intent.putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 2500)
            intent.putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 2500)
            intent.putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 15000)
            speechRecognizer?.startListening(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "startListening failed", e)
            listening = false
        }
    }

    private fun stopListening() {
        listening = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Throwable) {
            Log.e(TAG, "stopListening failed", e)
        }
        speechRecognizer = null
    }

    fun enterSleepMode() {
        sleeping = true
        stopListening()
        AgentState.update(AgentStatus.SLEEPING)
        speak("ঠিক আছে বস, আমি ঘুমিয়ে পড়ছি। orb-এ ট্যাপ করে আবার জাগাতে পারো।")
    }

    fun exitSleepMode() {
        sleeping = false
        AgentState.update(AgentStatus.ONLINE_IDLE)
        speak("জি বস, আমি রেডি, বলো কী করতে হবে।")
        startListening()
    }

    // -- RecognitionListener callbacks --
    override fun onResults(results: Bundle?) {
        listening = false
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        if (!text.isNullOrBlank() && containsWakeWord(text)) {
            handleUserUtterance(stripWakeWord(text))
        } else {
            // Not addressed to TAJ (or empty) — stay quietly listening, don't reply.
            startListening()
        }
    }

    /** TAJ only acts when called by name — casual background talk is ignored. */
    private fun containsWakeWord(text: String): Boolean {
        val wakeWords = listOf("taj", "তাজ", "ताज")
        val lower = text.lowercase()
        return wakeWords.any { lower.contains(it) }
    }

    private fun stripWakeWord(text: String): String {
        var cleaned = text
        listOf("taj", "তাজ", "ताज").forEach { w ->
            cleaned = Regex("(?i)$w").replace(cleaned, "")
        }
        return cleaned.trim().ifBlank { text }
    }

    override fun onError(error: Int) {
        listening = false
        // Restart the loop so the assistant keeps "always listening" without user action.
        Handler(Looper.getMainLooper()).postDelayed({ if (!sleeping) startListening() }, 800)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---------------- Talking to Gemini ----------------

    private fun handleUserUtterance(text: String) {
        AgentState.update(AgentStatus.ONLINE_IDLE) // "thinking" — between listening and speaking
        scope.launch {
            try {
                // Give Gemini real screen context for trading and explicit screen-reading requests.
                // Normal casual chat does not upload the screen.
                val screenshot = if (needsScreenVision(text)) withTimeoutSafeScreenshot() else null
                val reply = withContextIO {
                    GeminiClient.ask(
                        apiKey = BuildConfig.GEMINI_API_KEY,
                        userText = text,
                        screenshotBase64 = screenshot,
                        history = conversationHistory.takeLast(10)
                    )
                }
                conversationHistory.add("user" to text)
                conversationHistory.add("assistant" to reply)

                ActionParser.executeActions(reply)
                speak(ActionParser.stripTagsForSpeech(reply))
            } catch (e: Throwable) {
                Log.e(TAG, "handleUserUtterance failed", e)
                speak("দুঃখিত বস, একটা সমস্যা হয়েছে, আবার বলো।")
            }
            if (!sleeping) startListening()
        }
    }

    private suspend fun withContextIO(block: () -> String): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private fun speak(text: String) {
        if (text.isBlank() || muted) {
            if (!sleeping) AgentState.update(AgentStatus.ONLINE_IDLE)
            return
        }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "taj_reply")
        } catch (e: Throwable) {
            Log.e(TAG, "speak() failed", e)
            if (!sleeping) AgentState.update(AgentStatus.ONLINE_IDLE)
        }
    }

    // ---------------- Screen capture (for trading/chart vision) ----------------

    private fun setupScreenCapture(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "TajScreenCapture",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        Log.i(TAG, "Screen capture session active — no further prompt needed while running")
    }

    /**
     * Grabs the latest available frame (if screen capture was granted), downsized and
     * JPEG-compressed to keep the upload small — important on slow mobile data, since a
     * full-resolution PNG can take a very long time to upload and cause timeouts.
     */
    private fun withTimeoutSafeScreenshot(): String? {
        val reader = imageReader ?: return null
        return try {
            val image = reader.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val fullBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Downscale to a max width of 480px — plenty for reading a trading chart,
            // but a fraction of the data of a full-resolution phone screenshot.
            val maxWidth = 480
            val scale = maxWidth.toFloat() / fullBitmap.width
            val targetHeight = (fullBitmap.height * scale).toInt().coerceAtLeast(1)
            val smallBitmap = Bitmap.createScaledBitmap(fullBitmap, maxWidth, targetHeight, true)
            fullBitmap.recycle()

            val stream = ByteArrayOutputStream()
            smallBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            smallBitmap.recycle()
            GeminiClient.encodeBitmapToBase64(stream.toByteArray())
        } catch (e: Throwable) {
            Log.w(TAG, "No screenshot available: ${e.message}")
            null
        }
    }

    /**
     * Only worth attaching a screenshot (extra upload time, real cost on slow data) when
     * the user is actually asking about the market/chart. Casual chat doesn't need it.
     */
    private fun needsScreenVision(text: String): Boolean {
        val keywords = listOf(
            "market", "trade", "trading", "candle", "chart", "signal", "call", "put",
            "screen", "screenshot", "what do you see", "what is on screen",
            "মার্কেট", "চার্ট", "সিগন্যাল", "ট্রেড", "ক্যান্ডেল", "স্ক্রিন", "স্ক্রিন দেখ",
            "এখানে কি", "কি দেখছ", "কি দেখা যাচ্ছে",
            "बाज़ार", "मार्केट", "चार्ट", "सिग्नल", "ट्रेड", "कैंडल", "स्क्रीन", "क्या दिख रहा"
        )
        val lower = text.lowercase()
        return keywords.any { lower.contains(it) }
    }

    // ---------------- Notification ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "TAJ AI", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.orb_notification_title))
            .setContentText(getString(R.string.orb_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
}
