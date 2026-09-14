package com.streamflixreborn.streamflix.extractors
import java.net.URI
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import okhttp3.Request
import okhttp3.OkHttpClient
import androidx.media3.common.MimeTypes
import android.util.Log

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

class VidnestExtractor : Extractor() {

    override val name = "Vidnest"
    override val mainUrl = "https://vidnest.io"
    override val aliasUrls = listOf(
        "https://vidnest.fun",
    )

    fun extractSubtitles(text: String): List<Video.Subtitle> {
        val tracksBlock = Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: return emptyList()

        val objectRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)

        return objectRegex.findAll(tracksBlock).mapNotNull { match ->
            val obj = match.groupValues[1]

            val kind = Regex("""kind\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            if (kind != "captions") return@mapNotNull null

            val rawFile = Regex("""file\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            val label = Regex("""label\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            val default = Regex(""""default"\s*:\s*(true|false)""")
                .find(obj)?.groupValues?.get(1)?.toBoolean() ?: false

            if (rawFile == null || label == null) return@mapNotNull null

            val file = Regex("""https://[^\s"']+""")
                .find(rawFile)?.value ?: return@mapNotNull null

            Video.Subtitle(
                file = file,
                label = label,
                initialDefault = default,
                default = if (UserPreferences.serverAutoSubtitlesDisabled) false else default
            )
        }.toList()
    }

    override suspend fun extract(link: String): Video {
        // VIDNEST_MODERN_API_V1
        // Use current new.vidnest.fun backends first.
        // Existing HTML/JWPlayer resolver below remains the legacy fallback.
        tryModernVidnest(link)?.let {
            return it
        }

        val service = Service.build(mainUrl)
        val doc = service.get(link)

        val scriptTags = doc.select("script[type=text/javascript]")

        var m3u8: String? = null

        var subtitles : List<Video.Subtitle> = emptyList();

        for (script in scriptTags) {
            val scriptData = script.data()
            if ("jwplayer" in scriptData && "sources" in scriptData && "file" in scriptData) {
                val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                if (match != null) {
                    m3u8 = match.groupValues[1]
                    subtitles = extractSubtitles(scriptData)
                    break
                }
            }
        }

        if (m3u8 == null) {
            throw Exception("Stream URL not found in script tags")
        }

        return Video(
            source = m3u8,
            subtitles = subtitles,
            useServerSubtitleSetting = true
        )
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                return retrofit.create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String): Document
    }

    private val modernVidnestClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun tryModernVidnest(
        link: String,
    ): Video? {
        val uri = runCatching {
            URI(link)
        }.getOrNull() ?: return null

        val parts = uri.path
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }

        /*
         * Only the movie API has been verified against the live service.
         * TV therefore deliberately falls through to the existing legacy
         * implementation instead of guessing endpoint formats.
         */
        if (
            parts.size < 2 ||
            parts[0] != "movie"
        ) {
            return null
        }

        val tmdbId = parts[1]

        if (tmdbId.isBlank()) {
            return null
        }

        val base =
            "https://new.vidnest.fun"

        val candidates = listOf(
            "Catflix" to "$base/yflix/movie/$tmdbId",
            "Filxer" to "$base/rogflix/movie/$tmdbId",

            // Currently sometimes upstream 502, but keep it.
            "Prime" to "$base/vidrock/movie/$tmdbId",

            "Gama" to "$base/vidzee/movie/$tmdbId",
            "Zeta" to "$base/nextgencloudfabric/movie/$tmdbId",
            "Alfa" to "$base/videasy/movie/$tmdbId",
            "Ophim" to "$base/klikxxi/movie/$tmdbId",
            "Beta" to "$base/vidxyz/movie/$tmdbId",

            // Currently sometimes upstream 502, but keep them.
            "Lamda" to "$base/allmovies/movie/$tmdbId",
            "Hexa" to "$base/vidlink/movie/$tmdbId",
        )

        for ((serverName, url) in candidates) {
            val video = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        VIDNEST_USER_AGENT,
                    )
                    .header(
                        "Accept",
                        "application/json,text/plain,*/*",
                    )
                    .header(
                        "Origin",
                        "https://vidnest.fun",
                    )
                    .header(
                        "Referer",
                        "https://vidnest.fun/",
                    )
                    .build()

                modernVidnestClient
                    .newCall(request)
                    .execute()
                    .use { response ->

                        Log.d(
                            VIDNEST_TAG,
                            "$serverName HTTP ${response.code}",
                        )

                        if (!response.isSuccessful) {
                            return@use null
                        }

                        val raw =
                            response.body
                                ?.string()
                                .orEmpty()

                        if (raw.isBlank()) {
                            return@use null
                        }

                        val responseJson =
                            JSONObject(raw)

                        val decoded =
                            decodeVidnestResponse(
                                responseJson
                            )
                                ?: return@use null

                        parseVidnestVideo(
                            serverName = serverName,
                            json = decoded,
                        )
                    }
            }.onFailure { error ->
                Log.w(
                    VIDNEST_TAG,
                    "$serverName failed: ${error.message}",
                )
            }.getOrNull()

            if (video != null) {
                Log.d(
                    VIDNEST_TAG,
                    "$serverName selected",
                )

                return video
            }
        }

        Log.d(
            VIDNEST_TAG,
            "Modern API exhausted; using legacy extractor",
        )

        return null
    }

    private fun decodeVidnestResponse(
        json: JSONObject,
    ): JSONObject? {
        if (!json.optBoolean("encrypted")) {
            return json
        }

        val encrypted =
            json.optString("data")
                .takeIf { it.isNotBlank() }
                ?: return null

        val decoded =
            decodeVidnestBase64(
                encrypted
            )
                ?: return null

        return runCatching {
            JSONObject(decoded)
        }.getOrNull()
    }

    /*
     * Current Vidnest API does not use normal Base64 ordering.
     * This alphabet was taken from the current Vidnest player bundle.
     */
    private fun decodeVidnestBase64(
        input: String,
    ): String? {
        val alphabet =
            "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="

        val lookup =
            IntArray(256) { -1 }

        alphabet.forEachIndexed { index, char ->
            if (char.code < lookup.size) {
                lookup[char.code] = index
            }
        }

        return runCatching {
            val output =
                ByteArrayOutputStream()

            var position = 0

            while (position < input.length) {
                val block =
                    input.substring(
                        position,
                        minOf(
                            position + 4,
                            input.length,
                        ),
                    )
                        .padEnd(
                            4,
                            '=',
                        )

                val values =
                    IntArray(4) { index ->
                        val char =
                            block[index]

                        if (char == '=') {
                            64
                        } else {
                            lookup
                                .getOrElse(
                                    char.code
                                ) { -1 }
                                .takeIf {
                                    it >= 0
                                }
                                ?: 64
                        }
                    }

                output.write(
                    (
                        (values[0] shl 2) or
                            (values[1] shr 4)
                        ) and 0xff
                )

                if (values[2] != 64) {
                    output.write(
                        (
                            ((values[1] and 15) shl 4) or
                                (values[2] shr 2)
                            ) and 0xff
                    )
                }

                if (values[3] != 64) {
                    output.write(
                        (
                            ((values[2] and 3) shl 6) or
                                values[3]
                            ) and 0xff
                    )
                }

                position += 4
            }

            output
                .toByteArray()
                .toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun parseVidnestVideo(
        serverName: String,
        json: JSONObject,
    ): Video? {

        /*
         * Schema 1:
         *
         * Catflix / Filxer / Alfa / Zeta
         *
         * {
         *   "url": "...",
         *   "headers": {...}
         * }
         */
        json.optString("url")
            .takeIf {
                it.isNotBlank()
            }
            ?.let { url ->

                val subtitles =
                    parseVidnestSubtitles(
                        json
                    )

                return buildVidnestVideo(
                    serverName = serverName,
                    url = url,
                    headers =
                        parseVidnestHeaders(
                            json.optJSONObject(
                                "headers"
                            )
                        ),
                    subtitles = subtitles,
                    hls =
                        json.optBoolean(
                            "hls"
                        ) ||
                            looksLikeHls(url),
                )
            }

        /*
         * Schema 2:
         *
         * Gama / Beta
         *
         * {
         *   "streams": [...]
         * }
         */
        json.optJSONArray("streams")
            ?.let { streams ->

                if (streams.length() > 0) {
                    var selected:
                        JSONObject? = null

                    /*
                     * Prefer explicit English.
                     */
                    for (
                        index in
                        0 until streams.length()
                    ) {
                        val item =
                            streams.optJSONObject(
                                index
                            )
                                ?: continue

                        val language =
                            item.optString(
                                "language"
                            )

                        if (
                            language.equals(
                                "English",
                                ignoreCase = true,
                            ) ||
                            language.equals(
                                "EN",
                                ignoreCase = true,
                            )
                        ) {
                            selected = item
                            break
                        }
                    }

                    /*
                     * Original is next best choice.
                     */
                    if (selected == null) {
                        for (
                            index in
                            0 until streams.length()
                        ) {
                            val item =
                                streams.optJSONObject(
                                    index
                                )
                                    ?: continue

                            if (
                                item
                                    .optString(
                                        "language"
                                    )
                                    .equals(
                                        "Original",
                                        ignoreCase = true,
                                    )
                            ) {
                                selected = item
                                break
                            }
                        }
                    }

                    if (selected == null) {
                        selected =
                            streams.optJSONObject(0)
                    }

                    val url =
                        selected
                            ?.optString("url")
                            ?.takeIf {
                                it.isNotBlank()
                            }

                    if (url != null) {
                        val type =
                            selected
                                ?.optString(
                                    "type"
                                )
                                .orEmpty()

                        return buildVidnestVideo(
                            serverName = serverName,
                            url = url,
                            headers =
                                parseVidnestHeaders(
                                    selected
                                        ?.optJSONObject(
                                            "headers"
                                        )
                                ),
                            subtitles =
                                parseVidnestSubtitles(
                                    json
                                ),
                            hls =
                                type.equals(
                                    "hls",
                                    ignoreCase = true,
                                ) ||
                                    looksLikeHls(
                                        url
                                    ),
                        )
                    }
                }
            }

        /*
         * Schema 3:
         *
         * Ophim
         *
         * {
         *   "sources": [...]
         * }
         */
        json.optJSONArray("sources")
            ?.let { sources ->

                if (sources.length() > 0) {
                    val source =
                        sources
                            .optJSONObject(0)

                    val url =
                        source
                            ?.optString("url")
                            ?.takeIf {
                                it.isNotBlank()
                            }

                    if (url != null) {
                        val type =
                            source.optString(
                                "type"
                            )

                        return buildVidnestVideo(
                            serverName = serverName,
                            url = url,
                            headers =
                                emptyMap(),
                            subtitles =
                                parseVidnestSubtitles(
                                    json
                                ),
                            hls =
                                type.equals(
                                    "hls",
                                    ignoreCase = true,
                                ) ||
                                    looksLikeHls(
                                        url
                                    ),
                        )
                    }
                }
            }

        /*
         * Schema 4:
         *
         * Zeta may expose several URLs.
         */
        json.optJSONArray("all_urls")
            ?.let { urls ->
                for (
                    index in
                    0 until urls.length()
                ) {
                    val url =
                        urls
                            .optString(index)
                            .takeIf {
                                it.isNotBlank()
                            }
                            ?: continue

                    return buildVidnestVideo(
                        serverName = serverName,
                        url = url,
                        headers =
                            parseVidnestHeaders(
                                json.optJSONObject(
                                    "headers"
                                )
                            ),
                        subtitles =
                            parseVidnestSubtitles(
                                json
                            ),
                        hls = true,
                    )
                }
            }

        return null
    }

    private fun buildVidnestVideo(
        serverName: String,
        url: String,
        headers: Map<String, String>,
        subtitles: List<Video.Subtitle>,
        hls: Boolean,
    ): Video {
        Log.d(
            VIDNEST_TAG,
            "$serverName stream=${url.take(180)}",
        )

        val mimeType =
            when {
                hls ->
                    MimeTypes.APPLICATION_M3U8

                url
                    .substringBefore("?")
                    .endsWith(
                        ".mkv",
                        ignoreCase = true,
                    ) ->
                    MimeTypes.VIDEO_MATROSKA

                else ->
                    MimeTypes.VIDEO_MP4
            }

        return Video(
            source = url,
            headers = headers,
            subtitles = subtitles,
            type = mimeType,
            useServerSubtitleSetting = true,
        )
    }

    private fun parseVidnestHeaders(
        json: JSONObject?,
    ): Map<String, String> {
        if (json == null) {
            return emptyMap()
        }

        val result =
            linkedMapOf<String, String>()

        val keys =
            json.keys()

        while (keys.hasNext()) {
            val key =
                keys.next()

            val value =
                json
                    .optString(key)
                    .trim()

            if (value.isNotBlank()) {
                result[key] =
                    value
            }
        }

        return result
    }

    private fun parseVidnestSubtitles(
        json: JSONObject,
    ): List<Video.Subtitle> {
        val result =
            mutableListOf<Video.Subtitle>()

        val subtitles =
            json.optJSONArray(
                "subtitles"
            )
                ?: return result

        for (
            index in
            0 until subtitles.length()
        ) {
            val item =
                subtitles
                    .optJSONObject(index)
                    ?: continue

            val url =
                item
                    .optString("url")
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?: continue

            val label =
                item
                    .optString(
                        "lang",
                        "Subtitle",
                    )

            result +=
                Video.Subtitle(
                    label = label,
                    file = url,
                )
        }

        return result
    }

    private fun looksLikeHls(
        url: String,
    ): Boolean {
        val clean =
            url.substringBefore("?")
                .lowercase()

        return clean.endsWith(
            ".m3u8"
        ) ||
            clean.endsWith(
                ".txt"
            ) ||
            clean.contains(
                "/hls/"
            ) ||
            clean.contains(
                "/hls2/"
            ) ||
            clean.contains(
                "/hls3/"
            )
    }

    private companion object {
        const val VIDNEST_TAG =
            "VidnestModern"

        const val VIDNEST_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0.0.0 Safari/537.36"
    }

}
