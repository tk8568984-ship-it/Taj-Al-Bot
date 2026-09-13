package com.tajai.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TajAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TajAccessibility"
        var instance: TajAccessibilityService? = null
    }

    private var flashlightOn = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TAJ accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun openApp(packageName: String): Boolean {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return true
    }

    fun openAppByName(name: String): Boolean {
        val wanted = name.trim().lowercase()
        if (wanted.isBlank()) return false

        return try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(
                android.content.pm.PackageManager.GET_META_DATA
            ).filter {
                pm.getLaunchIntentForPackage(it.packageName) != null
            }

            val exact = apps.firstOrNull {
                pm.getApplicationLabel(it).toString()
                    .trim()
                    .lowercase() == wanted
            }

            val partial = apps.firstOrNull {
                pm.getApplicationLabel(it).toString()
                    .trim()
                    .lowercase()
                    .contains(wanted)
            }

            val candidate = exact ?: partial ?: return false
            openApp(candidate.packageName)

        } catch (e: Throwable) {
            Log.e(TAG, "openAppByName failed for $name", e)
            false
        }
    }

    fun searchYoutube(query: String) {
        if (query.isBlank()) return

        try {
            val launch = packageManager.getLaunchIntentForPackage(
                "com.google.android.youtube"
            )

            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)

                Handler(mainLooper).postDelayed({
                    typeIntoYoutubeSearch(query, 0)
                }, 1800)

                return
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not launch YouTube app", e)
        }

        try {
            val uri = Uri.parse(
                "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
            )

            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            startActivity(intent)

        } catch (e: Throwable) {
            Log.e(TAG, "searchYoutube fallback failed", e)
        }
    }

    private fun typeIntoYoutubeSearch(query: String, attempt: Int) {
        try {
            val root = rootInActiveWindow

            if (root == null) {
                if (attempt < 4) {
                    Handler(mainLooper).postDelayed({
                        typeIntoYoutubeSearch(query, attempt + 1)
                    }, 700)
                }
                return
            }

            val searchNode = findEditableOrSearchNode(root)

            if (searchNode != null) {
                searchNode.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
                )

                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        query
                    )
                }

                val ok = searchNode.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
                )

                if (ok) {
                    Handler(mainLooper).postDelayed({
                        tapFirstMatching(
                            rootInActiveWindow,
                            listOf("Search", "সার্চ", "بحث")
                        )
                    }, 500)

                    return
                }
            }

        } catch (e: Throwable) {
            Log.w(TAG, "YouTube accessibility typing failed", e)
        }

        if (attempt < 4) {
            Handler(mainLooper).postDelayed({
                typeIntoYoutubeSearch(query, attempt + 1)
            }, 700)
        } else {
            try {
                val uri = Uri.parse(
                    "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
                )

                val intent = Intent(
                    Intent.ACTION_VIEW,
                    uri
                ).setPackage("com.google.android.youtube")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                startActivity(intent)

            } catch (_: Throwable) {
            }
        }
    }

    private fun findEditableOrSearchNode(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        if (
            root.isEditable ||
            root.className?.toString()
                ?.contains("EditText", ignoreCase = true) == true
        ) {
            return root
        }

        if (
            root.text?.toString()
                ?.contains("Search", ignoreCase = true) == true ||
            root.contentDescription?.toString()
                ?.contains("Search", ignoreCase = true) == true
        ) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditableOrSearchNode(child)

            if (found != null) return found
        }

        return null
    }

    private fun tapFirstMatching(
        root: AccessibilityNodeInfo?,
        labels: List<String>
    ): Boolean {

        if (root == null) return false

        for (label in labels) {
            val node = findNodeByText(root, label)

            if (
                node != null &&
                node.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
            ) {
                return true
            }
        }

        return false
    }

    fun resolveContactNumber(name: String): String? {
        return try {
            val uri =
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI

            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val selection =
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"

            val selectionArgs = arrayOf("%$name%")

            contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->

                if (cursor.moveToFirst()) {
                    val numberIndex = cursor.getColumnIndex(
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                    )

                    if (numberIndex >= 0) {
                        cursor.getString(numberIndex)?.replace(" ", "")
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

        } catch (e: Throwable) {
            Log.e(
                TAG,
                "resolveContactNumber failed — is READ_CONTACTS permission granted?",
                e
            )
            null
        }
    }

    fun dialNumber(number: String) {
        try {
            val intent = Intent(
                Intent.ACTION_CALL,
                Uri.parse("tel:$number")
            )

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)

        } catch (e: Throwable) {
            Log.w(
                TAG,
                "Direct call failed (permission?), opening dialer instead",
                e
            )

            try {
                val intent = Intent(
                    Intent.ACTION_DIAL,
                    Uri.parse("tel:$number")
                )

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)

            } catch (e2: Throwable) {
                Log.e(TAG, "Dialer fallback also failed", e2)
            }
        }
    }

    fun sendWhatsAppMessage(
        phoneNumberWithCountryCode: String,
        message: String
    ) {
        val uri = Uri.parse(
            "https://wa.me/$phoneNumberWithCountryCode?text=${Uri.encode(message)}"
        )

        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)

        Handler(mainLooper).postDelayed({
            tapButtonByDescription("Send")
        }, 3500)
    }

    fun toggleFlashlight() {
        try {
            val cameraManager =
                getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val camId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return

            flashlightOn = !flashlightOn
            cameraManager.setTorchMode(camId, flashlightOn)

        } catch (e: Throwable) {
            Log.e(TAG, "toggleFlashlight failed", e)
        }
    }

    fun typeIntoFocusedField(text: String): Boolean {
        val focused =
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

        val args = Bundle()

        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )

        return focused.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        )
    }

    fun tapButtonByDescription(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, label) ?: return false

        return node.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        )
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {

        if (
            root.text?.toString()
                ?.contains(text, ignoreCase = true) == true ||
            root.contentDescription?.toString()
                ?.contains(text, ignoreCase = true) == true
        ) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByText(child, text)

            if (found != null) return found
        }

        return null
    }

    fun openWifiPanel() {
        val intent = Intent(Settings.Panel.ACTION_WIFI)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    fun openSettingsScreen(
        action: String = Settings.ACTION_SETTINGS
    ) {
        val intent = Intent(action)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}
