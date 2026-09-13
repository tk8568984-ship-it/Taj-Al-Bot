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

class FloatingOrbService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "FloatingOrbService"
        private const val CHANNEL_ID = "taj_ai_channel"
        private const val NOTIF_ID = 1001

        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"

        private const val COMMAND_DELAY_MS = 2500L
        private const val RESTART_DELAY_MS = 1200L

        var instance: FloatingOrbService? = null

        fun start(
            context: Context,
            projectionResultCode: Int = 0,
            projectionData: Intent? = null
        ) {
            val intent = Intent(context, FloatingOrbService::class.java)
            intent.putExtra(EXTRA_PROJECTION_RESULT_CODE, projectionResultCode)
            intent.putExtra(EXTRA_PROJECTION_DATA, projectionData)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOrbService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var orbView: View? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var sleeping = false
    private var listening = false
    private var processing = false
    private var speaking = false
    private var muted = false
    private var serviceDestroyed = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private val scope = CoroutineScope(Dispatchers.Main)

    fun setMuted(value: Boolean) {
        muted = value
    }

    override fun onCreate() {
        super.onCreate()

        instance = this
        serviceDestroyed = false

        windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()

        tts = TextToSpeech(this) { status ->
            try {
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("bn", "BD")
                    selectFemaleVoice()

                    tts?.setOnUtteranceProgressListener(
                        object : android.speech.tts.UtteranceProgressListener() {

                            override fun onStart(utteranceId: String?) {
                                mainHandler.post {
                                    speaking = true
                                    AgentState.update(AgentStatus.SPEAKING)
                                }
                            }

                            override fun onDone(utteranceId: String?) {
                                mainHandler.post {
                                    speaking = false

                                    if (!sleeping && !serviceDestroyed) {
                                        AgentState.update(AgentStatus.ONLINE_IDLE)

                                        // Start a fresh listening session only AFTER
                                        // TTS has completely finished.
                                        scheduleListening(700L)
                                    }
                                }
                            }

                            override fun onError(utteranceId: String?) {
                                mainHandler.post {
                                    speaking = false

                                    if (!sleeping && !serviceDestroyed) {
                                        AgentState.update(AgentStatus.ONLINE_IDLE)
                                        scheduleListening(700L)
                                    }
                                }
                            }
                        }
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "TTS initialization failed", e)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        startForeground(
            NOTIF_ID,
            buildNotification()
        )

        showOrb()

        val resultCode =
            intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0) ?: 0

        val data =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(
                    EXTRA_PROJECTION_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
            }

        if (resultCode != 0 && data != null) {
            setupScreenCapture(resultCode, data)
        }

        AgentState.update(AgentStatus.ONLINE_IDLE)

        // Only start listening if nothing is currently happening.
        if (!sleeping && !processing && !speaking && !listening) {
            scheduleListening(500L)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        serviceDestroyed = true

        mainHandler.removeCallbacksAndMessages(null)

        stopListening()

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Throwable) {
            Log.e(TAG, "TTS shutdown failed", e)
        }

        removeOrb()

        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "Screen capture cleanup failed", e)
        }

        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        instance = null

        AgentState.update(AgentStatus.OFFLINE)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ============================================================
    // ORB
    // ============================================================

    private fun showOrb() {
        if (orbView != null) return

        val inflater =
            getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        val view =
            inflater.inflate(R.layout.floating_orb, null)

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
                    params.x =
                        initialX +
                                (event.rawX - initialTouchX).toInt()

                    params.y =
                        initialY +
                                (event.rawY - initialTouchY).toInt()

                    windowManager.updateViewLayout(
                        view,
                        params
                    )

                    moved = true
                    true
                }

                MotionEvent.ACTION_UP -> {

                    if (!moved) {
                        if (sleeping) {
                            exitSleepMode()
                        } else if (!listening && !processing && !speaking) {
                            startListening()
                        }
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
        try {
            orbView?.let {
                if (it.isAttachedToWindow) {
                    windowManager.removeView(it)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "removeOrb failed", e)
        }

        orbView = null
    }

    // ============================================================
    // VOICE ENGINE
    // ============================================================

    private fun createRecognizerIfNeeded() {

        if (speechRecognizer != null) return

        try {
            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(this)

        } catch (e: Throwable) {
            Log.e(TAG, "Recognizer creation failed", e)
            speechRecognizer = null
        }
    }

    private fun scheduleListening(delay: Long) {

        mainHandler.removeCallbacksAndMessages(null)

        if (sleeping ||
            processing ||
            speaking ||
            listening ||
            serviceDestroyed
        ) {
            return
        }

        mainHandler.postDelayed(
            {
                if (!sleeping &&
                    !processing &&
                    !speaking &&
                    !listening &&
                    !serviceDestroyed
                ) {
                    startListening()
                }
            },
            delay
        )
    }

    private fun startListening() {

        if (sleeping ||
            processing ||
            speaking ||
            listening ||
            serviceDestroyed
        ) {
            return
        }

        try {

            createRecognizerIfNeeded()

            val recognizer = speechRecognizer
                ?: return

            listening = true

            AgentState.update(
                AgentStatus.LISTENING
            )

            val intent =
                Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                )

            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "bn-BD"
            )

            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                "bn-BD"
            )

            /*
             * IMPORTANT:
             *
             * Do NOT use very small silence values here.
             * Small values cause Android to stop the microphone
             * when the user pauses for a moment while speaking.
             */

            intent.putExtra(
                "android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS",
                3500L
            )

            intent.putExtra(
                "android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS",
                3000L
            )

            intent.putExtra(
                "android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS",
                10000L
            )

            /*
             * We don't need partial results.
             * The command is processed only after the complete
             * utterance has arrived.
             */
            intent.putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
            )

            recognizer.startListening(intent)

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "startListening failed",
                e
            )

            listening = false

            if (!sleeping && !processing && !speaking) {
                scheduleListening(RESTART_DELAY_MS)
            }
        }
    }

    private fun stopListening() {

        listening = false

        try {
            speechRecognizer?.cancel()
        } catch (e: Throwable) {
            Log.e(TAG, "Recognizer cancel failed", e)
        }

        try {
            speechRecognizer?.destroy()
        } catch (e: Throwable) {
            Log.e(TAG, "Recognizer destroy failed", e)
        }

        speechRecognizer = null
    }

    // ============================================================
    // SLEEP MODE
    // ============================================================

    fun enterSleepMode() {

        sleeping = true

        mainHandler.removeCallbacksAndMessages(null)

        stopListening()

        try {
            tts?.stop()
        } catch (_: Throwable) {
        }

        speaking = false
        processing = false

        AgentState.update(
            AgentStatus.SLEEPING
        )

        speak(
            "ঠিক আছে বস, আমি ঘুমিয়ে পড়ছি। orb-এ ট্যাপ করে আবার জাগাতে পারো।"
        )
    }

    fun exitSleepMode() {

        sleeping = false

        processing = false
        speaking = false

        AgentState.update(
            AgentStatus.ONLINE_IDLE
        )

        speak(
            "জি বস, আমি রেডি, বলো কী করতে হবে।"
        )
    }

    // ============================================================
    // SPEECH RECOGNITION CALLBACKS
    // ============================================================

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "Microphone ready")
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "User started speaking")
    }

    override fun onRmsChanged(rmsdB: Float) {
        // Intentionally empty.
        // RMS changes must NOT restart or stop the microphone.
    }

    override fun onBufferReceived(buffer: ByteArray?) {
    }

    override fun onEndOfSpeech() {
        /*
         * Android has detected the end of the user's speech.
         *
         * DO NOT immediately start another microphone session.
         * onResults() will deliver the final recognized sentence.
         */
        Log.d(TAG, "Speech ended — waiting for final results")
    }

    override fun onPartialResults(
        partialResults: Bundle?
    ) {
        /*
         * Partial results intentionally ignored.
         *
         * This prevents the app from reacting before the user
         * has finished speaking.
         */
    }

    override fun onEvent(
        eventType: Int,
        params: Bundle?
    ) {
    }

    override fun onResults(results: Bundle?) {

        listening = false

        val text =
            results
                ?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )
                ?.firstOrNull()
                ?.trim()

        Log.d(
            TAG,
            "FINAL speech result: $text"
        )

        if (text.isNullOrBlank()) {

            if (!sleeping &&
                !processing &&
                !speaking
            ) {
                scheduleListening(RESTART_DELAY_MS)
            }

            return
        }

        /*
         * IMPORTANT:
         *
         * Do NOT restart the microphone here immediately.
         *
         * First process the complete sentence.
         */

        if (containsWakeWord(text)) {

            val command =
                stripWakeWord(text)

            if (command.isNotBlank()) {
                handleUserUtterance(command)
            } else {

                // User only said "TAJ".
                speak(
                    "জি বস, বলো কী করতে হবে।"
                )
            }

        } else {

            /*
             * Background conversation is ignored.
             * We restart only after this recognition session
             * has completely finished.
             */

            scheduleListening(RESTART_DELAY_MS)
        }
    }

    override fun onError(error: Int) {

        listening = false

        Log.w(
            TAG,
            "SpeechRecognizer error: $error"
        )

        /*
         * Never restart immediately.
         *
         * Immediate restart is what causes the repeated:
         *
         * OFF → ON → OFF → ON
         *
         * behavior on some Android phones.
         */

        if (!sleeping &&
            !processing &&
            !speaking &&
            !serviceDestroyed
        ) {
            scheduleListening(RESTART_DELAY_MS)
        }
    }

    // ============================================================
    // WAKE WORD
    // ============================================================

    private fun containsWakeWord(
        text: String
    ): Boolean {

        val wakeWords =
            listOf(
                "taj",
                "তাজ",
                "ताज"
            )

        val lower =
            text.lowercase(Locale.getDefault())

        return wakeWords.any {
            lower.contains(it)
        }
    }

    private fun stripWakeWord(
        text: String
    ): String {

        var cleaned = text

        listOf(
            "taj",
            "তাজ",
            "ताज"
        ).forEach { word ->

            cleaned =
                Regex(
                    Regex.escape(word),
                    RegexOption.IGNORE_CASE
                ).replace(
                    cleaned,
                    ""
                )
        }

        return cleaned.trim()
    }

    // ============================================================
    // GEMINI
    // ============================================================

    private fun handleUserUtterance(
        text: String
    ) {

        if (processing || sleeping) {
            return
        }

        /*
         * Mic is now completely finished.
         */
        listening = false
        processing = true

        AgentState.update(
            AgentStatus.ONLINE_IDLE
        )

        /*
         * Give the recognizer a moment to completely release
         * the microphone before doing network work.
         */
        mainHandler.postDelayed({

            scope.launch {

                try {

                    val screenshot =
                        if (needsScreenVision(text)) {
                            withTimeoutSafeScreenshot()
                        } else {
                            null
                        }

                    val reply =
                        withContextIO {

                            GeminiClient.ask(
                                apiKey =
                                    BuildConfig.GEMINI_API_KEY,

                                userText =
                                    text,

                                screenshotBase64 =
                                    screenshot,

                                history =
                                    conversationHistory
                                        .takeLast(10)
                            )
                        }

                    conversationHistory.add(
                        "user" to text
                    )

                    conversationHistory.add(
                        "assistant" to reply
                    )

                    ActionParser.executeActions(
                        reply
                    )

                    processing = false

                    speak(
                        ActionParser.stripTagsForSpeech(
                            reply
                        )
                    )

                } catch (e: Throwable) {

                    Log.e(
                        TAG,
                        "handleUserUtterance failed",
                        e
                    )

                    processing = false

                    speak(
                        "দুঃখিত বস, একটা সমস্যা হয়েছে, আবার বলো।"
                    )
                }
            }

        }, COMMAND_DELAY_MS)
    }

    private suspend fun withContextIO(
        block: () -> String
    ): String =
        kotlinx.coroutines.withContext(
            Dispatchers.IO
        ) {
            block()
        }

    // ============================================================
    // TEXT TO SPEECH
    // ============================================================

    private fun speak(
        text: String
    ) {

        if (text.isBlank()) {

            processing = false

            if (!sleeping) {
                AgentState.update(
                    AgentStatus.ONLINE_IDLE
                )

                scheduleListening(700L)
            }

            return
        }

        if (muted) {

            processing = false
            speaking = false

            if (!sleeping) {
                AgentState.update(
                    AgentStatus.ONLINE_IDLE
                )

                scheduleListening(700L)
            }

            return
        }

        try {

            /*
             * Make absolutely sure microphone is not active
             * while the assistant is speaking.
             */
            stopListening()

            speaking = true

            AgentState.update(
                AgentStatus.SPEAKING
            )

            tts?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "taj_reply"
            )

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "speak failed",
                e
            )

            speaking = false
            processing = false

            if (!sleeping) {
                AgentState.update(
                    AgentStatus.ONLINE_IDLE
                )

                scheduleListening(700L)
            }
        }
    }

    private fun selectFemaleVoice() {

        try {

            val engine = tts
                ?: return

            val voices =
                engine.voices
                    ?: return

            val candidates =
                voices.filter {
                    it.locale.language == "bn"
                }

            val femaleBD =
                candidates.firstOrNull {
                    it.locale.country.equals(
                        "BD",
                        ignoreCase = true
                    ) &&
                            it.name.contains(
                                "female",
                                ignoreCase = true
                            )
                }

            val femaleAny =
                candidates.firstOrNull {
                    it.name.contains(
                        "female",
                        ignoreCase = true
                    )
                }

            val bdAny =
                candidates.firstOrNull {
                    it.locale.country.equals(
                        "BD",
                        ignoreCase = true
                    )
                }

            val chosen =
                femaleBD
                    ?: femaleAny
                    ?: bdAny
                    ?: candidates.firstOrNull()

            if (chosen != null) {

                engine.voice = chosen

                Log.i(
                    TAG,
                    "TTS voice selected: ${chosen.name} ${chosen.locale}"
                )

            } else {

                Log.w(
                    TAG,
                    "No Bengali TTS voice found"
                )
            }

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "selectFemaleVoice failed",
                e
            )
        }
    }

    // ============================================================
    // SCREEN CAPTURE
    // ============================================================

    private fun setupScreenCapture(
        resultCode: Int,
        data: Intent
    ) {

        try {

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    data
                )

            val metrics =
                resources.displayMetrics

            val width =
                metrics.widthPixels

            val height =
                metrics.heightPixels

            imageReader =
                ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    2
                )

            virtualDisplay =
                mediaProjection?.createVirtualDisplay(
                    "TajScreenCapture",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    null
                )

            Log.i(
                TAG,
                "Screen capture active"
            )

        } catch (e: Throwable) {

            Log.e(
                TAG,
                "setupScreenCapture failed",
                e
            )
        }
    }

    private fun withTimeoutSafeScreenshot(): String? {

        val reader =
            imageReader
                ?: return null

        return try {

            val image =
                reader.acquireLatestImage()
                    ?: return null

            val plane =
                image.planes[0]

            val buffer =
                plane.buffer

            val pixelStride =
                plane.pixelStride

            val rowStride =
                plane.rowStride

            val rowPadding =
                rowStride -
                        pixelStride *
                        image.width

            val fullBitmap =
                Bitmap.createBitmap(
                    image.width +
                            rowPadding /
                            pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )

            fullBitmap.copyPixelsFromBuffer(
                buffer
            )

            image.close()

            val maxWidth = 480

            val scale =
                maxWidth.toFloat() /
                        fullBitmap.width

            val targetHeight =
                (
                    fullBitmap.height *
                            scale
                    ).toInt()
                    .coerceAtLeast(1)

            val smallBitmap =
                Bitmap.createScaledBitmap(
                    fullBitmap,
                    maxWidth,
                    targetHeight,
                    true
                )

            fullBitmap.recycle()

            val stream =
                ByteArrayOutputStream()

            smallBitmap.compress(
                Bitmap.CompressFormat.JPEG,
                60,
                stream
            )

            smallBitmap.recycle()

            GeminiClient.encodeBitmapToBase64(
                stream.toByteArray()
            )

        } catch (e: Throwable) {

            Log.w(
                TAG,
                "No screenshot available: ${e.message}"
            )

            null
        }
    }

    private fun needsScreenVision(
        text: String
    ): Boolean {

        val keywords =
            listOf(
                "market",
                "trade",
                "trading",
                "candle",
                "chart",
                "signal",
                "call",
                "put",
                "screen",
                "screenshot",
                "what do you see",
                "what is on screen",

                "মার্কেট",
                "চার্ট",
                "সিগন্যাল",
                "ট্রেড",
                "ক্যান্ডেল",
                "স্ক্রিন",
                "স্ক্রিন দেখ",
                "এখানে কি",
                "কি দেখছ",
                "কি দেখা যাচ্ছে",

                "बाज़ार",
                "मार्केट",
                "चार्ट",
                "सिग्नल",
                "ट्रेड",
                "कैंडल",
                "स्क्रीन",
                "क्या दिख रहा"
            )

        val lower =
            text.lowercase(
                Locale.getDefault()
            )

        return keywords.any {
            lower.contains(it)
        }
    }

    // ============================================================
    // NOTIFICATION
    // ============================================================

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "TAJ AI",
                    NotificationManager.IMPORTANCE_LOW
                )

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun buildNotification(): Notification {

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                getString(
                    R.string.orb_notification_title
                )
            )
            .setContentText(
                getString(
                    R.string.orb_notification_text
                )
            )
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setOngoing(true)
            .build()
    }
}
