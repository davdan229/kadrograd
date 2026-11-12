package ru.kadrograd

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer

internal object SourceHttp {
    // NiceHttp takes seconds, whereas coroutine withTimeout takes milliseconds.
    const val REQUEST_SECONDS = 12L
    private val cancellationChecks = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { work ->
        Thread(work, "kadrograd-dns-cancel").apply { isDaemon = true }
    }

    fun client(): okhttp3.OkHttpClient = app.baseClient.let { it.newBuilder().dns(SourceDns(it.dns)).build() }
    private fun requests() = Requests(baseClient = client(), defaultHeaders = app.defaultHeaders,
        defaultReferer = app.defaultReferer, defaultCookies = app.defaultCookies, responseParser = app.responseParser,
        defaultData = app.defaultData, defaultCacheTime = app.defaultCacheTime,
        defaultCacheTimeUnit = app.defaultCacheTimeUnit, defaultTimeOut = app.defaultTimeOut)

    fun proceed(chain: okhttp3.Interceptor.Chain, request: Request): Response = try {
        chain.proceed(request)
    } catch (failure: java.net.UnknownHostException) {
        if (chain.call().isCanceled()) throw IOException("Canceled", failure)
        val builder = client().newBuilder()
            .connectTimeout(chain.connectTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(chain.readTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(chain.writeTimeoutMillis().toLong(), TimeUnit.MILLISECONDS)
        val timeout = chain.call().timeout().timeoutNanos()
        if (timeout > 0) builder.callTimeout(timeout, TimeUnit.NANOSECONDS)
        val call = builder.build().newCall(request)
        val cancellation = cancellationChecks.scheduleAtFixedRate({
            if (chain.call().isCanceled()) call.cancel()
        }, 100, 100, TimeUnit.MILLISECONDS)
        try {
            val response = call.execute()
            val body = response.body
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                private val tracked by lazy {
                    object : ForwardingSource(body.source()) {
                        override fun close() {
                            try { super.close() } finally { cancellation.cancel(false) }
                        }
                    }.buffer()
                }
                override fun source() = tracked
            }).build()
        } catch (error: Exception) {
            cancellation.cancel(false)
            throw error
        }
    }

    /** The whole response stays cancellable when an alternate endpoint wins. */
    suspend fun <T : Any> readEndpoint(url: String, headers: Map<String,String>, timeoutSeconds: Long,
        head: Boolean = false, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).apply {
            if (head) head()
            headers.forEach { (name,value) -> header(name,value) }
        }.build()
        val call = client().newBuilder().callTimeout(timeoutSeconds,TimeUnit.SECONDS).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        read(it)
                    }
                })
            }
        })
    }

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
        return request(source, timeoutSeconds) { requests().get(url, referer = referer, headers = headers, timeout = timeoutSeconds) }
    }

    /** A transient catalogue failure can be retried once without increasing the normal wait budget. */
    suspend fun getCatalog(source: String, url: String, referer: String? = null,
        headers: Map<String, String> = emptyMap()): String =
        getCatalogOrNull(source,url,referer,headers)
            ?: throw ErrorLoadingException("$source: не удалось получить ответ каталога через эту сеть")

    suspend fun getCatalogOrNull(source: String, url: String, referer: String? = null,
        headers: Map<String, String> = emptyMap()): String? =
        withTimeoutOrNull((REQUEST_SECONDS + 1) * 1_000) {
            for (attempt in 0..1) {
                try {
                    val response = requests().get(url, referer = referer, headers = headers, timeout = if (attempt == 0) 6 else 5)
                    val text = response.okhttpResponse.use { raw ->
                        when {
                            raw.isSuccessful -> withContext(Dispatchers.IO) { response.text }
                            raw.code in setOf(502,503,504) && raw.header("Retry-After") == null -> null
                            else -> throw ErrorLoadingException("$source: сервер вернул HTTP ${raw.code}")
                        }
                    }
                    if (text != null) return@withTimeoutOrNull text
                } catch (failure: IOException) {
                    if (failure is javax.net.ssl.SSLException || failure is java.net.ProtocolException)
                        throw ErrorLoadingException("$source: не удалось прочитать ответ каталога")
                    if (attempt == 1) return@withTimeoutOrNull null
                }
                if (attempt == 0) delay(200)
            }
            null
        }

    suspend fun post(source: String, url: String, data: Map<String, String>, timeoutSeconds: Long = REQUEST_SECONDS,
        headers: Map<String,String> = emptyMap(), referer: String? = null): NiceResponse {
        require(timeoutSeconds in 1..45)
        return request(source, timeoutSeconds) { requests().post(url, data = data, timeout = timeoutSeconds, headers = headers, referer = referer) }
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
