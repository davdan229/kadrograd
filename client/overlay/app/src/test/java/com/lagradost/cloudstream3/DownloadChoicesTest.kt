package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.downloader.DownloadChoices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadChoicesTest {
    private suspend fun link(label: String, resolution: Int, vararg voices: String) =
        newExtractorLink("Fixture", label, "https://cdn.example/$label.mpd", ExtractorLinkType.DASH) {
            quality = resolution
            extractorData = org.json.JSONObject().put("kind", "zona-dash-v1").put("width", 1920).put("height", resolution)
                .put("voices", org.json.JSONObject(voices.mapIndexed { index, name -> "$index" to name }.toMap())).toString()
        }

    @Test fun pickerOffersEachQualityAndVoiceWithoutAmbiguousOrDuplicateEntries() = runBlocking {
        val multiHd = link("multi-hd", 1080, "Студия А", "Студия Б")
        val multiSd = link("multi-sd", 480, "Студия А", "Студия Б")
        val hdA = link("hd-a", 1080, "Студия А")
        val hdB = link("hd-b", 1080, "Студия Б")
        val sdA = link("sd-a", 480, "Студия А")
        val sdB = link("sd-b", 480, "Студия Б")
        val duplicate = link("duplicate", 1080, "студия а")
        assertEquals(listOf(hdA, hdB, sdA, sdB), DownloadChoices.select(listOf(multiHd,multiSd,hdA,hdB,sdA,sdB,duplicate)))
    }
    @Test fun oldExtensionsKeepQualityWhenNotEveryVoiceHasAnExplicitLink() = runBlocking {
        val multi = link("multi", 720, "Студия А", "Студия Б")
        val single = link("single", 720, "Студия А")
        assertEquals(listOf(multi,single), DownloadChoices.select(listOf(multi,single)))
    }
    @Test fun unrelatedProvidersArePreserved() = runBlocking {
        val direct = newExtractorLink("Other", "Original", "https://cdn.example/file.mp4", ExtractorLinkType.VIDEO)
        assertEquals(listOf(direct), DownloadChoices.select(listOf(direct)))
    }
}
