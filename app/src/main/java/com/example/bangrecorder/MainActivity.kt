package com.example.bangrecorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var levelText: TextView
    private lateinit var thresholdText: TextView
    private lateinit var thresholdBar: SeekBar
    private lateinit var toggleButton: Button
    private lateinit var listView: ListView

    private val adapter = EventAdapter()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val dayTime = SimpleDateFormat("EEE d MMM yyyy · HH:mm:ss", Locale.getDefault())
    private var shownVersion = -1
    private var player: MediaPlayer? = null
    private var playingFile: String? = null

    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep content clear of the status/nav bars (Android 15 draws edge-to-edge).
        val root = findViewById<View>(R.id.root)
        val pad = root.paddingLeft
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            }
            insets
        }

        statusText = findViewById(R.id.status)
        levelBar = findViewById(R.id.level)
        levelText = findViewById(R.id.levelText)
        thresholdText = findViewById(R.id.thresholdText)
        thresholdBar = findViewById(R.id.threshold)
        toggleButton = findViewById(R.id.toggle)
        listView = findViewById(R.id.list)

        // Threshold slider: 30..90 positive dB
        val savedTh = prefs.getFloat("threshold", RecorderService.DEFAULT_THRESHOLD)
        val th = if (savedTh < 0f) savedTh + 90f else savedTh
        RecorderService.thresholdDb = th
        thresholdBar.max = 60
        thresholdBar.progress = (th - 30f).toInt().coerceIn(0, 60)
        updateThresholdLabel()
        thresholdBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = (30 + progress).toFloat()
                RecorderService.thresholdDb = v
                prefs.edit().putFloat("threshold", v).apply()
                updateThresholdLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        toggleButton.setOnClickListener {
            if (RecorderService.running) RecorderService.stop(this) else startWithPermissions()
        }
        findViewById<Button>(R.id.shareAll).setOnClickListener { shareAll() }

        listView.adapter = adapter
        listView.emptyView = findViewById(R.id.empty)
        listView.setOnItemClickListener { _, _, pos, _ -> togglePlay(adapter.getItem(pos), fromStart = false) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showOptions(adapter.getItem(pos))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        shownVersion = -1
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    // ---------- status ----------

    private fun updateThresholdLabel() {
        thresholdText.text = String.format(
            Locale.US,
            "Trigger level: %.0f dB  (triggers when noise exceeds this level)",
            RecorderService.thresholdDb
        )
    }

    private fun refreshStatus() {
        val running = RecorderService.running
        toggleButton.text = if (running) "Stop listening" else "Start listening"
        val err = RecorderService.lastError
        statusText.text = when {
            !running && err != null -> "Stopped: $err"
            !running -> "Stopped"
            RecorderService.capturing -> "● Bang detected, finishing the clip…"
            else -> "Listening. Each bang saves ${RecorderService.PRE_SEC}s before + ${RecorderService.POST_SEC}s after."
        }
        val lvl = if (running) RecorderService.levelDb else 0f
        levelBar.max = 90
        levelBar.progress = lvl.toInt().coerceIn(0, 90)   // bar shows 0..90 dB
        levelText.text = if (running) {
            String.format(Locale.US, "Now %.0f dB · background %.0f dB", lvl, RecorderService.backgroundDb)
        } else {
            "The level meter works while listening. Clap near the phone to test your trigger level."
        }

        val v = RecorderService.eventsVersion
        if (v != shownVersion) {
            shownVersion = v
            adapter.set(EventStore.load(this))
        }
    }

    // ---------- starting ----------

    private fun startWithPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) maybeAskBatteryThenStart()
        else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            maybeAskBatteryThenStart()
        } else {
            toast("Microphone permission is needed to hear the bangs.")
        }
    }

    /** Asks once to be exempt from battery optimisation, otherwise some phones kill it after a while. */
    private fun maybeAskBatteryThenStart() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("askedBattery", false)) {
            RecorderService.start(this)
            return
        }
        prefs.edit().putBoolean("askedBattery", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Keep listening all day?")
            .setMessage("Android may pause apps in the background to save battery. Allow Bang Recorder to run unrestricted so it doesn't miss anything.")
            .setPositiveButton("Allow") { _, _ ->
                RecorderService.start(this)
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("Not now") { _, _ -> RecorderService.start(this) }
            .show()
    }

    // ---------- playback / sharing ----------

    private fun clipFile(e: BangEvent) = File(EventStore.dir(this), e.file)

    private fun togglePlay(e: BangEvent, fromStart: Boolean) {
        if (playingFile == e.file && !fromStart) {
            stopPlayback()
            return
        }
        stopPlayback()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(clipFile(e).absolutePath)
            mp.prepare()
            if (!fromStart) mp.seekTo(((e.offsetSec - 2f).coerceAtLeast(0f) * 1000).toInt())
            mp.setOnCompletionListener { stopPlayback() }
            mp.start()
            player = mp
            playingFile = e.file
        } catch (ex: Exception) {
            toast("Can't play this clip: ${ex.message}")
        }
        adapter.notifyDataSetChanged()
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        playingFile = null
        adapter.notifyDataSetChanged()
    }

    private fun showOptions(e: BangEvent) {
        AlertDialog.Builder(this)
            .setTitle(dayTime.format(Date(e.timeMillis)))
            .setItems(arrayOf("Play from start", "Share", "Delete")) { _, which ->
                when (which) {
                    0 -> togglePlay(e, fromStart = true)
                    1 -> share(listOf(clipFile(e)), "audio/wav")
                    2 -> confirmDelete(e)
                }
            }
            .show()
    }

    private fun confirmDelete(e: BangEvent) {
        AlertDialog.Builder(this)
            .setMessage("Delete the clip from ${dayTime.format(Date(e.timeMillis))}?")
            .setPositiveButton("Delete") { _, _ ->
                if (playingFile == e.file) stopPlayback()
                EventStore.delete(this, e)
                adapter.set(EventStore.load(this))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareAll() {
        val clips = EventStore.load(this).map { clipFile(it) }
        if (clips.isEmpty()) {
            toast("Nothing recorded yet.")
            return
        }
        share(listOf(EventStore.logFile(this)) + clips, "*/*")
    }

    private fun share(files: List<File>, mime: String) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = mime
        intent.clipData = ClipData.newRawUri("", uris[0]).apply {
            for (u in uris.drop(1)) addItem(ClipData.Item(u))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share recordings"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- list ----------

    private inner class EventAdapter : BaseAdapter() {
        private var items: List<BangEvent> = emptyList()

        fun set(list: List<BangEvent>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int): BangEvent = items[position]
        override fun getItemId(position: Int) = items[position].timeMillis

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val e = items[position]
            val playing = e.file == playingFile
            v.findViewById<TextView>(android.R.id.text1).text =
                (if (playing) "▶ " else "") + dayTime.format(Date(e.timeMillis))
            val secs = e.offsetSec.toInt()
            val mb = clipFile(e).length() / 1_000_000.0
            val peakPos = if (e.peakDb <= 0f) e.peakDb + 90f else e.peakDb
            v.findViewById<TextView>(android.R.id.text2).text = buildString {
                append(String.format(Locale.US, "Peak %.0f dB · bang at %d:%02d in clip", peakPos, secs / 60, secs % 60))
                if (e.hits > 1) append(" · ${e.hits} bangs")
                append(String.format(Locale.US, " · %.1f MB", mb))
                if (playing) append(" · tap to stop")
            }
            return v
        }
    }

    companion object {
        private const val REQ_PERMS = 1
    }
}
