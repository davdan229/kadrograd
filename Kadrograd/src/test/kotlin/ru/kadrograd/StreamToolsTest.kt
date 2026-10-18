package ru.kadrograd

import org.junit.Assert.*
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class StreamToolsTest {
    @Test fun qualityUsesSourceLabelWithoutDowngrading() {
        assertEquals(1080, StreamTools.quality("1080p Ultra"))
        assertEquals(720, StreamTools.quality("720p"))
        assertEquals(2160, StreamTools.quality("4K UHD"))
        assertEquals(0, StreamTools.quality("HD"))
    }
    @Test fun extractsAlternativeLinksWithoutLosingQueryCommas() {
        val links = StreamTools.labeledUrls("[1080p]https://cdn.example/a.m3u8?x=a,b or https://cdn.example/a.mp4,[720p]https://cdn.example/b.m3u8")
        assertEquals(3, links.size)
        assertEquals("https://cdn.example/a.m3u8?x=a,b", links[0].url)
        assertEquals("720p", links.last().label)
    }
    @Test fun rejectsInvalidAndScriptLinks() {
        assertTrue(StreamTools.labeledUrls("[720p]javascript:alert(1),[1080p]not-a-url").isEmpty())
        assertFalse(StreamTools.isHttp("https://user:pass@example.org/private"))
    }
    @Test fun preservesSubtitleLanguageAndSignedUrl() {
        val links = StreamTools.labeledUrls("[Русский]https://cdn.example/ru.vtt?token=abc%2F123,[English]https://cdn.example/en.srt")
        assertEquals("Русский", links.first().label)
        assertTrue(links.first().url.endsWith("token=abc%2F123"))
        assertEquals(2, links.size)
    }
    @Test fun validatesOnlyHttpsOrigins() {
        assertEquals("https://example.org", StreamTools.origin(" https://example.org/ "))
        assertNull(StreamTools.origin("https://example.org/title/1"))
        assertNull(StreamTools.origin("http://example.org"))
        assertNull(StreamTools.origin("https://user:password@example.org"))
        assertNull(StreamTools.origin("https://example.org?token=secret"))
    }
    @Test fun decodesRezkaTransportAndClearText() {
        val raw = "[1080p]https://cdn.example/video.m3u8"
        val encoded = Base64.encode(raw.toByteArray())
        val noise = Base64.encode("@#!".toByteArray())
        assertEquals(raw, StreamTools.decodeRezka("#h$noise${encoded.take(12)}//_//${encoded.drop(12)}"))
        assertEquals(raw, StreamTools.decodeRezka(raw))
        assertEquals("", StreamTools.decodeRezka("#hnot-valid!"))
    }
    @Test fun decodesFilmixTransportAndDoesNotTruncatePlainUrl() {
        val raw = "[720p]https://cdn.example/video.mp4"
        assertEquals(raw, StreamTools.decodeFilmix("#0" + Base64.encode(raw.toByteArray())))
        assertEquals("https://cdn.example/season.json", StreamTools.decodeFilmix("https://cdn.example/season.json"))
    }
    @Test fun removesDuplicateLinks() {
        val links = StreamTools.labeledUrls("[720p]https://cdn.example/video.mp4 or https://cdn.example/video.mp4")
        assertEquals(1, links.size)
    }
    @Test fun supportsProtocolRelativeCdnLinks() {
        assertEquals("https://cdn.example/video.m3u8", StreamTools.labeledUrls("[720p]//cdn.example/video.m3u8").single().url)
    }
    @Test fun stripsSiteMarkupFromQualityLabels() {
        val link = StreamTools.labeledUrls("[<span class=\"premium\">1080p Ultra<img src=\"icon.svg\"></span>]https://cdn.example/video.m3u8").single()
        assertEquals("1080p Ultra", link.label)
        assertEquals(1080, StreamTools.quality(link.label))
    }
}
