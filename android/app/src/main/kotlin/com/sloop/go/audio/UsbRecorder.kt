// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.audio

import android.content.ContentValues
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Records the FM-1's USB audio (SLOOP / Felucca UAC1: the master out, stereo 16 bit at 44.1 or 48 kHz) into a WAV
 * or FLAC file ([FlacEncoder]), as a computer's DAW would. The stream reaches Android's audio stack as a USB input device; this reads it with
 * AudioRecord pinned to that device and writes the file from its own thread. With no [Out] it only meters.
 */
class UsbRecorder {
    /** A USB audio input the phone reports, with what it says it can do (empty arrays: unknown / any). */
    class Input(val device: AudioDeviceInfo) {
        val id: Int get() = device.id
        val label: String = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB audio"
        val rates: IntArray = device.sampleRates
        val channels: IntArray = device.channelCounts
    }

    /** [bits]: 16 or 24 (PCM) or 32 (float, WAV only); [channels]: 2 or 1 (L + R mixed); [flac]: FLAC, else WAV. */
    data class Format(val rate: Int, val bits: Int, val channels: Int, val flac: Boolean = false) {
        /** Uncompressed; a FLAC file is smaller by how much the music lets it. */
        val bytesPerSecond: Int get() = rate * channels * bits / 8
        val ext: String get() = if (flac) "flac" else "wav"
        val mime: String get() = if (flac) FlacEncoder.MIME else RecFiles.MIME
    }

    /** Where a take goes: an open read-write descriptor, how to keep the file once written and how to drop it. */
    class Out(val name: String, val where: String, val uri: Uri, val pfd: ParcelFileDescriptor,
              val commit: () -> Unit, val discard: () -> Unit)

    /** A finished run. [uri] is null when nothing was kept (metering only, or no audio arrived). */
    class Result(val name: String?, val where: String?, val uri: Uri?, val mime: String, val seconds: Double,
                 val bytes: Long, val headerOk: Boolean, val error: String?)

    @Volatile var running = false; private set
    @Volatile var frames = 0L; private set
    @Volatile var bytes = 0L; private set
    @Volatile var peakL = 0f; private set
    @Volatile var peakR = 0f; private set
    @Volatile var clipped = false
    @Volatile var silentSeconds = 0f; private set
    @Volatile var warning: String? = null; private set
    @Volatile var rate = 0; private set

    private var thread: Thread? = null
    private var result: Result? = null

    /** Starts reading [device] in [fmt], writing to [out] when given. Returns an error text, or null when running. */
    fun start(ctx: Context, device: AudioDeviceInfo?, fmt: Format, out: Out?): String? {
        if (running) return "The recorder is already running"
        var float = true
        val r = open(ctx, fmt.rate, true) ?: open(ctx, fmt.rate, false).also { float = false }
        if (r == null) { out?.let(::drop); return "Cannot open the USB input at ${fmt.rate} Hz" }
        if (device != null && !r.setPreferredDevice(device)) {
            r.release(); out?.let(::drop); return "Android refused the USB input"
        }
        val fos = out?.let { ParcelFileDescriptor.AutoCloseOutputStream(it.pfd) }
        val os = fos?.let { BufferedOutputStream(it, 1 shl 18) }
        val flac = if (os != null && fmt.flac) FlacEncoder(fmt.rate, fmt.channels, fmt.bits, os) else null
        try {
            // playable even if never patched: WAV with an unknown length, FLAC with an unknown length / MD5
            os?.write(flac?.header() ?: Wav.header(fmt.rate, fmt.channels, fmt.bits, Wav.MAX_DATA))
            r.startRecording()
        } catch (e: Exception) {
            r.release(); runCatching { fos?.close() }; out?.discard?.invoke()
            return "Cannot start recording: ${e.message ?: e.javaClass.simpleName}"
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release(); runCatching { fos?.close() }; out?.discard?.invoke()
            return "The USB input did not start (is another app recording?)"
        }
        frames = 0; bytes = 0; peakL = 0f; peakR = 0f; clipped = false; silentSeconds = 0f; warning = null
        rate = fmt.rate; result = null
        running = true
        thread = Thread({ loop(r, float, fmt, device, out, fos, os, flac) }, "usb-recorder").also { it.start() }
        return null
    }

    /** Stops, finishes the file and returns the run (also when the thread had already ended on its own). */
    fun stop(): Result {
        running = false
        thread?.join(4000)
        thread = null
        return takeResult() ?: Result(null, null, null, RecFiles.MIME, 0.0, 0, false, null)
    }

    /** The run the thread finished on its own (an unplugged cable, a write error, the size limit), once. */
    @Synchronized fun takeResult(): Result? = result.also { result = null }

    private fun loop(r: AudioRecord, float: Boolean, fmt: Format, device: AudioDeviceInfo?, out: Out?,
                     fos: ParcelFileDescriptor.AutoCloseOutputStream?, os: BufferedOutputStream?,
                     flac: FlacEncoder?) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = fmt.rate / 50                                   // 20 ms
        val f = FloatArray(block * 2)
        val s = if (float) null else ShortArray(block * 2)
        val buf = if (flac == null) ByteArray(block * fmt.channels * fmt.bits / 8) else ByteArray(0)
        var error: String? = null
        var routeChecked = false
        try {
            while (running) {
                val n = if (s == null) r.read(f, 0, f.size, AudioRecord.READ_BLOCKING)
                    else r.read(s, 0, s.size).also { k -> for (i in 0 until k) f[i] = s[i] / 32768f }
                if (n < 0) { if (running) error = "The USB input stopped (error $n): is the FM-1 still connected?"; break }
                val fr = n / 2
                if (fr == 0) continue
                var pl = 0f
                var pr = 0f
                for (i in 0 until fr) { pl = max(pl, abs(f[2 * i])); pr = max(pr, abs(f[2 * i + 1])) }
                if (pl >= CLIP || pr >= CLIP) clipped = true
                peakL = max(pl, peakL * 0.8f); peakR = max(pr, peakR * 0.8f)
                silentSeconds = if (max(pl, pr) < SILENCE) silentSeconds + fr.toFloat() / fmt.rate else 0f
                if (flac != null) bytes += flac.write(f, fr)
                else if (os != null) {
                    val nb = Wav.encode(f, fr, fmt.channels, fmt.bits, buf)
                    if (bytes + nb > Wav.MAX_DATA) { error = "Stopped at the WAV size limit (4 GB)"; break }
                    os.write(buf, 0, nb)
                    bytes += nb
                }
                frames += fr
                if (!routeChecked && frames > fmt.rate / 2) {
                    routeChecked = true
                    val routed = r.routedDevice
                    if (device != null && routed != null && routed.id != device.id)
                        warning = "Android records from \"${routed.productName}\", not the chosen USB input"
                }
            }
        } catch (e: Exception) {
            error = "Write failed: ${e.message ?: e.javaClass.simpleName}"
        }
        runCatching { r.stop() }
        r.release()
        var headerOk = false
        if (fos != null && os != null) {
            headerOk = runCatching {
                val head = flac?.finish()?.also { bytes = flac.bytes } ?: Wav.header(fmt.rate, fmt.channels, fmt.bits, bytes)
                os.flush()
                val ch = fos.channel
                ch.position(0)
                ch.write(ByteBuffer.wrap(head))
            }.isSuccess
            runCatching { fos.fd.sync() }
            runCatching { os.close() }
        }
        val seconds = frames.toDouble() / fmt.rate
        val kept = out != null && bytes > 0
        if (out != null) runCatching { if (kept) out.commit() else out.discard() }
        synchronized(this) {
            result = Result(out?.name, out?.where, if (kept) out?.uri else null, fmt.mime, seconds, bytes, headerOk, error)
        }
        peakL = 0f; peakR = 0f
        running = false
    }

    private fun drop(out: Out) {
        runCatching { out.pfd.close() }
        runCatching { out.discard() }
    }

    private fun open(ctx: Context, rate: Int, float: Boolean): AudioRecord? {
        val enc = if (float) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, enc)
        if (min <= 0) return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // UNPROCESSED: no gain control / noise suppression where the phone offers it; USB inputs rarely get any anyway
        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        val quarter = rate / 4 * 2 * (if (float) 4 else 2)               // 250 ms of stereo
        return try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(enc)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(max(min * 2, quarter))
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED || run { it.release(); false } }
        } catch (e: Exception) { null }
    }

    companion object {
        private const val CLIP = 0.9999f
        private const val SILENCE = 1f / 32768f
        private val USB = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY)

        /** The USB audio inputs the phone sees (the FM-1 shows up as one while its cable is in). */
        fun inputs(ctx: Context): List<Input> {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in USB }.map(::Input)
        }
    }
}

/** Where the recorder's files go: a folder the user picked (SAF), else Music/Sloop Go. */
object RecFiles {
    private const val DIR = "Sloop Go"
    const val MIME = "audio/x-wav"

    /** The default location as the user sees it. */
    fun defaultLabel(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 29) "${Environment.DIRECTORY_MUSIC}/$DIR" else defaultDir(ctx).path

    private fun defaultDir(ctx: Context) =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(ctx.filesDir, "Music"), DIR)

    /** A picked folder's name, for the page. */
    fun folderName(ctx: Context, tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: tree.lastPathSegment ?: "Folder"

    fun create(ctx: Context, tree: Uri?, name: String, mime: String = MIME): UsbRecorder.Out {
        val cr = ctx.contentResolver
        if (tree != null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val uri = DocumentsContract.createDocument(cr, parent, mime, name)
                ?: throw IOException("the folder refused a new file")
            val pfd = cr.openFileDescriptor(uri, "rw")
                ?: run { DocumentsContract.deleteDocument(cr, uri); throw IOException("cannot open $name") }
            return UsbRecorder.Out(name, folderName(ctx, tree), uri, pfd, commit = {},
                discard = { runCatching { DocumentsContract.deleteDocument(cr, uri) } })
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$DIR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("the media store refused a new file")
            val pfd = cr.openFileDescriptor(uri, "rw")
                ?: run { cr.delete(uri, null, null); throw IOException("cannot open $name") }
            return UsbRecorder.Out(name, defaultLabel(ctx), uri, pfd,
                commit = { cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) },
                discard = { cr.delete(uri, null, null) })
        }
        val dir = defaultDir(ctx).apply { mkdirs() }
        val file = File(dir, name)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        val uri = runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file) }
            .getOrElse { Uri.fromFile(file) }
        return UsbRecorder.Out(name, dir.path, uri, pfd, commit = {}, discard = { file.delete() })
    }
}
