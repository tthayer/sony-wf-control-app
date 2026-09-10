package com.thelightphone.sonywf.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Plain HTTPS GET, the only network shape the Sony "AutoMagic" feed uses
 * (spec-firmware-download.md §2: no auth, no custom headers, no pinning).
 * Abstracted so the feed logic is unit-testable without a socket.
 *
 * Not a `fun interface`: Kotlin forbids a default parameter value on a
 * functional interface's abstract method, and the default [onProgress] is worth
 * more to callers than SAM conversion. Implement it with an object expression.
 */
interface HttpFetcher {
    suspend fun get(
        url: String,
        onProgress: ((received: Long, total: Long) -> Unit)? = null,
    ): ByteArray
}

/**
 * [HttpFetcher] on [HttpsURLConnection]. Reads the whole body into memory; the
 * firmware binaries are a few MB and the transfer layer wants a `ByteArray`
 * anyway. [onProgress] reports `total = -1` when the server sends no
 * Content-Length.
 */
class HttpsUrlFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : HttpFetcher {

    override suspend fun get(
        url: String,
        onProgress: ((received: Long, total: Long) -> Unit)?,
    ): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection()
        if (connection !is HttpsURLConnection) {
            throw IOException("not an https url: $url")
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doInput = true
            connection.connect()
            val status = connection.responseCode
            if (status != HttpsURLConnection.HTTP_OK) {
                throw IOException("HTTP $status for $url")
            }
            val total = connection.contentLengthLong
            // Pre-size only for a sane Content-Length; a hostile one must not OOM us.
            val initial = if (total in 1..MAX_PRESIZE) total.toInt() else DEFAULT_BUFFER
            val sink = ByteArrayOutputStream(initial)
            val buffer = ByteArray(CHUNK)
            var received = 0L
            connection.inputStream.use { input ->
                while (true) {
                    // read() is not interruptible, so cancellation is checked here.
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    received += read
                    // A server that never stops sending must not exhaust the heap.
                    if (received > MAX_BODY_BYTES) {
                        throw IOException("body exceeds $MAX_BODY_BYTES bytes for $url")
                    }
                    sink.write(buffer, 0, read)
                    onProgress?.invoke(received, total)
                }
            }
            sink.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CHUNK = 16 * 1024
        const val DEFAULT_BUFFER = 32 * 1024
        const val MAX_PRESIZE = 64L * 1024 * 1024
        const val MAX_BODY_BYTES = 64L * 1024 * 1024
    }
}
