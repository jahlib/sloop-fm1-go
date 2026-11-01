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
import com.sloop.go.audio.RecFiles
import com.sloop.go.audio.UsbRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    fun nextName(): String {
        val stem = prefix.trim().ifBlank { "FM1" }
        return "${stem}_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.${format.ext}"
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

    var frames by mutableLongStateOf(0); private set
    var runRate by mutableIntStateOf(44100); private set
    var bytes by mutableLongStateOf(0); private set
    var peakL by mutableFloatStateOf(0f); private set
    var peakR by mutableFloatStateOf(0f); private set
    var clipped by mutableStateOf(false); private set
    var silent by mutableFloatStateOf(0f); private set
    var warning by mutableStateOf<String?>(null); private set
    var last by mutableStateOf<UsbRecorder.Result?>(null); private set
    val seconds: Double get() = frames.toDouble() / runRate

    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false); private set
    var said by mutableIntStateOf(0); private set
    fun say(text: String, error: Boolean = false) { message = text; isError = error; said++ }

    /** FLAC is integer only: a 32-bit float choice records 24 bit there (the pick is kept for WAV). */
    val format: UsbRecorder.Format get() =
        UsbRecorder.Format(rate, if (flac) bits.coerceAtMost(24) else bits, channels, flac)

    /** [record]: write a file; otherwise only meter the input (to set the level before a take). */
    suspend fun start(record: Boolean) {
        if (busy) return
        busy = true
        try {
            if (active) ended(withContext(Dispatchers.IO) { rec.stop() }, quiet = true)
            refreshInputs()
            val inp = input ?: run { say("No USB audio input: connect the FM-1 with its USB cable", true); return }
            val out = if (!record) null else try {
                withContext(Dispatchers.IO) { RecFiles.create(app, folder, nextName(), format.mime) }
            } catch (e: Exception) {
                say("Cannot create the file in $whereLabel: ${e.message ?: e.javaClass.simpleName}" +
                    if (folder != null) " — choose the folder again" else "", true)
                return
            }
            val err = withContext(Dispatchers.IO) { rec.start(app, inp.device, format, out) }
            if (err != null) { say(err, true); return }
            frames = 0; bytes = 0; clipped = false; warning = null; silent = 0f; runRate = rate
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
        peakL = rec.peakL; peakR = rec.peakR
        if (rec.clipped) clipped = true
        silent = rec.silentSeconds
        warning = rec.warning
        if (active && !rec.running) rec.takeResult()?.let { ended(it) }
    }

    fun resetClip() { rec.clipped = false; clipped = false }

    private fun ended(r: UsbRecorder.Result, quiet: Boolean = false) {
        val wasRecording = recording
        recording = false; monitoring = false
        peakL = 0f; peakR = 0f
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

fun hms(seconds: Double): String {
    val t = seconds.toLong()
    return "%d:%02d:%02d.%d".format(t / 3600, t / 60 % 60, t % 60, ((seconds - t) * 10).toInt().coerceIn(0, 9))
}

fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}
