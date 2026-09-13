package com.tajai.assistant

import android.util.Log
import org.json.JSONObject
import java.util.regex.Pattern

object ActionParser {

    private const val TAG = "ActionParser"
    private val ACTION_PATTERN: Pattern = Pattern.compile("\\[ACTION:(\\w+):(\\{.*?\\})\\]")

    private val APP_PACKAGES = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "telegram" to "org.telegram.messenger",
        "spotify" to "com.spotify.music",
        "dialer" to "com.android.dialer",
        "settings" to "com.android.settings"
    )

    /** Strips action tags out so they are never spoken aloud by TTS. */
    fun stripTagsForSpeech(reply: String): String = try {
        ACTION_PATTERN.matcher(reply).replaceAll("").trim()
    } catch (e: Throwable) {
        Log.e(TAG, "stripTagsForSpeech failed", e)
        reply
    }

    /** Finds and executes every action tag found in the reply. Returns count executed. */
    fun executeActions(reply: String): Int {
        val service = TajAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Accessibility service not connected — cannot execute actions")
            return 0
        }
        var count = 0
        try {
            val matcher = ACTION_PATTERN.matcher(reply)
            while (matcher.find()) {
                try {
                    val type = matcher.group(1)
                    val jsonStr = matcher.group(2) ?: "{}"
                    val payload = try {
                        JSONObject(jsonStr)
                    } catch (e: Throwable) {
                        JSONObject()
                    }
                    when (type) {
                        "OPEN_APP" -> {
                            val appKey = payload.optString("app")
                            val appName = payload.optString("appName")
                            val pkg = APP_PACKAGES[appKey.lowercase()]
                            if (pkg != null) service.openApp(pkg)
                            else if (appName.isNotBlank()) service.openAppByName(appName)
                            else if (appKey.isNotBlank()) service.openAppByName(appKey)
                        }
                        "CALL" -> {
                            val number = payload.optString("number")
                            val name = payload.optString("name")
                            val resolved = if (number.isNotBlank()) number
                                else if (name.isNotBlank()) service.resolveContactNumber(name)
                                else null
                            if (!resolved.isNullOrBlank()) service.dialNumber(resolved)
                        }
                        "SEND_WHATSAPP" -> {
                            val number = payload.optString("number")
                            val name = payload.optString("name")
                            val message = payload.optString("message")
                            val resolved = if (number.isNotBlank()) number
                                else if (name.isNotBlank()) service.resolveContactNumber(name)
                                else null
                            if (!resolved.isNullOrBlank()) service.sendWhatsAppMessage(resolved, message)
                        }
                        "SEARCH_YOUTUBE" -> {
                            val query = payload.optString("query")
                            if (query.isNotBlank()) service.searchYoutube(query)
                        }
                        "TOGGLE_FLASHLIGHT" -> service.toggleFlashlight()
                        "OPEN_WIFI" -> service.openWifiPanel()
                        "TYPE_TEXT" -> {
                            val text = payload.optString("text")
                            if (text.isNotBlank()) service.typeIntoFocusedField(text)
                        }
                        "SLEEP" -> FloatingOrbService.instance?.enterSleepMode()
                        "WAKE" -> FloatingOrbService.instance?.exitSleepMode()
                        else -> Log.w(TAG, "Unknown action type: $type")
                    }
                    count++
                } catch (e: Throwable) {
                    // One bad/unexpected action tag should never crash the whole app.
                    Log.e(TAG, "Failed to execute one action tag", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "executeActions failed", e)
        }
        return count
    }
}
