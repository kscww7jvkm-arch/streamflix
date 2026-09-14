package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import androidx.media3.common.MimeTypes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class VideasyExtractor : Extractor() {
    override val name = "Videasy"
    override val mainUrl = "https://api.speedracelight.com"

    data class ServerConfig(
        val name: String,
        val endpoint: String,
        val movieOnly: Boolean = false
    )

    private val englishServers = listOf(
        ServerConfig("Yoru", "cdn"),
        ServerConfig("Cypher", "downloader2"),
        ServerConfig("Breach", "m4uhd"),
        ServerConfig("Neon", "vsrc"),
        ServerConfig("Vyse", "hdmovie")
    )

    fun servers(videoType: Video.Type, language: String): List<Video.Server> {
        return when (language) {
            "en" -> {
                englishServers.mapNotNull { config ->
                    if (config.movieOnly && videoType !is Video.Type.Movie) return@mapNotNull null
                    
                    val url = when (videoType) {
                        is Video.Type.Movie -> {
                            val year = videoType.releaseDate.split("-").firstOrNull() ?: ""
                            "$mainUrl/${config.endpoint}/sources-with-title?title=${videoType.title}&mediaType=movie&year=$year&tmdbId=${videoType.id}&imdbId=${videoType.imdbId ?: ""}"
                        }
                        is Video.Type.Episode -> {
                            val year = videoType.tvShow.releaseDate?.split("-")?.firstOrNull() ?: ""
                            "$mainUrl/${config.endpoint}/sources-with-title?title=${videoType.tvShow.title}&mediaType=tv&year=$year&tmdbId=${videoType.tvShow.id}&imdbId=${videoType.tvShow.imdbId ?: ""}&episodeId=${videoType.number}&seasonId=${videoType.season.number}"
                        }
                    }
                    
                    Video.Server(
                        id = "${config.name} (Videasy)",
                        name = "${config.name} (Videasy)",
                        src = url
                    )
                }
            }
            else -> {
                val serverName = when (language) {
                    "de" -> "Killjoy (Videasy)"
                    else -> return emptyList()
                }
                
                val videasyLang = when (language) {
                    "de" -> "german"
                    else -> return emptyList()
                }

                val endpoint = "meine"

                val url = when (videoType) {
                    is Video.Type.Movie -> {
                        val year = videoType.releaseDate.split("-").firstOrNull() ?: ""
                        "$mainUrl/$endpoint/sources-with-title?title=${videoType.title}&mediaType=movie&year=$year&tmdbId=${videoType.id}&imdbId=${videoType.imdbId ?: ""}&language=$videasyLang"
                    }
                    is Video.Type.Episode -> {
                        val year = videoType.tvShow.releaseDate?.split("-")?.firstOrNull() ?: ""
                        "$mainUrl/$endpoint/sources-with-title?title=${videoType.tvShow.title}&mediaType=tv&year=$year&tmdbId=${videoType.tvShow.id}&imdbId=${videoType.tvShow.imdbId ?: ""}&episodeId=${videoType.number}&seasonId=${videoType.season.number}&language=$videasyLang"
                    }
                }

                listOf(Video.Server(
                    id = serverName,
                    name = serverName,
                    src = url
                ))
            }
        }
    }

    fun server(videoType: Video.Type, language: String): Video.Server? {
        return servers(videoType, language).firstOrNull()
    }

    override suspend fun extract(link: String): Video {
        val client = OkHttpClient()
        val originalUri = Uri.parse(link)

        val tmdbId = originalUri
            .getQueryParameter("tmdbId")
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Videasy TMDb ID missing")

        val endpoint = originalUri
            .pathSegments
            .firstOrNull()
            .orEmpty()

        /*
         * VIDEASY_SEED_PROTOCOL_V2
         *
         * Current Videasy protocol:
         *
         * 1. GET /seed?mediaId=TMDB
         * 2. sources-with-title?...&enc=2&seed=...
         * 3. dec-videasy with text + id + seed
         *
         * Keep the previous protocol below as a fallback.
         */
        val seed = getSeed(
            client = client,
            tmdbId = tmdbId,
        )

        if (!seed.isNullOrBlank()) {
            val candidates = buildModernCandidates(
                originalUri = originalUri,
                endpoint = endpoint,
                seed = seed,
            )

            for (candidate in candidates) {
                val video = runCatching {
                    Log.d(
                        TAG,
                        "Modern endpoint=$endpoint url=$candidate",
                    )

                    val encrypted =
                        fetchEncrypted(
                            client = client,
                            url = candidate,
                        )

                    val result =
                        decryptModern(
                            client = client,
                            encrypted = encrypted,
                            tmdbId = tmdbId,
                            seed = seed,
                        )

                    buildVideo(
                        resultJson = result,
                        endpoint = endpoint,
                    )
                }.onFailure {
                    Log.w(
                        TAG,
                        "Modern endpoint=$endpoint failed: ${it.message}",
                    )
                }.getOrNull()

                if (video != null) {
                    return video
                }
            }
        }

        Log.d(
            TAG,
            "Modern Videasy failed; trying legacy protocol",
        )

        return extractLegacy(
            link = link,
            client = client,
            endpoint = endpoint,
            tmdbId = tmdbId,
        )
    }

    private fun getSeed(
        client: OkHttpClient,
        tmdbId: String,
    ): String? {
        return runCatching {
            val request = Request.Builder()
                .url("$mainUrl/seed?mediaId=${Uri.encode(tmdbId)}")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,*/*")
                .header("Origin", PLAYER_URL)
                .header("Referer", "$PLAYER_URL/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(
                        TAG,
                        "Seed HTTP ${response.code}",
                    )
                    return@use null
                }

                val raw =
                    response.body
                        ?.string()
                        .orEmpty()

                JSONObject(raw)
                    .optString("seed")
                    .takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun buildModernCandidates(
        originalUri: Uri,
        endpoint: String,
        seed: String,
    ): List<String> {
        val title =
            originalUri
                .getQueryParameter("title")
                .orEmpty()

        /*
         * Current Videasy expects the title double encoded.
         *
         * Example:
         * Spider-Man: Brand New Day
         * ->
         * Spider-Man%253A%2520Brand%2520New%2520Day
         */
        val encodedTitle =
            Uri.encode(
                Uri.encode(title)
            )

        val mediaType =
            originalUri
                .getQueryParameter("mediaType")
                .orEmpty()

        val year =
            originalUri
                .getQueryParameter("year")
                .orEmpty()

        val tmdbId =
            originalUri
                .getQueryParameter("tmdbId")
                .orEmpty()

        val imdbId =
            originalUri
                .getQueryParameter("imdbId")
                .orEmpty()

        val episodeId =
            originalUri
                .getQueryParameter("episodeId")

        val seasonId =
            originalUri
                .getQueryParameter("seasonId")

        val language =
            originalUri
                .getQueryParameter("language")

        val bases = when (endpoint) {
            /*
             * Old Neon /vsrc currently returns 404.
             * Keep it first, then try the current Videasy backend.
             */
            "vsrc" -> listOf(
                "$mainUrl/vsrc",
                "https://api.videasy.net/myflixerzupcloud",
            )

            else -> listOf(
                "$mainUrl/$endpoint"
            )
        }

        return bases.map { base ->
            buildString {
                append(base)
                append("/sources-with-title")
                append("?title=")
                append(encodedTitle)

                append("&mediaType=")
                append(Uri.encode(mediaType))

                append("&year=")
                append(Uri.encode(year))

                append("&tmdbId=")
                append(Uri.encode(tmdbId))

                append("&imdbId=")
                append(Uri.encode(imdbId))

                episodeId
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append("&episodeId=")
                        append(Uri.encode(it))
                    }

                seasonId
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append("&seasonId=")
                        append(Uri.encode(it))
                    }

                language
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        append("&language=")
                        append(Uri.encode(it))
                    }

                append("&enc=2")
                append("&seed=")
                append(Uri.encode(seed))
            }
        }
    }

    private fun fetchEncrypted(
        client: OkHttpClient,
        url: String,
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", PLAYER_URL)
            .header("Referer", "$PLAYER_URL/")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception(
                    "Videasy source HTTP ${response.code}"
                )
            }

            response.body
                ?.string()
                ?.takeIf { it.isNotBlank() }
                ?: throw Exception(
                    "Videasy encrypted response empty"
                )
        }
    }

    private fun decryptModern(
        client: OkHttpClient,
        encrypted: String,
        tmdbId: String,
        seed: String,
    ): JSONObject {
        val payload = JSONObject().apply {
            put("text", encrypted)
            put("id", tmdbId)
            put("seed", seed)
        }

        val request = Request.Builder()
            .url("https://enc-dec.app/api/dec-videasy")
            .post(
                payload
                    .toString()
                    .toRequestBody(
                        "application/json".toMediaType()
                    )
            )
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception(
                    "Videasy decrypt HTTP ${response.code}"
                )
            }

            val raw =
                response.body
                    ?.string()
                    .orEmpty()

            val outer = JSONObject(raw)

            when (
                val result = outer.opt("result")
            ) {
                is JSONObject -> result

                is String -> {
                    JSONObject(result)
                }

                else -> {
                    throw Exception(
                        "Videasy decrypt result missing"
                    )
                }
            }
        }
    }

    private fun buildVideo(
        resultJson: JSONObject,
        endpoint: String,
    ): Video {
        val sources =
            resultJson.optJSONArray("sources")
                ?: throw Exception(
                    "No Videasy sources"
                )

        if (sources.length() == 0) {
            throw Exception(
                "Empty Videasy sources"
            )
        }

        /*
         * Vyse is multilingual.
         * TMDb EN must not accidentally select Hindi merely because it
         * appears first in the response.
         */
        var selected: JSONObject? = null

        if (endpoint == "hdmovie") {
            for (i in 0 until sources.length()) {
                val item =
                    sources.optJSONObject(i)
                        ?: continue

                val quality =
                    item
                        .optString("quality")
                        .lowercase()

                if (
                    quality.contains("english") ||
                    quality == "en"
                ) {
                    selected = item
                    break
                }
            }
        }

        if (selected == null) {
            /*
             * Prefer 1080p where the provider exposes multiple qualities.
             */
            for (i in 0 until sources.length()) {
                val item =
                    sources.optJSONObject(i)
                        ?: continue

                if (
                    item
                        .optString("quality")
                        .contains(
                            "1080",
                            ignoreCase = true,
                        )
                ) {
                    selected = item
                    break
                }
            }
        }

        if (selected == null) {
            selected = sources.optJSONObject(0)
        }

        val sourceUrl =
            selected
                ?.optString("url")
                ?.takeIf { it.isNotBlank() }
                ?: throw Exception(
                    "Videasy source URL missing"
                )

        val subtitles =
            mutableListOf<Video.Subtitle>()

        resultJson
            .optJSONArray("subtitles")
            ?.let { tracks ->
                for (i in 0 until tracks.length()) {
                    val track =
                        tracks.optJSONObject(i)
                            ?: continue

                    val url =
                        track
                            .optString("url")
                            .takeIf { it.isNotBlank() }
                            ?: continue

                    val label =
                        track
                            .optString("language")
                            .ifBlank {
                                track.optString(
                                    "lang",
                                    "Unknown",
                                )
                            }

                    subtitles += Video.Subtitle(
                        label = label,
                        file = url,
                    )
                }
            }

        Log.d(
            TAG,
            "Selected endpoint=$endpoint source=${
                sourceUrl.take(180)
            }",
        )

        return Video(
            source = sourceUrl,
            type = if (
                sourceUrl
                    .substringBefore("?")
                    .endsWith(
                        ".m3u8",
                        ignoreCase = true,
                    )
            ) {
                MimeTypes.APPLICATION_M3U8
            } else {
                MimeTypes.VIDEO_MP4
            },
            subtitles = subtitles,
            headers = mapOf(
                "Referer" to "$PLAYER_URL/",
                "Origin" to PLAYER_URL,
                "User-Agent" to USER_AGENT,
            ),
        )
    }

    private fun extractLegacy(
        link: String,
        client: OkHttpClient,
        endpoint: String,
        tmdbId: String,
    ): Video {
        val request = Request.Builder()
            .url(link)
            .header(
                "User-Agent",
                USER_AGENT,
            )
            .build()

        val response =
            client
                .newCall(request)
                .execute()

        val encData =
            response
                .body
                ?.string()
                ?: throw Exception(
                    "Failed to get encrypted data"
                )

        response.close()

        val json = JSONObject().apply {
            put("text", encData)
            put("id", tmdbId)
        }

        val body =
            json
                .toString()
                .toRequestBody(
                    "application/json".toMediaType()
                )

        val decRequest =
            Request.Builder()
                .url(
                    "https://enc-dec.app/api/dec-videasy"
                )
                .post(body)
                .build()

        val decResponse =
            client
                .newCall(decRequest)
                .execute()

        val decBody =
            decResponse
                .body
                ?.string()
                ?: "{}"

        decResponse.close()

        val outer =
            JSONObject(decBody)

        val resultJson =
            when (
                val result =
                    outer.opt("result")
            ) {
                is JSONObject -> result

                is String ->
                    JSONObject(result)

                else ->
                    throw Exception(
                        "No legacy Videasy result"
                    )
            }

        return buildVideo(
            resultJson = resultJson,
            endpoint = endpoint,
        )
    }

    private companion object {
        const val TAG = "VideasyExtractor"

        const val PLAYER_URL =
            "https://player.videasy.to"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Safari/537.36"
    }

}