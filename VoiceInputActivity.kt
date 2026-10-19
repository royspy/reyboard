package com.example.banglakeyboard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast

/** Invisible activity that opens the system speech recognizer and hands the result to the keyboard. */
class VoiceInputActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, intent.getStringExtra("lang") ?: "bn-BD")
        try { startActivityForResult(i, 1) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No speech recognizer installed", Toast.LENGTH_LONG).show(); finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res == RESULT_OK && t != null) {
            sendBroadcast(Intent(BanglaIME.ACTION_VOICE).setPackage(packageName).putExtra("text", t))
        }
        finish()
    }
}
