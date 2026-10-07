// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.midi

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** A MIDI port pair the user can connect to. */
data class MidiDeviceDesc(
    val id: Int,
    val name: String,
    val manufacturer: String,
    val hasInput: Boolean,   // Android input port = host -> device (we send)
    val hasOutput: Boolean,  // Android output port = device -> host (we receive)
)

/** An open connection: send bytes to the device, receive reassembled SysEx frames. */
class MidiConnection internal constructor(
    private val device: android.media.midi.MidiDevice,
    private val inPort: MidiInputPort?,
    private val outPort: MidiOutputPort?,
    private val receiver: MidiReceiver?,
    private val manager: MidiManager,
    private val callback: MidiManager.DeviceCallback,
) {
    fun send(bytes: ByteArray) {
        val p = inPort ?: throw java.io.IOException("MIDI input port unavailable")
        p.send(bytes, 0, bytes.size)
    }

    fun close() {
        try { manager.unregisterDeviceCallback(callback) } catch (_: Exception) {}
        try { if (receiver != null) outPort?.disconnect(receiver) } catch (_: Exception) {}
        try { inPort?.close() } catch (_: Exception) {}
        try { outPort?.close() } catch (_: Exception) {}
        try { device.close() } catch (_: Exception) {}
    }
}

/**
 * Thin wrapper over the system [MidiManager] for direct USB-MIDI. Enumerates devices, opens a
 * device's first input + output ports, and reassembles complete SysEx frames before delivering
 * them (Android may split a SysEx across several onSend calls).
 */
class MidiEngine(private val context: Context) {

    private val manager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private val ioThread = HandlerThread("sloop-midi").apply { start() }
    private val ioHandler = Handler(ioThread.looper)

    val available: Boolean get() = manager != null

    @Suppress("DEPRECATION")
    private fun infos(): Array<MidiDeviceInfo> = manager?.devices ?: emptyArray()

    fun devices(): List<MidiDeviceDesc> = infos().mapNotNull { info ->
        var hasIn = false
        var hasOut = false
        for (p in info.ports) when (p.type) {
            MidiDeviceInfo.PortInfo.TYPE_INPUT -> hasIn = true
            MidiDeviceInfo.PortInfo.TYPE_OUTPUT -> hasOut = true
        }
        if (!hasIn && !hasOut) return@mapNotNull null
        MidiDeviceDesc(info.id, name(info), manufacturer(info), hasIn, hasOut)
    }

    /** Picks the FM-1 / SLOOP (Felucca) device if present, else the first device. */
    fun preferredDevice(): MidiDeviceDesc? {
        val all = devices()
        return all.firstOrNull { it.name.contains("felucca", true) || it.name.contains("sloop", true) }
            ?: all.firstOrNull()
    }

    private fun name(info: MidiDeviceInfo): String {
        val p = info.properties
        return listOfNotNull(
            p.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            p.getString(MidiDeviceInfo.PROPERTY_NAME),
        ).firstOrNull { !it.isNullOrBlank() } ?: "MIDI ${info.id}"
    }

    private fun manufacturer(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: ""

    /** Opens the device by id and wires up receive. Returns null on failure. */
    suspend fun open(deviceId: Int, onReceive: (ByteArray) -> Unit, onLost: () -> Unit): MidiConnection? {
        val mm = manager ?: return null
        val info = infos().firstOrNull { it.id == deviceId } ?: return null
        return suspendCancellableCoroutine { cont ->
            mm.openDevice(info, { device ->
                if (device == null) {
                    if (cont.isActive) cont.resume(null)
                    return@openDevice
                }
                val inNum = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }?.portNumber
                val outNum = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }?.portNumber
                val inPort = inNum?.let { device.openInputPort(it) }
                val outPort = outNum?.let { device.openOutputPort(it) }
                val receiver = if (outPort != null) {
                    SysexReceiver(onReceive).also { outPort.connect(it) }
                } else null
                if (inPort == null && outPort == null) {
                    try { device.close() } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(null)
                    return@openDevice
                }
                val callback = object : MidiManager.DeviceCallback() {
                    override fun onDeviceRemoved(removed: MidiDeviceInfo) {
                        if (removed.id == deviceId) onLost()
                    }
                }
                mm.registerDeviceCallback(callback, ioHandler)
                if (cont.isActive) cont.resume(MidiConnection(device, inPort, outPort, receiver, mm, callback))
                else {
                    mm.unregisterDeviceCallback(callback)
                    try { inPort?.close(); outPort?.close(); device.close() } catch (_: Exception) {}
                }
            }, ioHandler)
        }
    }

    /**
     * Best-effort raw USB permission request for the FM-1 (VID 0x1209 / PID 0x0001). MidiManager
     * usually opens USB-MIDI without it, but requesting grants the most reliable, persistent access.
     */
    fun requestUsbPermission(context: Context) {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val device = usb.deviceList.values.firstOrNull { it.vendorId == 0x1209 && it.productId == 0x0001 }
            ?: usb.deviceList.values.firstOrNull() ?: return
        if (usb.hasPermission(device)) return
        val action = "com.sloop.go.USB_PERMISSION"
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try { c.unregisterReceiver(this) } catch (_: Exception) {}
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        usb.requestPermission(device, pi)
    }

    fun release() {
        ioThread.quitSafely()
    }

    /** Reassembles complete SysEx frames (F0..F7). Non-SysEx bytes are ignored. */
    private class SysexReceiver(private val onFrame: (ByteArray) -> Unit) : MidiReceiver() {
        private val buf = ArrayList<Byte>(640)
        private var inSysex = false

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                when {
                    b >= 0xF8 -> Unit
                    b == 0xF0 -> { inSysex = true; buf.clear(); buf.add(msg[i]) }
                    inSysex && b in 0x80..0xF6 -> { inSysex = false; buf.clear() }
                    inSysex -> {
                        buf.add(msg[i])
                        if (b == 0xF7) {
                            inSysex = false
                            onFrame(buf.toByteArray())
                            buf.clear()
                        }
                    }
                }
                i++
            }
        }
    }
}
