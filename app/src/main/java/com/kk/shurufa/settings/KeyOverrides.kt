package com.kk.shurufa.settings

import com.kk.shurufa.Prefs
import com.kk.shurufa.keyboard.KeyOverride
import org.json.JSONObject

/** Key edits made in 键位编辑, stored per layout as JSON: { "q": {"u":"1","d":"","l":null,...,"long":["…"]} }. */
object KeyOverrides {
    private fun key(layout: String) = "key_ovr_$layout"

    fun load(prefs: Prefs, layout: String): Map<String, KeyOverride> {
        val raw = prefs.sp.getString(key(layout), null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { k ->
                val v = o.getJSONObject(k)
                fun s(n: String) = if (v.has(n) && !v.isNull(n)) v.getString(n) else null
                val long = if (v.has("long") && !v.isNull("long")) v.getJSONArray("long").let { a -> (0 until a.length()).map { a.getString(it) } } else null
                KeyOverride(s("u"), s("d"), s("l"), s("r"), long)
            }
        }.getOrDefault(emptyMap())
    }

    fun save(prefs: Prefs, layout: String, main: String, o: KeyOverride?) {
        val raw = prefs.sp.getString(key(layout), null)
        val obj = runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())
        if (o == null) obj.remove(main) else obj.put(main, JSONObject().apply {
            put("u", o.up ?: JSONObject.NULL); put("d", o.down ?: JSONObject.NULL)
            put("l", o.left ?: JSONObject.NULL); put("r", o.right ?: JSONObject.NULL)
            put("long", o.long?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
        })
        prefs.put(key(layout), obj.toString())
    }

    fun clear(prefs: Prefs, layout: String) = prefs.sp.edit().remove(key(layout)).apply()
}
