package ru.kadrograd

import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SourceHttpTest {
    @Test fun cancelledPlaybackAlsoCancelsTheDnsFallbackConnection() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("delayed segment").bodyDelay(4, TimeUnit.SECONDS).build())
            server.start()
            val request = okhttp3.Request.Builder().url(server.url("/segment")).build()
            val original = okhttp3.OkHttpClient().newCall(request)
            val chain = object : okhttp3.Interceptor.Chain {
                override fun request() = request
                override fun proceed(request: okhttp3.Request): okhttp3.Response = throw java.net.UnknownHostException("fixture")
                override fun connection(): okhttp3.Connection? = null
                override fun call() = original
                override fun connectTimeoutMillis() = 1000
                override fun readTimeoutMillis() = 5000
                override fun writeTimeoutMillis() = 1000
                override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
                override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
                override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
            }
            val response = withContext(Dispatchers.IO) { SourceHttp.proceed(chain, request) }
            val reading = async(Dispatchers.IO) {
                response.use {
                    try { it.body.string(); false } catch (_: java.io.IOException) { true }
                }
            }
            delay(100)
            original.cancel()
            assertTrue(withTimeout(1_500) { reading.await() })
        }
    }
    @Test fun catalogRetriesTransientGatewayFailureOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(502).body("Bad gateway").build())
            server.enqueue(MockResponse.Builder().body("catalog").build())
            server.start()
            assertEquals("catalog",SourceHttp.getCatalog("Проверка",server.url("/").toString()))
            assertEquals(2,server.requestCount)
        }
    }

    @Test fun catalogDoesNotRetryAccessDenialOrServerBackoff() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(403).build())
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After","30").build())
            server.start()
            for ((index,status) in listOf(403,503).withIndex()) {
                try { SourceHttp.getCatalog("Проверка",server.url("/").toString()); fail("Expected HTTP $status") }
                catch (e: ErrorLoadingException) { assertTrue(e.message.orEmpty().contains("HTTP $status")) }
                assertEquals(index+1,server.requestCount)
            }
        }
    }

    @Test fun catalogRetriesWhenBodyStallsAfterSuccessfulHeaders() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("late").bodyDelay(8,TimeUnit.SECONDS).build())
            server.enqueue(MockResponse.Builder().body("complete").build())
            server.start()
            assertEquals("complete",SourceHttp.getCatalog("Проверка",server.url("/").toString()))
            assertEquals(2,server.requestCount)
        }
    }
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
