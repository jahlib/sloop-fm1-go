// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go contributors. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.store

/** The ready-made patterns written to the store on first run (then they are the user's: rename, delete, replace). */
object DefaultClips {
    private const val KICK = 0
    private const val KICK2 = 1
    private const val SNARE = 2
    private const val CLAP = 3
    private const val HAT = 4
    private const val OPEN_HAT = 5
    private const val PEDAL = 6
    private const val RIM = 7
    private const val SNARE2 = 8
    private const val TOM_LO = 9
    private const val TOM_HI = 10
    private const val CRASH = 11
    private const val RIDE = 12
    private const val SHAKER = 13
    private const val CONGA = 14
    private const val COWBELL = 15

    /** One bar of 16 steps, a lane per row ('x' = hit). */
    private fun drum(name: String, vararg lanes: Pair<Int, String>): Pair<String, Clip> =
        name to Clip(ClipKind.DRUM, 16, lanes.flatMap { (lane, s) ->
            s.mapIndexedNotNull { i, c -> if (c == 'x') ClipNote(i, 1, lane, 100) else null }
        })

    /** Four bars of 64 steps; each lane is a 64-char row (usually a 16-step row repeated). */
    private fun drum64(name: String, vararg lanes: Pair<Int, String>): Pair<String, Clip> =
        name to Clip(ClipKind.DRUM, 64, lanes.flatMap { (lane, s) ->
            s.mapIndexedNotNull { i, c -> if (c == 'x') ClipNote(i, 1, lane, 100) else null }
        })

    /** [length] steps (32 / 64), a lane per row; shorter rows simply end early. */
    private fun drumN(name: String, length: Int, vararg lanes: Pair<Int, String>): Pair<String, Clip> =
        name to Clip(ClipKind.DRUM, length, lanes.flatMap { (lane, s) ->
            s.take(length).mapIndexedNotNull { i, c -> if (c == 'x') ClipNote(i, 1, lane, 100) else null }
        })

    /** A chord as "start:length:pitch" words. */
    private fun chord(start: Int, len: Int, vararg pitches: Int) = pitches.joinToString(" ") { "$start:$len:$it" }

    /** The same words moved [by] steps later. */
    private fun shift(notes: String, by: Int) = notes.trim().split(Regex("\\s+")).joinToString(" ") {
        val (s, l, p) = it.split(":").map(String::toInt)
        "${s + by}:$l:$p"
    }

    /** Notes as "start:length:pitch" words. */
    private fun piano(name: String, notes: String, length: Int = 16): Pair<String, Clip> =
        name to Clip(ClipKind.PIANO, length, notes.trim().split(Regex("\\s+")).map {
            val (s, l, p) = it.split(":").map(String::toInt)
            ClipNote(s, l, p, 100)
        })

    private fun every(step: Int, vararg pitches: Int, count: Int = 16) =
        (0 until count).joinToString(" ") { i -> "${i * step}:1:${pitches[i % pitches.size]}" }

    /** A [block]-step phrase repeated [times] times, shifting each note's start — for 32/64-step clips. */
    private fun phrase(notes: String, block: Int, times: Int) =
        (0 until times).joinToString(" ") { rep ->
            notes.trim().split(Regex("\\s+")).joinToString(" ") { w ->
                val (s, l, p) = w.split(":").map(String::toInt)
                "${rep * block + s}:$l:$p"
            }
        }

    fun drums(): List<Pair<String, Clip>> = drumsBase() + drumsNew()

    fun piano(): List<Pair<String, Clip>> = pianoBase() + pianoNew()

    /** Added with 1.6.8 — an existing install gets only these. */
    fun drumsNew(): List<Pair<String, Clip>> = listOf(
        drumN("Two bar fill", 32,
            KICK to "x...x...x...x...".repeat(2), CLAP to "....x.......x...".repeat(2),
            HAT to "x.x.x.x.x.x.x.x.".repeat(2), OPEN_HAT to "..x...x...x...x.".repeat(2),
            TOM_HI to "................" + "............x.x.", TOM_LO to "................" + "..............x."),
        drumN("Rock fill", 32,
            KICK to "x.......x.x....." + "x.......x.x.x...",
            SNARE to "....x.......x..." + "....x.......x.xx",
            HAT to "x.x.x.x.x.x.x.x.".repeat(2), CRASH to "x" + ".".repeat(31),
            TOM_LO to "................" + "............x..."),
        drumN("Trap roll", 32,
            KICK to "x......x..x....." + "x..x....x.x.....",
            CLAP to "........x......." + "........x.......",
            HAT to "x.x.x.xxx.x.xxxx" + "x.x.xxx.x.xxxxxx",
            OPEN_HAT to "......x........." + "..............x."),
        drumN("Dub techno", 32,
            KICK to "x...x...x...x...".repeat(2), OPEN_HAT to "..x...x...x...x.".repeat(2),
            RIM to "...x.......x...." + "...x.......x..x.",
            SHAKER to "x.x.x.x.x.x.x.x." + "x.xxx.x.x.xxx.x.", PEDAL to "x...............".repeat(2)),
        drumN("UK garage", 32,
            KICK to "x.........x....." + "x..x......x.....",
            SNARE to "....x.......x..." + "....x.......x.x.",
            HAT to "x.x.x.x.x.x.x.x.".repeat(2), SHAKER to ".x.xx.x..x.xx.x." + ".x.x..x.x..xx.xx"),
        drumN("Latin clave", 32,
            KICK to "x.......x......." + "x.......x..x....",
            RIM to "x..x..x...x.x..." + "..x.x...x..x..x.",
            CONGA to "..x..x..x..x..x." + "..x.x.x...x.x.x.",
            COWBELL to "x...x...x...x...".repeat(2), PEDAL to "x.x.x.x.x.x.x.x.".repeat(2)),
        drumN("Footwork", 64,
            KICK to "x..x..x.x..x..x.".repeat(4),
            CLAP to "....x.......x..." + "....x.......x..." + "....x.......x..." + "....x...x.x.x.x.",
            HAT to "x.x.x.x.x.x.x.x.".repeat(4), OPEN_HAT to "..............x.".repeat(4),
            SHAKER to "x" + ".".repeat(63)),
        drumN("Jungle", 64,
            KICK to "x.........x....." + "x.........x....." + "x.x.......x....." + "x....x....x.....",
            SNARE to "....x..x.x..x...".repeat(4),
            HAT to "x.x.x.x.x.x.x.x.".repeat(4), RIDE to "..x...x...x...x.".repeat(4),
            CRASH to "x" + ".".repeat(63)),
        drumN("Hip hop long", 64,
            KICK to "x.....x..x.x...." + "x.....x..x.x...." + "x.....x..x.x...." + "x.....x..x......",
            SNARE to "....x.......x...".repeat(4),
            HAT to "x.x.x.x.x.x.x.x.".repeat(4),
            OPEN_HAT to "..............x." + "................" + "..............x." + "............x.x.",
            SHAKER to ".x.x.x.x.x.x.x.x".repeat(4)),
        drumN("Build up", 64,
            KICK to "x...x...x...x...".repeat(4),
            SNARE to "........x......." + "....x...x...x..." + "x.x.x.x.x.x.x.x." + "xxxxxxxxxxxxxxxx",
            HAT to "x.x.x.x.x.x.x.x.".repeat(4), CRASH to "x" + ".".repeat(63),
            TOM_HI to "................".repeat(3) + "x.x.x.x........."),
    )

    private fun drumsBase(): List<Pair<String, Clip>> = listOf(
        drum("Four on the floor",
            KICK to "x...x...x...x...", CLAP to "....x.......x...",
            HAT to "x.x.x.x.x.x.x.x.", OPEN_HAT to "..x...x...x...x."),
        drum("Rock",
            KICK to "x.......x.x.....", SNARE to "....x.......x...",
            HAT to "x.x.x.x.x.x.x.x.", CRASH to "x..............."),
        drum("Boom bap",
            KICK to "x.....x..x.x....", SNARE to "....x.......x...",
            HAT to "x.x.x.x.x.x.x.x.", OPEN_HAT to "..............x."),
        drum("Trap",
            KICK to "x......x..x.....", CLAP to "........x.......",
            HAT to "x.x.x.xxx.x.x.xx", OPEN_HAT to "......x........."),
        drum("House",
            KICK to "x...x...x...x...", CLAP to "....x.......x...",
            OPEN_HAT to "..x...x...x...x.", HAT to "x.xxx.xxx.xxx.xx", SHAKER to ".x.x.x.x.x.x.x.x"),
        drum("Techno",
            KICK to "x...x...x...x...", HAT to "x.xxx.xxx.xxx.xx",
            OPEN_HAT to "..x...x...x...x.", RIM to "...x.......x....", CLAP to "............x..."),
        drum("Breakbeat",
            KICK to "x.x.......xx....", SNARE to "....x..x.x..x...",
            HAT to "x.x.x.x.x.x.x.x.", OPEN_HAT to "......x.......x."),
        drum("Drum & bass",
            KICK to "x.........x.....", SNARE to "....x.......x...",
            HAT to "x.x.x.x.x.x.x.x.", RIDE to "..x...x...x...x."),
        drum("Disco",
            KICK to "x...x...x...x...", SNARE to "....x.......x...",
            HAT to "x...x...x...x...", OPEN_HAT to "..x...x...x...x.", CLAP to "....x.......x..."),
        drum("Funk",
            KICK to "x.....x..x.x....", SNARE to "....x.......x...",
            SNARE2 to ".x.....x...x....", HAT to "x.xxx.xxx.xxx.xx"),
        drum("Reggae one drop",
            KICK to "........x.......", RIM to "........x.......",
            HAT to "..x...x...x...x.", PEDAL to "x...x...x...x..."),
        drum("Bossa nova",
            KICK to "x..x....x..x....", RIM to "x..x..x...x..x..",
            PEDAL to "x.x.x.x.x.x.x.x.", CONGA to "....x.....x.x...", COWBELL to "x......x........"),
        drum("Half time",
            KICK to "x.........x.....", SNARE to "........x.......",
            HAT to "x.x.x.x.x.x.x.x.", OPEN_HAT to "..............x."),
        drum("Double time",
            KICK to "x.........x.....", SNARE to "....x.....x..x..",
            HAT to "x.xxx.xxx.xxx.xx", SHAKER to "..x...x...x...x."),
        drum("Swing",
            KICK to "x...x.....x.....", SNARE to "....x.......x...",
            HAT to "x.xx.xx.xx.xx.xx", OPEN_HAT to "..x.......x....."),
        drum("Electro",
            KICK to "x.....x..x......", SNARE to "....x.......x...",
            CLAP to "....x.......x...", HAT to "..x...x...x...x.", RIM to "............x..."),
        drum("Afrobeat",
            KICK to "x.....x....x....", RIM to "..x..x..x..x....",
            CONGA to "x..x.xx...x..x.x", HAT to "x.x.x.x.x.x.x.x.", COWBELL to "....x......x...."),
        drum("Samba",
            KICK to "x.......x.......", RIM to "x.xx.xx.xx.xx.xx",
            CONGA to "..x...x...x...x.", PEDAL to "x.x.x.x.x.x.x.x.", COWBELL to "x......x..x....."),
        drum64("Four bars",
            KICK to ("x...x...x...x...".repeat(3) + "x...x...x..x.x.."),
            CLAP to "....x.......x...".repeat(4),
            HAT to "x.x.x.x.x.x.x.x.".repeat(4),
            OPEN_HAT to "..x...x...x...x.".repeat(4),
            CRASH to ("x" + ".".repeat(63))),
        drum64("DnB journey",
            KICK to ("x.........x.....".repeat(2) + "x.........x.x..." + "x....x....x....."),
            SNARE to "....x.......x...".repeat(4),
            RIDE to "..x...x...x...x.".repeat(4),
            HAT to "x.x.x.x.x.x.x.x.".repeat(4),
            CRASH to ("x" + ".".repeat(63))),
    )

    fun pianoNew(): List<Pair<String, Clip>> {
        val bassA = "0:2:36 3:1:36 6:1:36 8:2:39 11:1:36 14:2:41"
        val bassB = "0:2:36 3:1:36 6:1:36 8:2:43 11:1:41 14:2:39"
        return listOf(
            piano("Minor groove", "0:2:36 3:1:36 6:1:39 8:2:41 11:1:39 14:1:36 " +
                "16:2:36 19:1:36 22:1:39 24:2:43 27:1:41 30:1:39", 32),
            piano("Arp climb", every(1, 48, 51, 55, 58, 60, 63, 67, 70, count = 32), 32),
            piano("Chord stabs 2 bars",
                listOf(0, 3, 6, 10).joinToString(" ") { chord(it, 1, 57, 60, 64) } + " " +
                    listOf(16, 19, 22, 26).joinToString(" ") { chord(it, 1, 53, 57, 60) }, 32),
            piano("Pad progression", chord(0, 16, 45, 57, 60, 64) + " " + chord(16, 16, 41, 53, 57, 60) + " " +
                chord(32, 16, 36, 48, 52, 55) + " " + chord(48, 16, 43, 55, 59, 62), 64),
            piano("Bassline variations", shift(bassA, 0) + " " + shift(bassB, 16) + " " +
                shift(bassA, 32) + " " + shift(bassB, 48), 64),
            piano("Major melody", "0:2:72 2:2:74 4:2:76 6:2:79 8:4:76 12:2:74 14:2:72 " +
                "16:2:69 18:2:72 20:2:74 22:2:76 24:4:74 28:4:72", 32),
            piano("Octave bounce", every(2, 36, 48, 36, 48, 36, 48, 43, 55, count = 16), 32),
            piano("Polyrhythm 3 vs 4", every(3, 48, count = 22) + " " + every(4, 60, 55, count = 16), 64),
            piano("Gallop bass", phrase("0:1:36 1:1:36 3:1:36", 4, 8), 32),
            piano("Epic arpeggio",
                listOf(
                    intArrayOf(57, 60, 64, 69, 64, 60), intArrayOf(53, 57, 60, 65, 60, 57),
                    intArrayOf(48, 52, 55, 60, 55, 52), intArrayOf(55, 59, 62, 67, 62, 59),
                ).withIndex().joinToString(" ") { (k, p) -> shift(every(1, *p, count = 16), 16 * k) }, 64),
        )
    }

    private fun pianoBase(): List<Pair<String, Clip>> = listOf(
        piano("Octave pulse", every(1, 36, 48)),
        piano("Minor arp up", every(1, 48, 51, 55, 60)),
        piano("Major arp up and down", every(1, 48, 52, 55, 60, 55, 52)),
        piano("Funk bass", "0:2:36 3:1:36 5:1:39 6:1:41 8:2:36 11:1:43 12:1:41 14:1:39"),
        piano("Techno stabs", "0:1:57 0:1:60 0:1:64 3:1:57 3:1:60 3:1:64 6:1:57 6:1:60 6:1:64 10:1:55 10:1:59 10:1:62"),
        piano("Pad chords", "0:8:57 0:8:60 0:8:64 8:8:53 8:8:57 8:8:60"),
        piano("Walking bass", "0:4:36 4:4:40 8:4:43 12:4:41"),
        piano("Acid line", "0:1:36 1:1:36 2:1:48 4:1:36 6:1:39 7:1:36 9:1:48 10:1:36 12:1:41 14:1:43 15:1:36"),
        piano("Reggae skank", "2:1:52 2:1:55 2:1:59 6:1:52 6:1:55 6:1:59 10:1:52 10:1:55 10:1:59 14:1:52 14:1:55 14:1:59"),
        piano("Minor melody", "0:2:67 2:1:70 3:1:72 4:2:70 6:2:67 8:2:65 10:2:63 12:4:60"),
        piano("Sixteenth ostinato", "0:1:48 1:1:48 2:1:60 3:1:48 4:1:51 5:1:48 6:1:60 7:1:48 " +
            "8:1:53 9:1:48 10:1:60 11:1:48 12:1:51 13:1:48 14:1:60 15:1:55"),
        piano("Dub bass", "0:3:36 6:2:36 8:3:39 12:2:41 14:2:34"),
        piano("Minor arp down", every(1, 60, 55, 51, 48)),
        piano("Major arp three octaves", every(1, 48, 52, 55, 60, 64, 67, 72)),
        piano("Broken chord", "0:1:48 1:1:55 2:1:52 3:1:60 4:1:48 5:1:55 6:1:52 7:1:60 " +
            "8:1:48 9:1:55 10:1:52 11:1:60 12:1:48 13:1:55 14:1:52 15:1:64"),
        piano("Eighth note bass", every(2, 36, count = 8)),
        piano("Offbeat stabs", "2:1:60 2:1:64 6:1:59 6:1:62 10:1:57 10:1:60 14:1:55 14:1:59"),
        piano("Major stabs", "0:1:60 0:1:64 0:1:67 4:1:60 4:1:64 4:1:67 " +
            "8:1:62 8:1:65 8:1:69 12:1:59 12:1:62 12:1:67"),
        piano("Waltz", "0:4:36 4:2:48 4:2:52 4:2:55 8:2:48 8:2:52 8:2:55", 12),
        piano("Minor seven arp", every(1, 48, 51, 55, 58, 62)),
        piano("Pentatonic climb", every(1, 48, 50, 52, 55, 57, 60, 62, 64, 67, 69, 72, 74, 76, 79, 81)),
        piano("Root and fifth", every(2, 36, 43, count = 8)),
        piano("Syncopated line", "0:1:48 3:1:48 6:1:50 8:1:48 11:1:50 14:1:52"),
        piano("Dark bass", "0:3:36 4:3:38 8:3:36 12:3:34"),
        piano("Gated trance", "0:1:57 0:1:60 0:1:64 2:1:57 2:1:60 2:1:64 4:1:57 4:1:60 4:1:64 6:1:57 6:1:60 6:1:64 " +
            "8:1:55 8:1:59 8:1:62 10:1:57 10:1:60 10:1:64 12:1:55 12:1:59 12:1:62 14:1:57 14:1:60 14:1:64"),
        piano("Chromatic walk", every(2, 36, 38, 40, 41, 43, 41, 40, 38, count = 8)),
        piano("Two bar melody", "0:2:60 2:1:62 3:1:64 4:2:67 6:2:65 8:3:64 12:2:62 14:2:60 " +
            "16:2:62 18:1:64 19:1:67 20:2:69 22:2:67 24:2:65 28:4:64", 32),
        piano("Chord journey", "0:16:60 0:16:64 0:16:67 16:16:57 16:16:60 16:16:64 " +
            "32:16:53 32:16:57 32:16:60 48:16:55 48:16:59 48:16:62", 64),
        piano("Mega arp", every(1, 48, 52, 55, 60, 64, 67, 72, 76, count = 64), 64),
        piano("Bass marathon", phrase("0:2:36 2:1:36 4:2:36 6:1:36 8:2:39 10:1:39 12:2:41 14:1:36", 16, 4), 64),
    )
}
