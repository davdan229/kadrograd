package ru.kadrograd

import okhttp3.OkHttpClient
import okhttp3.Request
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import kotlin.io.encoding.Base64

class ZonaTracksTest {
    private val epoch = 1_790_000_000L
    private fun context(media: String = "https://video.cdn.example/film/master.mpd?token=test") =
        ZonaTracks.Context(media=media, alphabet=ZonaTracks.LETTERS.reversed(), parameter="session123",
            epoch=epoch, observed=epoch*1_000, width=1920, height=800,
            voices=mapOf("0" to "Рус. Дублированный", "1" to "Eng.Original"),agent="Kadrograd-test")

    private fun mpd(width: Int = 1920, height: Int = 800, sar: String = "1:1") = """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S"><Period>
        <AdaptationSet id="10" contentType="audio" lang="ru0"><Representation id="0" mimeType="audio/webm" codecs="opus">
        <SegmentTemplate initialization="a/init.webm?token=test" media="a/${'$'}Number${'$'}.webm?token=test" startNumber="1"/></Representation></AdaptationSet>
        <AdaptationSet id="11" contentType="audio" lang="ru1"><Representation id="1" mimeType="audio/webm" codecs="opus"/></AdaptationSet>
        <AdaptationSet id="12" contentType="audio"><Representation id="2" mimeType="audio/webm" codecs="opus"/></AdaptationSet>
        <AdaptationSet contentType="video" par="12:5">
        <Representation id="18" width="864" height="360" sar="1:1" mimeType="video/webm" codecs="vp9"/>
        <Representation id="20" width="$width" height="$height" sar="$sar" frameRate="24000/1001" mimeType="video/webm" codecs="vp9">
        <SegmentTemplate initialization="v/init.webm?token=test" media="v/${'$'}Number${'$'}.webm?token=test" startNumber="1"/></Representation>
        </AdaptationSet></Period></MPD>
    """.trimIndent()

    @Test fun qualitySelectionPreservesCroppedFrameDimensionsAndSquarePixels() {
        val document = ZonaTracks.manifest(ZonaTracks.rewriteManifest(mpd(),context()))
        val video = document.getElementsByTag("Representation").single { it.hasAttr("width") }
        assertEquals("1920",video.attr("width")); assertEquals("800",video.attr("height"))
        assertEquals("1:1",video.attr("sar")); assertEquals("24000/1001",video.attr("frameRate"))
        assertEquals("12:5",video.parent()!!.attr("par"))
        assertTrue(video.getElementsByTag("SegmentTemplate").single().attr("media").contains("${'$'}Number${'$'}"))
    }

    @Test fun anamorphicPixelRatioIsPreserved() {
        val document = ZonaTracks.manifest(ZonaTracks.rewriteManifest(mpd(720,576,"16:15"),context().copy(width=720,height=576)))
        val video = document.getElementsByTag("Representation").single { it.hasAttr("width") }
        assertEquals("16:15",video.attr("sar")); assertEquals("720",video.attr("width")); assertEquals("576",video.attr("height"))
    }

    @Test fun audioNamesFollowRepresentationIdsAndUnavailableTracksAreRemoved() {
        val document = ZonaTracks.manifest(ZonaTracks.rewriteManifest(mpd(),context()))
        val tracks = document.getElementsByTag("AdaptationSet").filter { it.attr("contentType") == "audio" }
        assertEquals(2,tracks.size)
        assertEquals(listOf("Рус. Дублированный","Eng.Original"),tracks.map { it.getElementsByTag("Label").single().text() })
        assertEquals(listOf("ru","en"),tracks.map { it.attr("lang") })
        assertEquals(listOf("10","11"),tracks.map { it.attr("id") })
    }

    @Test fun languageSlotsMapVoicesEvenWhenVideoUsesIdZero() {
        val doc = ZonaTracks.manifest("""<MPD><Period>
            <AdaptationSet contentType="audio" lang="ru0"><Representation id="2"/></AdaptationSet>
            <AdaptationSet contentType="audio" lang="ru1"><Representation id="1"/></AdaptationSet>
            <AdaptationSet contentType="video"><Representation id="0" width="1280" height="720"/></AdaptationSet>
            </Period></MPD>""")
        assertEquals(mapOf("2" to "ТВ3","1" to "Eng.Original"),ZonaTracks.namedAudio(doc,listOf("ТВ3","Eng.Original")))
        assertEquals(mapOf("1" to "Eng.Original"),ZonaTracks.namedAudio(doc,listOf("delete","Eng.Original")))
    }

    @Test fun signedSegmentTransformationKeepsPathQueryAndServerTime() {
        val ctx = context()
        val transformed = ctx.transform("https://other.cdn.example/film/v/100.webm?token=test",epoch*1_000)
        val encoded = URI(transformed).rawPath.removePrefix("/x-en-x/")
        val decoded = Base64.decode(encoded.map { ch ->
            ctx.alphabet.indexOf(ch).let { if (it < 0) ch else ZonaTracks.LETTERS[it] }
        }.joinToString("")).toString(Charsets.UTF_8)
        assertEquals("${kotlin.math.round(epoch/3600.0).toLong()}//film/v/100.webm?token=test&session123",decoded)
        assertEquals(transformed,ctx.transform(transformed,epoch*1_000))
        assertEquals(ctx.transform(ctx.media,epoch*1_000),ctx.transform(ctx.media+"#quality",epoch*1_000))
        val unrelated = "https://subtitles.example/sub.vtt?token=test"
        assertEquals(unrelated,ctx.transform(unrelated,epoch*1_000))
    }

    @Test fun interceptorUsesRequiredAgentPreservesBaseUrlAndRewritesOnlyManifest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body(mpd()).addHeader("Content-Type","application/dash+xml").build())
            server.enqueue(MockResponse.Builder().body("media-bytes").build())
            server.start()
            val original = server.url("/film/master.mpd?token=test").toString()
            val ctx = context(original)
            val client = OkHttpClient.Builder().addInterceptor(ZonaTracks.interceptor(ctx)).build()
            client.newCall(Request.Builder().url(original).build()).execute().use { response ->
                assertEquals(original,response.request.url.toString())
                val bytes = response.body.bytes()
                assertEquals(bytes.size.toString(),response.header("Content-Length"))
                val document = ZonaTracks.manifest(bytes.toString(Charsets.UTF_8))
                assertEquals(listOf(ZonaTracks.Variant(1920,800)),ZonaTracks.variants(document))
            }
            val manifestRequest = server.takeRequest()
            assertTrue(manifestRequest.url.encodedPath.startsWith("/x-en-x/"))
            assertEquals("Kadrograd-test",manifestRequest.headers["User-Agent"])
            client.newCall(Request.Builder().url(server.url("/film/v/1.webm?token=test")).header("Range","bytes=0-63").build()).execute().use {
                assertEquals("media-bytes",it.body.string())
            }
            assertEquals("bytes=0-63",server.takeRequest().headers["Range"])
        }
    }

    @Test fun unsafeOrUnsupportedManifestsAndMissingQualityAreRejected() {
        for (text in listOf("<!DOCTYPE MPD><MPD/>","<!ENTITY x SYSTEM 'file:///test'><MPD/>","<MPD><ContentProtection/></MPD>","<html>blocked</html>")) {
            assertThrows(IllegalArgumentException::class.java) { ZonaTracks.manifest(text) }
        }
        assertThrows(IllegalArgumentException::class.java) { ZonaTracks.rewriteManifest(mpd(),context().copy(width=3840,height=2160)) }
        assertFalse(context().copy(parameter="bad\nheader").valid())
        assertFalse(context().copy(alphabet="a".repeat(52)).valid())
        assertFalse(context().copy(agent="bad\r\nheader").valid())
    }

    @Test fun playerLiteralRespectsQuotedAndEscapedBracketsAndRejectsTruncation() {
        val literal = """{"audio":{"names":["Рус { } ]","escaped \" quote"]},"cc":[]}"""
        assertEquals(literal,ZonaTracks.literal("source: $literal,other:true",8))
        assertThrows(IllegalStateException::class.java) { ZonaTracks.literal("{\"a\":[",0) }
        assertThrows(IllegalArgumentException::class.java) { ZonaTracks.literal("{]",0) }
    }
}
