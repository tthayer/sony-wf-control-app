package com.thelightphone.sonywf

/**
 * Firmware-update slice of [SonyUiState.Connected]. See docs/protocol/fw-update-design.md §8.
 * Kept separate from the connection state so the update flow can advance without
 * re-deriving the rest of the Connected snapshot.
 */
sealed interface FirmwareUpdateUi {
    /** No method info yet, or a v1 device that never reports one. */
    data object Unknown : FirmwareUpdateUi

    data object Checking : FirmwareUpdateUi

    data object UpToDate : FirmwareUpdateUi

    /** Device cannot be updated from here (e.g. no update support at all). */
    data class Unsupported(val reason: String) : FirmwareUpdateUi

    /** [installable] is false for MTK / MC_APP devices: check only, install via Sony's app. */
    data class Available(val version: String, val sizeBytes: Long, val installable: Boolean) : FirmwareUpdateUi

    data class Confirming(val version: String) : FirmwareUpdateUi

    data class Downloading(val percent: Int) : FirmwareUpdateUi

    data class Transferring(val percent: Int) : FirmwareUpdateUi

    data class Installing(val percent: Int) : FirmwareUpdateUi

    data class Completed(val version: String) : FirmwareUpdateUi

    data class Failed(val message: String) : FirmwareUpdateUi
}
