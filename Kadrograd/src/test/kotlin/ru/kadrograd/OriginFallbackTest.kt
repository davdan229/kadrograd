package ru.kadrograd

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.TimeUnit

class OriginFallbackTest {
    @Test fun quickPrimaryDoesNotContactAlternate() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = firstReachable(listOf("primary","alternate")) { attempted += it; it }
        assertEquals("primary",result)
        assertEquals(listOf("primary"),attempted)
    }

    @Test fun slowPrimaryCannotHoldUpWorkingAlternate() = runBlocking {
        var primaryCancelled = false
        val result = withTimeout(2_000) {
            firstReachable(listOf("primary","alternate"),20) {
                if (it == "primary") try { awaitCancellation() } finally { primaryCancelled = true }
                else "available"
            }
        }
        assertEquals("available",result)
        assertTrue(primaryCancelled)
    }

    @Test fun malformedResponsesDoNotCountAsSuccess() = runBlocking {
        assertEquals("alternate",firstReachable(listOf("primary","alternate"),20) {
            require(it == "alternate")
            it
        })
        assertNull(firstReachable(listOf("primary","alternate"),20) { error("invalid response") })
    }

    @Test fun stalledHttpBodyIsCancelledWhenAlternateCompletes() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.url.encodedPath == "/slow") {
                        MockResponse.Builder().body("late").bodyDelay(4,TimeUnit.SECONDS).build()
                    } else MockResponse.Builder().body("ready").build()
            }
            server.start()
            val endpoints = listOf(server.url("/slow").toString(),server.url("/fast").toString())
            val result = withTimeout(2_000) {
                firstReachable(endpoints,100) { url -> SourceHttp.readEndpoint(url,emptyMap(),5) { it.body.string() } }
            }
            assertEquals("ready",result)
        }
    }
}
