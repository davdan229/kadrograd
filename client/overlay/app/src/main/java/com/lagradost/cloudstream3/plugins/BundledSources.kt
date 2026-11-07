package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object BundledSources {
    const val repositoryUrl = "https://raw.githubusercontent.com/davdan229/kadrograd/main/repo.json"
    private const val internalName = "Kadrograd"
    private const val seeded = "extension_seeded"
    val installLock = Mutex()

    private fun preferences(context: Context) =
        context.getSharedPreferences("kadrograd_client", Context.MODE_PRIVATE)

    fun finish(context: Context) {
        preferences(context).edit().putBoolean(seeded, true).apply()
    }

    fun prepare(context: Context, installed: Array<PluginData>): PluginData? {
        val settings = preferences(context)
        if (!settings.getBoolean("home_types_migrated", false)) {
            if (DataStoreHelper.homePreference.toSet() == setOf(TvType.Movie, TvType.TvSeries)) {
                DataStoreHelper.homePreference = TvType.values().filter { it != TvType.NSFW }
            }
            settings.edit().putBoolean("home_types_migrated", true).apply()
        }
        if (preferences(context).getBoolean(seeded, false)) return null
        val target = PluginManager.getPluginPath(context, internalName, repositoryUrl)
        if (installed.any { it.filePath == target.absolutePath && target.isFile }) {
            finish(context)
            return null
        }
        val metadata = context.assets.open("kadrograd/extension.json").bufferedReader().use { JSONObject(it.readText()) }
        val version = metadata.getInt("version")
        val digest = metadata.getString("sha256")
        require(Regex("[a-f0-9]{64}").matches(digest))
        val payload = context.assets.open("kadrograd/Kadrograd.cs3").use { it.readBytes() }
        require(payload.size in 1..10_000_000)
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash(payload) == digest) { "Bundled extension checksum mismatch" }
        val directory = requireNotNull(target.parentFile)
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create extension directory" }
        if (!target.isFile || hash(target.readBytes()) != digest) {
            val temporary = File.createTempFile("extension-", ".part", directory)
            try {
                temporary.writeBytes(payload)
                require(temporary.renameTo(target)) { "Cannot install bundled extension" }
            } finally { temporary.delete() }
        }
        return PluginData(internalName, metadata.getString("url"), true, target.absolutePath, version)
    }
}
