package ru.kadrograd

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class SourceDnsTest {
    @Test fun workingNetworkResolverIsPreserved() {
        val expected = listOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)))
        val dns = SourceDns(object : Dns { override fun lookup(hostname: String) = expected }) { error("No fallback expected") }
        assertEquals(expected, dns.lookup("cdn.example.org"))
    }
    @Test fun failedPublicLookupUsesFallbackWithoutChangingTheHostname() {
        val primary = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname) }
        val expected = listOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)))
        val dns = SourceDns(primary) { hostname -> assertEquals("cdn.example.org", hostname); expected }
        assertEquals(expected, dns.lookup("cdn.example.org"))
    }
    @Test fun privateAndInvalidNamesAreNotSentToPublicDns() {
        val primary = object : Dns { override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname) }
        val dns = SourceDns(primary) { error("Private lookup must stay on the device") }
        for (host in listOf("localhost", "127.0.0.1", "fe80::1", "nas.local", "a.invalid", "a.test", "bad host.com")) {
            try { dns.lookup(host); fail("Expected failed private lookup") } catch (_: UnknownHostException) { }
        }
    }
}
