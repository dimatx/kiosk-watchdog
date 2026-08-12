package com.shymoose.wifiwatchdog

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService

/**
 * The bridge that lets a normal, unrooted app flip airplane mode.
 *
 * Airplane mode cannot be set directly: `Settings.Global.AIRPLANE_MODE_ON` is
 * only half of it, and the `ACTION_AIRPLANE_MODE_CHANGED` broadcast that
 * actually tears the radios down is protected by `CONNECTIVITY_INTERNAL`
 * (`signature|privileged`, so not grantable via `pm grant`).
 *
 * Settings does expose a real entry point — `AirplaneModeVoiceActivity`, which
 * calls `ConnectivityManager.setAirplaneMode()` as uid 1000 — but it extends
 * `VoiceSettingsActivity`, which refuses anything that is not
 * `isVoiceInteractionRoot()`. In other words the intent has to be launched by
 * the *current* voice interaction service through a session.
 *
 * So we register as an assistant. On this device the assistant slot is empty
 * (`voice_interaction_service` is null), so claiming it costs the user nothing.
 * This is the same mechanism MacroDroid uses.
 */
class AssistantService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        instance = this
        EventLog.add(this, EventLevel.INFO, "Assistant bridge ready")
    }

    override fun onShutdown() {
        if (instance === this) instance = null
        super.onShutdown()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Opens a session that will launch Settings' airplane-mode voice activity.
     *
     * [showSession] must be called from the main thread and only by the current
     * interactor, otherwise the system throws.
     */
    fun requestAirplane(enable: Boolean): Boolean {
        val args = Bundle().apply { putBoolean(EXTRA_ENABLE, enable) }
        return runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching { showSession(args, 0) }.onFailure {
                    EventLog.add(this, EventLevel.ERROR, "showSession failed: ${it.message}")
                }
            }
            true
        }.getOrElse {
            EventLog.add(this, EventLevel.ERROR, "Assistant request failed: ${it.message}")
            false
        }
    }

    companion object {
        const val EXTRA_ENABLE = "com.shymoose.wifiwatchdog.EXTRA_ENABLE"

        /** Set once the system has bound us as the active interactor. */
        @Volatile
        var instance: AssistantService? = null
            private set
    }
}
