// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RIFF/WAVE for the USB recorder: PCM 16 / 24 bit or IEEE float 32 bit, little endian, a 44-byte header. */
object Wav {
    const val HEADER = 44
    /** The largest data chunk the RIFF size field (36 + data, unsigned 32 bit) can describe. */
    const val MAX_DATA = 0xFFFFFFFFL - 36

    fun header(rate: Int, channels: Int, bits: Int, dataBytes: Long): ByteArray {
        val data = dataBytes.coerceIn(0, MAX_DATA)
        val block = channels * bits / 8
        return ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + data).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16)
            putShort((if (bits == 32) 3 else 1).toShort())      // 3 IEEE float, 1 PCM
            putShort(channels.toShort()); putInt(rate); putInt(rate * block)
            putShort(block.toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(data.toInt())
        }.array()
    }

    /**
     * Interleaved stereo floats ([frames] of them) -> [out] in the file's format; returns the bytes written.
     * Integer formats scale by 2^(bits-1), so a 16-bit source (the FM-1's stream) comes back bit for bit.
     * Mono is the mean of L and R.
     */
    fun encode(src: FloatArray, frames: Int, channels: Int, bits: Int, out: ByteArray): Int {
        var o = 0
        for (f in 0 until frames) {
            val l = src[2 * f]
            val r = src[2 * f + 1]
            if (channels == 1) o = put((l + r) * 0.5f, bits, out, o)
            else { o = put(l, bits, out, o); o = put(r, bits, out, o) }
        }
        return o
    }

    /** A float sample as a [bits]-bit integer (16 or 24): scaled by 2^(bits-1), rounded, clipped. */
    fun quant(x: Float, bits: Int): Int = if (bits == 16) Math.round(x * 32768f).coerceIn(-32768, 32767)
        else Math.round(x.toDouble() * 8388608.0).coerceIn(-8388608L, 8388607L).toInt()

    private fun put(x: Float, bits: Int, out: ByteArray, at: Int): Int = when (bits) {
        16 -> {
            val v = quant(x, 16)
            out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte()
            at + 2
        }
        24 -> {
            val v = quant(x, 24)
            out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte(); out[at + 2] = (v shr 16).toByte()
            at + 3
        }
        else -> {
            val v = java.lang.Float.floatToRawIntBits(x)
            out[at] = v.toByte(); out[at + 1] = (v shr 8).toByte()
            out[at + 2] = (v shr 16).toByte(); out[at + 3] = (v shr 24).toByte()
            at + 4
        }
    }
}
