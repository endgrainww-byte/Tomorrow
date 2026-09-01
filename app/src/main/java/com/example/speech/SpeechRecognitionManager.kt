package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed class SpeechState {
    object Idle : SpeechState()
    data class Listening(val rmsDb: Float = 0f, val partialText: String = "") : SpeechState()
    object Processing : SpeechState()
    data class Success(val text: String) : SpeechState()
    data class Error(val message: String) : SpeechState()
}

class SpeechRecognitionManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRecognizer"
    }

    private var speechRecognizer: SpeechRecognizer? = null

    private val _speechState = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private var currentPartialText = ""

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            setupRecognizer()
        } else {
            _speechState.value = SpeechState.Error("Speech recognition is not available on this device.")
        }
    }

    private fun setupRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "onReadyForSpeech")
                        _speechState.value = SpeechState.Listening(rmsDb = 0f, partialText = currentPartialText)
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "onBeginningOfSpeech")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        if (_speechState.value is SpeechState.Listening) {
                            val normalizedRms = (rmsdB + 2f).coerceIn(0f, 10f) / 10f
                            _speechState.value = SpeechState.Listening(
                                rmsDb = normalizedRms,
                                partialText = currentPartialText
                            )
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "onEndOfSpeech")
                        _speechState.value = SpeechState.Processing
                    }

                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                            SpeechRecognizer.ERROR_NETWORK -> "Network connection error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
                            else -> "Speech recognition error ($error)"
                        }
                        Log.w(TAG, "onError: $message ($error)")
                        _speechState.value = SpeechState.Error(message)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: currentPartialText
                        Log.d(TAG, "onResults: $text")
                        if (text.isNotBlank()) {
                            _speechState.value = SpeechState.Success(text)
                        } else {
                            _speechState.value = SpeechState.Idle
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull() ?: ""
                        if (partial.isNotBlank()) {
                            currentPartialText = partial
                            _speechState.value = SpeechState.Listening(
                                rmsDb = 0.5f,
                                partialText = partial
                            )
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
            _speechState.value = SpeechState.Error("Could not initialize speech engine.")
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _speechState.value = SpeechState.Error("Speech recognition is not available.")
            return
        }

        setupRecognizer()
        currentPartialText = ""

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
            _speechState.value = SpeechState.Listening(0f, "")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SpeechRecognizer", e)
            _speechState.value = SpeechState.Error("Could not start recording.")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SpeechRecognizer", e)
        }
    }

    fun resetState() {
        _speechState.value = SpeechState.Idle
        currentPartialText = ""
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying SpeechRecognizer", e)
        }
    }
}
