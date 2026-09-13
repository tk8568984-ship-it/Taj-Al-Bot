package com.tajai.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tajai.assistant.databinding.ActivityMainBinding

/**
 * This screen is only for ONE-TIME setup. Once all four permissions are granted
 * and the agent is started, the user never has to open this screen again —
 * TAJ keeps running as a background service + floating orb.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val micPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
            refreshStatus()
        }

    private val callPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
            refreshStatus()
        }

    // Screen capture (for trading/chart vision) — Android requires this consent dialog
    // once per capture session. We ask ONCE here, right when the agent starts, and the
    // service then keeps that same session alive for as long as TAJ stays running —
    // so it is never asked again while the agent is on.
    private val screenCaptureLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                FloatingOrbService.start(this, result.resultCode, result.data)
            } else {
                // User can still use TAJ without screen vision — just skip that part.
                FloatingOrbService.start(this)
            }
            startActivity(Intent(this, AgentHomeActivity::class.java))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnMicPermission.setOnClickListener {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.btnCallPermission.setOnClickListener {
            callPermissionLauncher.launch(
                arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
            )
        }

        binding.btnOverlayPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnAccessibilityPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnStartAgent.setOnClickListener {
            if (allPermissionsGranted()) {
                val projectionManager =
                    getSystemService(MediaProjectionManager::class.java)
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                binding.tvStatus.text = "पहले ऊपर की चारों permission complete करें।"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val call = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = isAccessibilityServiceEnabled()

        binding.btnMicPermission.text = "1. माइक्रोफ़ोन Permission ${if (mic) "✅" else "दें"}"
        binding.btnCallPermission.text = "2. Call / Contacts Permission ${if (call) "✅" else "दें"}"
        binding.btnOverlayPermission.text = "3. Floating Orb ${if (overlay) "✅ On है" else "On करें"}"
        binding.btnAccessibilityPermission.text =
            "4. Accessibility Service ${if (accessibility) "✅ On है" else "On करें"}"
    }

    private fun allPermissionsGranted(): Boolean {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = isAccessibilityServiceEnabled()
        return mic && overlay && accessibility
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${TajAccessibilityService::class.java.name}"
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            if (colonSplitter.next().equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
