package com.lagradost.cloudstream3.plugins

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object BundledSources {
    const val repositoryUrl = "https://raw.githubusercontent.com/davdan229/kadrograd/main/repo.json"

    suspend fun prepare(context: Context, installed: Array<PluginData>): PluginData? {
        val metadata = context.assets.open("kadrograd/extension.json").bufferedReader().use { JSONObject(it.readText()) }
        val version = metadata.getInt("version")
        if (installed.any { it.internalName == "Kadrograd" && it.version >= version && File(it.filePath).isFile }) return null
        val digest = metadata.getString("sha256")
        require(Regex("[a-f0-9]{64}").matches(digest))
        val payload = context.assets.open("kadrograd/Kadrograd.cs3").use { it.readBytes() }
        require(payload.size in 1..10_000_000)
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash(payload) == digest) { "Bundled extension checksum mismatch" }
        val directory = File(context.filesDir, "bundled-sources").apply { mkdirs() }
        val target = File(directory, "Kadrograd-$digest.cs3")
        if (!target.isFile || hash(target.readBytes()) != digest) {
            val temporary = File.createTempFile("extension-", ".part", directory)
            try {
                temporary.writeBytes(payload)
                require(temporary.renameTo(target)) { "Cannot install bundled extension" }
            } finally { temporary.delete() }
        }
        return PluginData("Kadrograd", metadata.getString("url"), true, target.absolutePath, version)
    }
}
