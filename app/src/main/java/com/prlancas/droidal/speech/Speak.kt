package com.prlancas.droidal.speech

import android.speech.tts.TextToSpeech
import com.prlancas.droidal.event.EventBus
import com.prlancas.droidal.event.events.Say
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

class Speak(val ttobj: TextToSpeech) {

    init {
        runBlocking {
            withContext(Dispatchers.IO) {
//                launch {
                    EventBus.subscribe<Say> {
                        say(it.sentence)
//                    }
                }
            }
        }
    }

    private fun say(sentence: String) {
        ttobj.language = Locale.UK;

        ttobj.speak(StringBuilder(sentence), TextToSpeech.QUEUE_ADD, null, sentence);
    }
}