package com.ghaffar.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

object Prayer {
    /** English key used by the timings API, Urdu name for speaking. */
    val names = listOf(
        "Fajr" to "فجر",
        "Dhuhr" to "ظہر",
        "Asr" to "عصر",
        "Maghrib" to "مغرب",
        "Isha" to "عشاء"
    )

    /** Today's prayer times for the saved city, or null if offline. Call off the main thread. */
    fun fetch(c: Context): Map<String, String>? = try {
        val city = URLEncoder.encode(P.get(c, "city", "Karachi"), "UTF-8")
        val country = URLEncoder.encode(P.get(c, "country", "Pakistan"), "UTF-8")
        val method = P.get(c, "method", "1")
        val url = URL("https://api.aladhan.com/v1/timingsByCity?city=$city&country=$country&method=$method")
        val con = url.openConnection() as HttpURLConnection
        con.connectTimeout = 15000
        con.readTimeout = 20000
        val t = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            .getJSONObject("data").getJSONObject("timings")
        names.associate { (en, _) -> en to t.getString(en).take(5) }
    } catch (e: Exception) {
        null
    }

    /** Sets all upcoming alarms. Call off the main thread. */
    fun refresh(c: Context) {
        val times = fetch(c)
        if (times != null) {
            names.forEachIndexed { i, (en, ur) ->
                val at = timeToday(times[en] ?: return@forEachIndexed)
                if (at > System.currentTimeMillis()) setAlarm(c, at, 100 + i, "PRAYER", ur)
            }
        }
        scheduleDaily(c, P.get(c, "morning", "05:45"), 200, "MORNING")
        scheduleDaily(c, P.get(c, "bedtime", "22:30"), 201, "SLEEP")

        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        setAlarm(c, cal.timeInMillis, 99, "REFRESH", "")
    }

    private fun timeToday(hhmm: String): Long {
        val p = hhmm.split(":")
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, p[0].toInt()); set(Calendar.MINUTE, p[1].toInt())
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun scheduleDaily(c: Context, hhmm: String, code: Int, action: String) {
        if (!Regex("\\d{1,2}:\\d{2}").matches(hhmm.trim())) return
        var at = timeToday(hhmm.trim())
        if (at <= System.currentTimeMillis()) at += 24L * 60 * 60 * 1000
        setAlarm(c, at, code, action, "")
    }

    fun setAlarm(c: Context, at: Long, code: Int, action: String, name: String) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(c, AlarmReceiver::class.java)
            .setAction("com.ghaffar.ai.$action")
            .putExtra("name", name)
        val pi = PendingIntent.getBroadcast(
            c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}
