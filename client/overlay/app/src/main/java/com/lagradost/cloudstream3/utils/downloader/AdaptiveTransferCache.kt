package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.io.File
import java.security.MessageDigest

/** Keeps incoming media until the selected download is finalized or cancelled. */
internal class AdaptiveTransferCache(context: Context, id: Int, link: ExtractorLink) {
    private val database = StandaloneDatabaseProvider(context.applicationContext)
    private val identity = buildString {
        append(link.type).append('\n').append(link.url).append('\n')
        append(link.extractorData).append('\n').append(link.referer).append('\n')
        link.headers.toSortedMap().forEach { (key, value) -> append(key).append(':').append(value).append('\n') }
    }
    private val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    private val directory = File(root(context, id), key)
    private val cache: SimpleCache

    init {
        root(context, id).listFiles()?.filter {
            it != directory && it.isDirectory && !SimpleCache.isCacheFolderLocked(it)
        }?.forEach { SimpleCache.delete(it, database) }
        cache = try { SimpleCache(directory, NoOpCacheEvictor(), database) }
        catch (error: Exception) { database.close(); throw error }
    }

    fun factory(upstream: DataSource.Factory): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)

    fun close() {
        try { cache.release() }
        finally { database.close() }
    }

    companion object {
        private fun root(context: Context, id: Int) = File(context.filesDir, "adaptive-segments/$id")

        fun discard(context: Context, id: Int) {
            val root = root(context, id)
            if (!root.isDirectory) return
            StandaloneDatabaseProvider(context.applicationContext).use { database ->
                root.listFiles()?.filter { it.isDirectory && !SimpleCache.isCacheFolderLocked(it) }
                    ?.forEach { SimpleCache.delete(it, database) }
            }
            root.delete()
        }
    }
}
