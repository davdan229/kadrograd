package ru.kadrograd

import kotlinx.coroutines.runBlocking
import mockwebserver3.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import kotlin.io.encoding.Base64

class ZonaHlsTest {
    private val base = "https://video.cdn.example/film/master.m3u8"
    private val names = listOf("Студия, Дубляж","Японский оригинал")
    private fun context(media: String = base) = ZonaTracks.Context(kind="zona-hls-v1",media=media,
        alphabet=ZonaTracks.LETTERS,parameter="session",epoch=1_790_000_000,observed=System.currentTimeMillis(),
        width=1280,height=720,agent="Kadrograd-test")
    private fun master(prefix: String = "") = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="rus0",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="ru",URI="${prefix}a0.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="jpn1",DEFAULT=NO,AUTOSELECT=NO,LANGUAGE="ja",URI="${prefix}a1.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="backup",NAME="rus0",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="ru",URI="${prefix}backup-a0.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1280x720,AUDIO="audio"
        ${prefix}720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=500000,CODECS="avc1.64001e,mp4a.40.2",RESOLUTION=640x360,AUDIO="audio"
        ${prefix}360.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1280x720,AUDIO="backup"
        ${prefix}backup-720.m3u8
    """.trimIndent()

    @Test fun resolutionSelectionKeepsCodecDimensionsAndMakesStudioLabelsVisible() {
        val parsed = ZonaHls.parse(master(),base)
        val voices = ZonaHls.labels(parsed,names).filterKeys { !it.contains("backup") }
        val ctx = context().copy(voices=voices,streams=listOf(parsed.videos.first().key))
        val output = ZonaHls.rewrite(master().replace(".m3u8",".m3u8?signature=new"),ctx)
        val result = ZonaHls.parse(output,base)
        assertEquals(listOf(ZonaTracks.Variant(1280,720)),result.videos.map { it.variant })
        assertEquals("avc1.64001f,mp4a.40.2",result.videos.single().attributes["CODECS"])
        assertEquals(names,result.audio.map { it.attributes["NAME"] })
        assertTrue(result.audio.none { "LANGUAGE" in it.attributes })
        assertFalse(output.contains("backup")); assertFalse(output.contains("640x360"))
        assertEquals(1,result.audio.count { it.attributes["DEFAULT"]=="YES" })
        assertTrue(result.videos.single().url.endsWith("?signature=new"))
        assertTrue(result.audio.all { it.url.endsWith("?signature=new") })
    }
    @Test fun unavailableFirstVoicePromotesAvailableOriginalWithoutRelabellingItsLanguage() {
        val parsed = ZonaHls.parse(master(),base)
        val ctx = context().copy(voices=mapOf(parsed.audio[1].key to names[1]),
            streams=listOf(parsed.videos.first().key))
        val result = ZonaHls.parse(ZonaHls.rewrite(master(),ctx),base)
        assertEquals("YES",result.audio.single().attributes["DEFAULT"])
        assertEquals("ja",result.audio.single().attributes["LANGUAGE"])
    }
    @Test fun mediaPlaylistIsPreservedAndUnsupportedOrMissingSelectionsFail() {
        val media = "#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n"
        assertEquals(media,ZonaHls.rewrite(media,context()))
        assertThrows(IllegalArgumentException::class.java) { ZonaHls.parse("<html>blocked</html>",base) }
        assertThrows(IllegalArgumentException::class.java) { ZonaHls.rewrite(master(),context()) }
        val bad = master().replace("720.m3u8","file:///tmp/video")
        assertThrows(IllegalArgumentException::class.java) { ZonaHls.parse(bad,base) }
    }
    @Test fun resolverKeepsWorkingQualitiesAndVoicesAcrossAudioGroups() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val plain = Base64.decode(request.url.encodedPath.substringAfter("/x-en-x/")).toString(Charsets.UTF_8)
                    val path = plain.substringAfter("//").substringBefore('?')
                    return when(path) {
                        "master.m3u8" -> MockResponse.Builder().body(master()).build()
                        "a0.m3u8","720.m3u8" -> MockResponse.Builder().code(404).body("missing").build()
                        "a1.m3u8","360.m3u8","backup-a0.m3u8","backup-720.m3u8" ->
                            MockResponse.Builder().body("#EXTM3U\n#EXTINF:10,\n1.ts\n#EXTINF:10,\n2.ts\n#EXT-X-ENDLIST\n").build()
                        "1.ts","2.ts" -> MockResponse.Builder().code(206).body("G"+"x".repeat(31)).build()
                        else -> MockResponse.Builder().code(500).body(path).build()
                    }
                }
            }
            server.start()
            val url = server.url("/master.m3u8").toString()
            val found = ZonaHls.load(url,context(url),names,emptyList())
            val lower = ZonaTracks.Variant(640,360)
            val higher = ZonaTracks.Variant(1280,720)
            assertEquals(listOf(higher,lower),found.variants)
            assertEquals(listOf(names[1]),found.voicesFor(lower).values.toList())
            assertEquals(listOf(names[0]),found.voicesFor(higher).values.toList())
            assertEquals(mapOf(names[0] to higher,names[1] to lower),
                ZonaTracks.voiceChoices(listOf(found)).associate { it.name to it.variant })
            val client = OkHttpClient.Builder().addInterceptor(ZonaTracks.interceptor(
                found.context.copy(width=640,height=360,voices=found.voicesFor(lower)))).build()
            fun get(target: String) = client.newCall(Request.Builder().url(target).build()).execute().use {
                assertTrue(it.isSuccessful)
                it.body.string()
            }
            val parsed = ZonaHls.parse(get("$url#kg-640x360"),url)
            assertEquals("ja",parsed.audio.single().attributes["LANGUAGE"])
            assertEquals(listOf(ZonaTracks.Variant(640,360)),parsed.videos.map { it.variant })
            for (playlist in listOf(parsed.audio.single().url,parsed.videos.single().url)) {
                assertTrue(get(playlist).contains("#EXTINF:"))
                assertTrue(get(java.net.URI(playlist).resolve("1.ts").toString()).startsWith("G"))
            }
        }
    }
}
