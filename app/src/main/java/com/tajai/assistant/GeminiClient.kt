package com.tajai.assistant

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {

    private const val TAG = "GeminiClient"
    private const val MODEL = "gemini-3.6-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val SYSTEM_PROMPT = """
        आप "TAJ" हैं — एक असली Personal AI Voice Assistant जो user के फ़ोन पर चलता है।
        आप सिर्फ़ बातें नहीं करते — आप असली काम कर सकते हैं: app खोलना, call करना, WhatsApp पर message भेजना, फ़ोन की settings खोलना।
        आपका जवाब हमेशा बोलकर सुनाया जाएगा (Text-to-Speech), इसलिए natural, छोटा और सीधा जवाब दें — कोई markdown, bullet, या symbol मत इस्तेमाल करें।

        जब user कोई काम करने को कहे, अपने जवाब के आख़िर में सही ACTION TAG ज़रूर जोड़ें (यह tag सिर्फ़ system पढ़ेगा, बोला नहीं जाएगा):

        - App खोलना: [ACTION:OPEN_APP:{"appName":"फोन में दिखने वाला exact app name"}] — user जिस installed app का नाम बोले, वही नाम दें; fixed app list तक सीमित न रहें। पुराने aliases के लिए app field भी स्वीकार्य है।
        - Call करना: [ACTION:CALL:{"number":"9876543210"}] — अगर number पता नहीं, सिर्फ़ contact का नाम बताया गया है तो: [ACTION:CALL:{"name":"राहुल"}]
        - WhatsApp message भेजना: [ACTION:SEND_WHATSAPP:{"number":"9198XXXXXXX","message":"नमस्ते"}] — या नाम से: [ACTION:SEND_WHATSAPP:{"name":"राहुल","message":"नमस्ते"}]
        - YouTube पर कोई गाना/वीडियो खोजकर चलाना: [ACTION:SEARCH_YOUTUBE:{"query":"गाने या सिंगर का नाम"}]
        - Flashlight/torch on-off करना: [ACTION:TOGGLE_FLASHLIGHT:{}]
        - Wi-Fi panel खोलना: [ACTION:OPEN_WIFI:{}]
        - जो भी text box focus में है उसमें टाइप करना: [ACTION:TYPE_TEXT:{"text":"..."}]
        - सोने/बंद होने को कहे (ताज बंद हो जाओ, sleep, band ho jao): [ACTION:SLEEP:{}]
        - जगाने को कहे (ताज उठो, wake up): [ACTION:WAKE:{}]

        अगर contact का नाम बताया गया है तो उसी नाम से "name" field भरें (number मत बनाइए) — फ़ोन अपने आप contacts में ढूंढ लेगा। अगर नाम से कोई contact ना मिले, तभी user से number पूछें।

        आपका दूसरा बड़ा काम है Trading Analyst बनना। जब user पूछे कि मार्केट किस तरफ़ जाएगा, अगला candle क्या होगा, या trading signal माँगे — तो नीचे दिए गए 30 guidelines के आधार पर जवाब दें। TAJ के पास screen/chart screenshot उपलब्ध हो तो उसे वास्तविक visual evidence की तरह ध्यान से देखकर pattern, candle body/wick, EMA, RSI, support-resistance, liquidity, OB/FVG/MSS आदि confluence जाँचें। केवल screenshot में दिखने वाली evidence के आधार पर CALL (ऊपर) या PUT (नीचे) या WAIT বলें, সঙ্গে confidence % এবং এক লাইনে কারণ। Screenshot না থাকলে visual certainty দাবি করবেন না এবং WAIT/clarifying response দিন।

        30 Trading Guidelines:
        ${TradingKnowledge.asPromptBlock()}

        व्यवहार: भरोसेमंद, दोस्ताना, आत्मविश्वासी। User को "बॉস" कहकर संबोधित करें। आपका हर जवाब हमेशा बंगाली (Bengali) भाषा में होना चाहिए, चाहे guidelines या user का सवाल किसी भी भाषा में क्यों न हो — सिर्फ़ अगर user खुद अंग्रेज़ी में लगातार बात करे तो अंग्रेज़ी में जवाब दें।
    """.trimIndent()

    /**
     * @param userText what the user said (from speech recognition)
     * @param screenshotBase64 optional JPEG screenshot (for screen/trading vision questions)
     * @param history optional prior turns as pairs of (role, text) to keep short conversational memory
     */
    fun ask(
        apiKey: String,
        userText: String,
        screenshotBase64: String? = null,
        history: List<Pair<String, String>> = emptyList()
    ): String {
        val contents = JSONArray()

        // System instruction goes as the first "user" turn framed as context (Gemini v1beta
        // also supports a dedicated systemInstruction field, used below).
        for ((role, text) in history) {
            val turn = JSONObject()
            turn.put("role", if (role == "assistant") "model" else "user")
            val parts = JSONArray()
            parts.put(JSONObject().put("text", text))
            turn.put("parts", parts)
            contents.put(turn)
        }

        val userTurn = JSONObject()
        userTurn.put("role", "user")
        val userParts = JSONArray()
        userParts.put(JSONObject().put("text", userText))
        if (screenshotBase64 != null) {
            val inlineData = JSONObject()
            inlineData.put("mimeType", "image/jpeg")
            inlineData.put("data", screenshotBase64)
            userParts.put(JSONObject().put("inlineData", inlineData))
        }
        userTurn.put("parts", userParts)
        contents.put(userTurn)

        val body = JSONObject()
        body.put("contents", contents)
        val systemInstruction = JSONObject()
        val sysParts = JSONArray()
        sysParts.put(JSONObject().put("text", SYSTEM_PROMPT))
        systemInstruction.put("parts", sysParts)
        body.put("systemInstruction", systemInstruction)

        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini error ${response.code}: $raw")
                    return@use "মাফ করবেন বস, এখন উত্তর পাওয়া গেল না। নেটওয়ার্ক বা API key চেক করুন।"
                }
                try {
                    val json = JSONObject(raw)
                    json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                } catch (e: Throwable) {
                    Log.e(TAG, "Parse error", e)
                    "মাফ করবেন বস, উত্তর বুঝতে সমস্যা হয়েছে।"
                }
            }
        } catch (e: Throwable) {
            // Network hiccup (no internet, timeout, DNS, etc.) — never let this crash the app.
            Log.e(TAG, "Network error talking to Gemini", e)
            "মাফ করবেন বস, এখন নেটওয়ার্ক এ সমস্যা হচ্ছে, একটু পরে আবার চেষ্টা করো।"
        }
    }

    fun encodeBitmapToBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)
}
