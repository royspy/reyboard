package com.example.banglakeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class KbTheme(
    val id: String,
    val name: String,
    val bg: Int,
    val key: Int,
    val text: Int,
    val special: Int,
    val accent: Int,
    val hint: Int,
    val image: String? = null,   // absolute path of background image (gallery or AI generated)
    val keyAlpha: Int = 255,
    val builtin: Boolean = true
)

object Themes {
    val DEFAULTS = listOf(
        KbTheme("light", "Light", 0xFFECEFF1.toInt(), 0xFFFFFFFF.toInt(), 0xFF212121.toInt(), 0xFFCFD8DC.toInt(), 0xFF1A73E8.toInt(), 0xFF78909C.toInt()),
        KbTheme("dark", "Dark", 0xFF1B1B1F.toInt(), 0xFF2E2F33.toInt(), 0xFFEDEDED.toInt(), 0xFF3C3D42.toInt(), 0xFF8AB4F8.toInt(), 0xFF9AA0A6.toInt()),
        KbTheme("midnight", "Midnight", 0xFF0B1330.toInt(), 0xFF18244D.toInt(), 0xFFE8EEFF.toInt(), 0xFF24336B.toInt(), 0xFF5C8DFF.toInt(), 0xFF8FA3D9.toInt()),
        KbTheme("forest", "Forest", 0xFF14291C.toInt(), 0xFF21412D.toInt(), 0xFFE6F4EA.toInt(), 0xFF2F5A3F.toInt(), 0xFF4CD08A.toInt(), 0xFF8CBF9F.toInt()),
        KbTheme("rose", "Rose", 0xFFFCE4EC.toInt(), 0xFFFFFFFF.toInt(), 0xFF4A1530.toInt(), 0xFFF8BBD0.toInt(), 0xFFD81B60.toInt(), 0xFFB07A90.toInt())
    )

    private fun p(c: Context) = c.getSharedPreferences("kb_prefs", Context.MODE_PRIVATE)

    fun custom(c: Context): List<KbTheme> {
        val arr = try { JSONArray(p(c).getString("custom_themes", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<KbTheme>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                KbTheme(
                    o.getString("id"), o.getString("name"), o.getInt("bg"), o.getInt("key"),
                    o.getInt("text"), o.getInt("special"), o.getInt("accent"), o.getInt("hint"),
                    if (o.has("image")) o.getString("image") else null,
                    o.optInt("alpha", 255), false
                )
            )
        }
        return out
    }

    fun all(c: Context) = DEFAULTS + custom(c)

    fun current(c: Context): KbTheme {
        val id = p(c).getString("theme", "light")
        return all(c).firstOrNull { it.id == id } ?: DEFAULTS[0]
    }

    fun select(c: Context, id: String) { p(c).edit().putString("theme", id).apply() }

    private fun write(c: Context, list: List<KbTheme>) {
        val arr = JSONArray()
        list.forEach { t ->
            val o = JSONObject().put("id", t.id).put("name", t.name).put("bg", t.bg).put("key", t.key)
                .put("text", t.text).put("special", t.special).put("accent", t.accent)
                .put("hint", t.hint).put("alpha", t.keyAlpha)
            if (t.image != null) o.put("image", t.image)
            arr.put(o)
        }
        p(c).edit().putString("custom_themes", arr.toString()).apply()
    }

    fun saveCustom(c: Context, t: KbTheme) { write(c, custom(c).filter { it.id != t.id } + t); select(c, t.id) }

    fun delete(c: Context, id: String) {
        custom(c).firstOrNull { it.id == id }?.image?.let { java.io.File(it).delete() }
        write(c, custom(c).filter { it.id != id })
        if (p(c).getString("theme", "") == id) select(c, "light")
    }
}
