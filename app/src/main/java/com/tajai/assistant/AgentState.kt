package com.tajai.assistant

import android.util.Log

enum class AgentStatus { OFFLINE, ONLINE_IDLE, LISTENING, SPEAKING, SLEEPING }

/**
 * FloatingOrbService updates this whenever it starts listening / gets a reply / sleeps.
 * AgentHomeActivity (the screen shown in the video reference) observes it to animate
 * the particle sphere and update the "LISTENING ACTIVE" / "SPEAKING RESPONDING" text —
 * without needing any extra permission or cross-process binding, since both run in
 * the same app process.
 */
object AgentState {
    private const val TAG = "AgentState"

    var status: AgentStatus = AgentStatus.OFFLINE
        private set

    private val listeners = mutableListOf<(AgentStatus) -> Unit>()

    fun update(newStatus: AgentStatus) {
        status = newStatus
        // Copy the list before iterating, and guard each observer individually —
        // one screen's UI update failing should never crash the background service.
        listeners.toList().forEach {
            try {
                it(newStatus)
            } catch (e: Throwable) {
                Log.e(TAG, "An observer failed to handle status update", e)
            }
        }
    }

    fun observe(listener: (AgentStatus) -> Unit) {
        listeners.add(listener)
        try {
            listener(status)
        } catch (e: Throwable) {
            Log.e(TAG, "Observer failed on initial call", e)
        }
    }

    fun removeObserver(listener: (AgentStatus) -> Unit) {
        listeners.remove(listener)
    }
}
