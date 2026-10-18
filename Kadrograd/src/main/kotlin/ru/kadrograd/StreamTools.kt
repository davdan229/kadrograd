package ru.kadrograd

import java.net.URI
import org.jsoup.Jsoup
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Parsing of site transport formats; no guessed video resolutions or episode numbers. */
@OptIn(ExperimentalEncodingApi::class)
internal object StreamTools {
    data class LabeledUrl(val label: String, val url: String)

    fun origin(value: String): String? = runCatching {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.path.isNullOrBlank() || uri.path == "/")
        "https://${uri.rawAuthority}"
    }.getOrNull()

    fun isHttp(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun labeledUrls(value: String): List<LabeledUrl> {
        val sections = Regex("\\[([^]\\r\\n]+)](.*?)(?=,\\s*\\[|$)", RegexOption.DOT_MATCHES_ALL)
        return sections.findAll(value.replace("\\/", "/")).flatMap { match ->
            match.groupValues[2].split(" or ").asSequence().mapNotNull { raw ->
                val candidate = raw.trim().trimEnd(',')
                val url = if (candidate.startsWith("//")) "https:$candidate" else candidate
                if (isHttp(url)) LabeledUrl(Jsoup.parse(match.groupValues[1]).text().trim(), url) else null
            }
        }.distinct().toList()
    }

    fun quality(label: String): Int = when {
        label.contains("4k", true) || label.contains("uhd", true) -> 2160
        else -> Regex("(?<!\\d)(2160|1440|1080|720|576|480|360|240)(?:p|\\b)", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun decodeRezka(value: String): String {
        val raw = value.trim().replace("\\/", "/")
        if (raw.startsWith("[")) return raw
        var clean = raw.removePrefix("#h").replace("//_//", "")
        val chars = "@#!^$"
        // Remove longest tokens first: encoded two-character tokens can prefix longer tokens.
        for (size in 3 downTo 2) {
            var tokens = listOf("")
            repeat(size) { tokens = tokens.flatMap { prefix -> chars.map { prefix + it } } }
            for (token in tokens) clean = clean.replace(Base64.encode(token.toByteArray()), "")
        }
        return runCatching { String(Base64.decode(clean), Charsets.UTF_8) }.getOrDefault("")
    }

    fun decodeFilmix(value: String): String {
        val raw = value.trim().trim('"').replace("\\/", "/")
        if (raw.startsWith("[") || isHttp(raw)) return raw
        var clean = raw.removePrefix("#0").removePrefix("#h")
        for (token in listOf(":<:bzl3UHQwaWk0MkdXZVM3TDdB", ":<:SURhQnQwOEM5V2Y3bFlyMGVI", ":<:bE5qSTlWNVUxZ01uc3h0NFFy", ":<:Mm93S0RVb0d6c3VMTkV5aE54", ":<:MTluMWlLQnI4OXVic2tTNXpU")) {
            clean = clean.replace(token, "")
        }
        return runCatching { String(Base64.decode(clean), Charsets.UTF_8) }.getOrDefault("")
    }
}
