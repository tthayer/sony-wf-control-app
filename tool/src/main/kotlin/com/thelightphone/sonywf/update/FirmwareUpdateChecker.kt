package com.thelightphone.sonywf.update

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of one update check. Network and feed failures surface as [Error]. */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val update: AvailableUpdate) : UpdateCheck
    data class Error(val message: String) : UpdateCheck
}

/**
 * Drives the feed: build the info URL from the device-reported IDs, decode and
 * verify it, run the rule engine, and download plus verify the chosen binary.
 */
class FirmwareUpdateChecker(private val fetcher: HttpFetcher = HttpsUrlFetcher()) {

    /**
     * Never throws for an expected failure; a cancellation still propagates so
     * callers can tear the check down with their scope.
     */
    suspend fun check(
        params: UpdateParams,
        model: String,
        firmwareVersion: String,
    ): UpdateCheck = try {
        val raw = fetcher.get(SonyUpdateFeed.infoUrl(params.categoryId, params.serviceId))
        val info = SonyUpdateFeed.decodeInfo(raw, params.categoryId, params.serviceId)
        val conditions = SonyUpdateFeed.parseInfo(info.xml)
        val ctx = DeviceContext(
            model = model,
            firmwareVersion = firmwareVersion,
            serialNo = SonyUpdateFeed.effectiveSerial(model, params.serialNumber),
            nation = params.nationCode,
        )
        val update = SonyUpdateFeed.select(conditions, ctx, info.digest)
        // The feed's own rules normally exclude the installed version; the extra
        // comparison keeps us from re-offering it when they do not.
        if (update != null && update.version != firmwareVersion) {
            UpdateCheck.Available(update)
        } else {
            UpdateCheck.UpToDate
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // No reflection on the class name: the SDK build policy forbids it.
        UpdateCheck.Error(e.message ?: "update check failed")
    }

    /** Fetches and verifies the firmware binary. Throws [FeedException] if it does not match. */
    suspend fun download(update: AvailableUpdate, onProgress: (Int) -> Unit): FirmwareImage {
        val bytes = fetcher.get(update.url) { received, total ->
            // Fall back to the feed's Size when the server omits Content-Length.
            val expected = if (total > 0) total else update.sizeBytes
            if (expected > 0) {
                onProgress((received * 100 / expected).coerceIn(0L, 100L).toInt())
            }
        }
        // Hashing several MB blocks; keep it off whatever thread called us.
        val verified = withContext(Dispatchers.Default) {
            SonyUpdateFeed.verifyBinary(bytes, update.sizeBytes, update.macHex, update.digest)
        }
        if (!verified) {
            throw FeedException("firmware binary failed verification (size or MAC)")
        }
        return FirmwareImage(
            bytes = bytes,
            version = update.version,
            fileName = SonyUpdateFeed.fileNameFor(update.url),
            digest = update.digest,
            macHex = update.macHex,
        )
    }
}
