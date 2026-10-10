// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Sloop Go. Based on the SLOOP firmware (isod89) and Felucca (Leo Kuroshita);
// see NOTICE.md.
package com.sloop.go.device

import com.sloop.go.proto.DrumStep
import com.sloop.go.proto.Step

/**
 * One reversible edit, kept in RAM with the value before and after it. Undo and redo do not roll anything back on
 * the device: they send the other value through the very same paths as a hand edit (the request queue).
 */
sealed interface Change {
    /** The track that has to be selected for this change to be written; null when it can be written from anywhere. */
    val track: Int?

    data class Param(val scope: Int, val id: Int, val tr: Int, val before: Int, val after: Int) : Change {
        override val track get() = if (scope == 0) tr else null
    }

    data class Mix(val tr: Int, val before: Pair<Int, Boolean>, val after: Pair<Int, Boolean>) : Change {
        override val track: Int? get() = null
    }

    data class Synth(val tr: Int, val index: Int, val before: Step, val after: Step) : Change {
        override val track get() = tr
    }

    data class Drum(val tr: Int, val index: Int, val before: DrumStep, val after: DrumStep) : Change {
        override val track get() = tr
    }

    data class Micro(val tr: Int, val step: Int, val before: Int, val after: Int) : Change {
        override val track get() = tr
    }

    data class Fill(val tr: Int, val step: Int, val before: Int, val after: Int) : Change {
        override val track get() = tr
    }

    data class Lock(val tr: Int, val step: Int, val param: Int, val before: Int?, val after: Int?) : Change {
        override val track get() = tr
    }

    /**
     * An edit that lives in a page's own editor (FM6 voice, drum synth kit, song order). [apply] puts a value back; it
     * is the page's usual way of changing it, so the device gets it the usual way too. Equal non-null [key]s that
     * follow each other quickly merge into one entry (a knob drag).
     */
    class Custom<T>(val key: String?, val before: T, val after: T, private val apply: suspend (T) -> Unit) : Change {
        override val track: Int? get() = null
        suspend fun replay(forward: Boolean) = apply(if (forward) after else before)

        @Suppress("UNCHECKED_CAST")
        fun merged(next: Custom<*>) = Custom(key, before, next.after as T, apply)
    }
}

/** One user action: every change it made, in the order they were made. */
class HistoryEntry(val changes: List<Change>, val at: Long) {
    /** The track an undo/redo of this entry has to select first. */
    val track: Int? get() = changes.firstNotNullOfOrNull { it.track }
}

/**
 * Undo / redo stacks held in memory only. [record] adds an action and empties the redo stack; actions made inside a
 * [transaction] (one gesture that writes many steps) become one entry. A drag of the same knob merges into one entry.
 * [onChange] reports whether undo / redo have anything to do.
 */
class EditHistory(
    private val limit: Int = 300,
    private val mergeWindowMs: Long = 800,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onChange: (canUndo: Boolean, canRedo: Boolean) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private val open = ThreadLocal<MutableList<Change>?>()

    val undoCount: Int get() = synchronized(lock) { undoStack.size }
    val redoCount: Int get() = synchronized(lock) { redoStack.size }

    fun record(change: Change) {
        open.get()?.let { it.add(change); return }
        push(listOf(change))
    }

    /** Everything recorded by [block] on this thread becomes one entry. */
    fun <T> transaction(block: () -> T): T {
        if (open.get() != null) return block()
        val list = ArrayList<Change>()
        open.set(list)
        try {
            return block()
        } finally {
            open.set(null)
            if (list.isNotEmpty()) push(list)
        }
    }

    private fun push(changes: List<Change>) {
        val now = clock()
        val state = synchronized(lock) {
            redoStack.clear()
            val top = undoStack.lastOrNull()
            val merged = if (top != null && changes.size == 1 && top.changes.size == 1 && now - top.at <= mergeWindowMs)
                merge(top.changes[0], changes[0]) else null
            if (merged != null) {
                undoStack.removeLast()
                undoStack.addLast(HistoryEntry(listOf(merged), now))
            } else {
                undoStack.addLast(HistoryEntry(changes, now))
                while (undoStack.size > limit) undoStack.removeFirst()
            }
            undoStack.isNotEmpty() to false
        }
        onChange(state.first, state.second)
    }

    private fun merge(a: Change, b: Change): Change? = when {
        a is Change.Param && b is Change.Param && a.scope == b.scope && a.id == b.id && a.tr == b.tr ->
            a.copy(after = b.after)
        a is Change.Mix && b is Change.Mix && a.tr == b.tr -> a.copy(after = b.after)
        a is Change.Custom<*> && b is Change.Custom<*> && a.key != null && a.key == b.key -> a.merged(b)
        else -> null
    }

    /** Takes the newest entry to undo; [putBack] / [done] say what happened to it. */
    fun takeUndo(): HistoryEntry? = take(undoStack)
    fun takeRedo(): HistoryEntry? = take(redoStack)

    private fun take(stack: ArrayDeque<HistoryEntry>): HistoryEntry? {
        val e = synchronized(lock) { stack.removeLastOrNull() }
        if (e != null) publish()
        return e
    }

    /** The entry taken by [takeUndo] ([forward] = false) or [takeRedo] was applied: it now waits on the other stack. */
    fun done(e: HistoryEntry, forward: Boolean) {
        synchronized(lock) { (if (forward) undoStack else redoStack).addLast(e) }
        publish()
    }

    /** The entry could not be applied: it goes back where it was. */
    fun putBack(e: HistoryEntry, forward: Boolean) {
        synchronized(lock) { (if (forward) redoStack else undoStack).addLast(e) }
        publish()
    }

    /** Forgets the entries that touch something whose meaning changed (an engine or preset swap, a reload). */
    fun drop(match: (Change) -> Boolean) {
        synchronized(lock) {
            undoStack.removeAll { e -> e.changes.any(match) }
            redoStack.removeAll { e -> e.changes.any(match) }
        }
        publish()
    }

    fun dropTrack(track: Int) = drop { it.track == track }

    fun dropKey(prefix: String) = drop { it is Change.Custom<*> && it.key?.startsWith(prefix) == true }

    fun clear() {
        synchronized(lock) { undoStack.clear(); redoStack.clear() }
        publish()
    }

    private fun publish() {
        val (u, r) = synchronized(lock) { undoStack.isNotEmpty() to redoStack.isNotEmpty() }
        onChange(u, r)
    }
}

/** Deep copies for what goes into the history: the arrays of a step must not change under it. */
fun Step.snapshot() = copy(notes = notes.copyOf())
fun DrumStep.snapshot() = copy(lvl = lvl.copyOf(), rat = rat.copyOf())
