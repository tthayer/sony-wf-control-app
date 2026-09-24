package com.thelightphone.sonywf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sonywf.protocol.AncMode
import com.thelightphone.sonywf.protocol.SonyBattery
import com.thelightphone.sonywf.update.FirmwareUpdateMethod

@InitialScreen
class SonyWfHomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SonyWfViewModel>(sealedActivity) {

    override val viewModelClass: Class<SonyWfViewModel>
        get() = SonyWfViewModel::class.java

    override fun createViewModel() = SonyWfViewModel(lightContext.bluetoothSerial)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text(title(state)),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val s = state) {
                        is SonyUiState.Connecting -> CenteredMessage("Looking for your headphones…")
                        is SonyUiState.NotFound -> CenteredMessage(
                            "No Sony headphones found.\nPair them in Bluetooth settings, then tap RETRY."
                        )
                        is SonyUiState.Unsupported -> CenteredMessage(
                            "Bluetooth isn't available on this device."
                        )
                        is SonyUiState.Failed -> CenteredMessage(s.message)
                        is SonyUiState.Connected -> ConnectedBody(s)
                    }
                }

                LightBottomBar(bottomBarButtons(state))
            }
        }
    }

    private fun title(state: SonyUiState): String =
        (state as? SonyUiState.Connected)?.model ?: "Sony"

    private fun bottomBarButtons(state: SonyUiState): List<LightBarButton> =
        when (state) {
            is SonyUiState.Connected -> {
                val update = updateButtons(state.update, state.updateMethod)
                if (updateOwnsBar(state.update)) {
                    update
                } else {
                    // LightBottomBar allows at most 3 text items, so UPDATE takes LEVEL's slot.
                    ancButtons(state, MAX_TEXT_BUTTONS - update.size) + update
                }
            }
            is SonyUiState.Connecting -> emptyList()
            else -> listOf(
                LightBarButton.Text(text = "RETRY", onClick = viewModel::retry),
            )
        }

    /** True once the update flow needs the whole bar to itself. */
    private fun updateOwnsBar(update: FirmwareUpdateUi): Boolean = when (update) {
        is FirmwareUpdateUi.Confirming,
        is FirmwareUpdateUi.Downloading,
        is FirmwareUpdateUi.Transferring,
        is FirmwareUpdateUi.Installing,
        is FirmwareUpdateUi.Completed,
        is FirmwareUpdateUi.Failed,
        is FirmwareUpdateUi.Diagnostics,
        -> true
        else -> false
    }

    private fun updateButtons(
        update: FirmwareUpdateUi,
        method: FirmwareUpdateMethod?,
    ): List<LightBarButton> = when (update) {
        is FirmwareUpdateUi.Available -> buildList {
            if (update.installable) {
                add(LightBarButton.Text(text = "UPDATE", onClick = viewModel::startUpdate))
            }
            // The RACE probe stays reachable on Airoha chips, where the install
            // path itself rides that socket, and on anything we cannot flash.
            if (!update.installable || method == FirmwareUpdateMethod.MTK) {
                add(LightBarButton.Text(text = "DIAG", onClick = viewModel::runAirohaDiagnostics))
            }
        }
        is FirmwareUpdateUi.Unsupported -> listOf(
            LightBarButton.Text(text = "DIAG", onClick = viewModel::runAirohaDiagnostics),
        )
        is FirmwareUpdateUi.Diagnostics -> listOf(
            LightBarButton.Text(text = "OK", onClick = viewModel::dismissDiagnostics),
        )
        is FirmwareUpdateUi.Confirming -> listOf(
            LightBarButton.Text(text = "START", onClick = viewModel::confirmUpdate),
            LightBarButton.Text(text = "BACK", onClick = viewModel::cancelUpdate),
        )
        is FirmwareUpdateUi.Downloading,
        is FirmwareUpdateUi.Transferring,
        -> listOf(LightBarButton.Text(text = "CANCEL", onClick = viewModel::cancelUpdate))
        is FirmwareUpdateUi.Installing -> emptyList() // past the point of no return
        is FirmwareUpdateUi.Completed,
        is FirmwareUpdateUi.Failed,
        -> listOf(LightBarButton.Text(text = "OK", onClick = viewModel::dismissUpdateResult))
        else -> emptyList()
    }

    /** ANC controls trimmed to [budget] slots; LEVEL is the first to go. */
    private fun ancButtons(state: SonyUiState.Connected, budget: Int): List<LightBarButton> {
        if (!state.ancSupported || budget <= 0) return emptyList()
        return buildList {
            add(LightBarButton.Text(text = "MODE", onClick = viewModel::cycleMode))
            if (state.mode == AncMode.AMBIENT && budget >= MAX_TEXT_BUTTONS) {
                add(LightBarButton.Text(text = "LEVEL", onClick = viewModel::cycleAmbientLevel))
            }
            if (size < budget) {
                add(LightBarButton.Text(text = "VOICE", onClick = viewModel::toggleVoice))
            }
        }
    }

    @Composable
    private fun ConnectedBody(s: SonyUiState.Connected) {
        // The report is long raw hex, so it needs the whole body, not a line.
        val update = s.update
        if (update is FirmwareUpdateUi.Diagnostics) {
            DiagnosticsBody(update.lines)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1.5f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.spacedBy(0.75f.gridUnitsAsDp(), Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Once the update flow takes over, drop the ANC block: 31 vertical grid
            // units cannot hold both it and the progress/warning text.
            val focusedOnUpdate = updateOwnsBar(s.update)
            if (focusedOnUpdate) {
                LightText(
                    text = s.model,
                    variant = LightTextVariant.Subtitle,
                    align = TextAlign.Center,
                )
            } else if (s.ancSupported) {
                LightText(
                    text = modeLabel(s.mode),
                    variant = LightTextVariant.Subtitle,
                    align = TextAlign.Center,
                )
                if (s.mode == AncMode.AMBIENT) {
                    LightText(
                        text = "Level ${s.ambientLevel} / 20",
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                    )
                }
                LightText(
                    text = "Focus on Voice: ${if (s.voicePassthrough) "On" else "Off"}",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    align = TextAlign.Center,
                )
            } else {
                LightText(
                    text = "Connected",
                    variant = LightTextVariant.Subtitle,
                    align = TextAlign.Center,
                )
            }
            batteryLine(s.battery)?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    align = TextAlign.Center,
                )
            }
            s.firmwareVersion?.let {
                LightText(
                    text = "Firmware $it",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    align = TextAlign.Center,
                )
            }
            updateLine(s.update, s.updateMethod)?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                )
            }
            updateHint(s.update, s.updateMethod)?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    align = TextAlign.Center,
                )
            }
        }
    }

    /** Raw DIAG report: smallest style, monospaced, scrollable, never truncated. */
    @Composable
    private fun DiagnosticsBody(lines: List<String>) {
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            for (line in lines) {
                LightText(
                    text = line,
                    variant = LightTextVariant.Micro,
                    monospace = true,
                )
            }
        }
    }

    /** One status line for the update flow; null when there is nothing to say. */
    private fun updateLine(update: FirmwareUpdateUi, method: FirmwareUpdateMethod?): String? = when (update) {
        is FirmwareUpdateUi.Unknown -> null
        is FirmwareUpdateUi.Checking -> "Checking for updates"
        is FirmwareUpdateUi.UpToDate -> "Up to date"
        is FirmwareUpdateUi.CheckFailed -> "Can't reach the update server"
        is FirmwareUpdateUi.Unsupported -> update.reason
        is FirmwareUpdateUi.Available -> "Firmware ${update.version} available"
        is FirmwareUpdateUi.Confirming -> "Install firmware ${update.version}?"
        is FirmwareUpdateUi.Downloading -> "Downloading ${update.percent}%"
        is FirmwareUpdateUi.Transferring -> "Transferring ${update.percent}%"
        // MTK installs happen on the headphones after a reboot, not over our link.
        is FirmwareUpdateUi.Installing ->
            if (method == FirmwareUpdateMethod.MTK) {
                "Installing, headphones will restart"
            } else {
                "Installing… keep the app open"
            }
        is FirmwareUpdateUi.Completed -> "Update complete"
        is FirmwareUpdateUi.Failed -> update.message
        is FirmwareUpdateUi.Diagnostics -> null // DiagnosticsBody owns the whole body
    }

    /** Secondary line: the non-installable route, and the pre-install warning. */
    private fun updateHint(update: FirmwareUpdateUi, method: FirmwareUpdateMethod?): String? = when (update) {
        is FirmwareUpdateUi.Available ->
            if (update.installable) null else "Install with Sony Sound Connect app"
        // The Airoha transfer is far slower than Tandem's and mutes audio, so say so.
        is FirmwareUpdateUi.Confirming ->
            if (method == FirmwareUpdateMethod.MTK) {
                "The transfer takes 15 to 40 minutes. Keep the headphones on\n" +
                    "and within reach; audio may pause while it runs.\n" +
                    "Keep the phone on this screen until it finishes."
            } else {
                "Keep the headphones on, near the phone, and this app open.\n" +
                    "Do not use them until the update finishes."
            }
        is FirmwareUpdateUi.Completed -> "Firmware ${update.version}"
        else -> null
    }

    private fun modeLabel(mode: AncMode): String = when (mode) {
        AncMode.OFF -> "Off"
        AncMode.ANC -> "Noise Cancelling"
        AncMode.AMBIENT -> "Ambient Sound"
    }

    /**
     * Adaptive battery line. Prefer the granular per-component view (L / R / Case)
     * whenever the device reports any of it — earbuds also answer the single-battery
     * query with an aggregate, which must NOT mask the real per-bud readings. Fall
     * back to a single reading only for devices that report nothing else (over-ear
     * like the WH-1000XM5).
     */
    private fun batteryLine(b: SonyBattery): String? {
        val parts = buildList {
            b.left?.let { add("L $it%") }
            b.right?.let { add("R $it%") }
            b.case?.let { add("Case $it%") }
        }
        if (parts.isNotEmpty()) return parts.joinToString("   ")
        return b.single?.let { "Battery $it%" }
    }

    @Composable
    private fun CenteredMessage(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp()),
        )
    }

    private companion object {
        /** LightBottomBar refuses more than three text items. */
        const val MAX_TEXT_BUTTONS = 3
    }
}
