package ru.kadrograd

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal object SourceHttp {
    // NiceHttp takes seconds, whereas coroutine withTimeout takes milliseconds.
    const val REQUEST_SECONDS = 12L

    private suspend fun request(source: String, timeoutSeconds: Long, block: suspend () -> NiceResponse): NiceResponse = try {
        val response = withTimeoutOrNull((timeoutSeconds + 1) * 1_000) { block() }
            ?: throw ErrorLoadingException("$source: сервер не ответил вовремя через эту сеть")
        if (response.code !in 200..299) {
            response.okhttpResponse.close()
            throw ErrorLoadingException("$source: сервер вернул HTTP ${response.code}")
        }
        response
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        throw ErrorLoadingException("$source: сервер не отвечает или недоступен через эту сеть. Проверьте адрес источника и подключение.")
    }

    suspend fun get(source: String, url: String, referer: String? = null,
        headers: Map<String, String> = emptyMap(), timeoutSeconds: Long = REQUEST_SECONDS): NiceResponse {
        require(timeoutSeconds in 1..45)
        return request(source, timeoutSeconds) { app.get(url, referer = referer, headers = headers, timeout = timeoutSeconds) }
    }

    /** A transient catalogue failure can be retried once without increasing the normal wait budget. */
    suspend fun getCatalog(source: String, url: String, referer: String? = null,
        headers: Map<String, String> = emptyMap()): String =
        withTimeoutOrNull((REQUEST_SECONDS + 1) * 1_000) {
            for (attempt in 0..1) {
                try {
                    val response = app.get(url, referer = referer, headers = headers, timeout = if (attempt == 0) 6 else 5)
                    val text = response.okhttpResponse.use { raw ->
                        when {
                            raw.isSuccessful -> withContext(Dispatchers.IO) { response.text }
                            attempt == 0 && raw.code in setOf(502,503,504) && raw.header("Retry-After") == null -> null
                            else -> throw ErrorLoadingException("$source: сервер вернул HTTP ${raw.code}")
                        }
                    }
                    if (text != null) return@withTimeoutOrNull text
                } catch (failure: IOException) {
                    if (attempt == 1 || failure is javax.net.ssl.SSLException || failure is java.net.ProtocolException)
                        throw ErrorLoadingException("$source: не удалось получить ответ каталога через эту сеть")
                }
                if (attempt == 0) delay(200)
            }
            null
        } ?: throw ErrorLoadingException("$source: каталог не ответил вовремя через эту сеть")

    suspend fun post(source: String, url: String, data: Map<String, String>, timeoutSeconds: Long = REQUEST_SECONDS,
        headers: Map<String,String> = emptyMap(), referer: String? = null): NiceResponse {
        require(timeoutSeconds in 1..45)
        return request(source, timeoutSeconds) { app.post(url, data = data, timeout = timeoutSeconds, headers = headers, referer = referer) }
    }

    suspend fun accessibleMp4(url: String, referer: String): Boolean = try {
        get("Видеосервер", url, referer, mapOf("Range" to "bytes=0-31"), timeoutSeconds = 3).okhttpResponse.use { response ->
            withContext(Dispatchers.IO) {
                if (response.code !in setOf(200, 206)) return@withContext false
                val prefix = response.body.source().readByteArray(12)
                prefix.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }
}
