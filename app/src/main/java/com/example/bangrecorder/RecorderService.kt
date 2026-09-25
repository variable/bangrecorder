package com.example.bangrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens to the microphone all day.
 *
 * Instead of recording 1-minute files and throwing away the quiet ones, it keeps the
 * last ~11 s of audio in a rolling memory buffer and only writes to storage when a bang
 * is detected. Each saved clip = 5 s before the bang + 5 s after it, so the bang is
 * never cut off at the edge of a segment, and the timestamp is the exact moment of the peak.
 */
class RecorderService : Service() {

    companion object {
        const val PRE_SEC = 5                // seconds kept before the bang
        const val POST_SEC = 5               // seconds recorded after the bang
        const val DEFAULT_THRESHOLD = 75f    // positive dB peak needed to count as a bang (slider in the app)
        const val IMPULSE_DB = 15f           // peak must also jump this far above the background level

        private const val FRAME_MS = 20
        private const val BG_TIME_CONSTANT_MS = 5000f
        private const val CHANNEL_ID = "listening"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.example.bangrecorder.STOP"

        // Simple shared state the activity polls. Same process, so no IPC needed.
        @Volatile var running = false
            private set
        @Volatile var capturing = false
            private set
        @Volatile var levelDb = 0f
            private set
        @Volatile var backgroundDb = 0f
            private set
        @Volatile var eventsVersion = 0
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile var thresholdDb = DEFAULT_THRESHOLD

        fun start(ctx: Context) {
            lastError = null
            ctx.startForegroundService(Intent(ctx, RecorderService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RecorderService::class.java))
        }
    }

    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val saver = Executors.newSingleThreadExecutor()
    private var savedThisSession = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError = "microphone permission not granted"
            stopSelf()
            return START_NOT_STICKY
        }
        val savedTh = getSharedPreferences("settings", MODE_PRIVATE).getFloat("threshold", thresholdDb)
        thresholdDb = if (savedTh < 0f) savedTh + 90f else savedTh

        try {
            val n = buildNotification("Waiting for a loud bang…")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            lastError = "couldn't start listening: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BangRecorder:listen")
            .apply { acquire() }

        stopRequested = false
        running = true
        worker = Thread({ listenLoop() }, "bang-listener").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        worker?.join(3000)
        worker = null
        saver.shutdown()
        try {
            saver.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        capturing = false
        levelDb = 0f
        backgroundDb = 0f
        super.onDestroy()
    }

    private fun listenLoop() {
        val rec = openRecorder()
        if (rec == null) {
            lastError = "couldn't open the microphone (is another app using it?)"
            Handler(Looper.getMainLooper()).post { stopSelf() }
            return
        }

        val rate = rec.sampleRate
        val frame = rate * FRAME_MS / 1000
        val pre = rate * PRE_SEC
        val post = rate * POST_SEC
        val ring = ShortArray(pre + post + rate)   // rolling buffer, ~11 s
        val buf = ShortArray(frame)
        val bgAlpha = FRAME_MS / BG_TIME_CONSTANT_MS

        var total = 0L            // samples captured since start
        var bg = Float.NaN        // slow-moving background loudness (positive dB)
        var meter = 0f            // peak-hold value for the on-screen meter
        var bangAt = -1L          // sample index of the bang in the clip being captured
        var bangTime = 0L
        var bangPeak = 0f
        var hits = 0
        var lastLoud = Long.MIN_VALUE / 2

        fun finishClip() {
            val start = max(0L, bangAt - pre)
            val len = (total - start).toInt()
            val clip = ShortArray(len)
            for (i in 0 until len) clip[i] = ring[((start + i) % ring.size).toInt()]
            val offsetSec = (bangAt - start).toFloat() / rate
            val t = bangTime
            val p = bangPeak
            val h = hits
            saver.execute { saveClip(clip, rate, t, p, offsetSec, h) }
            bangAt = -1L
            capturing = false
        }

        try {
            rec.startRecording()
            while (!stopRequested) {
                val n = rec.read(buf, 0, frame)
                if (n < 0) {
                    lastError = "microphone read error ($n)"
                    break
                }
                if (n == 0) continue

                var peak = 0
                var peakIdx = 0
                var sumSq = 0.0
                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    ring[((total + i) % ring.size).toInt()] = buf[i]
                    val a = abs(s)
                    if (a > peak) {
                        peak = a
                        peakIdx = i
                    }
                    sumSq += (s * s).toDouble()
                }
                val frameStart = total
                total += n

                val peakDb = toPositiveDb(dbfs(peak.toDouble()))
                val rmsDb = toPositiveDb(dbfs(sqrt(sumSq / n)))
                if (bg.isNaN()) bg = rmsDb
                meter = max(peakDb, meter - 0.5f)   // decays ~25 dB/s so the meter is readable
                levelDb = meter

                // A bang = noise exceeding the trigger level AND a sudden jump over the recent background.
                // The second check stops steady loud noise (vacuum, drill, music) re-triggering forever.
                val loud = peakDb >= thresholdDb && peakDb - bg >= IMPULSE_DB
                bg += (rmsDb - bg) * bgAlpha
                backgroundDb = bg

                if (loud) {
                    val at = frameStart + peakIdx
                    if (bangAt < 0) {
                        bangAt = at
                        bangTime = System.currentTimeMillis() - (total - at) * 1000 / rate
                        bangPeak = peakDb
                        hits = 1
                        capturing = true
                    } else {
                        if (frameStart - lastLoud > rate / 2) hits++   // separate bang inside the same clip
                        bangPeak = max(bangPeak, peakDb)
                    }
                    lastLoud = frameStart
                }

                if (bangAt >= 0 && total >= bangAt + post) finishClip()
            }
        } catch (e: Exception) {
            lastError = "listener stopped: ${e.message}"
        } finally {
            if (bangAt >= 0) finishClip()   // stopped mid-clip: keep what we have
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            rec.release()
            if (!stopRequested) Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }

    private fun toPositiveDb(dbfs: Float): Float = (dbfs + 90f).coerceAtLeast(0f)

    private fun dbfs(amplitude: Double): Float =
        if (amplitude < 1.0) -90f else (20.0 * log10(amplitude / 32768.0)).toFloat().coerceAtLeast(-90f)

    /** Prefer a raw mic source so automatic gain control doesn't squash the bangs. */
    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun openRecorder(): AudioRecord? {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = buildList {
            if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        for (rate in intArrayOf(16000, 44100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            for (src in sources) {
                try {
                    val r = AudioRecord(
                        src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        max(min, rate) // ~0.5 s of slack
                    )
                    if (r.state == AudioRecord.STATE_INITIALIZED) return r
                    r.release()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun saveClip(clip: ShortArray, rate: Int, time: Long, peak: Float, offsetSec: Float, hits: Int) {
        try {
            val dir = EventStore.dir(this)
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(time))
            var f = File(dir, "bang_$stamp.wav")
            var k = 2
            while (f.exists()) f = File(dir, "bang_${stamp}_${k++}.wav")

            WavWriter.write(f, clip, rate)
            EventStore.append(this, BangEvent(f.name, time, peak, offsetSec, hits))
            savedThisSession++
            eventsVersion++

            if (!stopRequested) {
                val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification("Last bang $t · $savedThisSession saved this session"))
            }
        } catch (e: Exception) {
            lastError = "couldn't save clip: ${e.message}"
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bang)
            .setContentTitle("Bang Recorder is listening")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_bang), "Stop", stop
                ).build()
            )
            .build()
    }
}
