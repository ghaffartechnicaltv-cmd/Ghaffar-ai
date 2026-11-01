package com.ghaffar.ai

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contracts.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var chat: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val history = mutableListOf<Pair<String, String>>()

    private val mic = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) {
            input.setText(said)
            send()
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        buildUi()
        askPermissions()
        Thread { try { Prayer.refresh(this) } catch (e: Exception) { } }.start()
    }

    // ---------------- screen ----------------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(TextView(this).apply {
            text = "غفار اے آئی"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(label: String, f: () -> Unit) = row.addView(Button(this).apply {
            text = label; setOnClickListener { f() }
        })
        b("⚙️ سیٹنگ") { settings() }
        b("🌙 سونے کا موڈ") { AudioService.quran(this, "sleep"); toast("سورہ رحمٰن شروع ہو گئی") }
        b("🌅 صبح کی تلاوت") { AudioService.quran(this, "morning") }
        b("🕌 نماز") { prayerDialog() }
        b("📖 آج کی آیت") { askAi("قرآن کی کوئی ایک آیت عربی میں لکھیں، اس کا آسان اردو ترجمہ اور دو لائن میں مختصر تشریح بتائیں۔") }
        b("🛑 روکیں") { AudioService.stop(this); tts?.stop() }
        root.addView(HorizontalScrollView(this).apply { addView(row) })

        scroll = ScrollView(this)
        chat = TextView(this).apply {
            textSize = 17f
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setTextIsSelectable(true)
            text = "السلام علیکم! میں غفار اے آئی ہوں۔ کچھ بھی پوچھیں، لکھ کر یا 🎤 دبا کر بول کر۔\n"
        }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply { hint = "یہاں لکھیں…"; maxLines = 4 }
        bottom.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(Button(this).apply { text = "🎤"; setOnClickListener { listen() } })
        bottom.addView(Button(this).apply { text = "بھیجیں"; setOnClickListener { send() } })
        root.addView(bottom)

        setContentView(root)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun append(who: String, t: String) {
        chat.append("\n$who: $t\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------- chat ----------------
    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        askAi(t)
    }

    private fun askAi(text: String) {
        append("آپ", text)
        Thread {
            val reply = Gemini.ask(this, history.takeLast(12), text)
            runOnUiThread {
                history.add("user" to text)
                history.add("model" to reply)
                append("غفار", reply)
                speak(reply)
            }
        }.start()
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, P.get(this@MainActivity, "voice_lang", "ur-PK"))
            putExtra(RecognizerIntent.EXTRA_PROMPT, "بولیں…")
        }
        try { mic.launch(i) } catch (e: Exception) { toast("اس فون میں آواز پہچاننے کی سہولت نہیں ملی۔") }
    }

    private fun speak(text: String) {
        val e = tts ?: return
        if (!ttsReady) return
        val clean = text.replace("*", "").replace("#", "").take(1500)
        val urdu = clean.any { it in '؀'..'ۿ' }
        val r = e.setLanguage(if (urdu) Locale("ur", "PK") else Locale.US)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("اردو آواز نہیں ملی۔ فون کی سیٹنگ میں Google Text-to-speech میں اردو ڈاؤن لوڈ کریں۔")
            if (urdu) return
        }
        e.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    // ---------------- prayer ----------------
    private fun prayerDialog() {
        Thread {
            val times = Prayer.fetch(this)
            var done = 0
            val cal = Calendar.getInstance()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            for (d in 0 until 7) {
                val day = fmt.format(cal.time)
                Prayer.names.forEach { (_, ur) -> if (P.get(this, "done_${day}_$ur") == "1") done++ }
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
            val msg = StringBuilder()
            if (times == null) msg.append("نماز کے اوقات نہیں ملے۔ انٹرنیٹ اور شہر چیک کریں۔\n")
            else Prayer.names.forEach { (en, ur) -> msg.append("$ur: ${times[en]}\n") }
            msg.append("\nپچھلے 7 دن میں نمازیں: $done / 35")
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("نماز").setMessage(msg.toString())
                    .setPositiveButton("ٹھیک ہے", null).show()
            }
        }.start()
    }

    // ---------------- settings ----------------
    private fun settings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        fun field(label: String, key: String, def: String = ""): EditText {
            box.addView(TextView(this).apply { text = label })
            val e = EditText(this)
            e.setSingleLine()
            e.setText(P.get(this, key, def))
            box.addView(e)
            return e
        }

        val apiKey = field("Gemini API Key (برین)", "api_key")
        val model = field("Gemini ماڈل", "model", "gemini-2.5-flash")
        val city = field("شہر (انگلش میں)", "city", "Karachi")
        val country = field("ملک (انگلش میں)", "country", "Pakistan")
        val morning = field("صبح تلاوت کا وقت (مثلاً 05:45)", "morning", "05:45")
        val bed = field("سونے کی تلاوت کا وقت (مثلاً 22:30، خالی = بند)", "bedtime", "22:30")
        val voiceLang = field("بولنے کی زبان (ur-PK یا en-US)", "voice_lang", "ur-PK")

        box.addView(TextView(this).apply { text = "قاری" })
        val names = Quran.reciters.keys.toList()
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val saved = P.get(this, "reciter", "Alafasy_128kbps")
        spinner.setSelection(Quran.reciters.values.indexOf(saved).coerceAtLeast(0))
        box.addView(spinner)

        val custom = field("اپنا قاری (everyayah.com کا فولڈر نام، خالی چھوڑ سکتے ہیں)", "reciter_custom")

        AlertDialog.Builder(this)
            .setTitle("سیٹنگ")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("محفوظ کریں") { _, _ ->
                P.set(this, "api_key", apiKey.text.toString().trim())
                P.set(this, "model", model.text.toString().trim())
                P.set(this, "city", city.text.toString().trim())
                P.set(this, "country", country.text.toString().trim())
                P.set(this, "morning", morning.text.toString().trim())
                P.set(this, "bedtime", bed.text.toString().trim())
                P.set(this, "voice_lang", voiceLang.text.toString().trim())
                P.set(this, "reciter", Quran.reciters.values.toList()[spinner.selectedItemPosition])
                P.set(this, "reciter_custom", custom.text.toString().trim())
                Thread { try { Prayer.refresh(this) } catch (e: Exception) { } }.start()
                toast("محفوظ ہو گیا")
            }
            .setNeutralButton("بیٹری پابندی ہٹائیں") { _, _ -> batteryFree() }
            .show()
    }

    // ---------------- permissions ----------------
    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        if (Build.VERSION.SDK_INT >= 31) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) } catch (e: Exception) { }
            }
        }
    }

    private fun batteryFree() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("بیٹری پابندی پہلے سے ہٹی ہوئی ہے")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}
