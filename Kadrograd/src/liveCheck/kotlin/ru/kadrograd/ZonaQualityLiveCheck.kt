package ru.kadrograd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

class ZonaQualityLiveCheck {
    private val api = ZonaProvider("https://w1.zona.im")
    @Test fun matrixAndMentalistHaveNamedAudioAndRealVideoVariants() = runBlocking {
        val matrix = api.load("${api.mainUrl}/movies/matritsa") as MovieLoadResponse
        val mentalist = api.load("${api.mainUrl}/tvseries/mentalist-2008") as TvSeriesLoadResponse
        val lion = api.load("${api.mainUrl}/movies/korol-lev") as MovieLoadResponse
        for ((name,data) in listOf("Матрица" to matrix.dataUrl,"Менталист S01E01" to mentalist.episodes.first().data,"Король Лев" to lion.dataUrl)) {
            val links = mutableListOf<ExtractorLink>(); val subtitles = mutableListOf<SubtitleFile>()
            api.loadLinks(data,false,subtitles::add,links::add)
            val dash = links.filter { it.type == ExtractorLinkType.DASH }
            assertTrue("Rich player must return explicit qualities",dash.size >= 2)
            println("$name: variants=${dash.map { it.quality }}; subtitles=${subtitles.size}")
            for (link in dash) {
                val context = ZonaTracks.context(link)!!
                assertTrue(context.voices.values.any { it.contains("Рус",true) || it.contains("ТВ3",true) })
                val client = app.baseClient.newBuilder().callTimeout(15,TimeUnit.SECONDS).addInterceptor(api.getVideoInterceptor(link)!!).build()
                val document = client.newCall(Request.Builder().url(link.url).build()).execute().use { response ->
                    assertTrue(response.isSuccessful); ZonaTracks.manifest(response.body.string())
                }
                assertEquals(listOf(ZonaTracks.Variant(context.width,context.height)),ZonaTracks.variants(document))
                assertTrue(document.getElementsByTag("Representation").filter { it.hasAttr("width") }.all { it.attr("sar").ifBlank { "1:1" } == "1:1" })
                val labels = document.getElementsByTag("Label").map { it.text() }
                assertEquals(context.voices.values.toSet(),labels.toSet())
                val base = document.getElementsByTag("BaseURL").firstOrNull()?.text()?.let { URI(context.media).resolve(it).toString() } ?: context.media
                val reps = document.getElementsByTag("Representation")
                for (rep in listOf(reps.first()!!,reps.last()!!)) {
                    val template = rep.getElementsByTag("SegmentTemplate").first()!!
                    val url = URI(base).resolve(template.attr("media").replace("\$Number\$",template.attr("startNumber").ifBlank { "1" })).toString()
                    client.newCall(Request.Builder().url(url).header("Range","bytes=0-63").build()).execute().use { r ->
                        assertTrue("Real player-interceptor segment: ${r.code}",r.isSuccessful)
                        assertEquals("1f43b675",r.body.source().readByteArray(4).joinToString("") { "%02x".format(it.toInt() and 255) })
                    }
                }
                println("$name: ${context.width}x${context.height}; ${context.voices.size} named audio tracks; native interceptor segments verified")
            }
        }
        val last = mutableListOf<ExtractorLink>()
        api.loadLinks(mentalist.episodes.last().data,false,{},last::add)
        assertTrue("Last episode must not reuse another episode's rich player",last.none { it.type == ExtractorLinkType.DASH })
        assertTrue("Existing final-episode fallback must remain available",last.isNotEmpty())
        println("Менталист S07E13: only published direct fallback; native DASH absent from source API")
    }
}
