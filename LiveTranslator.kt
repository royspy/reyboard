package com.example.banglakeyboard

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions

/** Bangla <-> English translation using ML Kit on-device models (free, offline after first download). */
class LiveTranslator {
    private fun make(src: String, dst: String): Translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
    )

    private val bnEn by lazy { make(TranslateLanguage.BENGALI, TranslateLanguage.ENGLISH) }
    private val enBn by lazy { make(TranslateLanguage.ENGLISH, TranslateLanguage.BENGALI) }
    private val cond = DownloadConditions.Builder().build()

    fun isBangla(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count { it in '\u0980'..'\u09FF' } * 2 > letters.length
    }

    /** Callback runs on the main thread; null on failure (e.g. model not yet downloaded and offline). */
    fun translate(text: String, cb: (String?) -> Unit) {
        val t = if (isBangla(text)) bnEn else enBn
        t.downloadModelIfNeeded(cond)
            .addOnSuccessListener {
                t.translate(text).addOnSuccessListener { cb(it) }.addOnFailureListener { cb(null) }
            }
            .addOnFailureListener { cb(null) }
    }

    fun preload(done: (Boolean) -> Unit) {
        bnEn.downloadModelIfNeeded(cond).addOnSuccessListener {
            enBn.downloadModelIfNeeded(cond).addOnSuccessListener { done(true) }.addOnFailureListener { done(false) }
        }.addOnFailureListener { done(false) }
    }
}
