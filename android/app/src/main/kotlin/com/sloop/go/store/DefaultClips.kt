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

    /** Notes as "start:length:pitch" words. */
    private fun piano(name: String, notes: String, length: Int = 16): Pair<String, Clip> =
        name to Clip(ClipKind.PIANO, length, notes.trim().split(Regex("\\s+")).map {
            val (s, l, p) = it.split(":").map(String::toInt)
            ClipNote(s, l, p, 100)
        })

    private fun every(step: Int, vararg pitches: Int, count: Int = 16) =
        (0 until count).joinToString(" ") { i -> "${i * step}:1:${pitches[i % pitches.size]}" }

    fun drums(): List<Pair<String, Clip>> = listOf(
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
    )

    fun piano(): List<Pair<String, Clip>> = listOf(
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
    )
}
