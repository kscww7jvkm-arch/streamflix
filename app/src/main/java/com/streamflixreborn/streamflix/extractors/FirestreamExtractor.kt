package com.streamflixreborn.streamflix.extractors

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URI
import java.net.URL

class FirestreamExtractor : Extractor() {

    override val name = "Firestream"
    override val mainUrl = "https://firestream.to"

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }

    private data class PageLoad(
        val html: String,
        val cookies: String,
    )

    override suspend fun extract(link: String): Video {
        val parsed = URL(link)
        val baseUrl = "${parsed.protocol}://${parsed.host}"
        val slug = parsed.path
            .trim('/')
            .substringAfterLast('/')

        if (slug.isBlank()) {
            throw Exception("Firestream slug not found")
        }

        val client = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        // Always fetch a fresh embed page because token-blob is one-time.
        val pageRequest = Request.Builder()
            .url(link)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", "$baseUrl/")
            .build()

        val page = client.newCall(pageRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception(
                    "Firestream embed HTTP ${response.code}"
                )
            }

            PageLoad(
                html = response.body?.string().orEmpty(),
                cookies = response.headers("Set-Cookie")
                    .map { it.substringBefore(';') }
                    .filter { it.isNotBlank() }
                    .joinToString("; "),
            )
        }

        if (page.html.isBlank()) {
            throw Exception("Firestream embed page is empty")
        }

        val document = Jsoup.parse(page.html, baseUrl)

        val videoDataRaw =
            document.getElementById("video-data")
                ?.data()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document.getElementById("video-data")
                    ?.html()
                    ?.trim()
                    .orEmpty()

        if (videoDataRaw.isBlank()) {
            throw Exception("Firestream video-data not found")
        }

        val root = JsonParser.parseString(videoDataRaw).asJsonObject
        val video =
            root.get("video")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: throw Exception("Firestream video object not found")

        fun stringValue(obj: JsonObject, key: String): String? =
            obj.get(key)
                ?.takeIf { !it.isJsonNull }
                ?.runCatching { asString }
                ?.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        var source =
            stringValue(video, "signedVideoUrl")
                ?: stringValue(video, "signedVideoSdUrl")

        if (source.isNullOrBlank()) {
            val tokenBlob =
                document.getElementById("token-blob")
                    ?.data()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: document.getElementById("token-blob")
                        ?.html()
                        ?.trim()
                        .orEmpty()

            if (tokenBlob.isBlank()) {
                throw Exception("Firestream token-blob not found")
            }

            val resolveJson = JsonObject().apply {
                addProperty("blob", tokenBlob)
            }

            val resolveRequest = Request.Builder()
                .url("$baseUrl/api/videos/$slug/resolve")
                .post(
                    resolveJson.toString()
                        .toRequestBody(
                            "application/json; charset=utf-8"
                                .toMediaType()
                        )
                )
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", baseUrl)
                .header("Referer", link)
                .apply {
                    if (page.cookies.isNotBlank()) {
                        header("Cookie", page.cookies)
                    }
                }
                .build()

            source = client.newCall(resolveRequest).execute().use { response ->
                val raw = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw Exception(
                        "Firestream resolve HTTP ${response.code}: " +
                            raw.take(250)
                    )
                }

                val resolved =
                    JsonParser.parseString(raw).asJsonObject

                stringValue(resolved, "signedVideoUrl")
                    ?: stringValue(resolved, "signedVideoSdUrl")
            }
        }

        if (source.isNullOrBlank()) {
            throw Exception("Firestream signed video URL not found")
        }

        val subtitles =
            video.get("subtitles")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { element ->
                    val obj = element.asJsonObject

                    val rawUrl =
                        stringValue(obj, "url")
                            ?: return@mapNotNull null

                    val subtitleUrl =
                        runCatching {
                            URI("$baseUrl/")
                                .resolve(rawUrl)
                                .toString()
                        }.getOrDefault(rawUrl)

                    val label =
                        stringValue(obj, "label")
                            ?: stringValue(obj, "language")
                            ?: "Subtitle"

                    Video.Subtitle(
                        file = subtitleUrl,
                        label = label,
                        default =
                            obj.get("default")
                                ?.takeIf { !it.isJsonNull }
                                ?.runCatching { asBoolean }
                                ?.getOrNull()
                                ?: false,
                    )
                }
                ?: emptyList()

        return Video(
            source = source,
            subtitles = subtitles,
        )
    }
}
