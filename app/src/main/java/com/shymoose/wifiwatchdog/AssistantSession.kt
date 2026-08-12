package com.shymoose.wifiwatchdog

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View

/**
 * A headless session whose only job is to launch Settings' airplane-mode voice
 * activity from inside a voice interaction, which is the one context where
 * `VoiceSettingsActivity.isVoiceInteractionRoot()` returns true.
 */
class AssistantSession(service: AssistantSessionService) : VoiceInteractionSession(service) {

    private val appContext = service.applicationContext

    /** No UI at all — the session must not paint over whatever is on screen. */
    override fun onCreateContentView(): View? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        // Anything that is not our own request (a long-press on home, say) is
        // ignored. Note the explicit containsKey check: a bare getBoolean would
        // default to false and switch airplane mode *off* unprompted.
        if (args == null || !args.containsKey(AssistantService.EXTRA_ENABLE)) {
            hide()
            return
        }

        val enable = args.getBoolean(AssistantService.EXTRA_ENABLE)
        val intent = Intent(ACTION_VOICE_CONTROL_AIRPLANE_MODE)
            .addCategory(Intent.CATEGORY_VOICE)
            .putExtra(EXTRA_AIRPLANE_MODE_ENABLED, enable)

        runCatching { startVoiceActivity(intent) }
            .onSuccess {
                EventLog.add(
                    appContext,
                    EventLevel.ACTION,
                    "Airplane mode ${if (enable) "ON" else "OFF"} requested via assistant"
                )
            }
            .onFailure {
                EventLog.add(appContext, EventLevel.ERROR, "Airplane request rejected: ${it.message}")
                hide()
            }
    }

    companion object {
        // Inlined rather than referenced from android.provider.Settings so the
        // build does not depend on the API level these constants were added in.
        const val ACTION_VOICE_CONTROL_AIRPLANE_MODE = "android.settings.VOICE_CONTROL_AIRPLANE_MODE"
        const val EXTRA_AIRPLANE_MODE_ENABLED = "airplane_mode_enabled"
    }
}
