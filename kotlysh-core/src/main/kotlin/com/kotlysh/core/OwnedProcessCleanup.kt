package com.kotlysh.core

import java.time.Instant
import java.util.concurrent.TimeUnit

/** Cleanup is restricted to the launched Process and descendants captured from it. */
internal object OwnedProcessCleanup {
    data class Child(val handle: ProcessHandle, val started: Instant) {
        fun stillSameProcess(): Boolean =
            handle.isAlive && handle.info().startInstant().orElse(null) == started
    }

    fun captureDescendants(process: Process): List<Child> =
        process.descendants().use { descendants ->
            descendants.toList().mapNotNull { handle ->
                handle.info().startInstant().orElse(null)?.let { Child(handle, it) }
            }.reversed()
        }

    /** Retains only identities observed in the launched process's live ancestry. */
    class Tracker(private val process: Process) {
        private val observed = linkedMapOf<Long, Child>()

        @Synchronized
        fun capture() {
            observed.values.removeIf { !it.stillSameProcess() }
            if (!process.isAlive) return
            captureDescendants(process).forEach { child -> observed[child.handle.pid()] = child }
        }

        @Synchronized
        fun snapshot(): List<Child> {
            capture()
            return observed.values.toList()
        }
    }

    @JvmOverloads
    fun finish(
        process: Process,
        descendants: List<Child>,
        graceful: Boolean,
        beforeParentStop: () -> Unit = {},
    ) = finish(process, { descendants }, graceful, beforeParentStop)

    /** Descendant lookup must run inside the guard that always stops the root. */
    fun finish(
        process: Process,
        descendants: () -> List<Child>,
        graceful: Boolean,
        beforeParentStop: () -> Unit = {},
    ) {
        // Thread interruption must not abandon an owned tree or replace an
        // earlier failure. Restore it only after termination has finished.
        var interrupted = Thread.interrupted()
        fun <T> waitUninterrupted(wait: () -> T): T {
            while (true) {
                try {
                    return wait()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        fun waitForParent(milliseconds: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)
            return waitUninterrupted {
                process.waitFor((deadline - System.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS)
            }
        }
        var captured = emptyList<Child>()
        try {
            // Record identities while the owned interpreter is still the ancestor.
            // Never rediscover orphaned children by PID, name, or a global search.
            try {
                captured = descendants()
                val layers = leafLayers(captured)
                val gracefulDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
                val forcedDeadline = gracefulDeadline + TimeUnit.MILLISECONDS.toNanos(200)
                // Finish descendant layers before signalling their captured
                // ancestors, sharing finite budgets across the entire tree.
                for (layer in layers) {
                    layer.filter { it.stillSameProcess() }.forEach { it.handle.destroy() }
                    waitUninterrupted { awaitDescendants(layer, gracefulDeadline) }
                    layer.filter { it.stillSameProcess() }.forEach { it.handle.destroyForcibly() }
                    waitUninterrupted { awaitDescendants(layer, forcedDeadline) }
                }
                if (graceful && process.isAlive) beforeParentStop()
            } finally {
                if (!graceful && process.isAlive) process.destroy()
                if (!waitForParent(200)) {
                    process.destroy()
                    if (!waitForParent(200)) {
                        process.destroyForcibly()
                        waitUninterrupted { process.waitFor() }
                    }
                }
                captured.filter { it.stillSameProcess() }.forEach { it.handle.destroyForcibly() }
                // Noncooperative parents may not reap a dead child until their own
                // exit. Await only retained identities after the root has stopped.
                val descendantDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
                waitUninterrupted { awaitDescendants(captured, descendantDeadline) }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun leafLayers(descendants: List<Child>): List<List<Child>> {
        val owned = descendants.filter { it.stillSameProcess() }.associateBy { it.handle.pid() }
        val parents = owned.mapValues { (_, child) ->
            child.handle.parent().map { it.pid() }.orElse(null)?.takeIf(owned::containsKey)
        }
        val childCounts = owned.keys.associateWith { 0 }.toMutableMap()
        parents.values.filterNotNull().forEach { parent -> childCounts[parent] = childCounts.getValue(parent) + 1 }
        val leaves = java.util.ArrayDeque(childCounts.filterValues { it == 0 }.keys)
        val layers = mutableListOf<List<Child>>()
        while (leaves.isNotEmpty()) {
            val layer = List(leaves.size) { owned.getValue(leaves.removeFirst()) }
            layers += layer
            layer.forEach { child ->
                parents[child.handle.pid()]?.let { parent ->
                    val remaining = childCounts.getValue(parent) - 1
                    childCounts[parent] = remaining
                    if (remaining == 0) leaves.addLast(parent)
                }
            }
        }
        return layers
    }

    private fun awaitDescendants(descendants: List<Child>, deadline: Long) {
        while (descendants.any { it.stillSameProcess() } && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
    }
}
