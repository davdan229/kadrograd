package ru.kadrograd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI

/** Public HLS master/audio playlists; no script evaluation or video transcoding. */
internal object ZonaHls {
    data class Video(val attributes: Map<String,String>, val url: String, val variant: ZonaTracks.Variant) {
        val key get() = listOf("AUDIO","RESOLUTION","CODECS","BANDWIDTH","FRAME-RATE").joinToString("|") { attributes[it].orEmpty() }
    }
    data class Audio(val attributes: Map<String,String>, val url: String) {
        val key get() = listOf("GROUP-ID","NAME","LANGUAGE","CHANNELS").joinToString("|") { attributes[it].orEmpty() }
    }
    data class Master(val lines: List<String>, val videos: List<Video>, val audio: List<Audio>)
    fun attributes(line: String): Map<String,String> =
        Regex("""([A-Z0-9-]+)=("[^"\r\n]*"|[^,\r\n]*)""").findAll(line.substringAfter(':'))
            .associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }
    private fun absolute(base: String, value: String): String = URI(base).resolve(value).toString().also { require(StreamTools.isHttp(it)) }
    fun parse(text: String, base: String): Master {
        require(text.length < 2_000_000 && text.trimStart().startsWith("#EXTM3U"))
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val videos = mutableListOf<Video>(); val audio = mutableListOf<Audio>()
        for ((index,line) in lines.withIndex()) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = attributes(line)
                val dimensions = Regex("(\\d+)x(\\d+)").matchEntire(attrs["RESOLUTION"].orEmpty()) ?: continue
                val path = lines.getOrNull(index+1)?.takeUnless { it.startsWith('#') } ?: continue
                videos += Video(attrs,absolute(base,path),ZonaTracks.Variant(dimensions.groupValues[1].toInt(),dimensions.groupValues[2].toInt()))
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                val attrs = attributes(line)
                if (attrs["TYPE"] == "AUDIO" && !attrs["URI"].isNullOrBlank()) audio += Audio(attrs,absolute(base,attrs.getValue("URI")))
            }
        }
        require(videos.size <= 100 && audio.size <= 200)
        return Master(lines,videos,audio)
    }
    fun labels(master: Master, names: List<String>): Map<String,String> {
        val used = mutableMapOf<String,MutableSet<String>>()
        return master.audio.mapNotNull { track ->
            val slot = Regex("(\\d+)$").find(track.attributes["NAME"].orEmpty())?.value?.toIntOrNull() ?: return@mapNotNull null
            val original = names.getOrNull(slot)?.takeIf { it.isNotBlank() && it != "delete" } ?: return@mapNotNull null
            val label = original.replace('"','\'').replace('\r',' ').replace('\n',' ')
            val group = used.getOrPut(track.attributes["GROUP-ID"].orEmpty()) { mutableSetOf() }
            var unique = label
            var suffix = 2
            while (!group.add(unique)) unique = "$label (${suffix++})"
            track.key to unique
        }.toMap()
    }

    fun rewrite(text: String, context: ZonaTracks.Context): String {
        val master = parse(text,context.media)
        if (master.videos.isEmpty()) return text
        val selected = master.videos.filter { it.key in context.streams && it.variant == ZonaTracks.Variant(context.width,context.height) }
        require(selected.isNotEmpty())
        val groups = selected.mapNotNull { it.attributes["AUDIO"] }.toSet()
        val audio = master.audio.filter { it.key in context.voices && it.attributes["GROUP-ID"] in groups }
        require(audio.isNotEmpty())
        val output = mutableListOf<String>(); var skipVideoUri = false
        val defaults = mutableSetOf<String>()
        for ((index,line) in master.lines.withIndex()) {
            if (skipVideoUri) { skipVideoUri=false; continue }
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                skipVideoUri=true
                val value = master.lines.getOrNull(index+1) ?: continue
                val url = absolute(context.media,value)
                if (selected.any { it.url == url && it.attributes == attributes(line) }) { output += line; output += value }
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                val attrs = attributes(line)
                if (attrs["TYPE"] != "AUDIO") { output += line; continue }
                val uri = attrs["URI"]?.let { absolute(context.media,it) } ?: continue
                val key = Audio(attrs,uri).key
                if (audio.none { it.key == key }) continue
                val name = context.voices.getValue(key).replace('"','\'').replace('\r',' ').replace('\n',' ')
                val isDefault = defaults.add(attrs["GROUP-ID"].orEmpty())
                var rendered = line.replace(Regex("""\bNAME="[^"]*"""")) { "NAME=\"$name\"" }
                    .replace(Regex("\\bDEFAULT=(YES|NO)"),"DEFAULT="+if (isDefault) "YES" else "NO")
                if (isDefault) rendered = rendered.replace("AUTOSELECT=NO","AUTOSELECT=YES")
                output += rendered
            } else output += line
        }
        return output.joinToString("\n",postfix="\n")
    }

    private suspend fun probe(url: String, context: ZonaTracks.Context): Boolean = try {
        val headers = mapOf("User-Agent" to context.agent)
        val text = SourceHttp.get("Zona",context.transform(url),headers=headers,timeoutSeconds=4).text
        require(text.trimStart().startsWith("#EXTM3U") && text.contains("#EXTINF:"))
        // Encrypted media is left to other published formats until its keys are verified.
        require(!Regex("#EXT-X-KEY:.*METHOD=(?!NONE)").containsMatchIn(text))
        val segments = text.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }.toList()
        require(segments.isNotEmpty())
        for (segment in listOf(segments.first(),segments[segments.size/2]).distinct()) {
            SourceHttp.get("Zona",context.transform(absolute(url,segment)),headers=headers+("Range" to "bytes=0-31"),timeoutSeconds=4)
                .okhttpResponse.use { response ->
                    withContext(Dispatchers.IO) {
                        val bytes = response.body.source().readByteArray(12)
                        require(bytes[0] == 0x47.toByte() || String(bytes,0,3,Charsets.US_ASCII) == "ID3")
                    }
                }
        }
        true
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }

    suspend fun load(url: String, context: ZonaTracks.Context, names: List<String>, subtitles: List<ZonaTracks.Sub>): ZonaTracks.Found = coroutineScope {
        val hls = context.copy(kind="zona-hls-v1",media=url)
        val text = SourceHttp.get("Zona",hls.transform(url),headers=mapOf("User-Agent" to hls.agent),timeoutSeconds=6).text
        val master = parse(text,url)
        val labels = labels(master,names)
        val compatible = master.videos.filter { it.attributes["CODECS"].orEmpty().split(',').any { codec -> codec.trim().startsWith("avc1.") } }
        val groups = compatible.mapNotNull { it.attributes["AUDIO"] }.distinct()
        for (group in groups) {
            val video = compatible.filter { it.attributes["AUDIO"] == group }
            val audio = master.audio.filter { it.attributes["GROUP-ID"] == group && it.key in labels }
            if (audio.isEmpty()) continue
            val limit = Semaphore(6)
            val results = (video.map { it.url }+audio.map { it.url }).distinct().map { target ->
                async { limit.withPermit { target to probe(target,hls) } }
            }.map { it.await() }.toMap()
            val available = video.filter { results[it.url] == true }
            val voices = labels.filterKeys { key -> audio.any { it.key == key && results[it.url] == true } }
            if (available.isNotEmpty() && voices.isNotEmpty()) return@coroutineScope ZonaTracks.Found(url,
                hls.copy(streams=available.map { it.key },voices=voices),available.map { it.variant }.distinct().sortedByDescending { it.height },subtitles)
        }
        throw com.lagradost.cloudstream3.ErrorLoadingException("Zona: проверенные HLS-видео и озвучки недоступны")
    }
}
