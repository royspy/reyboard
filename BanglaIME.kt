package com.example.banglakeyboard

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.util.concurrent.Executors

class BanglaIME : InputMethodService(), KeyboardView.Listener {

    companion object {
        const val ACTION_VOICE = "com.example.banglakeyboard.VOICE_RESULT"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val EMOJIS = ("😀 😃 😄 😁 😆 😅 😂 🤣 😊 🙂 😉 😍 🥰 😘 😋 😎 🤩 🥳 😏 😒 😞 😔 😢 😭 😤 😡 🥺 😱 😴 🤔 " +
                "🤗 🙄 😬 🤐 😷 🤒 🥵 🥶 👍 👎 👏 🙌 🙏 💪 ✌️ 🤞 👌 🤝 👋 ❤️ 🧡 💛 💚 💙 💜 🖤 💔 💕 💖 💯 🔥 ✨ 🎉 🎂 🎁 " +
                "🌹 🌸 🌙 ⭐ ☀️ 🌧️ ☕ 🍕 🍔 🍚 🍛 🍎 🍌 🥭 🐟 🐅 🇧🇩 🕌 📚 ✏️ 💻 📱 🚗 ✈️ 🏠 ⏰ ✅ ❌ ❓ ❗").split(" ")
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var predictor: Predictor
    private lateinit var translator: LiveTranslator
    private lateinit var root: LinearLayout
    private lateinit var kb: KeyboardView
    private lateinit var host: FrameLayout
    private lateinit var barToggle: TextView
    private lateinit var suggestScroll: HorizontalScrollView
    private lateinit var suggestRow: LinearLayout
    private lateinit var toolScroll: HorizontalScrollView
    private lateinit var toolRow: LinearLayout
    private var translateBtn: TextView? = null
    private var theme: KbTheme = Themes.DEFAULTS[0]

    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val avro = StringBuilder()
    private var symbols = false
    private var shifted = false
    private var capsLock = false
    private var lastShiftTap = 0L
    private var lastSpace = 0L
    private var passwordField = false
    private var panel: View? = null
    private var suggestGen = 0
    private var transGen = 0

    // suggestion-bar state
    private var comps: List<String> = emptyList()
    private var corr: List<String> = emptyList()
    private var trans: String? = null
    private var transSource = ""
    private var showSave = false
    private var curWord = ""
    private var literalAvro = false
    private var translateOn = false
    private val clips = ArrayList<String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- lifecycle
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (passwordField) return@OnPrimaryClipChangedListener
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() } catch (e: Exception) { null }
        if (!t.isNullOrBlank()) addClip(t)
    }

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val t = i?.getStringExtra("text") ?: return
            currentInputConnection?.commitText("$t ", 1)
            afterInput()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("kb_prefs", MODE_PRIVATE)
        predictor = Predictor.get(this)
        translator = LiveTranslator()
        try {
            val a = JSONArray(prefs.getString("clips", "[]"))
            for (i in 0 until a.length()) clips.add(a.getString(i))
        } catch (e: Exception) { }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        ContextCompat.registerReceiver(this, voiceReceiver, IntentFilter(ACTION_VOICE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        try { unregisterReceiver(voiceReceiver) } catch (e: Exception) { }
        predictor.save()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        theme = Themes.current(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        barToggle = TextView(this).apply {
            text = "⋮"; textSize = 20f; gravity = Gravity.CENTER
            setOnClickListener { toggleBar() }
        }
        suggestRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        suggestScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(suggestRow, WRAP, MATCH) }
        toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; addView(toolRow, WRAP, MATCH) }
        val frame = FrameLayout(this)
        frame.addView(suggestScroll, MATCH, MATCH)
        frame.addView(toolScroll, MATCH, MATCH)
        bar.addView(barToggle, dp(44), dp(42))
        bar.addView(frame, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(bar, MATCH, WRAP)

        host = FrameLayout(this)
        kb = KeyboardView(this).also { it.listener = this }
        host.addView(kb, MATCH, WRAP)
        root.addView(host, MATCH, WRAP)

        buildToolbar()
        applyPrefsAndTheme()
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::kb.isInitialized) return
        avro.setLength(0)
        closePanel()
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        passwordField = (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        symbols = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
        shifted = false; capsLock = false
        translateOn = prefs.getBoolean("translate", false)
        applyPrefsAndTheme()
        updateAutoCap(); updateSuggestions(); scheduleTranslate()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        flushAvro(); closePanel()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(os: Int, oe: Int, ns: Int, ne: Int, cs: Int, ce: Int) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce)
        if (avro.isNotEmpty() && (ns != ce || ne != ce)) { // cursor moved away from composing text
            currentInputConnection?.finishComposingText(); avro.setLength(0)
        }
        if (::kb.isInitialized) updateSuggestions()
    }

    // ---------------------------------------------------------------- theme & layout
    private fun applyPrefsAndTheme() {
        theme = Themes.current(this)
        root.setBackgroundColor(theme.bg)
        kb.theme = theme
        kb.keyHeightDp = prefs.getInt("key_height", 50)
        kb.haptic = prefs.getBoolean("haptic", true)
        kb.sound = prefs.getBoolean("sound", false)
        kb.oneHanded = prefs.getInt("one_hand", 0)
        barToggle.setTextColor(theme.text)
        for (i in 0 until toolRow.childCount) (toolRow.getChildAt(i) as TextView).setTextColor(theme.text)
        updateToolbarState()
        applyLayout()
    }

    private fun enabled(): List<String> =
        prefs.getString("enabled", "avro,bijoy,windows,qwerty")!!.split(",").filter { it in Layouts.ALL }.ifEmpty { listOf("avro") }

    private fun currentId(): String {
        val c = prefs.getString("layout", "avro")!!
        return if (c in enabled()) c else enabled()[0]
    }

    private fun currentLayout() = Layouts.get(if (symbols) "symbols" else currentId(), prefs.getBoolean("num_row", false))
    private fun applyLayout() { kb.layout = currentLayout(); syncShift() }
    private fun syncShift() { kb.shifted = shifted || capsLock; kb.capsLock = capsLock }

    private fun cycleLayout() {
        flushAvro()
        val list = enabled()
        val next = list[(list.indexOf(currentId()) + 1) % list.size]
        prefs.edit().putString("layout", next).apply()
        symbols = false; shifted = false; capsLock = false
        applyLayout(); updateSuggestions()
    }

    // ---------------------------------------------------------------- toolbar / panels
    private fun iconBtn(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 19f; gravity = Gravity.CENTER; minWidth = dp(48)
        setTextColor(theme.text); setOnClickListener { onClick() }
    }

    private fun buildToolbar() {
        toolRow.removeAllViews()
        toolRow.addView(iconBtn("📋") { showClipboard() }, WRAP, MATCH)
        toolRow.addView(iconBtn("☺") { showEmoji() }, WRAP, MATCH)
        toolRow.addView(iconBtn("🎤") { startVoice() }, WRAP, MATCH)
        translateBtn = iconBtn("🌐 A⇄অ") { toggleTranslate() }.also { toolRow.addView(it, WRAP, MATCH) }
        toolRow.addView(iconBtn("☝") { cycleOneHand() }, WRAP, MATCH)
        toolRow.addView(iconBtn("⚙") { openSettings() }, WRAP, MATCH)
    }

    private fun updateToolbarState() {
        translateBtn?.let {
            it.setTextColor(if (translateOn) theme.accent else theme.text)
            it.setTypeface(null, if (translateOn) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun toggleBar() {
        val showTools = toolScroll.visibility != View.VISIBLE
        toolScroll.visibility = if (showTools) View.VISIBLE else View.GONE
        suggestScroll.visibility = if (showTools) View.GONE else View.VISIBLE
        barToggle.text = if (showTools) "✕" else "⋮"
    }

    private fun toggleTranslate() {
        translateOn = !translateOn
        prefs.edit().putBoolean("translate", translateOn).apply()
        updateToolbarState(); trans = null; scheduleTranslate(); updateSuggestions()
        toast(if (translateOn) "Live translation ON (Bangla ⇄ English)" else "Live translation OFF")
    }

    private fun cycleOneHand() {
        val n = when (prefs.getInt("one_hand", 0)) { 0 -> 2; 2 -> 1; else -> 0 }
        prefs.edit().putInt("one_hand", n).apply()
        kb.oneHanded = n
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun startVoice() {
        flushAvro()
        startActivity(
            Intent(this, VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("lang", if (currentLayout().bangla) "bn-BD" else "en-US")
        )
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun openPanel(content: View, extraBottom: View? = null) {
        closePanel()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(theme.bg) }
        col.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(iconBtn("ABC") { closePanel() }.apply { textSize = 16f }, dp(80), dp(44))
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        extraBottom?.let { bottom.addView(it, WRAP, dp(44)) }
        col.addView(bottom, MATCH, WRAP)
        kb.visibility = View.INVISIBLE
        panel = col
        host.addView(col, FrameLayout.LayoutParams(MATCH, maxOf(kb.height, dp(220))))
    }

    private fun closePanel() {
        panel?.let { host.removeView(it) }
        panel = null
        if (::kb.isInitialized) kb.visibility = View.VISIBLE
    }

    private fun showEmoji() {
        flushAvro()
        val grid = GridLayout(this).apply { columnCount = 8 }
        EMOJIS.forEach { e ->
            val tv = TextView(this).apply {
                text = e; textSize = 24f; gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(7))
                setOnClickListener { currentInputConnection?.commitText(e, 1) }
            }
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0
            grid.addView(tv, lp)
        }
        val scroll = ScrollView(this).apply { addView(grid) }
        openPanel(scroll, iconBtn("⌫") { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) })
    }

    private fun showClipboard() {
        flushAvro()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        if (clips.isEmpty()) list.addView(TextView(this).apply { text = "Clipboard history is empty. Copy some text and it will show up here."; setTextColor(theme.hint); setPadding(dp(8), dp(16), dp(8), dp(16)) })
        clips.forEach { c ->
            list.addView(TextView(this).apply {
                text = c.take(160); maxLines = 3; textSize = 15f; setTextColor(theme.text)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(theme.key) }
                setOnClickListener { currentInputConnection?.commitText(c, 1); closePanel(); afterInput() }
                setOnLongClickListener { clips.remove(c); saveClips(); showClipboard(); true }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
        }
        openPanel(ScrollView(this).apply { addView(list) }, iconBtn("Clear all") { clips.clear(); saveClips(); showClipboard() })
    }

    private fun addClip(t: String) {
        clips.remove(t); clips.add(0, t)
        while (clips.size > 30) clips.removeAt(clips.lastIndex)
        saveClips()
    }

    private fun saveClips() { prefs.edit().putString("clips", JSONArray(clips).toString()).apply() }

    // ---------------------------------------------------------------- key handling
    override fun onKey(key: Key, shifted: Boolean) {
        when (key.code) {
            Codes.SHIFT -> handleShift()
            Codes.DEL -> handleDelete()
            Codes.ENTER -> handleEnter()
            Codes.SPACE -> handleSpace()
            Codes.SYMBOLS -> { flushAvro(); symbols = true; applyLayout() }
            Codes.ABC -> { symbols = false; applyLayout() }
            Codes.LANG -> cycleLayout()
            Codes.EMOJI -> showEmoji()
            else -> handleChar(key.out(shifted))
        }
    }

    override fun onText(text: String) {
        flushAvro()
        learnCurrent()
        currentInputConnection?.commitText(text, 1)
        afterInput()
    }

    override fun onSpaceSwipe(steps: Int) {
        flushAvro()
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onDeleteRepeat() = handleDelete()

    override fun onLongPressSpecial(key: Key) {
        when (key.code) {
            Codes.EMOJI -> showClipboard()
            else -> (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    private fun handleShift() {
        val now = SystemClock.uptimeMillis()
        if (capsLock) { capsLock = false; shifted = false }
        else if (shifted && now - lastShiftTap < 350) capsLock = true
        else shifted = !shifted
        lastShiftTap = now
        syncShift()
    }

    private fun handleChar(text: String) {
        val ic = currentInputConnection ?: return
        val lay = currentLayout()
        val ch = text[0]
        if (lay.phonetic && !symbols && text.length == 1 && ch.code < 128 && ch.isLetter()) {
            avro.append(text)
            ic.setComposingText(AvroEngine.convert(avro.toString()), 1)
        } else {
            flushAvro()
            if (text.isNotEmpty() && !Predictor.isWordChar(ch)) learnCurrent()
            val out = if (lay.phonetic && !symbols && ch in '0'..'9') ('\u09E6' + (ch - '0')).toString() else text
            ic.commitText(out, 1)
        }
        if (shifted && !capsLock) { shifted = false; syncShift() }
        afterInput()
    }

    private fun handleDelete() {
        val ic = currentInputConnection ?: return
        if (avro.isNotEmpty()) {
            avro.deleteCharAt(avro.length - 1)
            if (avro.isEmpty()) ic.commitText("", 1) else ic.setComposingText(AvroEngine.convert(avro.toString()), 1)
        } else {
            if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
            else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        afterInput()
    }

    private fun handleEnter() {
        flushAvro()
        learnCurrent()
        val ei = currentInputEditorInfo
        val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION
        if ((ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) currentInputConnection?.performEditorAction(action)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        learnCurrent()
        flushAvro()
        val now = SystemClock.uptimeMillis()
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        if (now - lastSpace < 400 && before.length == 2 && before[1] == ' ' && Predictor.isWordChar(before[0])) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(if (currentLayout().bangla) "। " else ". ", 1)
        } else ic.commitText(" ", 1)
        lastSpace = now
        afterInput()
    }

    private fun flushAvro() {
        if (avro.isNotEmpty()) { currentInputConnection?.finishComposingText(); avro.setLength(0) }
    }

    private fun updateAutoCap() {
        if (capsLock || symbols || currentId() != "qwerty") return
        val ei = currentInputEditorInfo ?: return
        val caps = currentInputConnection?.getCursorCapsMode(ei.inputType) ?: 0
        val ns = caps != 0
        if (ns != shifted) { shifted = ns; syncShift() }
    }

    private fun afterInput() {
        updateAutoCap(); updateSuggestions(); scheduleTranslate()
    }

    // ---------------------------------------------------------------- text helpers
    private fun currentWord(ic: InputConnection): String =
        (ic.getTextBeforeCursor(40, 0)?.toString() ?: "").takeLastWhile { Predictor.isWordChar(it) }

    private fun previousWord(ic: InputConnection, cur: String): String {
        val t = ic.getTextBeforeCursor(60, 0)?.toString() ?: return ""
        val head = t.dropLast(cur.length)
        val sep = head.takeLastWhile { !Predictor.isWordChar(it) }
        if (sep.any { it in "।.!?\n" }) return ""
        return head.dropLast(sep.length).takeLastWhile { Predictor.isWordChar(it) }
    }

    private fun learnCurrent() {
        val ic = currentInputConnection ?: return
        val cur = currentWord(ic)
        if (cur.isNotEmpty()) predictor.learn(previousWord(ic, cur), cur)
    }

    // ---------------------------------------------------------------- suggestions
    private fun updateSuggestions() {
        if (!::suggestRow.isInitialized) return
        val ic = currentInputConnection
        if (ic == null || passwordField || (!prefs.getBoolean("suggest", true) && !translateOn)) {
            comps = emptyList(); corr = emptyList(); showSave = false; curWord = ""; literalAvro = false
            renderChips(); return
        }
        val gen = ++suggestGen
        val cur = currentWord(ic)
        val prev = previousWord(ic, cur)
        curWord = cur
        literalAvro = avro.isNotEmpty()
        corr = emptyList()
        val suggestOn = prefs.getBoolean("suggest", true)
        val bangla = currentLayout().bangla
        if (!suggestOn) { comps = emptyList(); showSave = false }
        else if (cur.isEmpty()) { comps = predictor.next(prev, bangla, 6); showSave = false }
        else {
            comps = predictor.complete(cur, 5)
            val known = predictor.isKnown(cur)
            showSave = !known && cur.length >= 2
            if (!known && cur.length >= 2) {
                bg.execute {
                    val cs = predictor.corrections(cur, 4)
                    handler.post { if (gen == suggestGen) { corr = cs; renderChips() } }
                }
            }
        }
        renderChips()
    }

    private fun chip(text: String, color: Int, bold: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 17f; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        if (bold) setTypeface(null, Typeface.BOLD)
        setOnClickListener { onClick() }
    }

    private fun addChip(v: View) {
        if (suggestRow.childCount > 0) {
            suggestRow.addView(View(this).apply { setBackgroundColor(theme.hint) }, LinearLayout.LayoutParams(1, dp(20)))
        }
        suggestRow.addView(v, LinearLayout.LayoutParams(WRAP, MATCH))
    }

    private fun renderChips() {
        suggestRow.removeAllViews()
        val t = trans
        if (translateOn && t != null) addChip(chip("🌐 $t", theme.accent, true) { applyTranslation() })
        if (literalAvro && curWord.isNotEmpty()) addChip(chip(curWord, theme.text, true) { pick(curWord) })
        for (c in corr) if (c != curWord) addChip(chip("✓ $c", theme.text, false) { pick(c) })
        for (c in comps) if (c != curWord && c !in corr) addChip(chip(c, theme.text, false) { pick(c) })
        if (showSave && curWord.isNotEmpty()) addChip(chip("＋ \"$curWord\"", theme.accent, false) {
            predictor.addWord(curWord); toast("Saved to dictionary: $curWord"); updateSuggestions()
        })
        suggestScroll.scrollTo(0, 0)
    }

    private fun pick(text: String) {
        val ic = currentInputConnection ?: return
        val cur = currentWord(ic)
        val prev = previousWord(ic, cur)
        if (avro.isNotEmpty()) { avro.setLength(0); ic.commitText("$text ", 1) }
        else {
            if (cur.isNotEmpty()) ic.deleteSurroundingText(cur.length, 0)
            ic.commitText("$text ", 1)
        }
        predictor.learn(prev, text)
        afterInput()
    }

    // ---------------------------------------------------------------- live translation
    private val transRun = Runnable {
        val ic = currentInputConnection ?: return@Runnable
        val raw = sentenceBeforeCursor(ic)
        if (raw.trim().length < 2) { trans = null; renderChips(); return@Runnable }
        val gen = ++transGen
        translator.translate(raw.trim()) { r ->
            if (gen == transGen && r != null && r != raw.trim()) { trans = r; transSource = raw; renderChips() }
        }
    }

    private fun sentenceBeforeCursor(ic: InputConnection): String {
        val t = ic.getTextBeforeCursor(150, 0)?.toString() ?: ""
        val idx = maxOf(t.lastIndexOf('\n'), t.lastIndexOf('।'), t.lastIndexOf('.'), t.lastIndexOf('?'), t.lastIndexOf('!'))
        return t.substring(idx + 1)
    }

    private fun scheduleTranslate() {
        handler.removeCallbacks(transRun)
        if (!translateOn || passwordField) { trans = null; return }
        handler.postDelayed(transRun, 500)
    }

    private fun applyTranslation() {
        val ic = currentInputConnection ?: return
        val t = trans ?: return
        flushAvro()
        val raw = sentenceBeforeCursor(ic)
        if (raw != transSource) { toast("Text changed — try again"); return }
        val trailing = raw.takeLastWhile { it == ' ' }
        ic.deleteSurroundingText(raw.length, 0)
        ic.commitText(t + trailing, 1)
        trans = null
        afterInput()
    }
}
