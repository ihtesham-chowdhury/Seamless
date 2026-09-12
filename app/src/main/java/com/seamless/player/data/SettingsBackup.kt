package com.seamless.player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the app remembers about how you like it, as one readable file.
 *
 * What goes in: every setting, the folders you have hidden or locked, what you have pinned,
 * your favourites, the thumbnails you chose, the order each screen is in, which hints you have
 * seen — and the saved playback positions, which are worth keeping across a reinstall on the
 * same phone even though they mean nothing on another one.
 *
 * What stays out: the OpenSubtitles key, password and session token. Those live in their own
 * preferences file for exactly this reason. A backup is a file people mail to themselves and
 * drop in cloud storage, and a key is the user's to place rather than this app's to copy
 * around. The same reasoning keeps that file out of Android's own cloud backup; see
 * res/xml/backup_rules.xml.
 *
 * The format is JSON with the type written next to each value, because SharedPreferences is
 * typed and a string where an int is expected throws the moment anything reads it. A version
 * number is the first thing in the file, so a later format can be recognised instead of being
 * half-read by an older build.
 */
object SettingsBackup {

    /** What the file picker is told to make, and the one type that is certainly right. */
    const val MIME = "application/json"

    private const val FORMAT = 1
    private const val SETTINGS = "settings"
    private const val RESUME = "resume"

    /** A name with the date in it, so a folder of these sorts itself. */
    fun suggestedName(): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "seamless-backup-$day.json"
    }

    /** The whole of it, as text ready to be written wherever the user chose. */
    fun write(context: Context): String {
        val app = context.applicationContext
        val file = JSONObject()
        file.put("format", FORMAT)
        file.put("app", "Seamless")
        file.put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        file.put(SETTINGS, encode(prefs(app, SETTINGS)))
        file.put(RESUME, encode(prefs(app, RESUME)))
        return file.toString(2)
    }

    /**
     * Puts a backup back, and says how many values it carried.
     *
     * Replaces rather than merges: half of one set of settings and half of another is a state
     * the user never chose and cannot reason about. Anything the file does not name is left at
     * its default, which is what a fresh install would have given it.
     *
     * Throws if the text is not a backup, or is from a later format than this build knows.
     */
    fun read(context: Context, text: String): Int {
        val app = context.applicationContext
        val file = JSONObject(text)
        val format = file.optInt("format", 0)
        require(format in 1..FORMAT) { "not a Seamless backup, or from a newer version" }
        var restored = decode(prefs(app, SETTINGS), file.optJSONObject(SETTINGS))
        restored += decode(prefs(app, RESUME), file.optJSONObject(RESUME))
        return restored
    }

    private fun prefs(context: Context, name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun encode(from: SharedPreferences): JSONObject {
        val out = JSONObject()
        from.all.forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is Boolean -> entry.put("type", "bool").put("value", value)
                is Int -> entry.put("type", "int").put("value", value)
                is Long -> entry.put("type", "long").put("value", value)
                is Float -> entry.put("type", "float").put("value", value.toDouble())
                is String -> entry.put("type", "string").put("value", value)
                is Set<*> -> entry.put("type", "set")
                    .put("value", JSONArray(value.filterIsInstance<String>()))
                // A type this build does not write cannot be one this build needs back.
                else -> return@forEach
            }
            out.put(key, entry)
        }
        return out
    }

    private fun decode(target: SharedPreferences, from: JSONObject?): Int {
        if (from == null) return 0
        var restored = 0
        // Committed rather than applied: the caller rebuilds the screen from these values as
        // soon as this returns, and apply() only promises to get there eventually.
        target.edit(commit = true) {
            clear()
            from.keys().forEach { key ->
                val entry = from.optJSONObject(key) ?: return@forEach
                when (entry.optString("type")) {
                    "bool" -> putBoolean(key, entry.optBoolean("value"))
                    "int" -> putInt(key, entry.optInt("value"))
                    "long" -> putLong(key, entry.optLong("value"))
                    "float" -> putFloat(key, entry.optDouble("value").toFloat())
                    "string" -> putString(key, entry.optString("value"))
                    "set" -> {
                        val values = entry.optJSONArray("value") ?: JSONArray()
                        putStringSet(
                            key,
                            (0 until values.length()).mapTo(LinkedHashSet()) { values.optString(it) },
                        )
                    }
                    else -> return@forEach
                }
                restored++
            }
        }
        return restored
    }
}
