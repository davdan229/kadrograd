package ru.kadrograd

/** Optional client capability; official CloudStream keeps its existing supported formats. */
internal object ClientCapabilities {
    val adaptiveDownloads: Boolean by lazy {
        try {
            Class.forName("com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload")
                .getMethod("isSupported").invoke(null) == true
        } catch (_: ReflectiveOperationException) { false }
          catch (_: LinkageError) { false }
    }
}
