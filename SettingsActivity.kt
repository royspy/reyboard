package com.example.banglakeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.graphics.ColorUtils
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

class SettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var content: LinearLayout
    private lateinit var preview: ImageView
    private lateinit var predictor: Predictor
    private val translator by lazy { LiveTranslator() }
    private var pickedImage: String? = null
    private val REQ_IMG = 11

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("kb_prefs", Context.MODE_PRIVATE)
        predictor = Predictor.get(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(40)) }
        setContentView(ScrollView(this).apply { addView(content) })
        build()
    }

    private fun build() {
        content.removeAllViews()
        title("বাংলা কিবোর্ড", 26f)
        setupSection(); layoutSection(); themeSection(); dictSection(); translateSection()
    }

    // ---- small UI helpers
    private fun title(t: String, size: Float = 18f) = content.addView(TextView(this).apply {
        text = t; textSize = size; setTypeface(null, Typeface.BOLD); setPadding(0, dp(22), 0, dp(8))
    })
    private fun note(t: String) = content.addView(TextView(this).apply { text = t; textSize = 13f; setPadding(0, 0, 0, dp(6)) })
    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }.also { content.addView(it) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun switchRow(label: String, key: String, def: Boolean) = content.addView(Switch(this).apply {
        text = label; isChecked = prefs.getBoolean(key, def); setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
    })
    private fun edit(hint: String, def: String = "") = EditText(this).apply { setHint(hint); setText(def); setSingleLine() }

    // ---- 1. setup
    private fun setupSection() {
        title("1. Setup")
        btn("Enable keyboard in system settings") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btn("Switch to this keyboard") { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        content.addView(edit("Try typing here…"))
    }

    // ---- 2. layouts & behaviour
    private fun layoutSection() {
        title("2. Layouts & typing")
        note("Tick the layouts you want in the 🌐 key rotation. The keyboard also remembers the last one used.")
        val enabled = prefs.getString("enabled", "avro,bijoy,windows,qwerty")!!.split(",").toMutableSet()
        Layouts.ALL.forEach { id ->
            content.addView(CheckBox(this).apply {
                text = Layouts.title(id); isChecked = id in enabled
                setOnCheckedChangeListener { _, on ->
                    if (on) enabled.add(id) else if (enabled.size > 1) enabled.remove(id) else { isChecked = true }
                    prefs.edit().putString("enabled", Layouts.ALL.filter { it in enabled }.joinToString(",")).apply()
                }
            })
        }
        note("Default layout:")
        val rg = RadioGroup(this)
        val cur = prefs.getString("layout", "avro")
        Layouts.ALL.forEach { id ->
            rg.addView(RadioButton(this).apply { text = Layouts.title(id); tag = id; isChecked = id == cur; setId(android.view.View.generateViewId()) })
        }
        rg.setOnCheckedChangeListener { g, i -> prefs.edit().putString("layout", g.findViewById<RadioButton>(i).tag as String).apply() }
        content.addView(rg)

        note("Key height")
        content.addView(SeekBar(this).apply {
            max = 28; progress = prefs.getInt("key_height", 50) - 40
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { prefs.edit().putInt("key_height", 40 + p).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        switchRow("Number row on Avro / English", "num_row", false)
        switchRow("Word suggestions, next-word & spell check", "suggest", true)
        switchRow("Vibrate on key press", "haptic", true)
        switchRow("Click sound", "sound", false)
    }

    // ---- 3. themes
    private fun themeSection() {
        title("3. Themes")
        val sel = Themes.current(this).id
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Themes.all(this).forEach { t ->
            row.addView(TextView(this).apply {
                text = (if (t.id == sel) "✓ " else "") + t.name
                setTextColor(t.text); gravity = Gravity.CENTER; setPadding(dp(18), dp(14), dp(18), dp(14))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(t.bg); setStroke(dp(2), t.accent) }
                setOnClickListener { Themes.select(this@SettingsActivity, t.id); toast("Theme: ${t.name}"); build() }
                if (!t.builtin) setOnLongClickListener {
                    AlertDialog.Builder(this@SettingsActivity).setMessage("Delete theme \"${t.name}\"?")
                        .setPositiveButton("Delete") { _, _ -> Themes.delete(this@SettingsActivity, t.id); build() }
                        .setNegativeButton("Cancel", null).show(); true
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        content.addView(HorizontalScrollView(this).apply { addView(row) })
        note("Long-press a custom theme to delete it.")

        title("Create a theme", 16f)
        val name = edit("Theme name", "My theme"); content.addView(name)
        val bgE = edit("Background hex", "1B1B1F"); val keyE = edit("Key hex", "2E2F33")
        val txtE = edit("Text hex", "FFFFFF"); val accE = edit("Accent hex", "8AB4F8")
        listOf(bgE, keyE, txtE, accE).forEach { content.addView(it) }
        note("Key opacity (matters most with a background image)")
        val alpha = SeekBar(this).apply { max = 175; progress = 90 }; content.addView(alpha)

        btn("Pick background image from gallery") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_IMG)
        }
        val prompt = edit("Describe a design, e.g. 'monsoon clouds over green paddy fields'"); content.addView(prompt)
        btn("✨ Generate background with free AI") {
            if (prompt.text.isBlank()) toast("Type a description first") else generate(prompt.text.toString())
        }
        note("AI images come from the free Pollinations.ai service (no key). It needs internet, can be slow, and the service may change or rate-limit.")
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP }
        content.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)))

        btn("Save theme") {
            fun col(s: EditText, def: Int) = try { Color.parseColor("#" + s.text.toString().trim().removePrefix("#")) } catch (e: Exception) { def }
            val bg = col(bgE, 0xFF1B1B1F.toInt()); val key = col(keyE, 0xFF2E2F33.toInt()); val tx = col(txtE, Color.WHITE)
            val t = KbTheme(
                "custom_" + System.currentTimeMillis(), name.text.toString().ifBlank { "My theme" },
                bg, key, tx, ColorUtils.blendARGB(key, bg, 0.5f), col(accE, 0xFF8AB4F8.toInt()),
                ColorUtils.setAlphaComponent(tx, 0xAA), pickedImage, 80 + alpha.progress, false
            )
            Themes.saveCustom(this, t); pickedImage = null; toast("Theme saved & applied"); build()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req == REQ_IMG && res == RESULT_OK && uri != null) {
            Thread {
                try {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val bmp = decode(bytes) ?: throw Exception("unreadable image")
                    val path = saveImage(bmp)
                    runOnUiThread { pickedImage = path; preview.setImageBitmap(bmp); toast("Image ready — tap Save theme") }
                } catch (e: Exception) { runOnUiThread { toast("Failed: ${e.message}") } }
            }.start()
        }
    }

    private fun decode(b: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1
        while (o.outWidth / s > 1600) s *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun saveImage(bmp: Bitmap): String {
        val w = 1080
        val h = (bmp.height * w / bmp.width.toFloat()).toInt().coerceAtLeast(1)
        val sc = Bitmap.createScaledBitmap(bmp, w, h, true)
        val f = File(filesDir, "theme_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { sc.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return f.absolutePath
    }

    private fun generate(prompt: String) {
        toast("Generating… this can take up to a minute")
        Thread {
            try {
                val q = URLEncoder.encode("$prompt, soft abstract wallpaper, no text, no letters", "UTF-8").replace("+", "%20")
                val url = URL("https://image.pollinations.ai/prompt/$q?width=1080&height=640&nologo=true&seed=${Random.nextInt(100000)}")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 90000
                c.setRequestProperty("User-Agent", "BanglaKeyboard/1.0")
                val bytes = c.inputStream.use { it.readBytes() }
                val bmp = decode(bytes) ?: throw Exception("bad image data")
                val path = saveImage(bmp)
                runOnUiThread { pickedImage = path; preview.setImageBitmap(bmp); toast("Ready — tap Save theme") }
            } catch (e: Exception) { runOnUiThread { toast("Generation failed: ${e.message}") } }
        }.start()
    }

    // ---- 4. dictionary
    private fun dictSection() {
        title("4. My dictionary")
        note("Words you save (also via the ＋ chip while typing) are never flagged and rank higher in suggestions.")
        val w = edit("New word"); content.addView(w)
        btn("Add word") {
            if (w.text.isNotBlank()) { predictor.addWord(w.text.toString()); toast("Added"); w.setText("") }
        }
        btn("View / remove saved words") {
            val list = predictor.savedWords()
            if (list.isEmpty()) toast("No saved words yet") else
                AlertDialog.Builder(this).setTitle("Tap a word to remove it")
                    .setItems(list.toTypedArray()) { _, i -> predictor.removeWord(list[i]); toast("Removed") }.show()
        }
    }

    // ---- 5. translation
    private fun translateSection() {
        title("5. Live translation")
        note("Turn it on from the keyboard toolbar (⋮ → 🌐). Models (~30 MB each) download once, then work offline.")
        btn("Download Bangla ⇄ English models now") {
            toast("Downloading…")
            translator.preload { ok -> toast(if (ok) "Translation models ready" else "Download failed — check internet") }
        }
    }
}
