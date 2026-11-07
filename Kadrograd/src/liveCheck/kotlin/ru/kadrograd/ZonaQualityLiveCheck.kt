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
    @Test fun openBlenderFilmsPopulateTheFreshUhdShelfAndPlayWithoutTheZonaVideoServer() = runBlocking {
        api.videoApiOrigins = emptyList()
        val shelf = api.getMainPage(1, MainPageRequest("Фильмы в 4K", "verified-4k", false))
        val cards = shelf.items.single().list
        assertEquals(BlenderUhd.films.map { it.slug }, cards.map { it.url.substringAfterLast('/') })
        for (card in cards) {
            assertEquals(SearchQuality.FourK, card.quality)
            val details = api.load(card.url) as MovieLoadResponse
            val links = mutableListOf<ExtractorLink>()
            assertTrue(api.loadLinks(details.dataUrl, false, {}, links::add))
            println("${card.name}: ${links.map { it.name }}")
            assertTrue("Expected 4K, 1080p and 720p for ${card.name}", links.map { it.quality }.containsAll(listOf(2160, 1080, 720)))
            assertTrue(links.all { it.type == ExtractorLinkType.VIDEO && URI(it.url).host == "upload.wikimedia.org" })
        }
    }

    @Test fun publishedRickAndMortyPlayerReturnsSyendukSegments() = runBlocking {
        val sources = java.util.concurrent.ConcurrentLinkedQueue<ZonaTracks.Found>()
        ZonaTracks({listOf("https://vsw01.zonasearch.com")},
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .load("301",1,1,sources::add)
        val choice = ZonaTracks.voiceChoices(sources.toList()).single { it.name == "Сыендук" }
        assertEquals(ZonaTracks.Variant(1920,1080),choice.variant)
        assertTrue(choice.found.variants.any { it.height==720 })
        assertTrue(choice.found.variants.any { it.height in 360..500 })
        val ctx = choice.found.context.copy(width=1920,height=1080,voices=mapOf(choice.id to choice.name))
        val client = app.baseClient.newBuilder().callTimeout(12,TimeUnit.SECONDS).addInterceptor(ZonaTracks.interceptor(ctx)).build()
        val manifest = client.newCall(Request.Builder().url(choice.found.url).build()).execute().use {
            assertTrue(it.isSuccessful); ZonaTracks.manifest(it.body.string())
        }
        assertEquals(listOf("Сыендук"),manifest.getElementsByTag("Label").map { it.text() })
        val base = manifest.getElementsByTag("BaseURL").firstOrNull()?.text()?.let { URI(ctx.media).resolve(it) } ?: URI(ctx.media)
        for (rep in manifest.getElementsByTag("Representation")) {
            val template = rep.getElementsByTag("SegmentTemplate").first()!!
            val path = template.attr("media").replace("\$Number\$",template.attr("startNumber").ifBlank { "1" })
            client.newCall(Request.Builder().url(base.resolve(path).toString()).header("Range","bytes=0-63").build()).execute().use {
                assertTrue("Media HTTP ${it.code}",it.isSuccessful)
                assertEquals("1f43b675",it.body.source().readByteArray(4).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) })
            }
        }
        println("Rick and Morty S01E01: Сыендук 1920x1080 audio/video received")
    }
    @Test fun rickAndMortyHasNamedSyendukAndMultipleRealResolutions() = runBlocking {
        val series = api.load("${api.mainUrl}/tvseries/rik-i-morti") as TvSeriesLoadResponse
        for (season in listOf(1,8)) {
            val ep = series.episodes.single { it.season == season && it.episode == 1 }
            val links = mutableListOf<ExtractorLink>()
            assertTrue(api.loadLinks(ep.data,false,{},links::add))
            assertEquals(1080,links.first().quality)
            assertTrue(links.any { it.quality == 720 } && links.any { it.quality in 360..500 })
            val voice = links.first { it.name.contains("Сыендук") && it.quality == 1080 }
            val ctx = ZonaTracks.context(voice)!!
            val client = app.baseClient.newBuilder().callTimeout(12,TimeUnit.SECONDS)
                .addInterceptor(api.getVideoInterceptor(voice)!!).build()
            val manifest = client.newCall(Request.Builder().url(voice.url).build()).execute().use {
                assertTrue(it.isSuccessful); ZonaTracks.manifest(it.body.string())
            }
            assertEquals(listOf("Сыендук"),manifest.getElementsByTag("Label").map { it.text() })
            assertEquals(listOf(ZonaTracks.Variant(1920,1080)),ZonaTracks.variants(manifest))
            val base = manifest.getElementsByTag("BaseURL").firstOrNull()?.text()?.let { URI(ctx.media).resolve(it) } ?: URI(ctx.media)
            for (rep in manifest.getElementsByTag("Representation")) {
                val template = rep.getElementsByTag("SegmentTemplate").first()!!
                val path = template.attr("media").replace("\$Number\$",template.attr("startNumber").ifBlank { "1" })
                client.newCall(Request.Builder().url(base.resolve(path).toString()).header("Range","bytes=0-63").build()).execute().use {
                    assertTrue("Video/audio segment HTTP ${it.code}",it.isSuccessful)
                    assertEquals("1f43b675",it.body.source().readByteArray(4).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) })
                }
            }
            println("Rick and Morty S${season}E1: Сыендук 1920x1080; audio/video verified; qualities=${links.map { it.quality }.distinct()}")
        }
    }
    @Test fun fullHdModeEmitsOnlyPublished1080OrHigher() = runBlocking {
        val provider = ZonaProvider(api.mainUrl).apply { fullHdOnly = true }
        val film = provider.load("${provider.mainUrl}/movies/interstellar") as MovieLoadResponse
        val links = mutableListOf<ExtractorLink>()
        assertTrue(provider.loadLinks(film.dataUrl,false,{},links::add))
        assertTrue(links.isNotEmpty() && links.all { it.quality >= 1080 })
        val link = links.first()
        val context = ZonaTracks.context(link)!!
        val client = app.baseClient.newBuilder().callTimeout(15,TimeUnit.SECONDS).addInterceptor(provider.getVideoInterceptor(link)!!).build()
        val manifest = client.newCall(Request.Builder().url(link.url).build()).execute().use {
            assertTrue("Full HD manifest HTTP ${it.code}",it.isSuccessful)
            ZonaTracks.manifest(it.body.string())
        }
        assertEquals(listOf(ZonaTracks.Variant(context.width,context.height)),ZonaTracks.variants(manifest))
        val base = manifest.getElementsByTag("BaseURL").firstOrNull()?.text()?.let { URI(context.media).resolve(it) } ?: URI(context.media)
        val audio = manifest.getElementsByTag("AdaptationSet").first { it.attr("contentType") == "audio" }
            .getElementsByTag("Representation").first()!!
        val video = manifest.getElementsByTag("Representation").single { it.hasAttr("width") }
        for (representation in listOf(audio,video)) {
            val template = representation.getElementsByTag("SegmentTemplate").first()!!
            val target = base.resolve(template.attr("media").replace("\$Number\$",template.attr("startNumber").ifBlank { "1" }))
            client.newCall(Request.Builder().url(target.toString()).header("Range","bytes=0-63").build()).execute().use {
                assertTrue("Full HD segment HTTP ${it.code}",it.isSuccessful)
                assertEquals("1f43b675",it.body.source().readByteArray(4).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) })
            }
        }
        println("Interstellar: ${context.width}x${context.height}, strict 1080p; video and audio segments verified")
    }
    @Test fun hlsH264ProvidesNamedVoicesAndRealQualityForMovieAndCartoon() = runBlocking {
        for (slug in listOf("matritsa","korol-lev")) {
            val movie = api.load("${api.mainUrl}/movies/$slug") as MovieLoadResponse
            val links = mutableListOf<ExtractorLink>()
            api.loadLinks(movie.dataUrl,false,{},links::add)
            val hls = links.filter { ZonaTracks.context(it)?.kind == "zona-hls-v1" && "-voice-" !in it.url }
            assertTrue("$slug must have native HLS qualities",hls.size >= 2)
            if (slug == "korol-lev") assertTrue(hls.any { it.quality == 720 })
            for (link in hls) {
                val context = ZonaTracks.context(link)!!
                assertTrue(context.voices.size > 1)
                val client = app.baseClient.newBuilder().callTimeout(12,TimeUnit.SECONDS).addInterceptor(api.getVideoInterceptor(link)!!).build()
                fun get(url: String): String = client.newCall(Request.Builder().url(url).build()).execute().use {
                    assertTrue("HLS playlist HTTP ${it.code}",it.isSuccessful);it.body.string()
                }
                val master = ZonaHls.parse(get(link.url),context.media)
                assertEquals(listOf(ZonaTracks.Variant(context.width,context.height)),master.videos.map { it.variant }.distinct())
                assertEquals(context.voices.values.toSet(),master.audio.map { it.attributes["NAME"] }.toSet())
                assertTrue(master.videos.all { it.attributes["CODECS"].orEmpty().contains("avc1") })
                for (url in listOf(master.videos.first().url,master.audio.first().url,master.audio.last().url)) {
                    val segments = get(url).lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }.toList()
                    assertTrue(segments.isNotEmpty())
                    for (part in listOf(segments.first(),segments[segments.size/2]).distinct()) {
                        val mediaUrl = URI(url).resolve(part).toString()
                        client.newCall(Request.Builder().url(mediaUrl).header("Range","bytes=0-63").build()).execute().use {
                            assertTrue("HLS media HTTP ${it.code}",it.isSuccessful)
                            assertEquals(0x47,it.body.source().readByte().toInt() and 255)
                        }
                    }
                }
                println("$slug HLS: ${context.width}x${context.height}, ${context.voices.size} named voices, H.264, first/middle audio/video segments verified")
            }
        }
    }
    @Test fun matrixAndMentalistHaveNamedAudioAndRealVideoVariants() = runBlocking {
        val matrix = api.load("${api.mainUrl}/movies/matritsa") as MovieLoadResponse
        val mentalist = api.load("${api.mainUrl}/tvseries/mentalist-2008") as TvSeriesLoadResponse
        val lion = api.load("${api.mainUrl}/movies/korol-lev") as MovieLoadResponse
        for ((name,data) in listOf("Матрица" to matrix.dataUrl,"Менталист S01E01" to mentalist.episodes.first().data,"Король Лев" to lion.dataUrl)) {
            val links = mutableListOf<ExtractorLink>(); val subtitles = mutableListOf<SubtitleFile>()
            api.loadLinks(data,false,subtitles::add,links::add)
            val dash = links.filter { it.type == ExtractorLinkType.DASH && "-voice-" !in it.url }
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
