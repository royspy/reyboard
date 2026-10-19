package com.example.banglakeyboard

object Codes {
    const val SHIFT = -1
    const val SYMBOLS = -2
    const val ABC = -3
    const val ENTER = -4
    const val DEL = -5
    const val LANG = -6
    const val EMOJI = -7
    const val SPACE = 32
}

/**
 * label  = normal output, shift = shifted output (null -> label.uppercase()),
 * hints  = long-press alternates.
 */
data class Key(
    val label: String,
    val shift: String? = null,
    val hints: List<String> = emptyList(),
    val code: Int = 0,
    val weight: Float = 1f
) {
    fun out(shifted: Boolean): String = if (shifted) (shift ?: label.uppercase()) else label
}

class KbLayout(
    val id: String,
    val name: String,
    val rows: List<List<Key>>,
    val bangla: Boolean,
    val phonetic: Boolean
)

/**
 * Layout spec mini-language (one string per row, keys separated by spaces):
 *   label|shiftLabel|hint1§hint2   e.g.  ক|খ|;§'
 *   {SHIFT:1.5} {DEL} {SYM} {ABC} {LANG} {EMOJI} {SPACE:4} {ENTER}   (":w" = width weight)
 *
 * !! The Bijoy and Windows tables below are written from memory of the standard layouts.
 * !! Compare them with your muscle memory and just edit the strings if any key differs.
 */
object Layouts {
    val ALL = listOf("avro", "bijoy", "windows", "qwerty")

    fun title(id: String) = when (id) {
        "avro" -> "অভ্র (ফোনেটিক)"
        "bijoy" -> "বিজয়"
        "windows" -> "Windows বাংলা"
        "qwerty" -> "English QWERTY"
        else -> id
    }

    private const val YY = "\u09AF\u09BC"   // য়
    private const val DR = "\u09A1\u09BC"   // ড়
    private const val DHR = "\u09A2\u09BC"  // ঢ়

    private val cache = HashMap<String, KbLayout>()

    private fun parse(spec: String): List<Key> =
        spec.trim().split(" ").filter { it.isNotEmpty() }.map { t ->
            if (t.length > 2 && t.startsWith("{") && t.endsWith("}")) {
                val body = t.substring(1, t.length - 1)
                val name = body.substringBefore(":")
                val w = body.substringAfter(":", "1").toFloat()
                val code = when (name) {
                    "SHIFT" -> Codes.SHIFT
                    "DEL" -> Codes.DEL
                    "ENTER" -> Codes.ENTER
                    "SYM" -> Codes.SYMBOLS
                    "ABC" -> Codes.ABC
                    "LANG" -> Codes.LANG
                    "EMOJI" -> Codes.EMOJI
                    else -> Codes.SPACE
                }
                Key(name, null, emptyList(), code, w)
            } else {
                val p = t.split("|")
                Key(
                    p[0],
                    p.getOrNull(1)?.ifEmpty { null },
                    p.getOrNull(2)?.split("§")?.filter { it.isNotEmpty() } ?: emptyList()
                )
            }
        }

    fun get(id: String, numberRow: Boolean): KbLayout =
        cache.getOrPut("$id/$numberRow") { build(id, numberRow) }

    private const val BN_BOTTOM = "{SYM:1.3} {LANG} {EMOJI} ,||ৎ§ঁ§ঃ§;§' {SPACE:3.3} ।||.§!§?§- {ENTER:1.4}"
    private const val EN_BOTTOM = "{SYM:1.3} {LANG} {EMOJI} ,||;§: {SPACE:3.3} .||!§?§…§- {ENTER:1.4}"
    private const val NUM_PLAIN = "1 2 3 4 5 6 7 8 9 0"

    private fun build(id: String, numberRow: Boolean): KbLayout {
        val qwertyRows = listOf(
            "q|Q|1 w|W|2 e|E|3 r|R|4 t|T|5 y|Y|6 u|U|7 i|I|8 o|O|9 p|P|0",
            "a|A|@ s|S|# d|D|৳ f|F|% g|G|& h|H|- j|J|+ k|K|( l|L|)",
            "{SHIFT:1.5} z|Z|* x|X|\" c|C|' v|V|: b|B|; n|N|! m|M|? {DEL:1.5}"
        )
        return when (id) {
            "bijoy" -> KbLayout(
                id, "বিজয়",
                listOf(
                    "১|! ২|@ ৩|# ৪|৳ ৫|% ৬|^ ৭|& ৮|* ৯|( ০|)",
                    "ঙ|ং য|${YY} ড|ঢ প|ফ ট|ঠ চ|ছ জ|ঝ হ|ঞ গ|ঘ ${DR}|${DHR}|[§]",
                    "ৃ|ঋ ু|ূ ি|ী া|অ ্|। ব|ভ ক|খ ত|থ দ|ধ",
                    "{SHIFT:1.4} ্র|্য ও|ঔ ে|ৈ র|ল ন|ণ স|ষ ম|শ {DEL:1.4}",
                    BN_BOTTOM
                ).map(::parse), true, false
            )
            // Windows' built-in Bangla keyboard is InScript-based
            "windows" -> KbLayout(
                id, "বাংলা (Windows)",
                listOf(
                    "১|! ২|@ ৩|# ৪|৳ ৫|% ৬|^ ৭|& ৮|* ৯|( ০|)",
                    "ৌ|ঔ ৈ|ঐ া|আ ী|ঈ ূ|ঊ ব|ভ হ|ঙ গ|ঘ দ|ধ জ|ঝ ড|ঢ|ঞ",
                    "ো|ও ে|এ ্|অ ি|ই ু|উ প|ফ র|ঋ ক|খ ত|থ চ|ছ ট|ঠ",
                    "{SHIFT:1.2} ং|ঁ ম|ণ ন ৎ ল স|শ ষ য|${YY} {DEL:1.2}",
                    BN_BOTTOM
                ).map(::parse), true, false
            )
            "qwerty" -> KbLayout(
                id, "English",
                (if (numberRow) listOf(NUM_PLAIN) else emptyList<String>())
                    .plus(qwertyRows).plus(EN_BOTTOM).map(::parse), false, false
            )
            "symbols" -> KbLayout(
                id, "?123",
                listOf(
                    NUM_PLAIN,
                    "@ # ৳ % & - + ( ) /",
                    """= * " ' : ; ! ? {DEL:1.5}""",
                    "{ABC:1.5} , {SPACE:4} . {ENTER:1.5}"
                ).map(::parse), false, false
            )
            else -> KbLayout( // avro phonetic: QWERTY keys + transliteration engine
                "avro", "অভ্র",
                (if (numberRow) listOf(NUM_PLAIN) else emptyList<String>())
                    .plus(qwertyRows).plus(BN_BOTTOM).map(::parse), true, true
            )
        }
    }
}
