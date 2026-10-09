// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.proto

/** A request: command + 7-bit argument bytes. */
data class Req(val cmd: Int, val args: IntArray) {
    override fun equals(other: Any?) = other is Req && cmd == other.cmd && args.contentEquals(other.args)
    override fun hashCode() = 31 * cmd + args.contentHashCode()
}

private fun ia(vararg xs: Int) = xs

/** Request builders, mirroring editor.html `req`. */
object Requests {
    fun info() = Req(Cmd.INFO, IntArray(0))
    fun get(scope: Int, id: Int) = Req(Cmd.GET, ia(scope, id))
    fun set(scope: Int, id: Int, v: Int) = Req(Cmd.SET, intArrayOf(scope, id) + v14enc(v))
    fun dump() = Req(Cmd.DUMP, IntArray(0))
    fun desc(scope: Int, id: Int) = Req(Cmd.DESC, ia(scope, id))
    fun stepGet(i: Int) = Req(Cmd.STEP_GET, ia(i))

    /** v5 step set. Pass lvl/rat = null to keep older-firmware behaviour (levels cleared). */
    fun stepSet(i: Int, st: Step, withExt: Boolean = true): Req {
        val n = st.n.coerceIn(0, 4)
        val notes = IntArray(4) { st.notes.getOrElse(it) { 0 } and 0x7F }
        val head = intArrayOf(i, n) + notes + intArrayOf(
            st.time.coerceIn(0, 2), st.flags and 3, st.vel and 0x7F
        )
        val ext = if (!withExt) IntArray(0) else intArrayOf(
            st.lvl and 0x7F,
            ((st.lvl shr 7) and 1) or (((st.rat shr 7) and 1) shl 1),
            st.rat and 0x7F,
        )
        return Req(Cmd.STEP_SET, head + ext)
    }

    fun drumStepGet(i: Int) = Req(Cmd.DRUM_STEP, ia(i and 0x7F))

    fun drumStepSet(i: Int, d: DrumStep): Req {
        var lv = 0L
        var rt = 0L
        for (l in 0 until 16) if ((d.on shr l) and 1 == 1) {
            lv += (d.lvl[l].toLong() and 3L) shl (2 * l)
            rt += (d.rat[l].toLong() and 3L) shl (2 * l)
        }
        fun g7(x: Long, n: Int) = IntArray(n) { ((x shr (7 * it)) and 0x7F).toInt() }
        val args = intArrayOf(i and 0x7F) +
            g7((d.on.toLong() and 0xFFFF), 3) + g7(lv, 5) + g7(rt, 5)
        return Req(Cmd.DRUM_STEP, args)
    }

    fun preset(e: Int, p: Int) = Req(Cmd.PRESET, ia(e and 0x7F, p and 0x7F))
    fun names(e: Int) = Req(Cmd.NAMES, ia(e and 0x7F))

    /** 0 = off, 1 = pushes, 3 = also TRACK_CHANGED (v4 firmware answers 3, older 1). */
    fun watch(on: Int) = Req(Cmd.WATCH, ia(if (on == 3) 3 else if (on != 0) 1 else 0))
    fun ping() = Req(Cmd.PING, IntArray(0))

    fun track(sel: Int? = null) = Req(Cmd.TRACK, if (sel == null) IntArray(0) else ia(sel and 0x7F))
    fun trackMix(tr: Int, level: Int? = null, mute: Boolean = false): Req =
        if (level == null) Req(Cmd.TRACK_MIX, ia(tr and 0x7F))
        else Req(Cmd.TRACK_MIX, intArrayOf(tr and 0x7F) + v14enc(level) + intArrayOf(if (mute) 1 else 0))
    fun trackDump(tr: Int) = Req(Cmd.TRACK_DUMP, ia(tr and 0x7F))
    fun trackStepGet(tr: Int, i: Int) = Req(Cmd.TRACK_STEP, ia(tr and 0x7F, i and 0x7F))
    fun trackStepSet(tr: Int, i: Int, st: Step): Req {
        val inner = stepSet(i, st).args
        return Req(Cmd.TRACK_STEP, intArrayOf(tr and 0x7F) + inner)
    }

    fun trackParamGet(tr: Int, id: Int) = Req(Cmd.TRACK_PARAM, ia(tr and 0x7F, id and 0x7F))
    fun trackParamSet(tr: Int, id: Int, v: Int) =
        Req(Cmd.TRACK_PARAM, intArrayOf(tr and 0x7F, id and 0x7F) + v14enc(v))

    // v7/v8 (SLOOP 2.4): per-step nudges, fill conditions and parameter locks. All are per-track.
    fun microGet(tr: Int) = Req(Cmd.MICRO_GET, ia(tr and 0x7F))
    fun microSet(tr: Int, step: Int, nudge: Int) =
        Req(Cmd.MICRO_SET, ia(tr and 0x7F, step and 0x7F, (nudge + 64).coerceIn(0, 127)))
    fun fillGet(tr: Int) = Req(Cmd.FILL_GET, ia(tr and 0x7F))
    fun fillSet(tr: Int, step: Int, cond: Int) = Req(Cmd.FILL_SET, ia(tr and 0x7F, step and 0x7F, cond and 3))
    fun lockGet(tr: Int) = Req(Cmd.LOCK_GET, ia(tr and 0x7F))

    // v9 (SLOOP 2.4): FM6 patches. target 0 = a track's own patch, 1 = a bank slot, 2 = a factory patch.
    fun fm6Get(target: Int, index: Int) = Req(Cmd.FM6_GET, ia(target and 0x7F, index and 0x7F))
    fun fm6Put(target: Int, index: Int, packed: IntArray) =
        Req(Cmd.FM6_PUT, intArrayOf(target and 0x7F, index and 0x7F) + IntArray(packed.size) { packed[it] and 0x7F })
    fun fm6List() = Req(Cmd.FM6_LIST, IntArray(0))
    fun fm6Erase(index: Int) = Req(Cmd.FM6_ERASE, ia(index and 0x7F))

    // User sample slots USR1..4 (EDITOR_PROTOCOL.md cmds 11..15). Data/header travel pack7'd.
    fun smpBegin(slot: Int) = Req(Cmd.SMP_BEGIN, ia(slot and 0x7F))
    fun smpWrite(slot: Int, off: Int, bytes: ByteArray, pos: Int, len: Int) =
        Req(Cmd.SMP_WRITE, ia(slot and 0x7F, off and 0x7F, (off shr 7) and 0x7F, (off shr 14) and 0x7F) +
            Smp.pack7(bytes.copyOfRange(pos, pos + len)))
    fun smpEnd(slot: Int, hdr: ByteArray) = Req(Cmd.SMP_END, ia(slot and 0x7F) + Smp.pack7(hdr))
    fun smpErase(slot: Int) = Req(Cmd.SMP_ERASE, ia(slot and 0x7F))
    fun smpInfo() = Req(Cmd.SMP_INFO, IntArray(0))

    // v10 (SLOOP 2.5): SYN drum kits. `which` = a factory kit 0.., or Dsyn.USER + k.
    fun dsynList() = Req(Cmd.DSYN_LIST, IntArray(0))
    fun dsynGet(which: Int) = Req(Cmd.DSYN_GET, ia(which and 0x7F))
    fun dsynSound(k: Int, lane: Int, bytes: ByteArray) =
        Req(Cmd.DSYN_PUT, ia(k and 0x7F, lane and 0x7F) + Smp.pack7(bytes))
    fun dsynHead(k: Int, name: String, crush: Int, src: Int) =
        Req(Cmd.DSYN_PUT, ia(k and 0x7F, 16) + Smp.pack7(Dsyn.name8(name) + byteArrayOf(crush.toByte(), src.toByte())))
    fun dsynCopy(k: Int, src: Int) = Req(Cmd.DSYN_PUT, ia(k and 0x7F, 17) + Smp.pack7(byteArrayOf(src.toByte())))
    fun dsynStore() = Req(Cmd.DSYN_STORE, IntArray(0))
    fun dsynPlay(k: Int, lane: Int, vel: Int = 110) =
        Req(Cmd.DSYN_PLAY, ia(k and 0x7F, lane and 0x7F, vel.coerceIn(1, 127)))

    // v6 backup objects (BK_*), used for the song order and the FM6 bank. Numbers are 5 x 7 bit (u35).
    fun bkList() = Req(Cmd.BK_LIST, IntArray(0))
    fun bkGet(id: Int, off: Int, n: Int) =
        Req(Cmd.BK_GET, ia(id and 0x7F) + Backup.u35(off.toLong()) + ia(n and 0x7F, (n shr 7) and 0x7F))
    fun bkBegin(id: Int, len: Int, crc: Long) =
        Req(Cmd.BK_PUT, ia(0, id and 0x7F) + Backup.u35(len.toLong()) + Backup.u35(crc))
    fun bkData(id: Int, off: Int, bytes: ByteArray) =
        Req(Cmd.BK_PUT, ia(1, id and 0x7F) + Backup.u35(off.toLong()) + Smp.pack7(bytes))
    fun bkCommit(id: Int) = Req(Cmd.BK_PUT, ia(2, id and 0x7F))
    fun bkAbort(id: Int) = Req(Cmd.BK_PUT, ia(3, id and 0x7F))

    /** value = null deletes the lock on (step, param). */
    fun lockSet(tr: Int, step: Int, param: Int, value: Int? = null) =
        Req(Cmd.LOCK_SET, if (value == null) ia(tr and 0x7F, step and 0x7F, param and 0x7F)
            else intArrayOf(tr and 0x7F, step and 0x7F, param and 0x7F) + v14enc(value))
}

/** Which incoming frame is the reply to a given request (the device echoes key args). */
fun replyMatches(cmd: Int, args: IntArray, a: IntArray): Boolean = when (cmd) {
    Cmd.GET, Cmd.SET, Cmd.DESC -> a.getOrNull(0) == args.getOrNull(0) && a.getOrNull(1) == args.getOrNull(1)
    Cmd.STEP_GET, Cmd.STEP_SET, Cmd.NAMES,
    Cmd.SMP_BEGIN, Cmd.SMP_END, Cmd.SMP_ERASE -> a.getOrNull(0) == args.getOrNull(0)
    Cmd.SMP_WRITE -> a.getOrNull(0) == args.getOrNull(0) && a.getOrNull(1) == args.getOrNull(1) &&
        a.getOrNull(2) == args.getOrNull(2) && a.getOrNull(3) == args.getOrNull(3)
    Cmd.PROJECT -> a.getOrNull(0) == args.getOrNull(0) && a.getOrNull(1) == args.getOrNull(1)
    Cmd.UP_LIST, Cmd.UP_GET, Cmd.UP_PUT, Cmd.UP_STORE, Cmd.UP_LOAD, Cmd.UP_ERASE,
    Cmd.TRACK_MIX, Cmd.TRACK_DUMP, Cmd.DRUM_STEP -> a.getOrNull(0) == args.getOrNull(0)
    Cmd.WATCH -> ((a.getOrNull(0) ?: -1) and 1) == ((args.getOrNull(0) ?: -2) and 1)
    Cmd.TRACK_STEP, Cmd.TRACK_PARAM,
    Cmd.MICRO_SET, Cmd.FILL_SET -> a.getOrNull(0) == args.getOrNull(0) && a.getOrNull(1) == args.getOrNull(1)
    Cmd.FM6_GET, Cmd.FM6_PUT -> a.getOrNull(0) == args.getOrNull(0) && a.getOrNull(1) == args.getOrNull(1)
    Cmd.FM6_ERASE -> a.getOrNull(0) == args.getOrNull(0)
    Cmd.DSYN_GET -> a.getOrNull(0) == args.getOrNull(0)
    Cmd.DSYN_PUT, Cmd.DSYN_PLAY -> a.getOrNull(0) == args.getOrNull(0) && a.getOrNull(1) == args.getOrNull(1)
    Cmd.BK_GET -> a.getOrNull(0) == args.getOrNull(0) && (0 until 5).all { a.getOrNull(2 + it) == args.getOrNull(1 + it) }
    Cmd.BK_PUT -> a.getOrNull(0) == args.getOrNull(0) && a.getOrNull(1) == args.getOrNull(1)
    Cmd.MICRO_GET, Cmd.FILL_GET, Cmd.LOCK_GET -> a.getOrNull(0) == args.getOrNull(0)
    Cmd.LOCK_SET -> a.getOrNull(0) == args.getOrNull(0) && a.getOrNull(1) == args.getOrNull(1) &&
        a.getOrNull(2) == args.getOrNull(2)
    else -> true
}
