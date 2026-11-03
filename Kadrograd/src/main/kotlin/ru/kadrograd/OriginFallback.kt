package ru.kadrograd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Start the alternate endpoint after a short head start for the primary one. */
internal suspend fun <T : Any> firstReachable(
    origins: List<String>, alternateDelayMillis: Long = 450, request: suspend (String) -> T
): T? = coroutineScope {
    val candidates = origins.distinct().take(2)
    val results = Channel<T?>(candidates.size)
    val jobs = candidates.mapIndexed { index, origin ->
        launch {
            if (index != 0) delay(alternateDelayMillis)
            val result = try { request(origin) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            results.send(result)
        }
    }
    try {
        repeat(candidates.size) { results.receive()?.let { return@coroutineScope it } }
        null
    } finally {
        jobs.forEach { it.cancel() }
        results.cancel()
    }
}
