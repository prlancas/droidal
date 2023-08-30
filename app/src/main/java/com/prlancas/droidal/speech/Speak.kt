package com.prlancas.droidal.speech

import android.speech.tts.TextToSpeech
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.util.Locale

@OptIn(DelicateCoroutinesApi::class)
class Speak(val ttobj: TextToSpeech) {
    private val scope = MainScope()
    init {
        scope.launch(newSingleThreadContext("MyOwnThread")) {
            EventBus.subscribe<Say> {
                say(it.sentence)
            }
        }
    }

    private fun say(sentence: String) {
        ttobj.language = Locale.UK

        ttobj.speak(StringBuilder(sentence), TextToSpeech.QUEUE_ADD, null, sentence)
    }
}