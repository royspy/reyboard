package com.example.banglakeyboard

import android.content.Context
import java.io.File
import java.text.Normalizer
import java.util.TreeMap
import kotlin.math.abs

/**
 * Next-word prediction, completion, spell correction and the user dictionary.
 * Data: assets/words.txt  (word<TAB>freq)   assets/bigrams.txt (prev<TAB>next<TAB>count)
 * Replace/extend those two files with a large Bangla corpus for much better results.
 */
class Predictor private constructor(private val ctx: Context) {
    private val words = TreeMap<String, Int>()
    private val saved = TreeMap<String, Int>()
    private val usage = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private val ubigrams = HashMap<String, HashMap<String, Int>>()
    private var topBn: List<String> = emptyList()
    private var topEn: List<String> = emptyList()
    @Volatile private var ready = false
    private var dirty = 0
    private val userFile get() = File(ctx.filesDir, "user_data.txt")

    companion object {
        @Volatile private var inst: Predictor? = null
        fun get(c: Context): Predictor = inst ?: synchronized(this) {
            inst ?: Predictor(c.applicationContext).also { it.load(); inst = it }
        }
        fun isWordChar(c: Char): Boolean {
            if (Character.isLetterOrDigit(c) || c == '\u200c' || c == '\u200d') return true
            val t = Character.getType(c)
            return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
        }
    }

    private fun norm(s: String) = Normalizer.normalize(s.trim(), Normalizer.Form.NFC)
    private fun isBn(s: String) = s.isNotEmpty() && s[0] in '\u0980'..'\u09FF'

    private fun load() {
        Thread {
            val w = TreeMap<String, Int>()
            val b = HashMap<String, HashMap<String, Int>>()
            try {
                ctx.assets.open("words.txt").bufferedReader().useLines { seq ->
                    for (l in seq) { val p = l.split("\t"); if (p.size >= 2) w[norm(p[0]).lowercase()] = p[1].trim().toIntOrNull() ?: 1 }
                }
                ctx.assets.open("bigrams.txt").bufferedReader().useLines { seq ->
                    for (l in seq) {
                        val p = l.split("\t")
                        if (p.size >= 3) b.getOrPut(norm(p[0]).lowercase()) { HashMap() }[norm(p[1]).lowercase()] = p[2].trim().toIntOrNull() ?: 1
                    }
                }
            } catch (e: Exception) { /* keep what we have */ }
            synchronized(this@Predictor) {
                words.putAll(w); bigrams.putAll(b)
                try {
                    if (userFile.exists()) userFile.forEachLine { l ->
                        val p = l.split("\t")
                        when (p.getOrNull(0)) {
                            "W" -> saved[p[1]] = p[2].toInt()
                            "U" -> usage[p[1]] = p[2].toInt()
                            "B" -> ubigrams.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toInt()
                        }
                    }
                } catch (e: Exception) { }
                topBn = words.entries.filter { isBn(it.key) }.sortedByDescending { it.value }.take(12).map { it.key }
                topEn = words.entries.filter { !isBn(it.key) }.sortedByDescending { it.value }.take(12).map { it.key }
                ready = true
            }
        }.start()
    }

    private fun matchCase(model: String, w: String) =
        if (model.isNotEmpty() && model[0].isUpperCase() && w.isNotEmpty()) w[0].uppercase() + w.substring(1) else w

    @Synchronized fun isKnown(word: String): Boolean {
        if (!ready) return true // don't nag while loading
        val n = norm(word).lowercase()
        return words.containsKey(n) || saved.containsKey(n) || n.all { it.isDigit() }
    }

    @Synchronized fun complete(prefix: String, limit: Int): List<String> {
        if (!ready || prefix.isEmpty()) return emptyList()
        val p = norm(prefix).lowercase()
        val cand = HashMap<String, Int>()
        for (m in listOf(words, saved)) {
            for ((k, v) in m.subMap(p, p + "\uffff")) if (k != p) cand[k] = maxOf(cand[k] ?: 0, v)
        }
        return cand.entries.sortedByDescending { it.value + (usage[it.key] ?: 0) * 10 }
            .take(limit).map { matchCase(prefix, it.key) }
    }

    @Synchronized fun next(prev: String, bangla: Boolean, limit: Int): List<String> {
        if (!ready) return emptyList()
        val key = norm(prev).lowercase()
        val score = HashMap<String, Int>()
        bigrams[key]?.forEach { (k, v) -> score[k] = (score[k] ?: 0) + v }
        ubigrams[key]?.forEach { (k, v) -> score[k] = (score[k] ?: 0) + v * 5 }
        val out = score.entries.sortedByDescending { it.value }.map { it.key }.toMutableList()
        for (t in if (bangla) topBn else topEn) if (out.size < limit && t !in out) out.add(t)
        return out.take(limit)
    }

    /** Edit-distance (<=2) spell suggestions, ranked by distance then frequency. */
    @Synchronized fun corrections(word: String, limit: Int): List<String> {
        if (!ready) return emptyList()
        val w = norm(word).lowercase()
        if (w.length < 2) return emptyList()
        val maxD = if (w.length <= 3) 1 else 2
        val res = ArrayList<Triple<String, Int, Int>>()
        for (m in listOf(words, saved)) for ((k, v) in m) {
            if (abs(k.length - w.length) > maxD) continue
            val d = lev(w, k, maxD)
            if (d in 1..maxD) res.add(Triple(k, d, v))
        }
        res.sortWith(compareBy<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
        return res.take(limit).map { matchCase(word, it.first) }
    }

    private fun lev(a: String, b: String, max: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    @Synchronized fun learn(prev: String, word: String) {
        if (!ready) return
        val w = norm(word).lowercase()
        if (w.isEmpty() || !(words.containsKey(w) || saved.containsKey(w))) return
        usage[w] = (usage[w] ?: 0) + 1
        if (prev.isNotEmpty()) {
            val m = ubigrams.getOrPut(norm(prev).lowercase()) { HashMap() }
            m[w] = (m[w] ?: 0) + 1
        }
        if (++dirty >= 20) save()
    }

    @Synchronized fun addWord(word: String) {
        val w = norm(word).lowercase()
        if (w.isEmpty()) return
        saved[w] = 100
        save()
    }

    @Synchronized fun removeWord(word: String) { saved.remove(word); save() }
    @Synchronized fun savedWords(): List<String> = saved.keys.toList()

    @Synchronized fun save() {
        dirty = 0
        try {
            userFile.bufferedWriter().use { o ->
                saved.forEach { (k, v) -> o.write("W\t$k\t$v\n") }
                usage.forEach { (k, v) -> o.write("U\t$k\t$v\n") }
                ubigrams.forEach { (p, m) -> m.forEach { (k, v) -> o.write("B\t$p\t$k\t$v\n") } }
            }
        } catch (e: Exception) { }
    }
}
