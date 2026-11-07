package ru.kadrograd

import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** One resumable, bounded pass. No signed media URLs are written to the catalog. */
class UhdCatalogCheck {
    @Test fun updateVerifiedCatalog() = runBlocking {
        val output = System.getenv("KADROGRAD_UHD_OUTPUT")
        assumeTrue("Set KADROGRAD_UHD_OUTPUT to run the publisher", !output.isNullOrBlank())
        val target = File(requireNotNull(output)).canonicalFile
        require(target.name == "catalog.json" && target.parentFile?.isDirectory == true)
        val previous = if (target.isFile) {
            requireNotNull(tryParseJson<UhdCatalog.Snapshot>(target.readText()))
        } else UhdCatalog.Snapshot()
        require(previous.version == 1 && previous.cursor in 1..10_000 && previous.offset in 0..60)
        val now = System.currentTimeMillis()
        val entries = previous.movies.filter { now - it.checked in 0..259_200_000 }
            .associateBy { it.kpId }.toMutableMap()
        val api = ZonaProvider("https://w1.zona.im")
        val seen = mutableSetOf<Int>()
        var cursor = previous.cursor
        var offset = previous.offset
        var checked = 0
        var unavailable = 0

        suspend fun catalog(page: Int): ZonaProvider.Catalog {
            val text = SourceHttp.getCatalog("Zona", "${api.mainUrl}/api/movies?page=$page")
            return requireNotNull(tryParseJson<ZonaProvider.Catalog>(text))
        }

        suspend fun inspect(slug: String, kpId: Int) {
            if (!seen.add(kpId)) return
            try {
                val variants = withTimeoutOrNull(18_000) { api.discoverUhd(kpId) }
                    ?: throw IllegalStateException("Source timeout")
                val best = variants.maxByOrNull { it.width.toLong() * it.height }
                if (best == null) entries.remove(kpId)
                else entries[kpId] = UhdCatalog.Movie(slug, kpId, best.width, best.height, now)
                checked++
                println("UHD catalog: kp=$kpId status=${if (best == null) "no_uhd" else "verified"}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable++
                println("UHD catalog: kp=$kpId status=unavailable")
            }
        }

        withTimeoutOrNull(300_000) {
            // New releases recur in every pass, independently of the archive cursor.
            for (title in catalog(1).data.take(12)) {
                val id = title.kpId ?: continue
                if (!title.trailer && id > 0) inspect(title.slug, id)
            }
            // Revalidate the oldest successful observations before they expire.
            for (movie in entries.values.sortedBy { it.checked }.take(12).toList()) inspect(movie.slug, movie.kpId)
            repeat(2) {
                val page = catalog(cursor)
                for ((index, title) in page.data.withIndex().drop(offset)) {
                    val id = title.kpId
                    if (!title.trailer && id != null && id > 0) inspect(title.slug, id)
                    offset = index + 1
                }
                cursor = if (page.links?.next.isNullOrBlank()) 1 else cursor + 1
                offset = 0
            }
        }
        require(checked > 0) { "No source could be checked. Existing catalog is preserved." }
        val result = UhdCatalog.Snapshot(cursor = cursor, offset = offset,
            movies = entries.values.sortedByDescending { it.checked })
        val temporary = File(target.parentFile, "catalog.part")
        temporary.writeText(result.toJson() + "\n")
        java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        println("UHD catalog: checked=$checked unavailable=$unavailable verified=${entries.size} next=$cursor/$offset")
    }
}
