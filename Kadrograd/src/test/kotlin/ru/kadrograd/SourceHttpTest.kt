package ru.kadrograd

import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SourceHttpTest {
    @Test fun nonRespondingServerTimesOutInSecondsRatherThanHours() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("late").build())
            server.start()
            val started = System.nanoTime()
            try { SourceHttp.get("Проверка", server.url("/").toString(), timeoutSeconds = 1); fail("Expected timeout") }
            catch (expected: ErrorLoadingException) { assertTrue(expected.message.orEmpty().contains("сеть")) }
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            assertTrue("Expected one-second deadline, got $seconds seconds", seconds < 1.9)
        }
    }
    @Test fun callerCancellationIsNotConvertedToNetworkFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("late").build())
            server.start()
            try { withTimeout(100) { SourceHttp.get("Проверка", server.url("/").toString()) }; fail("Expected cancellation") }
            catch (_: TimeoutCancellationException) { }
        }
    }
    @Test fun mediaProbeRejectsHtmlBlockPageAndClosesBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("<html>Blocked</html>").build())
            server.enqueue(MockResponse.Builder().code(206).body("\u0000\u0000\u0000 ftypisom").build())
            server.start()
            assertFalse(SourceHttp.accessibleMp4(server.url("/blocked.mp4").toString(), server.url("/").toString()))
            assertTrue(SourceHttp.accessibleMp4(server.url("/video.mp4").toString(), server.url("/").toString()))
        }
    }
    @Test fun serverErrorsKeepHttpStatusVisible() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).body("Unavailable").build())
            server.start()
            try { SourceHttp.get("Проверка", server.url("/").toString()); fail("Expected HTTP failure") }
            catch (expected: ErrorLoadingException) { assertTrue(expected.message.orEmpty().contains("503")) }
        }
    }
}
