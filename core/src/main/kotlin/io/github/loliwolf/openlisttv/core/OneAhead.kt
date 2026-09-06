package io.github.loliwolf.openlisttv.core

import kotlinx.coroutines.*

/** Single-thread-confined ownership slot: one pending job/value, no unobserved async errors.
 * Values stay owned here until take(); cancellation disposes even a completed preload. */
class OneAhead<T : Any>(private val scope: CoroutineScope, private val release: (T) -> Unit) {
    private var generation = 0
    private var worker: Job? = null
    private var value: T? = null
    private var failure: Exception? = null
    val occupied get() = worker != null
    fun start(load: suspend () -> T?) {
        if (occupied) return
        val ticket = generation
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var owned: T? = null
            try {
                owned = load(); ensureActive()
                if (ticket == generation) { value = owned; owned = null }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (ticket == generation) failure = e }
            finally { owned?.let(release) }
        }
        worker = task; task.start()
    }
    suspend fun await() { worker?.join() }
    fun take(): T? {
        check(worker?.isActive != true) { "Await preparation before transferring ownership" }
        val result = value; val error = failure
        value = null; failure = null; worker = null
        if (error != null) throw error
        return result
    }
    fun cancel() {
        generation++; worker?.cancel(); worker = null
        value?.let(release); value = null; failure = null
    }
}
