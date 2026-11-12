package ru.kadrograd

import com.lagradost.cloudstream3.mapper
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Uses HTTPS DNS only when the network's normal resolver cannot resolve a public hostname. */
internal class SourceDns(private val primary: Dns,
    private val resolve: (String) -> List<InetAddress> = ::httpsLookup) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = try {
        primary.lookup(hostname)
    } catch (original: UnknownHostException) {
        if (!publicHostname(hostname)) throw original
        try { resolve(hostname).takeIf { it.isNotEmpty() } ?: throw original }
        catch (_: Exception) { throw original }
    }

    companion object {
        private data class Cached(val expires: Long, val addresses: List<InetAddress>)
        private val cache = ConcurrentHashMap<String, Cached>()
        private val locks = Array(16) { Any() }
        private val resolver by lazy {
            OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS).dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
                        "dns.google" -> listOf("8.8.8.8", "8.8.4.4")
                        "cloudflare-dns.com" -> listOf("1.1.1.1", "1.0.0.1")
                        else -> throw UnknownHostException(hostname)
                    }.map(InetAddress::getByName)
                }).build()
        }

        internal fun publicHostname(hostname: String): Boolean = hostname.length <= 253 &&
            hostname.contains('.') && !hostname.endsWith('.') &&
            hostname.substringAfterLast('.').any(Char::isLetter) &&
            hostname.substringAfterLast('.').lowercase() !in setOf("local", "lan", "internal", "localhost", "test", "invalid") &&
            hostname.split('.').all { it.length in 1..63 && Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?").matches(it) }

        private fun httpsLookup(hostname: String): List<InetAddress> = synchronized(locks[(hostname.hashCode() and Int.MAX_VALUE) % locks.size]) {
            cache[hostname]?.takeIf { it.expires > System.currentTimeMillis() }?.let {
                if (it.addresses.isEmpty()) throw UnknownHostException(hostname)
                return@synchronized it.addresses
            }
            for (endpoint in listOf("https://dns.google/resolve", "https://cloudflare-dns.com/dns-query")) {
                try {
                    val url = okhttp3.HttpUrl.Builder().scheme("https")
                        .host(java.net.URI(endpoint).host).encodedPath(java.net.URI(endpoint).path)
                        .addQueryParameter("name", hostname).addQueryParameter("type", "A").build()
                    val request = Request.Builder().url(url).header("Accept", "application/dns-json").build()
                    resolver.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val source = response.body.source()
                        require(!source.request(65_537)) { "Oversized DNS response" }
                        val json = mapper.readTree(source.readByteArray())
                        if (json.path("Status").asInt(-1) != 0) return@use
                        val answers = json.path("Answer").filter { it.path("type").asInt() == 1 }
                        val addresses = answers.mapNotNull { answer ->
                            val value = answer.path("data").asText()
                            val parts = value.split('.')
                            if (parts.size != 4 || parts.any { it.length !in 1..3 || it.any { c -> c !in '0'..'9' } || it.toInt() !in 0..255 }) null
                            else InetAddress.getByAddress(hostname, parts.map { it.toInt().toByte() }.toByteArray())
                        }.distinct().take(16)
                        if (addresses.isNotEmpty()) {
                            val ttl = answers.minOfOrNull { it.path("TTL").asLong(60) }?.coerceIn(10, 300) ?: 60
                            cache[hostname] = Cached(System.currentTimeMillis() + ttl * 1_000, addresses)
                            return@synchronized addresses
                        }
                    }
                } catch (_: Exception) { }
            }
            cache[hostname] = Cached(System.currentTimeMillis() + 10_000, emptyList())
            throw UnknownHostException(hostname)
        }
    }
}
