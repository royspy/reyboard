package com.example.banglakeyboard

/** Rule-based Avro-style phonetic transliteration (Latin -> Bangla). Longest match wins. */
object AvroEngine {
    private const val HASANTA = '\u09CD'
    private const val YY = "\u09AF\u09BC"
    private const val DR = "\u09A1\u09BC"
    private const val DHR = "\u09A2\u09BC"

    // kind 0 = consonant, 1 = vowel (a = independent form, b = kar/sign), 2 = other
    private class R(val k: String, val kind: Int, val a: String, val b: String = "")

    private val rules: List<R> = buildList {
        fun c(k: String, v: String) { add(R(k, 0, v)) }
        fun v(k: String, ind: String, kar: String) { add(R(k, 1, ind, kar)) }
        fun o(k: String, v: String) { add(R(k, 2, v)) }

        c("kkh", "ক্ষ"); c("gg", "জ্ঞ"); c("ngg", "ঙ্গ")
        c("NG", "ঞ"); c("Ng", "ঙ")
        c("kh", "খ"); c("k", "ক"); c("gh", "ঘ"); c("g", "গ")
        c("chh", "ছ"); c("ch", "ছ"); c("c", "চ"); c("jh", "ঝ"); c("j", "জ")
        c("Th", "ঠ"); c("T", "ট"); c("Dh", "ঢ"); c("D", "ড"); c("N", "ণ")
        c("th", "থ"); c("t", "ত"); c("dh", "ধ"); c("d", "দ"); c("n", "ন")
        c("ph", "ফ"); c("f", "ফ"); c("p", "প"); c("bh", "ভ"); c("v", "ভ"); c("b", "ব"); c("m", "ম")
        c("z", "য"); c("r", "র"); c("l", "ল")
        c("sh", "শ"); c("Sh", "ষ"); c("S", "শ"); c("s", "স"); c("h", "হ")
        c("Rh", DHR); c("R", DR); c("y", YY); c("q", "ক"); c("x", "ক্স")

        v("rri", "ঋ", "ৃ"); v("oi", "ঐ", "ৈ"); v("ou", "ঔ", "ৌ")
        v("ii", "ঈ", "ী"); v("uu", "ঊ", "ূ")
        v("o", "অ", ""); v("O", "ও", "ো"); v("a", "আ", "া")
        v("i", "ই", "ি"); v("I", "ঈ", "ী"); v("u", "উ", "ু"); v("U", "ঊ", "ূ"); v("e", "এ", "ে")

        o("ng", "ং"); o("w", "ও")
    }.sortedByDescending { it.k.length }

    fun convert(s: String): String {
        val sb = StringBuilder()
        var i = 0
        var prevCons = false
        while (i < s.length) {
            val r = rules.firstOrNull { s.startsWith(it.k, i) }
            if (r == null) { sb.append(s[i]); prevCons = false; i++; continue }
            when (r.kind) {
                0 -> { if (prevCons) sb.append(HASANTA); sb.append(r.a); prevCons = true }
                1 -> { sb.append(if (prevCons) r.b else r.a); prevCons = false }
                else -> { sb.append(r.a); prevCons = false }
            }
            i += r.k.length
        }
        return sb.toString()
    }
}
