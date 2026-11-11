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

    path = BASE + "utils/downloader/DownloadManager.kt"
    text = replace_once(changes[path], " || link.type == ExtractorLinkType.DASH", " /* DASH is handled by AdaptiveDownload below. */")
    text = replace_once(text, """                builder.setProgress((total / 1000).toInt(), (progress / 1000).toInt(), false)""", """                if (total > 0) {
                    builder.setProgress((total / 1000).coerceAtLeast(1).toInt(), (progress / 1000).toInt(), false)
                } else builder.setProgress(0, 0, true)""")
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
    text = replace_once(text, '                        "mp4",\n                        tryResume,',
        '                        if (link.url.toUri().lastPathSegment?.endsWith(".webm", ignoreCase = true) == true) "webm" else "mp4",\n                        tryResume,')
    text = replace_once(text, "            when (link.type) {\n                ExtractorLinkType.M3U8 -> {", """            if (link.type == ExtractorLinkType.DASH ||
                (link.type == ExtractorLinkType.M3U8 && getApiFromNameNull(ep.sourceApiName)?.getVideoInterceptor(link) != null)) {
                return AdaptiveDownload.download(context, link, ep.sourceApiName, name, folder ?: "", ep.id, callback)
            }
            when (link.type) {
                ExtractorLinkType.M3U8 -> {""")
    changes[path] = replace_once(text, "        } catch (_: Throwable) {\n            return DOWNLOAD_FAILED\n        } finally {\n            extractorJob.cancel()",
        "        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (_: Throwable) {\n            return DOWNLOAD_FAILED\n        } finally {\n            extractorJob.cancel()")

    path = BASE + "ui/download/DownloadViewModel.kt"
    text = replace_once(changes[path], "            if (childFile.fileLength <= 1) return@forEach", """            if (childFile.fileLength <= 1 &&
                com.lagradost.cloudstream3.utils.downloader.AdaptiveDownload.activeFileInfo(child.id) == null
            ) return@forEach""")
    changes[path] = replace_once(text, "            if (bytes <= 0 || downloads <= 0) {", "            if (downloads <= 0) {")

    path = BASE + "ui/download/button/BaseFetchButton.kt"
    text = replace_once(changes[path], "if (downloadedLength < 1024) 0 else maxOf(", "if (downloadedLength < 1024 || totalLength <= 0) 0 else maxOf(")
    text = replace_once(text, "downloadedBytes > 1024L && downloadedBytes + 1024L >= totalBytes", "totalBytes > 0 && downloadedBytes > 1024L && downloadedBytes + 1024L >= totalBytes")
    text = replace_once(text, """        progressBar.post {
            val steps = 10000L""", """        progressBar.post {
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
                view.text = if (totalBytes > 0) {
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
    changes[path] = replace_once(changes[path], """                            DOWNLOAD_ACTION_DOWNLOAD -> {
                                requirePathForActions(listOf(ACTION_DOWNLOAD_EPISODE to ep))
                            }""", """                            DOWNLOAD_ACTION_DOWNLOAD -> {
                                requirePathForActions(listOf(ACTION_DOWNLOAD_MIRROR to ep))
                            }""")

    path = BASE + "ui/result/ResultViewModel2.kt"
    text = replace_once(changes[path], "        isCasting: Boolean = false,\n        callback: (Pair<LinkLoadingResult, Int>) -> Unit",
        "        isCasting: Boolean = false,\n        forDownload: Boolean = false,\n        callback: (Pair<LinkLoadingResult, Int>) -> Unit")
    text = replace_once(text, """            // Could not find a better way to do this
            //val context = CloudStreamApp.context
            postPopup(
                text,
                links.links.map { txt("${it.name} ${Qualities.getStringByInt(it.quality)}") }""", """            val selected = if (forDownload) links.copy(links = com.lagradost.cloudstream3.utils.downloader.DownloadChoices.select(links.links)) else links
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
        picker = "Выбрать качество и озвучку" if path.parent.name == "values-ru" else "Choose quality and voice"
        text = re.sub(r'(<string name="episode_action_download_mirror">)[^<]*(</string>)', rf'\g<1>{picker}\g<2>', text)
        path.write_text(text, encoding="utf-8", newline="\n")
    marker.write_text(json.dumps({p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in HASHES}, indent=2))
    print("Prepared Kadrograd", CONFIG["versionName"], "from", CONFIG["revision"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    apply(parser.parse_args().checkout)
