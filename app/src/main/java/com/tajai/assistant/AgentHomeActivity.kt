package com.tajai.assistant

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tajai.assistant.databinding.ActivityAgentHomeBinding

/**
 * This is TAJ's "home" screen — shown after setup is done (mirrors the reference
 * video: dark background, particle sphere, ONLINE badge, bottom pill with
 * mic/status/speaker). It only DISPLAYS what FloatingOrbService is doing; all the
 * actual listening/speaking/action logic still lives in the service so it keeps
 * working even if this screen is closed.
 */
class AgentHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgentHomeBinding
    private var muted = false

    private val stateObserver: (AgentStatus) -> Unit = { status -> runOnUiThread { render(status) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            // Back to the one-time permission/setup screen if the user ever needs to re-check it
            startActivity(android.content.Intent(this, MainActivity::class.java))
        }

        binding.btnMic.setOnClickListener {
            if (AgentState.status == AgentStatus.SLEEPING) {
                FloatingOrbService.instance?.exitSleepMode()
            } else {
                FloatingOrbService.instance?.enterSleepMode()
            }
        }

        binding.btnSpeaker.setOnClickListener {
            muted = !muted
            FloatingOrbService.instance?.setMuted(muted)
            binding.btnSpeaker.alpha = if (muted) 0.4f else 1f
        }

        binding.btnChat.setOnClickListener {
            // Placeholder for a future text-chat fallback screen.
        }
    }

    override fun onResume() {
        super.onResume()
        AgentState.observe(stateObserver)
    }

    override fun onPause() {
        super.onPause()
        AgentState.removeObserver(stateObserver)
    }

    private fun render(status: AgentStatus) {
        when (status) {
            AgentStatus.OFFLINE -> {
                binding.tvStatusLabel.text = "OFFLINE"
                binding.tvStatusLine1.text = "TAJ AI"
                binding.tvStatusLine2.text = "बंद है — MainActivity से चालू करें"
                binding.particleSphere.setEnergy(0f)
            }
            AgentStatus.ONLINE_IDLE -> {
                binding.tvStatusLabel.text = "ONLINE"
                binding.tvStatusLine1.text = "READY"
                binding.tvStatusLine2.text = "बोलिए, मैं सुन रहा हूँ"
                binding.particleSphere.setEnergy(0.15f)
            }
            AgentStatus.LISTENING -> {
                binding.tvStatusLabel.text = "ONLINE"
                binding.tvStatusLine1.text = "LISTENING"
                binding.tvStatusLine2.text = "ACTIVE"
                binding.particleSphere.setEnergy(0.6f)
            }
            AgentStatus.SPEAKING -> {
                binding.tvStatusLabel.text = "ONLINE"
                binding.tvStatusLine1.text = "SPEAKING"
                binding.tvStatusLine2.text = "RESPONDING"
                binding.particleSphere.setEnergy(1f)
            }
            AgentStatus.SLEEPING -> {
                binding.tvStatusLabel.text = "ASLEEP"
                binding.tvStatusLine1.text = "SLEEPING"
                binding.tvStatusLine2.text = "जगाने के लिए mic दबाएँ"
                binding.particleSphere.setEnergy(0f)
            }
        }
    }
}
