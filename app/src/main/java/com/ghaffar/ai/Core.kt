package com.ghaffar.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simple saved settings. */
object P {
    private fun sp(c: Context) = c.getSharedPreferences("ghaffar", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun set(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    /** Custom reciter folder (if typed) wins over the list choice. */
    fun reciter(c: Context): String {
        val custom = get(c, "reciter_custom").trim()
        return if (custom.isNotEmpty()) custom else get(c, "reciter", "Alafasy_128kbps")
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

/** Quran audio from everyayah.com, ayah by ayah, same folder naming for every reciter. */
object Quran {
    val reciters = linkedMapOf(
        "مشاری راشد العفاسی" to "Alafasy_128kbps",
        "عبدالرحمن السدیس" to "Abdurrahmaan_As-Sudais_192kbps",
        "محمود خلیل الحصری" to "Husary_128kbps",
        "عبدالباسط (مرتل)" to "Abdul_Basit_Murattal_192kbps",
        "ماہر المعیقلی" to "Maher_AlMuaiqly_128kbps",
        "سعود الشریم" to "Saood_ash-Shuraym_128kbps"
    )

    fun url(reciter: String, surah: Int, ayah: Int) =
        "https://everyayah.com/data/$reciter/" + "%03d%03d.mp3".format(surah, ayah)

    fun surah(reciter: String, surah: Int, ayahCount: Int) =
        (1..ayahCount).map { url(reciter, surah, it) }

    /** Surah Rahman (55) has 78 ayahs. */
    fun sleep(reciter: String) = surah(reciter, 55, 78)

    /** Ayatul Kursi (2:255) then Surah Yaseen (36) with 83 ayahs. */
    fun morning(reciter: String) = listOf(url(reciter, 2, 255)) + surah(reciter, 36, 83)
}

/** Talks to Gemini over its normal web API. */
object Gemini {
    private const val SYSTEM =
        "You are Ghaffar AI, a friendly and helpful assistant. Reply in the same language the user " +
        "writes in (Urdu in Urdu script, or English). Keep answers clear and short. " +
        "For Islamic questions answer carefully, mention the Quran or Hadith source when you can, " +
        "and suggest asking a qualified scholar for fatwa-type questions."

    fun ask(c: Context, history: List<Pair<String, String>>, text: String): String {
        val key = P.get(c, "api_key").trim()
        if (key.isEmpty()) return "پہلے سیٹنگ میں Gemini API Key ڈالیں۔"
        val model = P.get(c, "model", "gemini-2.5-flash").trim().ifEmpty { "gemini-2.5-flash" }
        return try {
            val contents = JSONArray()
            for ((role, t) in history) contents.put(part(role, t))
            contents.put(part("user", text))
            val body = JSONObject()
                .put("contents", contents)
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
                )
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            val con = url.openConnection() as HttpURLConnection
            con.requestMethod = "POST"
            con.connectTimeout = 20000
            con.readTimeout = 60000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val ok = con.responseCode in 200..299
            val raw = (if (ok) con.inputStream else con.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = JSONObject(raw)
            if (!ok) {
                "خرابی: " + json.optJSONObject("error")?.optString("message", raw)
            } else {
                json.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text")
            }
        } catch (e: Exception) {
            "انٹرنیٹ یا سرور کا مسئلہ: ${e.message}"
        }
    }

    private fun part(role: String, t: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", t)))
}
