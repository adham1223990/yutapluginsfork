package com.github.yutaplug.profileeffects

/** Queue hierarchy changes after lifecycle dispatch; cancel stale work when a view reattaches. */
internal class DeferredCleanup(private val post: (Runnable) -> Unit, private val remove: (Runnable) -> Unit) {
    private var pending: Task? = null

    private inner class Task(private val action: () -> Unit) : Runnable {
        override fun run() {
            if (pending !== this) return
            pending = null
            action()
        }
    }

    fun schedule(action: () -> Unit) {
        cancel()
        val task = Task(action)
        pending = task
        post(task)
    }

    fun cancel() {
        val task = pending ?: return
        pending = null
        remove(task)
    }
}
