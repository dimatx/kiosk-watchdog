package com.shymoose.wifiwatchdog

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * A voice interaction service is not allowed to exist without naming a
 * recognition service, so this exists purely to satisfy the parser in
 * `VoiceInteractionServiceInfo`. It recognises nothing and immediately reports
 * an error to any caller that stumbles into it.
 */
class StubRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
