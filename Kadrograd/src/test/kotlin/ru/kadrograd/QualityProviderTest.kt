package ru.kadrograd

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QualityProviderTest {
    private class Fixture(private val heights: List<Int>) : QualityProvider() {
        override suspend fun loadSourceLinks(data: String, isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
            heights.forEach { height ->
                callback(newExtractorLink("Test","Test","https://example.org/$height.mp4") { quality = height })
            }
            return heights.isNotEmpty()
        }
    }

    @Test fun strictModeRejectsUnknownAndAllHeightsBelow1080WithoutUpscaling() = runBlocking {
        val provider = Fixture(listOf(0,480,720,800,1080,2160)).apply { fullHdOnly = true }
        val accepted = mutableListOf<Int>()
        assertTrue(provider.loadLinks("",false,{}) { accepted += it.quality })
        assertEquals(listOf(1080,2160),accepted)
    }

    @Test fun lowerQualityRequiresExplicitOptOut() = runBlocking {
        val provider = Fixture(listOf(720)).apply { fullHdOnly = true }
        val accepted = mutableListOf<Int>()
        try {
            provider.loadLinks("",false,{}) { accepted += it.quality }
            fail("Strict mode must report missing 1080p")
        } catch (e: ErrorLoadingException) {
            assertTrue(e.message.orEmpty().contains("1080p"))
        }
        assertTrue(accepted.isEmpty())
        provider.fullHdOnly = false
        assertTrue(provider.loadLinks("",false,{}) { accepted += it.quality })
        assertEquals(listOf(720),accepted)
    }
}
