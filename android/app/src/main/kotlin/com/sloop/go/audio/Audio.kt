// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import com.sloop.go.proto.Smp
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Audio in/out helpers for the Samples page: file decode, mic capture, zone preview. */
object Audio {

    const val REC_RATE = 44100
    const val MAX_REC_SECONDS = 60

    /**
     * Any readable audio -> mono samples + rate. WAVs go through the editor's own parser (it is
     * exact); everything else (mp3, m4a, ogg, flac, aiff…) through MediaExtractor + MediaCodec.
     */
    fun decode(ctx: Context, uri: Uri): Pair<Int, DoubleArray> {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val head = ByteArray(16)
            val n = s.read(head)
            if (n >= 12 && head[0] == 'R'.code.toByte() && head[8] == 'W'.code.toByte()) {
                val bytes = head.copyOf(n) + s.readBytes()
                try { return Smp.parseWav(bytes) } catch (_: Exception) { /* a WAV we can't read */ }
            }
        }
        return decodeCompressed(ctx, uri)
    }

    /** Compressed/container audio -> mono PCM16 -> floats, via the platform codecs. */
    private fun decodeCompressed(ctx: Context, uri: Uri): Pair<Int, DoubleArray> {
        val ex = MediaExtractor()
        try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { ex.setDataSource(it.fileDescriptor) }
                ?: throw Smp.SlotError("cannot read file")
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) throw Smp.SlotError("no audio in file")
            val sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            ex.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val chunks = ArrayList<ShortArray>()
            var total = 0
            val info = MediaCodec.BufferInfo()
            var eos = false
            try {
                while (!eos) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                    while (true) {
                        val oi = codec.dequeueOutputBuffer(info, 10_000)
                        if (oi < 0) break
                        val buf = codec.getOutputBuffer(oi)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val a = ShortArray(sb.remaining())
                        sb.get(a)
                        chunks.add(a)
                        total += a.size
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(oi, false)
                        if (end) { eos = true; break }
                    }
                }
            } finally {
                codec.stop(); codec.release(); ex.release()
            }
            val frames = total / ch
            if (frames <= 0) throw Smp.SlotError("no audio in file")
            val x = DoubleArray(frames)
            var at = 0
            for (c in 0 until chunks.size) {
                val a = chunks[c]
                for (i in a.indices step ch) {
                    var acc = 0.0
                    var k = 0
                    while (k < ch && i + k < a.size) { acc += a[i + k] / 32768.0; k++ }
                    val f = at + i / ch
                    if (f < frames) x[f] = acc / k
                }
                at += a.size / ch
            }
            return sr to x
        } catch (e: Smp.SlotError) { throw e }
        catch (e: Exception) { throw Smp.SlotError("cannot decode: ${e.message ?: "unsupported file"}") }
    }

    // ---------------------------------------------------------------- mic ---

    /** A microphone choice: an AudioSource preset (no device pinned) or a concrete input device. */
    class MicOption(val label: String, val source: Int, val device: AudioDeviceInfo? = null)

    /** The plain "Mic" preset, when nothing was picked. */
    fun micDefault() = MicOption("Mic", MediaRecorder.AudioSource.MIC)

    /** Mics to offer: source presets (voice call, camera back, raw) + the inputs the phone reports. */
    fun mics(ctx: Context): List<MicOption> {
        val out = mutableListOf(
            MicOption("Camera back", MediaRecorder.AudioSource.CAMCORDER),
            MicOption("Mic", MediaRecorder.AudioSource.MIC),
            MicOption("Voice call", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
            MicOption("Raw", MediaRecorder.AudioSource.UNPROCESSED),
        )
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            val label = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                    d.address?.takeIf { it.isNotBlank() }?.let { "Mic · $it" }
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset mic"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB mic"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic"
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE mic"
                else -> null
            } ?: continue
            out += MicOption(label, MediaRecorder.AudioSource.MIC, d)
        }
        return out
    }

    /** AudioRecord wrapper: 44100 Hz mono PCM16 into a growing buffer, with a live peak level. */
    class Recorder {
        @Volatile var level = 0f; private set
        @Volatile var recording = false; private set
        @Volatile var seconds = 0f; private set
        private var thread: Thread? = null
        private var rec: AudioRecord? = null
        private val chunks = ArrayList<ShortArray>()
        @Volatile private var total = 0

        fun start(mic: MicOption): Boolean {
            if (recording) return true
            val min = AudioRecord.getMinBufferSize(REC_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            val r = try {
                AudioRecord.Builder()
                    .setAudioSource(mic.source)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(REC_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(max(min, 8192))
                    .build()
            } catch (e: Exception) { return false }
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            if (mic.device != null && !r.setPreferredDevice(mic.device)) { r.release(); return false }
            try { r.startRecording() } catch (e: Exception) { r.release(); return false }
            chunks.clear(); total = 0; level = 0f; seconds = 0f
            rec = r
            recording = true
            thread = Thread {
                try {
                    val buf = ShortArray(4096)
                    val cap = REC_RATE * MAX_REC_SECONDS
                    while (recording && total < cap) {
                        val n = r.read(buf, 0, min(buf.size, cap - total))
                        if (n <= 0) break
                        var pk = 0
                        for (i in 0 until n) pk = max(pk, abs(buf[i].toInt()))
                        level = pk / 32768f
                        chunks.add(buf.copyOf(n))
                        total += n
                        seconds = total.toFloat() / REC_RATE
                    }
                } catch (_: Exception) { /* released while reading */ }
            }.also { it.start() }
            return true
        }

        /** Stops and returns the recording as mono floats at REC_RATE. */
        fun stop(): DoubleArray {
            recording = false
            val r = rec
            if (r != null) runCatching { r.stop() }    /* also unblocks a pending read() */
            thread?.join(800); thread = null
            r?.release(); rec = null
            level = 0f
            val x = DoubleArray(total)
            var at = 0
            for (c in chunks) { for (v in c) x[at++] = v / 32768.0 }
            chunks.clear(); total = 0
            return x
        }

        fun release() { if (recording) stop() }
    }

    // ---------------------------------------------------------------- preview ---

    private var track: AudioTrack? = null
    @Volatile var playing = false; private set

    /** Plays int16 samples at Smp.RATE once (the zones' format). */
    @Synchronized fun play(s: ShortArray) {
        stop()
        if (s.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(Smp.RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(s.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.setNotificationMarkerPosition(s.size)
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) { playing = false }
            override fun onPeriodicNotification(t: AudioTrack?) {}
        })
        t.write(s, 0, s.size)
        t.play()
        track = t
        playing = true
    }

    /** Plays a range of a float source (the chop tool's recording). */
    fun play(x: DoubleArray, start: Int, end: Int) {
        val n = max(1, end - start)
        val s = ShortArray(n) { i ->
            (x.getOrElse(start + i) { 0.0 } * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        play(s)
    }

    @Synchronized fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        playing = false
    }
}
