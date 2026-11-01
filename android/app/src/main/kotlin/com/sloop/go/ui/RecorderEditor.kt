// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sloop.go.audio.DynConfig
import com.sloop.go.audio.RecFiles
import com.sloop.go.audio.Spectrum
import com.sloop.go.audio.UsbRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The page's displays, each with its own on / off. */
enum class RecView { SPECTRUM, WAVE, PAN, LEVEL }

/** The Recorder page: the FM-1's USB audio into WAV files. Kept in the ViewModel, so a take goes on across pages. */
class RecorderEditor(private val app: Application) {
    private val prefs = app.getSharedPreferences("usbrec", 0)
    private val rec = UsbRecorder()

    var rate by mutableIntStateOf(prefs.getInt("rate", 44100)); private set
    var bits by mutableIntStateOf(prefs.getInt("bits", 16)); private set
    var channels by mutableIntStateOf(prefs.getInt("channels", 2)); private set
    var flac by mutableStateOf(prefs.getBoolean("flac", false)); private set
    var folder by mutableStateOf(prefs.getString("folder", null)?.let(Uri::parse)); private set
    var folderName by mutableStateOf(prefs.getString("folderName", null)); private set
    var prefix by mutableStateOf(prefs.getString("prefix", null) ?: "FM1"); private set

    /** Add the date and time to the file name (a new file every take); off, the name is used as it is. */
    var stamp by mutableStateOf(prefs.getBoolean("stamp", false)); private set
    fun pickStamp(v: Boolean) { stamp = v; prefs.edit().putBoolean("stamp", v).apply() }

    /** The compressor and limiter in the signal path (kept across restarts, changeable while it runs). */
    var dyn by mutableStateOf(DynConfig().let { d ->
        DynConfig(prefs.getBoolean("dComp", d.compOn), prefs.getInt("dThr", d.thr), prefs.getInt("dRatio", d.ratio10),
            prefs.getInt("dAtk", d.atk), prefs.getInt("dRel", d.rel10), prefs.getInt("dKnee", d.knee),
            prefs.getInt("dMk", d.makeup), prefs.getBoolean("dLim", d.limOn), prefs.getInt("dCeil", d.ceil10),
            prefs.getInt("dLRel", d.limRel10))
    }); private set

    /** The displays that are switched on; an off one is neither analysed nor drawn. */
    var views by mutableStateOf(RecView.entries.filter { prefs.getBoolean("view$it", true) }.toSet()); private set

    fun isOn(v: RecView) = v in views

    fun toggleView(v: RecView) {
        val on = v !in views
        views = if (on) views + v else views - v
        prefs.edit().putBoolean("view$v", on).apply()
        pushViews()
        if (!on) when (v) {                                         // an off display starts afresh when it comes back
            RecView.SPECTRUM -> { spec.fill(Spectrum.FLOOR); specHold.fill(Spectrum.FLOOR); specRev++ }
            RecView.PAN -> { pan = 0f; panLevel = 0f; trailN = 0 }
            else -> {}
        } else if (v == RecView.WAVE && active) viewFrame = rec.wave.count.toDouble()
    }

    private fun pushViews() {
        rec.tapOn = isOn(RecView.SPECTRUM); rec.panOn = isOn(RecView.PAN); rec.waveOn = isOn(RecView.WAVE)
    }

    init { rec.dynCfg = dyn; pushViews() }

    /** Changes the current settings: [f] gets what they are now, not what a knob saw when it was drawn. */
    fun editDyn(f: (DynConfig) -> DynConfig) = changeDyn(f(dyn))

    fun changeDyn(c: DynConfig) {
        dyn = c
        rec.dynCfg = c
        prefs.edit().putBoolean("dComp", c.compOn).putInt("dThr", c.thr).putInt("dRatio", c.ratio10)
            .putInt("dAtk", c.atk).putInt("dRel", c.rel10).putInt("dKnee", c.knee).putInt("dMk", c.makeup)
            .putBoolean("dLim", c.limOn).putInt("dCeil", c.ceil10).putInt("dLRel", c.limRel10).apply()
    }

    fun pickRate(v: Int) { rate = v; prefs.edit().putInt("rate", v).apply() }
    fun pickBits(v: Int) { bits = v; prefs.edit().putInt("bits", v).apply() }
    fun pickChannels(v: Int) { channels = v; prefs.edit().putInt("channels", v).apply() }
    fun pickFlac(v: Boolean) { flac = v; prefs.edit().putBoolean("flac", v).apply() }
    fun editPrefix(v: String) {
        prefix = v.filter { it.isLetterOrDigit() || it in " _-" }.take(24)
        prefs.edit().putString("prefix", prefix).apply()
    }

    fun pickFolder(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        folder = uri
        folderName = RecFiles.folderName(app, uri)
        prefs.edit().putString("folder", uri.toString()).putString("folderName", folderName).apply()
    }

    fun defaultFolder() {
        folder?.let { old ->
            runCatching {
                app.contentResolver.releasePersistableUriPermission(old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        folder = null; folderName = null
        prefs.edit().remove("folder").remove("folderName").apply()
    }

    val whereLabel: String get() = folderName ?: RecFiles.defaultLabel(app)

    /** What the name is followed by: the extension, with the date and time before it when [stamp] is on. */
    val nameTail: String get() = (if (stamp) "_yyyymmdd-hhmmss" else "") + "." + format.ext

    /** The file the next take is written to. */
    fun fileName(): String {
        val stem = prefix.trim().ifBlank { "FM1" }
        val at = if (stamp) "_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) else ""
        return "$stem$at.${format.ext}"
    }

    /** A file of that name is already in the destination: starting a take would replace it. */
    suspend fun nameTaken(): String? {
        val name = fileName()
        return name.takeIf { withContext(Dispatchers.IO) { RecFiles.exists(app, folder, name) } }
    }

    // ---- inputs ----
    var inputs by mutableStateOf<List<UsbRecorder.Input>>(emptyList()); private set
    var inputId by mutableIntStateOf(-1)
    val input: UsbRecorder.Input? get() = inputs.firstOrNull { it.id == inputId } ?: inputs.firstOrNull()
    fun refreshInputs() { inputs = UsbRecorder.inputs(app) }

    // ---- the run ----
    var recording by mutableStateOf(false); private set
    var monitoring by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    val active: Boolean get() = recording || monitoring

    /** The input test running now was begun by the FM-1 starting to play, so its stopping ends it too. */
    var autoTest = false

    var frames by mutableLongStateOf(0); private set
    var runRate by mutableIntStateOf(44100); private set
    var bytes by mutableLongStateOf(0); private set
    var peakL by mutableFloatStateOf(0f); private set
    var peakR by mutableFloatStateOf(0f); private set
    var reduction by mutableFloatStateOf(0f); private set
    var clipped by mutableStateOf(false); private set
    var warning by mutableStateOf<String?>(null); private set
    var last by mutableStateOf<UsbRecorder.Result?>(null); private set
    val seconds: Double get() = frames.toDouble() / runRate

    // ---- the waveform: the recent audio is in rec.wave; ZOOM picks how much time the screen shows, SPEED how fast it runs ----
    /** Index into [WAVE_ZOOMS]: the span of time across the waveform, from a few cycles of a note to a minute. */
    var zoom by mutableIntStateOf(prefs.getInt("wzoom", WAVE_ZOOMS.lastIndex).coerceIn(0, WAVE_ZOOMS.lastIndex)); private set
    /** How fast the waveform runs, % of real time: 100 is live, less is slow motion (it falls behind the live edge). */
    var speed by mutableIntStateOf(prefs.getInt("wspeed", 100).coerceIn(5, 100)); private set
    fun pickZoom(v: Int) { zoom = v.coerceIn(0, WAVE_ZOOMS.lastIndex); prefs.edit().putInt("wzoom", zoom).apply() }
    fun pickSpeed(v: Int) { speed = v.coerceIn(5, 100); prefs.edit().putInt("wspeed", speed).apply() }

    /** The span the waveform shows, in frames of the run. */
    val windowFrames: Double get() = WAVE_ZOOMS[zoom] * runRate

    private var viewFrame = 0.0
    private var viewNs = 0L

    /** The frame at the right edge of the waveform. */
    val viewEnd: Double get() = viewFrame

    /** The newest frame there is. */
    val liveFrames: Long get() = rec.wave.count

    /**
     * Called with every display frame the waveform is on: the view runs on at [speed] of real time. It may lead the
     * live edge by a moment (a 20 ms block, less on a close zoom — an empty sliver at the right edge) and falls behind
     * it by at most a couple of blocks live, or 30 s in slow motion (the overview holds about 95 s).
     */
    fun advanceView(nowNs: Long) {
        val dt = if (viewNs == 0L) 0.0 else ((nowNs - viewNs) / 1e9).coerceIn(0.0, 0.1)
        viewNs = nowNs
        val live = rec.wave.count.toDouble()
        val step = dt * runRate * speed / 100.0
        viewFrame += step                                                   // after a run it just goes on, off the screen
        if (active) {
            val lead = minOf(runRate / 50.0, windowFrames * 0.05)
            val back = if (speed >= 100) 2 * runRate / 50.0 else 30.0 * runRate
            viewFrame = viewFrame.coerceIn(live - back, live + lead)
        }
    }

    fun resetViewClock() { viewNs = 0 }

    /** Fills the min / max of [cols] slices of width [per] frames from frame [start] (see WaveStore.columns). */
    fun waveColumns(start: Double, per: Double, cols: Int, mn: FloatArray, mx: FloatArray) =
        rec.wave.columnsAt(start, per, cols, mn, mx)

    /** The run is over and what it drew has scrolled out of sight (or there never was one). */
    fun waveAway(): Boolean = !active && (rec.wave.count == 0L || viewFrame - windowFrames >= rec.wave.count)

    // ---- the spectrum: an FFT of the latest samples on every poll, smoothed the way an EQ's analyzer is ----
    private var analyzer: Spectrum? = null
    private val fftIn = FloatArray(FFT_SIZE)
    private val fftNow = FloatArray(Spectrum.POINTS)
    /** dBFS per display point ([Spectrum.freq]): fast up, slower down; [specHold] is a peak line falling 12 dB a second. */
    val spec = FloatArray(Spectrum.POINTS) { Spectrum.FLOOR }
    val specHold = FloatArray(Spectrum.POINTS) { Spectrum.FLOOR }
    /** Changes whenever [spec] does, for the drawing to follow. */
    var specRev by mutableIntStateOf(0); private set

    // ---- the pan: where the sound sits between the channels, -1 (left) .. 1 (right), by their loudness ----
    var pan by mutableFloatStateOf(0f); private set
    /** How loud it is now, 0..1 over -60..0 dBFS, to fade the pan needle when there is little to place. */
    var panLevel by mutableFloatStateOf(0f); private set
    private val trail = FloatArray(PAN_TRAIL)
    private var trailN = 0
    val trailCount: Int get() = minOf(trailN, PAN_TRAIL)
    /** An earlier pan position, [age] polls ago (0 = the latest). */
    fun trailAt(age: Int): Float = trail[((trailN - 1 - age) % PAN_TRAIL + PAN_TRAIL) % PAN_TRAIL]

    private fun clearAnalysis() {
        spec.fill(Spectrum.FLOOR); specHold.fill(Spectrum.FLOOR); specRev++
        pan = 0f; panLevel = 0f; trailN = 0
    }

    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false); private set
    var said by mutableIntStateOf(0); private set
    fun say(text: String, error: Boolean = false) { message = text; isError = error; said++ }

    /** FLAC is integer only: a 32-bit float choice records 24 bit there (the pick is kept for WAV). */
    val format: UsbRecorder.Format get() =
        UsbRecorder.Format(rate, if (flac) bits.coerceAtMost(24) else bits, channels, flac)

    /** [record]: write a file; otherwise only meter the input (to set the level before a take). */
    suspend fun start(record: Boolean, overwrite: Boolean = false) {
        if (busy) return
        busy = true
        try {
            if (active) ended(withContext(Dispatchers.IO) { rec.stop() }, quiet = true)
            refreshInputs()
            val inp = input ?: run { say("No USB audio input: connect the FM-1 with its USB cable", true); return }
            val out = if (!record) null else try {
                val name = fileName()
                withContext(Dispatchers.IO) { RecFiles.create(app, folder, name, format.mime, overwrite) }
            } catch (e: Exception) {
                say("Cannot create the file in $whereLabel: ${e.message ?: e.javaClass.simpleName}" +
                    if (folder != null) " — choose the folder again" else "", true)
                return
            }
            val err = withContext(Dispatchers.IO) { rec.start(app, inp.device, format, out) }
            if (err != null) { say(err, true); return }
            frames = 0; bytes = 0; clipped = false; warning = null; runRate = rate
            viewFrame = 0.0; viewNs = 0
            analyzer = Spectrum(rate, FFT_SIZE)
            clearAnalysis()
            if (record) recording = true else monitoring = true
        } finally { busy = false }
    }

    suspend fun stop() {
        if (busy || !active) return
        busy = true
        try { ended(withContext(Dispatchers.IO) { rec.stop() }) } finally { busy = false }
    }

    /** Copies the recorder's live values for the page; picks up a run that ended on its own. */
    fun poll() {
        if (busy) return
        frames = rec.frames; bytes = rec.bytes
        peakL = rec.peakL; peakR = rec.peakR; reduction = rec.reduction
        if (rec.clipped) clipped = true
        if (isOn(RecView.SPECTRUM)) analyzer?.let { a ->
            if (rec.copyTap(fftIn)) {
                a.analyze(fftIn, fftNow)
                for (i in 0 until Spectrum.POINTS) {
                    val v = fftNow[i]
                    spec[i] += (v - spec[i]) * (if (v > spec[i]) 0.65f else 0.15f)
                    specHold[i] = max(spec[i], specHold[i] - 0.6f)
                }
                specRev++
            }
        }
        if (isOn(RecView.PAN)) {
            val l = rec.rmsL
            val r = rec.rmsR
            val loud = max(l, r)
            if (loud > 0.0005f) {                                   // above -66 dBFS there is something to place
                pan += ((r - l) / (l + r) - pan) * 0.35f
                trail[trailN++ % PAN_TRAIL] = pan
            }
            panLevel += (((20f * log10(max(loud, 1e-5f)) + 60f) / 60f).coerceIn(0f, 1f) - panLevel) * 0.4f
        }
        warning = rec.warning
        if (active && !rec.running) rec.takeResult()?.let { ended(it) }
    }

    fun resetClip() { rec.clipped = false; clipped = false }

    private fun ended(r: UsbRecorder.Result, quiet: Boolean = false) {
        val wasRecording = recording
        autoTest = false
        recording = false; monitoring = false
        peakL = 0f; peakR = 0f; reduction = 0f
        clearAnalysis()
        if (r.uri != null) {
            last = r
            say("Saved ${r.name} · ${hms(r.seconds)} · ${sizeText(r.bytes)}" + (r.error?.let { " — $it" } ?: ""),
                r.error != null)
        } else if (r.error != null) say(r.error, true)
        else if (wasRecording && !quiet) say("No audio arrived — the file was not kept", true)
    }

    /** The ViewModel is going away: finish the file. */
    fun release() { if (active) rec.stop() }
}

/** The spans of time the waveform can show across its width, seconds: 10 ms (the wave itself) up to a minute. */
val WAVE_ZOOMS = doubleArrayOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 60.0)

private const val FFT_SIZE = 4096
private const val PAN_TRAIL = 20

fun hms(seconds: Double): String {
    val t = seconds.toLong()
    return "%d:%02d:%02d.%d".format(t / 3600, t / 60 % 60, t % 60, ((seconds - t) * 10).toInt().coerceIn(0, 9))
}

fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}
