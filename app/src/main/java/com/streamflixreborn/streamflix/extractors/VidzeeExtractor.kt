package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class VidzeeExtractor : Extractor() {

    private companion object {
        const val TAG = "VidzeeExtractor"
    }
    override val name = "Vidzee"
    override val mainUrl = "https://player.vidzee.wtf"
    private val coreApi = "https://core.vidzee.wtf"
    private val staticPass = "4f2a9c7d1e8b3a6f0d5c2e9a7b1f4d8c"

    private val client = OkHttpClient.Builder().build()

    data class ServerConfig(
        val name: String,
        val index: Int
    )

    private val servers = listOf(
        ServerConfig("Nflix", 0),
        ServerConfig("Duke", 1),
        ServerConfig("Glory", 2),
        ServerConfig("Nazy", 3),
        ServerConfig("Atlas", 4),
        ServerConfig("Drag", 5),
        ServerConfig("Achilles", 6),
        ServerConfig("Viet", 7),
        ServerConfig("Velocità", 8),
        ServerConfig("Hindi", 9),
        ServerConfig("Bengali", 10),
        ServerConfig("Tamil", 11),
        ServerConfig("Telugu", 12),
        ServerConfig("Malayalam", 13)
    )

    fun servers(videoType: Video.Type): List<Video.Server> {
        val baseUrl = when (videoType) {
            is Video.Type.Movie -> "$mainUrl/api/server?id=${videoType.id}"
            is Video.Type.Episode -> "$mainUrl/api/server?id=${videoType.tvShow.id}&ss=${videoType.season.number}&ep=${videoType.number}"
        }

        return servers.map { config ->
            Video.Server(
                id = "${config.name} (Vidzee)",
                name = "${config.name} (Vidzee)",
                src = "$baseUrl&sr=${config.index}"
            )
        }
    }

    fun server(videoType: Video.Type): Video.Server {
        return servers(videoType).first()
    }

    override suspend fun extract(link: String): Video = coroutineScope {
        // VIDZEE_DIRECT_API_V1
        // Current Vidzee exposes direct, unencrypted playback URLs through
        // core.vidzee.wtf. Keep the previous encrypted API below as fallback.
        tryDirectStream(link)?.let {
            return@coroutineScope it
        }

        val masterKey = getMasterKey()
            ?: throw Exception("Failed to get Vidzee master key")
        
        try {
            val request = Request.Builder()
                .url(link)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36")
                .header("Origin", mainUrl)
                .header("Referer", "$mainUrl/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("Network error")

            val body = response.body?.string() ?: throw Exception("Empty body")
            val json = JSONObject(body)
            val urlArray = json.optJSONArray("url") ?: throw Exception("No URLs found")
            if (urlArray.length() == 0) throw Exception("Empty URL array")

            val content = urlArray.getJSONObject(0)
            val encryptedLink = content.optString("link")
            if (encryptedLink.isEmpty()) throw Exception("Empty encrypted link")

            val decryptedUrl = decryptLink(encryptedLink, masterKey)
                ?: throw Exception("Failed to decrypt link")

            val tracks = json.optJSONArray("tracks")
            val subtitles = mutableListOf<Video.Subtitle>()
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(i)
                    val subUrl = track.optString("url")
                    if (subUrl.isNotEmpty()) {
                        subtitles.add(Video.Subtitle(
                            label = track.optString("lang", "Unknown"),
                            file = subUrl
                        ))
                    }
                }
            }

            val isDukeServer = link.contains("sr=1")
            val mimeType = if (isDukeServer) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8

            return@coroutineScope Video(
                source = decryptedUrl,
                subtitles = subtitles,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
                ),
                type = mimeType
            )
        } catch (e: Exception) {
            throw Exception("Failed to extract video: ${e.message}")
        }
    }

    private fun tryDirectStream(
        link: String,
    ): Video? {
        val uri = runCatching {
            Uri.parse(link)
        }.getOrNull() ?: return null

        val tmdbId = uri
            .getQueryParameter("id")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val season = uri
            .getQueryParameter("ss")
            ?.takeIf { it.isNotBlank() }

        val episode = uri
            .getQueryParameter("ep")
            ?.takeIf { it.isNotBlank() }

        val isEpisode =
            season != null &&
                episode != null

        /*
         * Live checks 2026-09-14:
         *
         * Movies:
         *   dcloud  -> 200
         *   tik     -> 200
         *   ipcloud -> may return 502
         *
         * TV:
         *   ipcloud -> 200
         *   dcloud  -> may return 502
         *   tik     -> may return 502
         *
         * Do not hard-fail on one backend. Try the currently preferred
         * backend first and automatically continue on server-side errors.
         */
        val directServers = if (isEpisode) {
            listOf(
                "ipcloud",
                "dcloud",
                "tik",
            )
        } else {
            listOf(
                "dcloud",
                "tik",
                "ipcloud",
            )
        }

        for (directServer in directServers) {
            val endpoint = if (isEpisode) {
                "$coreApi/streams/tv/" +
                    "$tmdbId/$season/$episode" +
                    "?s=$directServer&e=0"
            } else {
                "$coreApi/streams/movie/" +
                    "$tmdbId" +
                    "?s=$directServer&e=0"
            }

            val request = Request.Builder()
                .url(endpoint)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/140.0.0.0 Safari/537.36"
                )
                .header(
                    "Accept",
                    "application/json,text/plain,*/*",
                )
                .header(
                    "Origin",
                    mainUrl,
                )
                .header(
                    "Referer",
                    "$mainUrl/",
                )
                .build()

            val video = runCatching {
                client.newCall(request).execute().use { response ->
                    Log.d(
                        TAG,
                        "Direct $directServer HTTP ${response.code} " +
                            "type=${if (isEpisode) "tv" else "movie"} " +
                            "id=$tmdbId",
                    )

                    if (!response.isSuccessful) {
                        return@use null
                    }

                    val raw = response.body
                        ?.string()
                        .orEmpty()

                    val json = JSONObject(raw)

                    val source = json
                        .optString("url")
                        .trim()

                    if (source.isBlank()) {
                        return@use null
                    }

                    val headers =
                        linkedMapOf<String, String>(
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl,
                            "User-Agent" to
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/140.0.0.0 Safari/537.36",
                        )

                    json.optJSONObject("headers")
                        ?.let { sourceHeaders ->
                            val keys =
                                sourceHeaders.keys()

                            while (keys.hasNext()) {
                                val key = keys.next()

                                val value =
                                    sourceHeaders
                                        .optString(key)
                                        .trim()

                                if (value.isNotBlank()) {
                                    headers[key] = value
                                }
                            }
                        }

                    Log.d(
                        TAG,
                        "Direct $directServer selected " +
                            "source=${source.take(160)}",
                    )

                    Video(
                        source = source,
                        headers = headers,
                        type = if (
                            source
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
                    )
                }
            }.getOrNull()

            if (video != null) {
                return video
            }
        }

        Log.d(
            TAG,
            "Direct API exhausted; using legacy Vidzee extractor",
        )

        return null
    }

    private fun getMasterKey(): String? {
        return try {
            val request = Request.Builder()
                .url("$coreApi/api-key")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Origin", mainUrl)
                .header("Referer", "$mainUrl/")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val b64Data = response.body?.string() ?: return null
            val data = Base64.decode(b64Data, Base64.DEFAULT)

            val iv = data.sliceArray(0 until 12)
            val tag = data.sliceArray(12 until 28)
            val ciphertext = data.sliceArray(28 until data.size)

            val key = MessageDigest.getInstance("SHA-256")
                .digest(staticPass.toByteArray(Charsets.UTF_8))

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            val secretKey = SecretKeySpec(key, "AES")
            
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            // In Java/Android, the tag is expected to be at the end of the ciphertext
            val combined = ciphertext + tag
            val decrypted = cipher.doFinal(combined)
            
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptLink(encLink: String, masterKey: String): String? {
        return try {
            val decodedRaw = String(Base64.decode(encLink, Base64.DEFAULT), Charsets.UTF_8)
            val parts = decodedRaw.split(":")
            val iv = Base64.decode(parts[0], Base64.DEFAULT)
            val ciphertext = Base64.decode(parts[1], Base64.DEFAULT)

            val keyBytes = masterKey.toByteArray(Charsets.UTF_8)
            val paddedKey = ByteArray(32) { i -> if (i < keyBytes.size) keyBytes[i] else 0 }

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(paddedKey, "AES")
            val ivSpec = IvParameterSpec(iv)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(ciphertext)

            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
