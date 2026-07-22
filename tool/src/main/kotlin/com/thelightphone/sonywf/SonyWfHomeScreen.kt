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
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sonywf.protocol.AncMode

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
                    center = LightTopBarCenter.Text("Sony WF"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val s = state) {
                        is SonyUiState.Connecting -> CenteredMessage("Connecting to WF-1000XM5…")
                        is SonyUiState.NotPaired -> CenteredMessage(
                            "No WF-1000XM5 paired.\nPair it in Bluetooth settings, then tap RETRY."
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

    private fun bottomBarButtons(state: SonyUiState): List<LightBarButton> =
        when (state) {
            is SonyUiState.Connected -> buildList {
                add(LightBarButton.Text(text = "MODE", onClick = viewModel::cycleMode))
                if (state.mode == AncMode.AMBIENT) {
                    add(LightBarButton.Text(text = "LEVEL", onClick = viewModel::cycleAmbientLevel))
                }
                add(LightBarButton.Text(text = "VOICE", onClick = viewModel::toggleVoice))
            }
            is SonyUiState.Connecting -> emptyList()
            else -> listOf(
                LightBarButton.Text(text = "RETRY", onClick = viewModel::retry),
            )
        }

    @Composable
    private fun ConnectedBody(s: SonyUiState.Connected) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1.5f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.spacedBy(0.75f.gridUnitsAsDp(), Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
            LightText(
                text = batteryLine(s.leftBattery, s.rightBattery, s.caseBattery),
                variant = LightTextVariant.Fine,
                lighten = true,
                align = TextAlign.Center,
            )
        }
    }

    private fun modeLabel(mode: AncMode): String = when (mode) {
        AncMode.OFF -> "Off"
        AncMode.ANC -> "Noise Cancelling"
        AncMode.AMBIENT -> "Ambient Sound"
    }

    private fun batteryLine(left: Int?, right: Int?, case: Int?): String {
        fun pct(v: Int?) = v?.let { "$it%" } ?: "—"
        return "L ${pct(left)}   R ${pct(right)}   Case ${pct(case)}"
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
}
