package com.example.banglakeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import androidx.core.graphics.ColorUtils

/** Canvas-drawn keyboard: themes + background images, long-press alternates, space-bar cursor swipe, delete repeat. */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKey(key: Key, shifted: Boolean)
        fun onText(text: String)
        fun onSpaceSwipe(steps: Int)
        fun onDeleteRepeat()
        fun onLongPressSpecial(key: Key)
    }

    var listener: Listener? = null

    var layout: KbLayout? = null
        set(v) { field = v; requestLayout(); rebuild(); invalidate() }
    var shifted = false
        set(v) { field = v; invalidate() }
    var capsLock = false
        set(v) { field = v; invalidate() }
    var keyHeightDp = 50
        set(v) { field = v; requestLayout(); rebuild(); invalidate() }
    /** 0 = full width, 1 = left-handed, 2 = right-handed */
    var oneHanded = 0
        set(v) { field = v; rebuild(); invalidate() }
    var haptic = true
    var sound = false
    var theme: KbTheme = Themes.DEFAULTS[0]
        set(v) { field = v; bg = v.image?.let { decode(it) }; invalidate() }

    private var bg: Bitmap? = null
    private class KR(val key: Key, val r: RectF)
    private val rects = ArrayList<KR>()
    private var pressed: KR? = null
    private var popupItems: List<String>? = null
    private var popupIdx = 0
    private var popupLeft = 0f
    private var popupItemW = 0f
    private var longFired = false
    private var swiped = false
    private var lastSwipeX = 0f
    private val handler = Handler(Looper.getMainLooper())
    private val longRun = Runnable { onLong() }
    private val repeatRun = object : Runnable {
        override fun run() { listener?.onDeleteRepeat(); handler.postDelayed(this, 45) }
    }

    private val d = resources.displayMetrics.density
    private val fs = d * resources.configuration.fontScale
    private fun dp(v: Float) = v * d
    private fun sp(v: Float) = v * fs

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private val subCache = HashMap<String, String>()

    private fun decode(path: String): Bitmap? = try {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
    } catch (e: Throwable) { null }

    private fun rowH() = dp(keyHeightDp.toFloat())
    private fun pad() = dp(6f)
    private fun gap() = dp(3f)

    private fun rebuild() {
        rects.clear()
        val lay = layout ?: return
        if (width == 0) return
        val ow = if (oneHanded == 0) 0f else width * 0.18f
        val left = if (oneHanded == 2) ow else 0f
        val total = width - ow
        val rh = rowH()
        lay.rows.forEachIndexed { ri, row ->
            var sum = 0f
            for (k in row) sum += k.weight
            var x = left
            val top = pad() + ri * rh
            for (k in row) {
                val w = total * k.weight / sum
                rects.add(KR(k, RectF(x + gap() / 2, top + gap() / 2, x + w - gap() / 2, top + rh - gap() / 2)))
                x += w
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); rebuild() }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val rows = layout?.rows?.size ?: 4
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (pad() * 2 + rows * rowH()).toInt())
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    private fun display(s: String): String {
        if (s.isEmpty()) return s
        val t = Character.getType(s.codePointAt(0))
        return if (t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()) "\u25CC$s" else s
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        p.style = Paint.Style.FILL
        p.color = t.bg
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        bg?.let { b ->
            val s = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
            m.reset(); m.postScale(s, s); m.postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2)
            c.drawBitmap(b, m, bmpPaint)
            p.color = 0x33000000
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        }
        for (kr in rects) drawKey(c, kr)
        drawPopup(c)
    }

    private fun drawKey(c: Canvas, kr: KR) {
        val k = kr.key
        val t = theme
        val r = kr.r
        val special = k.code != 0 && k.code != Codes.SPACE
        var fill = when {
            k.code == Codes.ENTER -> t.accent
            special -> t.special
            else -> t.key
        }
        if (k.code == Codes.SHIFT && (shifted || capsLock)) fill = t.accent
        if (kr === pressed) fill = ColorUtils.blendARGB(fill, t.accent, 0.45f)
        val a = if (t.image != null) t.keyAlpha else 255
        p.style = Paint.Style.FILL
        p.color = withAlpha(fill, a)
        c.drawRoundRect(r, dp(7f), dp(7f), p)

        val label = when (k.code) {
            Codes.SHIFT -> if (capsLock) "⇪" else "⇧"
            Codes.DEL -> "⌫"
            Codes.ENTER -> "⏎"
            Codes.SYMBOLS -> "?১২৩"
            Codes.ABC -> "ABC"
            Codes.LANG -> "\uD83C\uDF10"
            Codes.EMOJI -> "☺"
            Codes.SPACE -> layout?.name ?: ""
            else -> display(k.out(shifted))
        }
        tp.textSize = when {
            k.code == Codes.SPACE -> sp(12f)
            k.code != 0 -> sp(16f)
            else -> sp(21f)
        }
        tp.color = if (k.code == Codes.ENTER) Color.WHITE else t.text
        val tw = tp.measureText(label)
        val maxW = r.width() - dp(6f)
        if (tw > maxW && tw > 0) tp.textSize = tp.textSize * maxW / tw
        c.drawText(label, r.centerX(), r.centerY() - (tp.descent() + tp.ascent()) / 2, tp)

        if (k.code == 0) {
            hp.color = t.hint
            hp.textSize = sp(10f)
            val hint = if (!shifted) (k.shift?.takeIf { it != k.label.uppercase() } ?: k.hints.firstOrNull()) else null
            if (hint != null) c.drawText(display(hint), r.right - dp(4f), r.top + dp(12f), hp)
            val lay = layout
            if (lay != null && lay.phonetic && k.label[0].isLetterOrDigit()) {
                val o = k.out(shifted)
                val sub = subCache.getOrPut(o) { AvroEngine.convert(o) }
                c.drawText(display(sub), r.right - dp(4f), r.bottom - dp(5f), hp)
            }
        }
    }

    private fun drawPopup(c: Canvas) {
        val items = popupItems ?: return
        val kr = pressed ?: return
        val t = theme
        val top = maxOf(0f, kr.r.top - dp(6f))
        val rect = RectF(popupLeft, top, popupLeft + popupItemW * items.size, kr.r.bottom)
        p.color = t.accent
        c.drawRoundRect(rect, dp(9f), dp(9f), p)
        items.forEachIndexed { i, s ->
            val cell = RectF(popupLeft + i * popupItemW, top, popupLeft + (i + 1) * popupItemW, kr.r.bottom)
            if (i == popupIdx) {
                p.color = Color.WHITE
                c.drawRoundRect(RectF(cell.left + dp(2f), cell.top + dp(3f), cell.right - dp(2f), cell.bottom - dp(3f)), dp(7f), dp(7f), p)
            }
            tp.color = if (i == popupIdx) Color.BLACK else Color.WHITE
            tp.textSize = sp(21f)
            c.drawText(display(s), cell.centerX(), cell.centerY() - (tp.descent() + tp.ascent()) / 2, tp)
        }
    }

    private fun find(x: Float, y: Float): KR? {
        var best: KR? = null
        var bd = Float.MAX_VALUE
        for (kr in rects) {
            if (kr.r.contains(x, y)) return kr
            val dx = x - kr.r.centerX(); val dy = y - kr.r.centerY()
            val dist = dx * dx + dy * dy
            if (dist < bd) { bd = dist; best = kr }
        }
        // finger is in a gap: snap to nearest key if it is close enough
        return if (best != null && bd < dp(40f) * dp(40f)) best else null
    }

    private fun feedback() {
        if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (sound) playSoundEffect(SoundEffectConstants.CLICK)
    }

    private fun onLong() {
        val kr = pressed ?: return
        val k = kr.key
        if (k.code != 0) {
            if (k.code == Codes.LANG || k.code == Codes.SPACE || k.code == Codes.EMOJI) {
                longFired = true; feedback(); listener?.onLongPressSpecial(k)
            }
            return
        }
        val alts = ArrayList<String>()
        val other = if (shifted) k.label else k.shift
        if (other != null && other != k.out(shifted) && other != k.label.uppercase()) alts.add(other)
        alts.addAll(k.hints)
        if (alts.isEmpty()) return
        popupItems = alts
        popupIdx = 0
        popupItemW = maxOf(kr.r.width(), dp(44f))
        val tw = popupItemW * alts.size
        popupLeft = (kr.r.centerX() - tw / 2).coerceIn(0f, maxOf(0f, width - tw))
        longFired = true
        feedback()
        invalidate()
    }

    private fun reset() {
        handler.removeCallbacks(longRun); handler.removeCallbacks(repeatRun)
        pressed = null; popupItems = null; longFired = false; swiped = false
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed = find(e.x, e.y); longFired = false; swiped = false; lastSwipeX = e.x
                val k = pressed?.key
                if (k != null) {
                    feedback()
                    if (k.code == Codes.DEL) {
                        listener?.onKey(k, shifted); longFired = true
                        handler.postDelayed(repeatRun, 400)
                    } else handler.postDelayed(longRun, 380)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val k = pressed?.key
                val items = popupItems
                if (items != null) {
                    popupIdx = ((e.x - popupLeft) / popupItemW).toInt().coerceIn(0, items.size - 1)
                    invalidate()
                } else if (k?.code == Codes.SPACE) {
                    val step = dp(14f)
                    val n = ((e.x - lastSwipeX) / step).toInt()
                    if (n != 0) {
                        swiped = true; handler.removeCallbacks(longRun)
                        listener?.onSpaceSwipe(n); lastSwipeX += n * step
                    }
                } else if (k != null && !longFired) {
                    val nk = find(e.x, e.y)
                    if (nk !== pressed) {
                        handler.removeCallbacks(longRun)
                        pressed = nk
                        if (nk != null) handler.postDelayed(longRun, 380)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val k = pressed?.key
                val items = popupItems
                if (items != null) listener?.onText(items[popupIdx])
                else if (k != null && !longFired && !swiped) listener?.onKey(k, shifted)
                reset()
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }
}
