package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.media3.common.MimeTypes
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
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


    private var relayServerSocket: ServerSocket? = null
    private var relayServerThread: Thread? = null
    private var relayPort: Int = 0

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

        val crypto = resolveMegaPlayCrypto(
            pageBody = pageBody,
            pageUrl = streamPageUrl,
            origin = origin,
        )

        val source = responses.asSequence()
            .mapNotNull { it.sources?.file?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: responses.asSequence()
                .mapNotNull { it.enc?.takeIf(String::isNotBlank) }
                .mapNotNull { encrypted ->
                    crypto?.let {
                        decryptMegaPlaySource(
                            encrypted = encrypted,
                            crypto = it,
                        )
                    }
                }
                .firstOrNull()
            ?: throw Exception("Nekostream source not found")

        val relayBase = if (
            crypto != null &&
            isPlaylistUrl(source)
        ) {
            startMegaPlayRelay(
                sourceUrl = source,
                crypto = crypto,
                origin = origin,
            )
        } else {
            ""
        }

        val playbackSource = if (relayBase.isNotBlank()) {
            "$relayBase/manifest.m3u8"
        } else {
            source
        }

        val metadata = responses.firstOrNull { !it.tracks.isNullOrEmpty() }
            ?: responses.firstOrNull()

        return Video(
            source = playbackSource,
            subtitles = metadata?.tracks.orEmpty()
                .filter { it.kind == null || it.kind == "captions" }
                .mapNotNull {
                    Video.Subtitle(
                        label = it.label?.ifBlank { null } ?: "Subtitle",
                        file = it.file ?: return@mapNotNull null,
                        default = it.default == true,
                    )
                },
            headers = if (relayBase.isNotBlank()) {
                emptyMap()
            } else {
                mapOf(
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
                )
            },
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    private fun resolveMegaPlayCrypto(
        pageBody: String,
        pageUrl: String,
        origin: String,
    ): MegaPlayCrypto? {
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

            val keyBytes = keyIv.groupValues[1]
                .toByteArray(Charsets.UTF_8)

            val ivBytes = keyIv.groupValues[2]
                .toByteArray(Charsets.UTF_8)

            val key = ByteArray(32)
            keyBytes.copyInto(
                destination = key,
                endIndex = minOf(keyBytes.size, key.size),
            )

            val iv = ByteArray(16)
            ivBytes.copyInto(
                destination = iv,
                endIndex = minOf(ivBytes.size, iv.size),
            )

            return MegaPlayCrypto(
                key = key,
                iv = iv,
            )
        }

        return null
    }

    private fun decryptMegaPlaySource(
        encrypted: String,
        crypto: MegaPlayCrypto,
    ): String? {
        val plainText = decryptMegaPlayValue(
            encrypted = encrypted,
            crypto = crypto,
        ) ?: return null

        val decoded = runCatching {
            JsonParser.parseString(plainText).asJsonObject
        }.getOrNull() ?: return null

        return sequenceOf("file", "url")
            .mapNotNull { keyName ->
                decoded.get(keyName)
                    ?.takeUnless { it.isJsonNull }
                    ?.runCatching { asString }
                    ?.getOrNull()
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
            .firstOrNull()
    }

    private fun decryptMegaPlayValue(
        encrypted: String,
        crypto: MegaPlayCrypto,
    ): String? {
        val padded = encrypted + "=".repeat(
            (4 - encrypted.length % 4) % 4
        )

        val cipherText = runCatching {
            Base64.decode(
                padded,
                Base64.URL_SAFE or Base64.NO_WRAP,
            )
        }.getOrNull() ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(
                "AES/CBC/PKCS5Padding"
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(crypto.key, "AES"),
                IvParameterSpec(crypto.iv),
            )

            cipher.doFinal(cipherText)
                .toString(Charsets.UTF_8)
                .trim()
        }.getOrNull()
    }

    private fun decryptSegmentUrl(
        url: String,
        crypto: MegaPlayCrypto,
    ): String {
        val token = Regex(
            """/segment/([A-Za-z0-9_-]+)"""
        )
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return url

        val decrypted = decryptMegaPlayValue(
            encrypted = token,
            crypto = crypto,
        )
            ?.trim()
            ?.takeIf {
                it.startsWith("http://") ||
                    it.startsWith("https://")
            }

        return decrypted ?: url
    }

    private fun isPlaylistUrl(url: String): Boolean {
        val clean = url.substringBefore("?")

        return clean.endsWith(
            ".m3u8",
            ignoreCase = true,
        ) || Regex(
            """index-[^/]+\.m3u8$""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(clean)
    }

    private fun startMegaPlayRelay(
        sourceUrl: String,
        crypto: MegaPlayCrypto,
        origin: String,
    ): String {
        stopMegaPlayRelay()

        return try {
            val socket = ServerSocket(0)

            relayServerSocket = socket
            relayPort = socket.localPort

            relayServerThread = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val clientSocket =
                            relayServerSocket?.accept()
                                ?: break

                        Thread {
                            try {
                                handleMegaPlayRelayRequest(
                                    clientSocket = clientSocket,
                                    sourceUrl = sourceUrl,
                                    crypto = crypto,
                                    origin = origin,
                                )
                            } catch (error: Exception) {
                                Log.e(
                                    TAG,
                                    "MegaPlay relay request crashed",
                                    error,
                                )

                                runCatching {
                                    writeRelayError(
                                        output = clientSocket.getOutputStream(),
                                        code = 500,
                                        message = "Relay request failed",
                                    )
                                }
                            } finally {
                                runCatching {
                                    clientSocket.close()
                                }
                            }
                        }.apply {
                            isDaemon = true
                            start()
                        }
                    }
                } catch (_: Exception) {
                    // Socket is intentionally closed when a new
                    // MegaPlay stream replaces this relay.
                }
            }.apply {
                isDaemon = true
                start()
            }

            "http://127.0.0.1:$relayPort"
        } catch (_: Exception) {
            ""
        }
    }

    private fun stopMegaPlayRelay() {
        runCatching {
            relayServerSocket?.close()
        }

        relayServerThread?.interrupt()

        relayServerSocket = null
        relayServerThread = null
        relayPort = 0
    }

    private fun handleMegaPlayRelayRequest(
        clientSocket: Socket,
        sourceUrl: String,
        crypto: MegaPlayCrypto,
        origin: String,
    ) {
        val input = clientSocket.getInputStream()
        val output = clientSocket.getOutputStream()

        val buffer = ByteArray(16384)
        val bytesRead = input.read(buffer)

        if (bytesRead <= 0) {
            return
        }

        val request = String(
            buffer,
            0,
            bytesRead,
            Charsets.UTF_8,
        )

        val lines = request
            .split("\r\n", "\n")

        val requestLine = lines
            .firstOrNull()
            .orEmpty()

        val parts = requestLine.split(" ")

        if (parts.size < 2 || parts[0] != "GET") {
            writeRelayError(
                output = output,
                code = 405,
                message = "Method Not Allowed",
            )
            return
        }

        val path = parts[1]

        val rangeHeader = lines
            .firstOrNull {
                it.startsWith(
                    "Range:",
                    ignoreCase = true,
                )
            }
            ?.substringAfter(":")
            ?.trim()

        when {
            path.startsWith("/manifest.m3u8") -> {
                serveMegaPlayPlaylist(
                    output = output,
                    playlistUrl = sourceUrl,
                    crypto = crypto,
                    origin = origin,
                )
            }

            path.startsWith("/playlist?url=") -> {
                val encoded = path.substringAfter(
                    "/playlist?url="
                )

                val playlistUrl = URLDecoder.decode(
                    encoded,
                    "UTF-8",
                )

                serveMegaPlayPlaylist(
                    output = output,
                    playlistUrl = playlistUrl,
                    crypto = crypto,
                    origin = origin,
                )
            }

            path.startsWith("/media?url=") -> {
                val encoded = path.substringAfter(
                    "/media?url="
                )

                val mediaUrl = URLDecoder.decode(
                    encoded,
                    "UTF-8",
                )

                proxyMegaPlayMedia(
                    output = output,
                    mediaUrl = mediaUrl,
                    origin = origin,
                    rangeHeader = rangeHeader,
                )
            }

            else -> {
                writeRelayError(
                    output = output,
                    code = 404,
                    message = "Not Found",
                )
            }
        }
    }

    private fun serveMegaPlayPlaylist(
        output: java.io.OutputStream,
        playlistUrl: String,
        crypto: MegaPlayCrypto,
        origin: String,
    ) {
        var lastCode = 502

        for (candidate in megaPlayRequestCandidates(playlistUrl)) {
            try {
                Log.e(
                    TAG,
                    "MegaPlay candidate RAW=[$candidate] playlist=[$playlistUrl]",
                )

                val request = buildMegaPlayMediaRequest(
                    url = candidate,
                    origin = origin,
                ).build()

                client.newCall(request).execute().use { response ->
                lastCode = response.code

                Log.d(
                    TAG,
                    "MegaPlay playlist host=${safeHost(candidate)} HTTP=${response.code}",
                )

                if (!response.isSuccessful) {
                    return@use
                }

                val body = response.body
                    ?.string()
                    .orEmpty()

                if (!body.contains("#EXTM3U")) {
                    Log.d(
                        TAG,
                        "MegaPlay playlist invalid host=${safeHost(candidate)}",
                    )
                    return@use
                }

                /*
                 * Keep playlistUrl as the base instead of candidate.
                 *
                 * When candidate is the yoot proxy we still want relative
                 * links to resolve against the original CDN URL. Individual
                 * child requests can then use their own fallback.
                 */
                val rewritten = rewriteMegaPlayPlaylist(
                    playlist = body,
                    playlistUrl = playlistUrl,
                    crypto = crypto,
                )

                val bytes = rewritten
                    .toByteArray(Charsets.UTF_8)

                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append(
                        "Content-Type: " +
                            "application/vnd.apple.mpegurl\r\n"
                    )
                    append(
                        "Content-Length: ${bytes.size}\r\n"
                    )
                    append(
                        "Access-Control-Allow-Origin: *\r\n"
                    )
                    append("Cache-Control: no-cache\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }

                output.write(
                    headers.toByteArray(Charsets.UTF_8)
                )
                output.write(bytes)
                    output.flush()

                    return
                }
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "MegaPlay playlist candidate crashed " +
                        "host=${safeHost(candidate)}",
                    error,
                )
            }
        }

        writeRelayError(
            output = output,
            code = lastCode,
            message = "Upstream playlist failed",
        )
    }

    private fun rewriteMegaPlayPlaylist(
        playlist: String,
        playlistUrl: String,
        crypto: MegaPlayCrypto,
    ): String {
        return playlist
            .lines()
            .joinToString("\n") { originalLine ->
                val trimmed = originalLine.trim()

                when {
                    trimmed.isBlank() -> {
                        originalLine
                    }

                    trimmed.startsWith("#") -> {
                        rewriteMegaPlayTagUris(
                            line = originalLine,
                            playlistUrl = playlistUrl,
                            crypto = crypto,
                        )
                    }

                    else -> {
                        val resolved =
                            resolvePlaylistUrl(
                                baseUrl = playlistUrl,
                                value = trimmed,
                            )

                        localRelayUrl(
                            decryptSegmentUrl(
                                url = resolved,
                                crypto = crypto,
                            )
                        )
                    }
                }
            }
    }

    private fun rewriteMegaPlayTagUris(
        line: String,
        playlistUrl: String,
        crypto: MegaPlayCrypto,
    ): String {
        return Regex(
            """URI="([^"]+)""""
        ).replace(line) { match ->
            val raw = match.groupValues[1]

            val resolved = resolvePlaylistUrl(
                baseUrl = playlistUrl,
                value = raw,
            )

            val decrypted = decryptSegmentUrl(
                url = resolved,
                crypto = crypto,
            )

            """URI="${localRelayUrl(decrypted)}""""
        }
    }

    private fun resolvePlaylistUrl(
        baseUrl: String,
        value: String,
    ): String {
        val normalizedValue =
            normalizeMegaPlayUrl(value)

        if (
            normalizedValue.startsWith("http://") ||
            normalizedValue.startsWith("https://")
        ) {
            return normalizedValue
        }

        val normalizedBase =
            normalizeMegaPlayUrl(baseUrl)

        return runCatching {
            URI(normalizedBase)
                .resolve(normalizedValue)
                .toString()
        }.getOrDefault(normalizedValue)
    }

    private fun localRelayUrl(
        url: String,
    ): String {
        val endpoint = if (isPlaylistUrl(url)) {
            "playlist"
        } else {
            "media"
        }

        val encoded = URLEncoder.encode(
            url,
            "UTF-8",
        )
            .replace("+", "%20")

        return "http://127.0.0.1:$relayPort/" +
            "$endpoint?url=$encoded"
    }

    private fun isDomainOrSubdomain(
        host: String,
        domain: String,
    ): Boolean {
        val normalizedHost = host
            .lowercase()
            .trimEnd('.')

        val normalizedDomain = domain
            .lowercase()
            .trimEnd('.')

        return normalizedHost == normalizedDomain ||
            normalizedHost.endsWith(".$normalizedDomain")
    }

    private fun normalizeMegaPlayUrl(
        url: String,
    ): String {
        val clean = url
            .trim()
            .trim('"', '\'')

        // Already valid.
        if (
            clean.startsWith("https://", ignoreCase = true) ||
            clean.startsWith("http://", ignoreCase = true)
        ) {
            return clean
        }

        /*
         * MegaPlay's decrypted payload currently arrives on Android with a
         * damaged scheme separator, visible in logcat as for example:
         *
         *     httpsL/fetch.nexabloom.top/...
         *
         * Do not depend on the exact damaged character. If the value starts
         * with http/https, reconstruct the scheme from the first slash after
         * the scheme name.
         */
        fun repairScheme(
            scheme: String,
        ): String? {
            if (!clean.startsWith(scheme, ignoreCase = true)) {
                return null
            }

            val slashIndex = clean.indexOf(
                '/',
                startIndex = scheme.length,
            )

            if (slashIndex < 0) {
                return null
            }

            val remainder = clean
                .substring(slashIndex + 1)
                .trimStart('/')

            if (remainder.isBlank()) {
                return null
            }

            return "$scheme://$remainder"
        }

        return repairScheme("https")
            ?: repairScheme("http")
            ?: clean
    }

    private fun safeHost(
        url: String,
    ): String {
        return runCatching {
            URI(
                normalizeMegaPlayUrl(url)
            ).host.orEmpty()
        }.getOrDefault("")
    }

    private fun shouldStripMegaPlayPayload(
        url: String,
    ): Boolean {
        val host = safeHost(url)

        return listOf(
            "ibyteimg.com",
            "tiktokcdn.com",
            "ipstatp.com",
            "yoot.akirax.buzz",
        ).any { domain ->
            isDomainOrSubdomain(
                host = host,
                domain = domain,
            )
        }
    }

    private fun megaPlayRequestCandidates(
        url: String,
    ): List<String> {
        val normalizedUrl =
            normalizeMegaPlayUrl(url)

        Log.e(
            TAG,
            "NORM_V3 input=[$url] output=[$normalizedUrl]",
        )

        val candidates =
            mutableListOf(normalizedUrl)

        val uri = runCatching {
            URI(normalizedUrl)
        }.getOrNull() ?: return candidates

        val host = uri.host
            ?.lowercase()
            .orEmpty()

        /*
         * MEGAPLAY_MIKORA_CDN_FALLBACK_V1
         *
         * Some MegaPlay sources currently return the same media through
         * fetch.nexabloom.top / ncdn.imgnex.top, but those hosts can answer
         * with HTTP 403.
         *
         * The same content IDs are also available through megap.mikora.top.
         * Its path is identical except that the /anime prefix is omitted.
         */
        if (
            (
                isDomainOrSubdomain(
                    host = host,
                    domain = "fetch.nexabloom.top",
                ) ||
                isDomainOrSubdomain(
                    host = host,
                    domain = "ncdn.imgnex.top",
                )
            ) &&
            uri.rawPath.orEmpty().startsWith("/anime/")
        ) {
            val mirrorPath =
                uri.rawPath
                    .orEmpty()
                    .removePrefix("/anime")

            val mirrorUrl = buildString {
                append("https://megap.mikora.top")
                append(mirrorPath)

                if (!uri.rawQuery.isNullOrBlank()) {
                    append("?")
                    append(uri.rawQuery)
                }
            }

            Log.d(
                TAG,
                "MegaPlay Mikora fallback $host -> $mirrorUrl",
            )

            candidates += mirrorUrl
        }

        /*
         * Current MegaPlay newclient.min.js uses yoot.akirax.buzz as
         * the TikTok CDN proxy. Do not proxy every MegaPlay URL:
         * the default CDN matcher only targets tiktokcdn.com.
         */
        if (
            isDomainOrSubdomain(
                host = host,
                domain = "tiktokcdn.com",
            ) &&
            !isDomainOrSubdomain(
                host = host,
                domain = "yoot.akirax.buzz",
            )
        ) {
            val rawPath = uri.rawPath
                ?.ifBlank { "/" }
                ?: "/"

            val domainParam =
                "domain=" +
                    URLEncoder.encode(
                        host,
                        "UTF-8",
                    )

            val query = if (
                uri.rawQuery.isNullOrBlank()
            ) {
                domainParam
            } else {
                "${uri.rawQuery}&$domainParam"
            }

            candidates +=
                "https://yoot.akirax.buzz" +
                    rawPath +
                    "?" +
                    query
        }

        return candidates.distinct()
    }

    private fun proxyMegaPlayMedia(
        output: java.io.OutputStream,
        mediaUrl: String,
        origin: String,
        rangeHeader: String?,
    ) {
        var lastCode = 502

        for (candidate in megaPlayRequestCandidates(mediaUrl)) {
            val stripPayload =
                shouldStripMegaPlayPayload(mediaUrl) ||
                    shouldStripMegaPlayPayload(candidate)

            val requestBuilder = buildMegaPlayMediaRequest(
                url = candidate,
                origin = origin,
            )

            /*
             * MegaPlay's SegmentStrip operates on the complete payload
             * and then removes its wrapper. For these hosts we therefore
             * intentionally avoid forwarding ExoPlayer's Range header.
             */
            if (
                !stripPayload &&
                !rangeHeader.isNullOrBlank()
            ) {
                requestBuilder.header(
                    "Range",
                    rangeHeader,
                )
            }

            client.newCall(
                requestBuilder.build()
            ).execute().use { response ->
                lastCode = response.code

                Log.d(
                    TAG,
                    "MegaPlay media host=${safeHost(candidate)} " +
                        "HTTP=${response.code} strip=$stripPayload",
                )

                if (!response.isSuccessful) {
                    return@use
                }

                val body = response.body
                    ?: return@use

                val upstreamLength =
                    body.contentLength()

                val outputLength = if (
                    stripPayload &&
                    upstreamLength >= SEGMENT_STRIP_BYTES
                ) {
                    upstreamLength -
                        SEGMENT_STRIP_BYTES
                } else {
                    upstreamLength
                }

                val responseCode = if (
                    stripPayload
                ) {
                    200
                } else {
                    response.code
                }

                val statusText = if (
                    responseCode == 206
                ) {
                    "Partial Content"
                } else {
                    "OK"
                }

                val contentType = if (
                    stripPayload
                ) {
                    "video/mp2t"
                } else {
                    response.header(
                        "Content-Type"
                    ) ?: "application/octet-stream"
                }

                val headers = buildString {
                    append(
                        "HTTP/1.1 $responseCode " +
                            "$statusText\r\n"
                    )

                    append(
                        "Content-Type: " +
                            "$contentType\r\n"
                    )

                    if (outputLength >= 0) {
                        append(
                            "Content-Length: " +
                                "$outputLength\r\n"
                        )
                    }

                    if (!stripPayload) {
                        response.header(
                            "Content-Range"
                        )?.let {
                            append(
                                "Content-Range: $it\r\n"
                            )
                        }

                        response.header(
                            "Accept-Ranges"
                        )?.let {
                            append(
                                "Accept-Ranges: $it\r\n"
                            )
                        }
                    }

                    append(
                        "Access-Control-Allow-Origin: *\r\n"
                    )
                    append(
                        "Cache-Control: no-cache\r\n"
                    )
                    append(
                        "Connection: close\r\n"
                    )
                    append("\r\n")
                }

                output.write(
                    headers.toByteArray(
                        Charsets.UTF_8
                    )
                )

                body.byteStream().use { input ->
                    if (stripPayload) {
                        var remaining =
                            SEGMENT_STRIP_BYTES.toLong()

                        while (remaining > 0) {
                            val skipped =
                                input.skip(remaining)

                            if (skipped > 0) {
                                remaining -= skipped
                            } else {
                                if (input.read() == -1) {
                                    break
                                }

                                remaining--
                            }
                        }
                    }

                    input.copyTo(output)
                }

                output.flush()

                return
            }
        }

        writeRelayError(
            output = output,
            code = lastCode,
            message = "Upstream media failed",
        )
    }

    private fun buildMegaPlayMediaRequest(
        url: String,
        origin: String,
    ): Request.Builder {
        return Request.Builder()
            .url(
                normalizeMegaPlayUrl(url)
            )
            .header(
                "User-Agent",
                USER_AGENT,
            )
            .header(
                "Accept",
                "*/*",
            )
            .header(
                "Referer",
                "$origin/",
            )
            .header(
                "Origin",
                origin,
            )
            .header(
                "Accept-Language",
                "en-US,en;q=0.9",
            )
    }

    private fun writeRelayError(
        output: java.io.OutputStream,
        code: Int,
        message: String,
    ) {
        val body = "$code $message"
            .toByteArray(Charsets.UTF_8)

        val response = buildString {
            append(
                "HTTP/1.1 $code $message\r\n"
            )
            append(
                "Content-Type: text/plain\r\n"
            )
            append(
                "Content-Length: ${body.size}\r\n"
            )
            append("Connection: close\r\n")
            append("\r\n")
        }

        output.write(
            response.toByteArray(Charsets.UTF_8)
        )
        output.write(body)
        output.flush()
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

    private data class MegaPlayCrypto(
        val key: ByteArray,
        val iv: ByteArray,
    )

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
        const val TAG = "NekostreamMegaPlay"
        const val SEGMENT_STRIP_BYTES = 252

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}