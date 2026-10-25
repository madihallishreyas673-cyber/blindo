package com.guidesight.app

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Tiny translation helper: L.t("English", "हिंदी"). */
object L {
    @Volatile var hi = false
    fun t(en: String, hiText: String) = if (hi) hiText else en
}

class Speaker(ctx: Context, private val prefs: Prefs) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = ArrayList<() -> Unit>()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    val busy: Boolean get() = tts?.isSpeaking == true

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { id?.let { callbacks.remove(it)?.invoke() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { id?.let { callbacks.remove(it)?.invoke() } }
                    override fun onStop(id: String?, interrupted: Boolean) { id?.let { callbacks.remove(it) } }
                })
                applyLanguage()
                synchronized(waiting) {
                    ready = true
                    waiting.forEach { it() }
                    waiting.clear()
                }
            }
        }
    }

    fun applyLanguage() {
        val loc = if (L.hi) Locale("hi", "IN") else Locale("en", "IN")
        val r = tts?.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(if (L.hi) Locale("hi") else Locale.ENGLISH)
        }
    }

    /** urgent = interrupts everything currently being said. */
    fun speak(text: String, urgent: Boolean = false, onFinish: (() -> Unit)? = null) {
        val job = { doSpeak(text, urgent, onFinish) }
        synchronized(waiting) {
            if (!ready) { waiting.add(job); return }
        }
        job()
    }

    private fun doSpeak(text: String, urgent: Boolean, onFinish: (() -> Unit)?) {
        val id = UUID.randomUUID().toString()
        if (onFinish != null) callbacks[id] = onFinish
        tts?.setSpeechRate(prefs.rate)
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, prefs.volume)
        tts?.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
    }

    fun shutdown() {
        tts?.stop(); tts?.shutdown(); tts = null
    }
}
