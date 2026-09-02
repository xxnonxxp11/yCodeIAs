package com.yugahashimoto.andcode.feature.assistant

import android.content.Intent
import android.speech.RecognitionService

class AndCodeRecognitionService : RecognitionService() {
    override fun onStartListening(
        intent: Intent?,
        listener: Callback?,
    ) = Unit

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
