package com.ghaffar.ai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Runs in the background (even with the screen off) to speak reminders
 * and to play Quran recitation for sleep and morning.
 */
class AudioService : Service() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = mutableListOf<String>()
    private var speaking = false

    private var player: MediaPlayer? = null
    private var queue: List<String> = emptyList()
    private var index = 0
    private var loop = false
    private var playing = false
    private var volume = 1f
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        fun speak(c: Context, text: String) = start(c, Intent(c, AudioService::class.java)
            .setAction("SPEAK").putExtra("text", text))

        fun quran(c: Context, kind: String) = start(c, Intent(c, AudioService::class.java)
            .setAction("QURAN").putExtra("kind", kind))

        fun stop(c: Context) {
            c.startService(Intent(c, AudioService::class.java).setAction("STOP"))
        }

        private fun start(c: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i)
            else c.startService(i)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            "SPEAK" -> say(intent.getStringExtra("text") ?: "")
            "QURAN" -> startQuran(intent.getStringExtra("kind") ?: "sleep")
            "STOP" -> finish()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = Notif.builder(this)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("غفار اے آئی")
            .setContentText("چل رہا ہے…")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)
    }

    // ---------- speaking ----------
    private fun say(text: String) {
        if (text.isBlank()) return
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    val r = tts?.setLanguage(Locale("ur", "PK"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                        tts?.setLanguage(Locale("hi", "IN"))
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onError(id: String?) { handler.post { speakDone() } }
                        override fun onDone(id: String?) { handler.post { speakDone() } }
                    })
                    pendingSpeech.forEach { speakNow(it) }
                    pendingSpeech.clear()
                } else {
                    speaking = false
                    checkIdle()
                }
            }
        }
        speaking = true
        if (ttsReady) speakNow(text) else pendingSpeech.add(text)
    }

    private fun speakNow(t: String) {
        tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    private fun speakDone() {
        speaking = false
        checkIdle()
    }

    // ---------- quran ----------
    private fun startQuran(kind: String) {
        val r = P.reciter(this)
        loop = kind == "sleep"
        queue = if (kind == "morning") Quran.morning(r) else Quran.sleep(r)
        index = 0
        volume = 1f
        playing = true
        handler.removeCallbacksAndMessages(null)
        if (kind == "sleep") {
            // After 45 minutes fade the sound out slowly and stop.
            handler.postDelayed({ fadeOut() }, 45L * 60 * 1000)
        }
        playNext()
    }

    private fun playNext() {
        if (!playing) return
        if (index >= queue.size) {
            if (loop) index = 0 else { playing = false; checkIdle(); return }
        }
        player?.release()
        val url = queue[index]
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            setWakeMode(this@AudioService, PowerManager.PARTIAL_WAKE_LOCK)
            try {
                setDataSource(url)
            } catch (e: Exception) {
                index++; handler.post { playNext() }; return@apply
            }
            setOnPreparedListener { it.setVolume(volume, volume); it.start() }
            setOnCompletionListener { index++; playNext() }
            setOnErrorListener { _, _, _ -> index++; handler.postDelayed({ playNext() }, 500); true }
            prepareAsync()
        }
    }

    private fun fadeOut() {
        val step = object : Runnable {
            override fun run() {
                volume -= 1f / 30f
                if (volume <= 0f) { playing = false; player?.release(); player = null; checkIdle(); return }
                player?.setVolume(volume, volume)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(step)
    }

    private fun checkIdle() {
        if (!speaking && !playing) finish()
    }

    private fun finish() {
        playing = false
        speaking = false
        handler.removeCallbacksAndMessages(null)
        player?.release(); player = null
        tts?.stop(); tts?.shutdown(); tts = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        tts?.shutdown()
        super.onDestroy()
    }
}
