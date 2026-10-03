package com.nipuna.voicesentry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.sqrt

class VoiceService : Service() {

    companion object {
        const val ACTION_STOP = "com.nipuna.voicesentry.STOP"
        private const val CHANNEL = "sentry"
    }

    @Volatile private var active = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var sinhalaOk = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY

        createChannel()
        ServiceCompat.startForeground(this, 1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        active = true
        VoiceState.running.value = true

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sentry:mic").also { it.acquire() }

        initTts(true)

        worker = Thread {
            try {
                loop()
            } catch (e: Throwable) {
                VoiceState.add("engine", "Error: ${e.message}", 0f, false)
            } finally {
                if (active) stopSelf()
            }
        }.also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        try { worker?.join(900) } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        VoiceState.running.value = false
        VoiceState.status.value = "Stopped"
        VoiceState.level.value = 0f
        super.onDestroy()
    }

    private fun initTts(useGoogle: Boolean) {
        val listener = TextToSpeech.OnInitListener { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("si", "LK"))
                sinhalaOk = r != null && r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                ttsReady = true
                if (!sinhalaOk) {
                    VoiceState.add("setup", "No Sinhala voice on this phone, using online voice instead", 0f, false)
                }
            } else if (useGoogle) {
                try { tts?.shutdown() } catch (_: Exception) {}
                initTts(false)
            }
        }
        tts = if (useGoogle) TextToSpeech(this, listener, "com.google.android.tts") else TextToSpeech(this, listener)
    }

    /** Speaks and blocks until finished (the mic is reset afterwards so it never hears itself). */
    private fun speak(text: String) {
        val t = tts
        if (t != null && ttsReady && sinhalaOk) {
            val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sentry")
            if (r == TextToSpeech.SUCCESS) {
                Thread.sleep(400)
                var n = 0
                while (active && t.isSpeaking && n < 300) { Thread.sleep(100); n++ }
                Thread.sleep(500)
                return
            }
        }
        // Phone has no Sinhala voice: fall back to an online Sinhala voice (needs internet).
        try {
            speakOnline(text)
        } catch (e: Exception) {
            VoiceState.add("voice", "Could not speak: ${e.message}", 0f, false)
        }
    }

    private fun speakOnline(text: String) {
        val chunks = text.split(Regex("(?<=[.!?।])\\s+")).filter { it.isNotBlank() }
        for (chunk in chunks) {
            if (!active) return
            val q = URLEncoder.encode(chunk.take(180), "UTF-8")
            val url = URL("https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=si&q=$q")
            val c = url.openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.connectTimeout = 8000
            c.readTimeout = 15000
            if (c.responseCode !in 200..299) {
                c.disconnect()
                throw Exception("online voice HTTP ${c.responseCode}")
            }
            val f = File(cacheDir, "say.mp3")
            c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            c.disconnect()

            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                mp.setDataSource(f.path)
                mp.prepare()
                mp.start()
                Thread.sleep(300)
                var n = 0
                while (active && mp.isPlaying && n < 300) { Thread.sleep(100); n++ }
            } finally {
                mp.release()
            }
        }
        Thread.sleep(500)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice Sentry", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, VoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Sentry is listening")
            .setContentText("Only your voice can give commands")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buzz(ms: Long) {
        try {
            getSystemService(Vibrator::class.java)
                .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun loop() {
        val prefs = Prefs(this)
        val ref = prefs.voiceprint
        if (ref == null) {
            VoiceState.add("setup", "Record your voice first", 0f, false)
            return
        }

        VoiceState.status.value = "Loading models…"
        RootShell.run(
            "dumpsys deviceidle whitelist +$packageName; " +
                "appops set $packageName RUN_ANY_IN_BACKGROUND allow; " +
                "appops set $packageName RUN_IN_BACKGROUND allow",
        )

        val vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 15f,
                ),
                sampleRate = Audio.SR,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
        val spk = SpeakerModel(this)
        val asr = AsrModel(this)

        val rec: AudioRecord = Audio.newRecord(4f)
        val shorts = ShortArray(512)
        val pre = ArrayDeque<FloatArray>()
        var floor = 0.004f
        var awakeUntil = 0L
        var frameNo = 0
        val rejects = ArrayDeque<Long>()
        var backoffUntil = 0L

        // Sends the owner's audio to the AI brain, speaks the answer, then keeps the conversation open for 10 s.
        fun converse(seg: FloatArray, score: Float) {
            VoiceState.status.value = "Thinking…"
            buzz(30)
            val r = Brain.ask(this@VoiceService, prefs, seg, score)
            VoiceState.add(r.heard, if (r.say.isNotBlank()) r.say else "(no reply)", score, r.ok)
            if (r.say.isNotBlank()) {
                VoiceState.status.value = "Speaking…"
                speak(r.say)
                try { rec.stop(); rec.startRecording() } catch (_: Exception) {}
                vad.clear()
                vad.reset()
                pre.clear()
            }
            awakeUntil = System.currentTimeMillis() + 15000
            VoiceState.status.value = "Listening"
        }

        // Spoken acknowledgement after the wake word.
        fun ack() {
            VoiceState.status.value = "Speaking…"
            speak("ඔව්, මොනවද කරන්න ඕනේ?")
            try { rec.stop(); rec.startRecording() } catch (_: Exception) {}
            vad.clear()
            vad.reset()
            pre.clear()
            awakeUntil = System.currentTimeMillis() + 12000
            VoiceState.status.value = "Listening"
        }

        try {
            rec.startRecording()
            VoiceState.status.value = "Listening"

            while (active) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                val f = FloatArray(n)
                var e = 0.0
                for (i in 0 until n) {
                    f[i] = shorts[i] / 32768f
                    e += (f[i] * f[i]).toDouble()
                }
                val rms = sqrt(e / n).toFloat()
                if (++frameNo % 3 == 0) VoiceState.level.value = rms

                // Cheap energy gate: the heavy VAD model only runs when something loud enough happens.
                val inSpeech = vad.isSpeechDetected() || !vad.empty()
                if (!inSpeech) {
                    // Gate follows the room noise but is capped, so speech in a noisy place is never blocked.
                    val gate = (floor * 2.2f).coerceIn(0.008f, 0.02f)
                    if (rms < gate) {
                        floor = floor * 0.995f + rms * 0.005f
                        pre.addLast(f)
                        if (pre.size > 10) pre.removeFirst()
                        continue
                    }
                    // Loud but not (yet) speech: let the noise floor creep up slowly.
                    floor = floor * 0.999f + rms * 0.001f
                    for (p in pre) vad.acceptWaveform(p)
                    pre.clear()
                }
                vad.acceptWaveform(f)

                while (!vad.empty()) {
                    val seg = vad.front().samples
                    vad.pop()
                    if (seg.size < 6400) continue

                    // Battery saver: after many rejected voices in a row (TV, crowd), only look at clearly close/loud speech.
                    val t0 = System.currentTimeMillis()
                    if (t0 < backoffUntil) {
                        var se = 0.0
                        for (x in seg) se += (x * x).toDouble()
                        if (sqrt(se / seg.size) < 0.03) {
                            VoiceState.status.value = "Listening"
                            continue
                        }
                    }

                    // 1) Whose voice is it? (cheap) Anyone but the owner is dropped silently.
                    VoiceState.status.value = "Verifying voice…"
                    val score = cosine(spk.embed(seg), ref)
                    // In a noisy room the voice match naturally drops, so the bar is lowered a little (max 10%).
                    // Unlock keeps its own strict bar and is NOT relaxed.
                    val relief = ((floor - 0.01f) * 6f).coerceIn(0f, 0.1f)
                    if (score < prefs.threshold - relief) {
                        rejects.addLast(t0)
                        while (rejects.isNotEmpty() && t0 - rejects.first() > 30000) rejects.removeFirst()
                        if (rejects.size >= 6) backoffUntil = t0 + 20000
                        VoiceState.add("(speech)", if (floor > 0.012f) "Noisy room / other voice" else "Other voice ignored", score, false)
                        VoiceState.status.value = "Listening"
                        continue
                    }

                    val now = System.currentTimeMillis()
                    val aiOn = prefs.assistantOn && prefs.geminiKey.isNotBlank() && prefs.requireWake

                    // Conversation is open (wake word was just said or the AI just answered): go straight to the AI.
                    if (aiOn && now < awakeUntil) {
                        converse(seg, score)
                        continue
                    }

                    // 2) What did the owner say? (offline, English, only for the owner)
                    VoiceState.status.value = "Understanding…"
                    val text = asr.transcribe(seg)
                    val t = norm(text)
                    if (t.isEmpty()) {
                        VoiceState.status.value = "Listening"
                        continue
                    }

                    // 3) Wake word
                    var rest: String? = t
                    if (prefs.requireWake) {
                        rest = null
                        val wake = norm(prefs.wakeWord)
                        val tail = findWake(t, wake)
                        if (tail != null) {
                            if (tail.trim().isEmpty()) {
                                awakeUntil = now + 12000
                                buzz(30)
                                VoiceState.add(text, if (aiOn) "Yes? Ask me anything" else "Yes? (say your command)", score, true)
                                if (aiOn) ack()
                            } else {
                                rest = tail.trim()
                            }
                        } else {
                            VoiceState.add(text, "No wake word", score, false)
                        }
                    }

                    if (rest != null) {
                        val res = Commands.execute(this, prefs, rest, score)
                        if (res.unknown && aiOn) {
                            // Not one of the built-in commands (probably Sinhala or a free-form request): ask the AI.
                            converse(seg, score)
                            continue
                        }
                        buzz(if (res.ok) 45 else 120)
                        VoiceState.add(text, res.label, score, res.ok)
                    }
                    VoiceState.status.value = "Listening"
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            spk.release()
            asr.release()
            try { vad.release() } catch (_: Throwable) {}
        }
    }

    /** Returns the text after the wake word, or null if the wake word was not heard. */
    private fun findWake(t: String, wake: String): String? {
        if (wake.isEmpty()) return t
        val idx = t.indexOf(wake)
        if (idx >= 0) return t.substring(idx + wake.length)
        if (!wake.contains(' ')) {
            val words = t.split(' ')
            val tol = if (wake.length >= 6) 2 else 1
            for (i in words.indices) {
                if (lev(words[i], wake) <= tol) return words.drop(i + 1).joinToString(" ")
            }
        }
        return null
    }
}
