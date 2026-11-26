package com.sudhir.dost

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Listens through the phone's speech recognition, in the chosen language. */
class Ears(private val ctx: Context) {
    private var sr: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun listen(tag: String, onPartial: (String) -> Unit, onResult: (String?, Int) -> Unit) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr = r
        var handled = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                if (handled) return
                handled = true
                onResult(null, error)
            }
            override fun onResults(results: Bundle?) {
                if (handled) return
                handled = true
                val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                onResult(l?.firstOrNull(), 0)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { if (it.isNotBlank()) onPartial(it) }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            r.startListening(i)
        } catch (e: Exception) {
            handled = true
            onResult(null, -1)
        }
    }

    fun cancel() {
        try { sr?.cancel() } catch (e: Exception) { }
        try { sr?.destroy() } catch (e: Exception) { }
        sr = null
    }
}
