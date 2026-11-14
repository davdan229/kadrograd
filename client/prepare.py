"""Apply the client changes to an exact CloudStream checkout. No upstream source is vendored."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

HERE = Path(__file__).resolve().parent
CONFIG = json.loads((HERE / "upstream.json").read_text(encoding="utf-8"))
BASE = "app/src/main/java/com/lagradost/cloudstream3/"
HASHES = {
    "app/build.gradle.kts": "4bd6bc507a747b44c084ea3d39295ff49857b7f1bf15f05a393ce076a823d11a",
    BASE + "ui/player/IGenerator.kt": "def8faa0e34926ee2dc9f7d1fce755f3df9b25163e74c7e8d7a9583d04c9b26b",
    BASE + "ui/player/CS3IPlayer.kt": "8493a2815a06a14e2eb210cf9bb7c6e2cacf1845f92ff4f853bf1d432fd4e202",
    BASE + "utils/downloader/DownloadManager.kt": "46b6aae840806c01a93981800ab9e4ed3e71129c577d4cc7ea6cf58ea9de3002",
    BASE + "utils/InAppUpdater.kt": "11e1a1fbc74f820d84b08a57e74f0e168b311e6e28e8f4d235047672dfc849e7",
    BASE + "plugins/PluginManager.kt": "624f571a6cdb25fd4bc34c70e1b23ed541eb94fc34cf235d418a2e6975d6413e",
    BASE + "ui/home/HomeViewModel.kt": "7efc2bdacb5fe34bfad05833c5a2e1d3b1362d0a0f16538ce663aa2e3d40b4bd",
    BASE + "ui/result/EpisodeAdapter.kt": "b377967f8b5663a3949836af46773cec8f83ef52c9cfb184de042c0626143f9e",
    BASE + "ui/result/ResultFragmentPhone.kt": "f6dabd385895cb88f3201f632c413d337a4d72289b7f7d28bf87d9ffbe1967a1",
    BASE + "ui/result/ResultViewModel2.kt": "9a44eb8865d9dca08302751d6a36b651181bb848f2507ecf85570a933e37f4d5",
    BASE + "ui/download/button/BaseFetchButton.kt": "aeeeb1d898cecbd41c547f4dae6e46807756fbb98a9aa5b1b4beb36fc4f5983f",
    BASE + "ui/download/button/DownloadButton.kt": "e27c51f8d32d0a9177869c4dc593f857a53a16a49e57d1315316f40e2fb9fc61",
    BASE + "ui/download/button/PieFetchButton.kt": "49262fe36f907a69ad590e1f8ff4591b8b6c25bc40308c74d614a81a45048a6f",
    BASE + "ui/download/DownloadViewModel.kt": "952dcd894d809147abe8adcd448ff4036ea53980d34c98eb381371302319991c",
    BASE + "utils/downloader/DownloadQueueManager.kt": "b15301122cb1a64992e9bbf07cf5ba81d2e225ad6dd3e779d5aa8e5e3fb4c94c",
    BASE + "utils/downloader/DownloadObjects.kt": "395ad54b411e45adab1513aa02f7b8498f501e02320337aeba48181b43940863",
    BASE + "services/DownloadQueueService.kt": "a6b252d201c93fd27a349d6963225e5a51f1ca410cf25fc9bb433636b6a91240",
}


def replace_once(text, before, after):
    if after in text:
        return text
    if text.count(before) != 1:
        raise ValueError("Upstream anchor is missing or ambiguous: " + before[:90])
    return text.replace(before, after, 1)


def apply(root):
    root = root.resolve(strict=True)
    marker = root / ".kadrograd-patched.json"
    previous = json.loads(marker.read_text()) if marker.exists() else {}
    changes = {}
    for relative, expected in HASHES.items():
        raw = (root / relative).read_bytes()
        digest = hashlib.sha256(raw).hexdigest()
        if digest not in (expected, previous.get(relative)):
            raise ValueError("Unexpected upstream source: " + relative)
        changes[relative] = raw.decode("utf-8")

    path = "app/build.gradle.kts"
    text = changes[path]
    text = replace_once(text, "    signingConfigs {\n", """    testBuildType = providers.gradleProperty("kadrogradTestBuildType").orElse("debug").get()
    signingConfigs {
        System.getenv("KADROGRAD_SIGNING_STORE_PATH")?.let { signingStore ->
            create("kadrograd") {
                storeFile = file(signingStore)
                storePassword = System.getenv("KADROGRAD_SIGNING_PASSWORD")
                keyAlias = "kadrograd"
                keyPassword = System.getenv("KADROGRAD_SIGNING_PASSWORD")
            }
        }
""")
    text = replace_once(text, "        release {\n            isDebuggable = false", "        release {\n            signingConfig = signingConfigs.findByName(\"kadrograd\")\n            isDebuggable = false")
    text = replace_once(text, "        debug {\n            isDebuggable = true", "        debug {\n            signingConfig = signingConfigs.findByName(\"kadrograd\") ?: signingConfigs.getByName(\"debug\")\n            isDebuggable = true")
    text = replace_once(text, 'applicationId = "com.lagradost.cloudstream3"', f'applicationId = "{CONFIG["applicationId"]}"')
    text = replace_once(text, "versionCode = libs.versions.versionCode.get().toInt()", f'versionCode = {CONFIG["versionCode"]}')
    text = replace_once(text, "versionName = libs.versions.versionName.get()", f'versionName = "{CONFIG["versionName"]}"')
    text = replace_once(text, "implementation(libs.bundles.media3)", 'implementation(libs.bundles.media3)\n    implementation("androidx.media3:media3-transformer:1.9.3")')
    text = replace_once(text, "dependencies {\n    // Testing", """// Instrumentation and the app share j$ classes. Configure L8 after AGP's task
// creation actions so that both APKs retain the same public compatibility APIs.
afterEvaluate {
    tasks.withType<com.android.build.gradle.internal.tasks.L8DexDesugarLibTask>().configureEach {
        keepRulesConfigurations.set(listOf("-dontobfuscate", "-dontoptimize", "-keep class * { *; }"))
        debuggable.set(false)
    }
}

dependencies {
    // Testing""")
    changes[path] = text

    path = BASE + "ui/player/IGenerator.kt"
    changes[path] = replace_once(changes[path],
        "val LOADTYPE_INAPP_DOWNLOAD = setOf(\n    ExtractorLinkType.VIDEO,\n    ExtractorLinkType.M3U8\n)",
        "val LOADTYPE_INAPP_DOWNLOAD = setOf(\n    ExtractorLinkType.VIDEO,\n    ExtractorLinkType.DASH,\n    ExtractorLinkType.M3U8\n)")

    path = BASE + "ui/player/CS3IPlayer.kt"
    text = replace_once(changes[path], """        audioSources: List<MediaSource> = emptyList()
    ): ExoPlayer""", """        audioSources: List<MediaSource> = emptyList(),
        downloadedFile: Boolean = false,
    ): ExoPlayer""")
    text = replace_once(text, """            .setFragmentedMp4ExtractorFlags(FragmentedMp4Extractor.FLAG_MERGE_FRAGMENTED_SIDX)

        // Create an online connection""", """            .setFragmentedMp4ExtractorFlags(FragmentedMp4Extractor.FLAG_MERGE_FRAGMENTED_SIDX)
            .apply {
                if (downloadedFile) {
                    // Older HLS downloads store MPEG-TS under .mp4. Look farther for
                    // PCR timestamps so padding or a large last frame won't disable seeking.
                    setTsExtractorTimestampSearchBytes(2 * 1024 * 1024)
                }
            }

        // Create an online connection""")
    text = replace_once(text, """        onlineSource: HttpDataSource.Factory? = null,
    ) {
        Log.i(TAG, "loadExo")""", """        onlineSource: HttpDataSource.Factory? = null,
        downloadedFile: Boolean = false,
    ) {
        Log.i(TAG, "loadExo")""")
    text = replace_once(text, "                onlineSource = onlineSource,\n            )\n\n            event(PlayerAttachedEvent",
        "                onlineSource = onlineSource,\n                downloadedFile = downloadedFile,\n            )\n\n            event(PlayerAttachedEvent")
    changes[path] = replace_once(text, "            loadExo(context, listOf(MediaItemSlice(mediaItem, Long.MIN_VALUE)), subSources)",
        "            loadExo(context, listOf(MediaItemSlice(mediaItem, Long.MIN_VALUE)), subSources, downloadedFile = true)")

    path = BASE + "utils/downloader/DownloadManager.kt"
    text = replace_once(changes[path], " || link.type == ExtractorLinkType.DASH", " /* DASH is handled by AdaptiveDownload below. */")
    text = replace_once(text, "            if (total <= 0) return null// crash, invalid data", "            // Adaptive exports can start before a size estimate is available.")
    text = replace_once(text, """                builder.setProgress((total / 1000).toInt(), (progress / 1000).toInt(), false)""", """                val adaptiveProgress = AdaptiveDownload.progressPercentage(ep.id)
                if (adaptiveProgress != null) {
                    builder.setProgress(100, adaptiveProgress, false)
                } else if (total > 0) {
                    builder.setProgress((total / 1000).coerceAtLeast(1).toInt(), (progress / 1000).toInt(), false)
                } else builder.setProgress(0, 0, true)""")
    text = replace_once(text, "                    progressPercentage = progress * 100 / total", "                    progressPercentage = if (total > 0) progress * 100 / total else 0")
    text = replace_once(text, """                            (if (linkName == null) "" else "$linkName\\n") + "$rowTwo\\n$progressPercentage % ($progressMbString/$totalMbString)$suffix$mbPerSecondString $remainingTime" """.rstrip(),
        """                            (if (linkName == null) "" else "$linkName\\n") +
                                if (AdaptiveDownload.progressText(context, ep.id, progress) != null) {
                                    "$rowTwo\\n" + AdaptiveDownload.progressText(context, ep.id, progress) + mbPerSecondString
                                } else if (total <= 0 && hlsTotal == null) {
                                    "$rowTwo\\n" + android.text.format.Formatter.formatShortFileSize(context, progress) + mbPerSecondString
                                } else "$rowTwo\\n$progressPercentage % ($progressMbString/$totalMbString)$suffix$mbPerSecondString $remainingTime" """.rstrip())
    text = replace_once(text, """    fun getDownloadFileInfo(
        context: Context,
        id: Int,
    ): DownloadedFileInfoResult? {
        try {""", """    fun getDownloadFileInfo(
        context: Context,
        id: Int,
    ): DownloadedFileInfoResult? {
        AdaptiveDownload.activeFileInfo(id)?.let { return it }
        try {""")
    text = replace_once(text, """            return DownloadedFileInfoResult(
                file.lengthOrThrow(),
                info.totalBytes,
                file.uriOrThrow()
            )""", """            val length = file.lengthOrThrow()
            val uri = file.uriOrThrow()
            val checked = DownloadRecordRepair.reconcile(context, id, info, uri, length)
            return DownloadedFileInfoResult(length, checked.totalBytes, uri)""")
    text = replace_once(text, '                        "mp4",\n                        tryResume,',
        '                        if (link.url.toUri().lastPathSegment?.endsWith(".webm", ignoreCase = true) == true) "webm" else "mp4",\n                        tryResume,')
    text = replace_once(text, "            when (link.type) {\n                ExtractorLinkType.M3U8 -> {", """            if (link.type == ExtractorLinkType.DASH ||
                (link.type == ExtractorLinkType.M3U8 && getApiFromNameNull(ep.sourceApiName)?.getVideoInterceptor(link) != null)) {
                return AdaptiveDownload.download(context, link, ep.sourceApiName, name, folder ?: "", ep.id, callback)
            }
            when (link.type) {
                ExtractorLinkType.M3U8 -> {""")
    text = replace_once(text, "            val currentLinks = mutableSetOf<ExtractorLink>()",
        "            val currentLinks = java.util.concurrent.ConcurrentHashMap.newKeySet<ExtractorLink>()")
    text = replace_once(text, "            // Remove link loading notification", """            val candidates = downloadItem.batchChoice?.let { selection ->
                currentLinks.filter { DownloadChoices.batchChoice(it) == selection }
            } ?: currentLinks.toList()
            // Remove link loading notification""")
    text = replace_once(text, "} else if (currentLinks.isEmpty()) {", "} else if (candidates.isEmpty()) {")
    text = replace_once(text, "val sortedLinks = currentLinks.sortedBy", "val sortedLinks = candidates.sortedBy")
    text = replace_once(text, """            metadata.type = DownloadType.IsDone
            return@withContext DOWNLOAD_SUCCESS
        } catch (t: Throwable) {
            logError(t)
            metadata.type = DownloadType.IsFailed
            return@withContext metadata.failedStatus()""", """            // At EOF the number of bytes written is exact, including resumed segments.
            metadata.totalBytes = metadata.bytesWritten
            metadata.bytesDownloaded = metadata.bytesWritten
            metadata.type = DownloadType.IsDone
            return@withContext DOWNLOAD_SUCCESS
        } catch (t: Throwable) {
            logError(t)
            metadata.type = DownloadType.IsFailed
            return@withContext metadata.failedStatus()""")
    changes[path] = replace_once(text, "        } catch (_: Throwable) {\n            return DOWNLOAD_FAILED\n        } finally {\n            extractorJob.cancel()",
        "        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (_: Throwable) {\n            return DOWNLOAD_FAILED\n        } finally {\n            extractorJob.cancel()")

    path = BASE + "utils/downloader/DownloadObjects.kt"
    text = replace_once(changes[path], '        @JsonProperty("subs") val subs: List<SubtitleData>? = null,',
        '        @JsonProperty("subs") val subs: List<SubtitleData>? = null,\n        @JsonProperty("batchChoice") val batchChoice: BatchChoice? = null,')
    changes[path] = replace_once(text, "    /** General data about the episode and show to start a download from. */", """    data class BatchChoice(
        @JsonProperty("source") val source: String,
        @JsonProperty("quality") val quality: Int,
        @JsonProperty("voice") val voice: String,
    )

    /** General data about the episode and show to start a download from. */""")

    path = BASE + "utils/downloader/DownloadQueueManager.kt"
    text = replace_once(changes[path], """            newQueue.forEach { obj ->
                setQueueStatus(obj.id, VideoDownloadManager.DownloadType.IsPending)
            }

            if (newQueue.any()) {""", """            if (DownloadRecovery.holdRestoredQueue(context)) {
                newQueue.forEach { obj -> setQueueStatus(obj.id, VideoDownloadManager.DownloadType.IsPaused) }
                return@startQueue
            }
            newQueue.forEach { obj ->
                setQueueStatus(obj.id, VideoDownloadManager.DownloadType.IsPending)
            }

            if (newQueue.any()) {""")
    changes[path] = replace_once(text, """        if (add(downloadQueueWrapper)) {""", """        DownloadRecovery.allowUserRequest(context)
        if (add(downloadQueueWrapper)) {""")

    path = BASE + "services/DownloadQueueService.kt"
    text = replace_once(changes[path], """    override fun onCreate() {
        isRunning = true""", """    override fun onCreate() {
        if (com.lagradost.cloudstream3.utils.downloader.DownloadRecovery.holdRestoredQueue(this)) {
            stopSelf()
            return
        }
        isRunning = true""")
    changes[path] = replace_once(text, """        return START_STICKY // We want the service restarted if its killed""", """        return if (com.lagradost.cloudstream3.utils.downloader.DownloadRecovery.holdRestoredQueue(this)) {
            START_NOT_STICKY
        } else START_STICKY""")

    path = BASE + "ui/download/DownloadViewModel.kt"
    text = replace_once(changes[path], "            if (childFile.fileLength <= 1) return@forEach", """            if (childFile.fileLength <= 1 &&
                com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload.activeFileInfo(child.id) == null
            ) return@forEach""")
    changes[path] = replace_once(text, "            if (bytes <= 0 || downloads <= 0) {", "            if (downloads <= 0) {")

    path = BASE + "ui/download/button/BaseFetchButton.kt"
    text = replace_once(changes[path], """        get() = if (downloadedLength < 1024) 0 else maxOf(
            0,
            minOf(100, (downloadedLength * 100L) / (totalLength + 1))
        )""", """        get() {
            if (status == DownloadStatusTell.IsDone) return 100L
            com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload.progressPercentage(id)?.let { return it.toLong() }
            return if (downloadedLength < 1024 || totalLength <= 0) 0L
                else (100.0 * downloadedLength / totalLength).toLong().coerceIn(0L, 100L)
        }""")
    text = replace_once(text, "(downloadedBytes * steps / (totalBytes + 1L)).toInt()", "if (totalBytes > 0) (downloadedBytes.toDouble() * steps / totalBytes).toInt().coerceIn(0, steps.toInt()) else 0")
    text = replace_once(text, "downloadedBytes > 1024L && downloadedBytes + 1024L >= totalBytes", "totalBytes > 0 && downloadedBytes > 1024L && downloadedBytes + 1024L >= totalBytes")
    text = replace_once(text, """        progressBar.post {
            val steps = 10000L""", """        progressBar.post {
            val adaptive = com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload.progressPercentage(persistentId)
            if (adaptive != null) {
                progressBar.clearAnimation()
                progressBar.isIndeterminate = false
                progressBar.max = 100
                progressBar.progress = adaptive
                if (doSetProgress) progressText?.text = com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload.progressText(context, persistentId, downloadedBytes)
                return@post
            }
            val unknownTotal = totalBytes <= 0 && downloadedBytes > 0
            progressBar.isIndeterminate = unknownTotal
            if (unknownTotal) {
                progressBar.clearAnimation()
                if (doSetProgress) progressText?.text = formatShortFileSize(context, downloadedBytes)
                return@post
            }
            val steps = 10000L""")
    changes[path] = text

    path = BASE + "ui/download/button/PieFetchButton.kt"
    changes[path] = replace_once(changes[path], "(metadata.downloadedLength > 1024 && metadata.downloadedLength + 1024 >= metadata.totalLength)", "(metadata.totalLength > 0 && metadata.downloadedLength > 1024 && metadata.downloadedLength + 1024 >= metadata.totalLength)")

    path = BASE + "ui/download/button/DownloadButton.kt"
    text = replace_once(changes[path], "            textView,\n            card,", "            textView ?: findViewById(R.id.result_movie_download_text_precentage),\n            card,")
    changes[path] = replace_once(text, """        val isVis = metadata.progressPercentage > 0
        progressText?.isVisible = isVis
        if (isVis)
            progressText?.text = "${metadata.progressPercentage}%"
    }""", """        updateProgressText(metadata.downloadedLength, metadata.totalLength)
    }

    override fun setProgress(downloadedBytes: Long, totalBytes: Long) {
        super.setProgress(downloadedBytes, totalBytes)
        updateProgressText(downloadedBytes, totalBytes)
    }

    private fun updateProgressText(downloadedBytes: Long, totalBytes: Long) {
        progressText?.post {
            val view = progressText ?: return@post
            view.isVisible = downloadedBytes > 0 && currentMetaData.status in setOf(
                DownloadStatusTell.IsDownloading, DownloadStatusTell.IsPaused
            )
            if (view.isVisible) {
                view.text = com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload.progressText(context, persistentId, downloadedBytes) ?: if (totalBytes > 0) {
                    "${(100.0 * downloadedBytes / totalBytes).toInt().coerceIn(0, 99)}%"
                } else android.text.format.Formatter.formatShortFileSize(context, downloadedBytes)
            }
        }
    }""")

    # This fork has another package and signing certificate; upstream APKs cannot update it.
    path = BASE + "utils/InAppUpdater.kt"
    changes[path] = replace_once(changes[path], "    ): Boolean {\n        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)",
        '    ): Boolean {\n        if (BuildConfig.APPLICATION_ID.startsWith("ru.kadrograd.")) return false\n        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)')

    # Keep the explicit automatic-download action; only the normal button opens the picker.
    path = BASE + "ui/result/EpisodeAdapter.kt"
    text = changes[path]
    before = "                                        ACTION_DOWNLOAD_EPISODE,"
    after = "                                        ACTION_DOWNLOAD_MIRROR,"
    if before in text:
        if text.count(before) != 2:
            raise ValueError("Unexpected episode download buttons")
        text = text.replace(before, after)
    changes[path] = text
    path = BASE + "ui/result/ResultFragmentPhone.kt"
    text = replace_once(changes[path], """                            DOWNLOAD_ACTION_DOWNLOAD -> {
                                requirePathForActions(listOf(ACTION_DOWNLOAD_EPISODE to ep))
                            }""", """                            DOWNLOAD_ACTION_DOWNLOAD -> {
                                requirePathForActions(listOf(ACTION_DOWNLOAD_MIRROR to ep))
                            }""")
    text = replace_once(text, "ConcurrentLinkedDeque<Pair<Int, ResultEpisode>>()", "ConcurrentLinkedDeque<() -> Unit>()")
    text = replace_once(text, """        pendingPathActions.addAll(list)
        val (_, path)""", """        requirePathForAction {
            list.forEach { (action, episode) -> viewModel.handleAction(EpisodeClickEvent(action, episode)) }
        }
    }

    private fun requirePathForAction(action: () -> Unit) {
        pendingPathActions.add(action)
        val (_, path)""")
    text = replace_once(text, """                val (action, data) = pendingPathActions.pop()
                viewModel.handleAction(
                    EpisodeClickEvent(
                        action,
                        data
                    )
                )""", "                pendingPathActions.removeFirst().invoke()")
    text = replace_once(text, "                                requirePathForActions(episodes.value.map { ACTION_DOWNLOAD_EPISODE to it })",
        "                                val batch = episodes.value.toList()\n                                requirePathForAction { viewModel.chooseBatchDownload(batch) }")
    changes[path] = replace_once(text, """                        popup.callback(it)
                    }
                )
            }
        }""", """                        popup.callback(it)
                    }
                ).also { dialog ->
                    if (title == act.getString(R.string.episode_action_download_mirror)) {
                        com.lagradost.cloudstream3.utils.downloader.DownloadChoiceDialog.fitSystemBars(dialog)
                    }
                }
            }
        }""")

    path = BASE + "ui/result/ResultViewModel2.kt"
    text = replace_once(changes[path], "        isCasting: Boolean = false,\n        callback: (Pair<LinkLoadingResult, Int>) -> Unit",
        "        isCasting: Boolean = false,\n        forDownload: Boolean = false,\n        forBatch: Boolean = false,\n        callback: (Pair<LinkLoadingResult, Int>) -> Unit")
    text = replace_once(text, """            // Could not find a better way to do this
            //val context = CloudStreamApp.context
            postPopup(
                text,
                links.links.map { txt("${it.name} ${Qualities.getStringByInt(it.quality)}") }""", """            val downloadChoices = com.lagradost.cloudstream3.utils.downloader.DownloadChoices
            val selected = if (forDownload) links.copy(links = downloadChoices.select(links.links)
                .let { offers -> if (forBatch) offers.filter { downloadChoices.batchChoice(it) != null } else offers }) else links
            if (forBatch && selected.links.isEmpty()) {
                showToast(R.string.no_links_found_toast, Toast.LENGTH_SHORT)
                return@loadLinks
            }
            postPopup(
                text,
                selected.links.map { txt("${it.name} ${Qualities.getStringByInt(it.quality)}") }""")
    text = replace_once(text, """                callback.invoke(links to (it ?: return@postPopup))
            }
        }
    }

    private fun acquireSingleSubtitle""", """                callback.invoke(selected to (it ?: return@postPopup))
            }
        }
    }

    private fun acquireSingleSubtitle""")
    text = replace_once(text, "    private var currentLoadLinkJob: Job? = null", """    fun chooseBatchDownload(episodes: List<ResultEpisode>) {
        val first = episodes.firstOrNull() ?: return
        val response = currentResponse ?: return
        acquireSingleLink(first, LOADTYPE_INAPP_DOWNLOAD, txt(R.string.episode_action_download_mirror),
            forDownload = true, forBatch = true) { (result, index) ->
            val choice = com.lagradost.cloudstream3.utils.downloader.DownloadChoices.batchChoice(result.links[index])
                ?: return@acquireSingleLink
            episodes.forEach { episode ->
                DownloadQueueManager.addToQueue(
                    DownloadObjects.DownloadQueueItem(
                        episode, response.isMovie(), response.name, response.type, response.posterUrl,
                        response.apiName, response.getId(), response.url, batchChoice = choice
                    ).toWrapper()
                )
            }
        }
    }

    private var currentLoadLinkJob: Job? = null""")
    changes[path] = replace_once(text, "                    txt(R.string.episode_action_download_mirror)\n                ) { (result, index) ->",
        "                    txt(R.string.episode_action_download_mirror),\n                    forDownload = true\n                ) { (result, index) ->")

    path = BASE + "ui/home/HomeViewModel.kt"
    text = replace_once(changes[path], "                    val items = data.value.mapNotNull { it?.items }.flatten()",
        """                    _page.postValue(Resource.Success(expandable))
                    val items = data.value.mapNotNull { it?.items }.flatten()""")
    text = replace_once(text, """                                3
                            )

                            _randomItems.postValue(randomItems)""", """                                3
                            )
                            if (previewResponses.isEmpty()) {
                                kotlinx.coroutines.withTimeoutOrNull(20_000) {
                                    repeat(2) {
                                        if (previewResponses.isEmpty()) {
                                            updatePreviewResponses(previewResponses, previewResponsesAdded, randomItems, 3)
                                        }
                                    }
                                }
                            }

                            _randomItems.postValue(randomItems)""")
    changes[path] = replace_once(text, """                        _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
                    }
                    _page.postValue(Resource.Success(expandable))
                } catch (e: Exception) {""", """                        _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
                    }
                } catch (e: Exception) {""")

    path = BASE + "plugins/PluginManager.kt"
    changes[path] = replace_once(changes[path], """    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context: Context) {
        assertNonRecursiveCallstack()
""", """    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context: Context) {
        assertNonRecursiveCallstack()
        try {
            BundledSources.installLock.withLock {
                val bundled = BundledSources.prepare(context, getPluginsOnline())
                if (bundled != null) {
                    if (loadPlugin(context, File(bundled.filePath), bundled)) {
                        RepositoryManager.addRepository(RepositoryData("Кадроград", BundledSources.repositoryUrl))
                        BundledSources.finish(context)
                    } else deletePluginData(bundled)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logError(error)
        }
""")

    for relative, text in changes.items():
        (root / relative).write_text(text, encoding="utf-8", newline="\n")
    for source in (HERE / "overlay").rglob("*"):
        if source.is_file():
            target = root / source.relative_to(HERE / "overlay")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
    for path in (root / "app/src/main/res").glob("values*/strings.xml"):
        text = path.read_text(encoding="utf-8")
        import re
        label = "CloudStream"
        text = re.sub(r'(<string name="app_name">)[^<]*(</string>)', rf'\g<1>{label}\g<2>', text)
        picker = "Выбрать качество и озвучку" if path.parent.name in {"values-ru", "values-b+ru"} else "Choose quality and voice"
        text = re.sub(r'(<string name="episode_action_download_mirror">)[^<]*(</string>)', rf'\g<1>{picker}\g<2>', text)
        path.write_text(text, encoding="utf-8", newline="\n")
    marker.write_text(json.dumps({p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in HASHES}, indent=2))
    print("Prepared Kadrograd", CONFIG["versionName"], "from", CONFIG["revision"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    apply(parser.parse_args().checkout)
