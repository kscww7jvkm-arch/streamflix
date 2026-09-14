package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import android.util.Base64
import androidx.media3.common.MimeTypes
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NekostreamExtractor : Extractor() {

    override val name = "Nekostream"
    override val mainUrl = "https://vidtube.site"
    override val aliasUrls = listOf(
        "https://megaplay.buzz",
        "https://vidwish.live",
    )

    private val client = OkHttpClient.Builder()
        .dns(DnsResolver.doh)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun extract(link: String): Video {
        val streamPageUrl = link.substringBefore("?") + link.substringAfter("?", "?autostart=true").let {
            if (link.contains("?")) "?${link.substringAfter("?")}" else it
        }
        val pageUri = Uri.parse(streamPageUrl)
        val origin = "${pageUri.scheme}://${pageUri.host}"
        val pageBody = getText(
            url = streamPageUrl,
            referer = "https://anikototv.to/",
            origin = origin,
            accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        )

        val fileId = Regex("""id=["']megaplay-player["'][^>]*data-id=["']([^"']+)""")
            .find(pageBody)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""data-id=["']([^"']+)["'][^>]*id=["']megaplay-player["']""")
                .find(pageBody)
                ?.groupValues
                ?.getOrNull(1)
            ?: throw Exception("Nekostream player file id not found")

        val streamType = Regex("""type:\s*['"]([^'"]+)""")
            .find(pageBody)
            ?.groupValues
            ?.getOrNull(1)

        fun sourcesUrl(endpoint: String) = buildString {
            append("$origin/stream/$endpoint?id=$fileId")
            streamType?.let { append("&type=${Uri.encode(it)}") }
            pageUri.getQueryParameter("s")?.let { append("&s=${Uri.encode(it)}") }
        }

        // MegaPlay may return either a plain sources.file or the newer
        // encrypted enc payload. Keep both endpoint variants because old
        // records may still use the legacy route.
        val responses = listOf("getSourcesNew", "getSources").mapNotNull { endpoint ->
            runCatching {
                Gson().fromJson(
                    getText(
                        url = sourcesUrl(endpoint),
                        referer = streamPageUrl,
                        origin = origin,
                        accept = "application/json, text/javascript, */*; q=0.01",
                        requestedWith = true,
                    ),
                    SourcesResponse::class.java,
                )
            }.getOrNull()
        }

        val source = responses.asSequence()
            .mapNotNull { it.sources?.file?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: responses.asSequence()
                .mapNotNull { it.enc?.takeIf(String::isNotBlank) }
                .mapNotNull { encrypted ->
                    decryptMegaPlaySource(
                        encrypted = encrypted,
                        pageBody = pageBody,
                        pageUrl = streamPageUrl,
                        origin = origin,
                    )
                }
                .firstOrNull()
            ?: throw Exception("Nekostream source not found")

        val metadata = responses.firstOrNull { !it.tracks.isNullOrEmpty() }
            ?: responses.firstOrNull()

        return Video(
            source = source,
            subtitles = metadata?.tracks.orEmpty()
                .filter { it.kind == null || it.kind == "captions" }
                .mapNotNull {
                    Video.Subtitle(
                        label = it.label?.ifBlank { null } ?: "Subtitle",
                        file = it.file ?: return@mapNotNull null,
                        default = it.default == true,
                    )
                },
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$origin/",
                "Origin" to origin,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-GPC" to "1",
            ),
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    private fun decryptMegaPlaySource(
        encrypted: String,
        pageBody: String,
        pageUrl: String,
        origin: String,
    ): String? {
        val scriptUrls = Regex(
            """<script[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
            .findAll(pageBody)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .distinct()
            .toList()

        for (src in scriptUrls) {
            val scriptUrl = runCatching {
                URI(pageUrl).resolve(src).toString()
            }.getOrNull() ?: continue

            val script = runCatching {
                getText(
                    url = scriptUrl,
                    referer = pageUrl,
                    origin = origin,
                    accept = "*/*",
                )
            }.getOrNull() ?: continue

            if (
                !script.contains("getSources", ignoreCase = true) ||
                !script.contains("AES-CBC", ignoreCase = true)
            ) {
                continue
            }

            // Current MegaPlay player exposes the SegmentDecrypt AES seed
            // and IV as adjacent P/w string constants. Resolve them from the
            // live player script instead of hardcoding a version.
            val keyIv = Regex(
                """var\s+P=["']([^"']+)["'],w=["']([^"']+)["']"""
            )
                .findAll(script)
                .firstOrNull { match ->
                    val from = match.range.last + 1
                    val to = minOf(script.length, from + 1800)
                    script.substring(from, to)
                        .contains("AES-CBC", ignoreCase = true)
                }
                ?: continue

            val keySeed = keyIv.groupValues[1]
            val ivSeed = keyIv.groupValues[2]

            val key = ByteArray(32)
            keySeed.toByteArray(Charsets.UTF_8)
                .copyInto(
                    destination = key,
                    endIndex = minOf(
                        keySeed.toByteArray(Charsets.UTF_8).size,
                        key.size,
                    ),
                )

            val iv = ByteArray(16)
            ivSeed.toByteArray(Charsets.UTF_8)
                .copyInto(
                    destination = iv,
                    endIndex = minOf(
                        ivSeed.toByteArray(Charsets.UTF_8).size,
                        iv.size,
                    ),
                )

            val padded = encrypted + "=".repeat(
                (4 - encrypted.length % 4) % 4
            )

            val cipherText = runCatching {
                Base64.decode(
                    padded,
                    Base64.URL_SAFE or Base64.NO_WRAP,
                )
            }.getOrNull() ?: continue

            val plainText = runCatching {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    IvParameterSpec(iv),
                )

                cipher.doFinal(cipherText)
                    .toString(Charsets.UTF_8)
            }.getOrNull() ?: continue

            val decoded = runCatching {
                JsonParser.parseString(plainText).asJsonObject
            }.getOrNull() ?: continue

            val source = sequenceOf("file", "url")
                .mapNotNull { keyName ->
                    decoded.get(keyName)
                        ?.takeUnless { it.isJsonNull }
                        ?.runCatching { asString }
                        ?.getOrNull()
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }
                .firstOrNull()

            if (!source.isNullOrBlank()) {
                return source
            }
        }

        return null
    }

    private fun getText(
        url: String,
        referer: String,
        origin: String,
        accept: String,
        requestedWith: Boolean = false,
    ): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", referer)
            .header("Origin", origin)
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")

        if (requestedWith) {
            requestBuilder.header("X-Requested-With", "XMLHttpRequest")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Nekostream request failed ${response.code}: $url")
            }
            return response.body?.string().orEmpty()
        }
    }

    private data class SourcesResponse(
        val sources: Sources? = null,
        val tracks: List<Track>? = null,
        val enc: String? = null,
    ) {
        data class Sources(
            val file: String? = null,
        )

        data class Track(
            val file: String? = null,
            val label: String? = null,
            val kind: String? = null,
            val default: Boolean? = null,
        )
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}