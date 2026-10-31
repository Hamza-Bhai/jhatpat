package io.github.hamzabhai.jhatpat.ui

import io.github.hamzabhai.jhatpat.logic.WifiQr
import io.github.hamzabhai.jhatpat.service.Mode
import io.github.hamzabhai.jhatpat.service.SharingState

/** What the Connect screen shows: exactly one QR code at a time. */
data class ConnectUi(
    /** Text of the QR code; empty when there is no address to share. */
    val qrText: String,
    val title: String,
    val caption: String,
    val showPin: Boolean,
    val pin: String,
    /** `ip:port` for typing into a browser, when there is one. */
    val address: String?,
    /** "1 of 2" in hotspot mode, null otherwise. */
    val stepLabel: String?,
    val nextButton: String?,
    val hasBack: Boolean,
    /** Hotspot step 1 only: the network name and password as text, for cameras that do not read WiFi QR codes. */
    val wifiName: String? = null,
    val wifiPassword: String? = null,
)

object ConnectUiModel {
    private const val LAST_STEP = 1

    /**
     * With WiFi there is one QR (the join link). With a hotspot the phone must join the hotspot
     * before it can open the page, so step 0 shows the WiFi QR and step 1 the join link.
     */
    fun of(state: SharingState.Running, step: Int): ConnectUi {
        val info = state.info
        if (info.ip.isEmpty()) {
            return ConnectUi(
                qrText = "",
                title = "No WiFi address",
                caption = "Connect this phone to a WiFi network, then stop and start sharing again.",
                showPin = false, pin = "", address = null, stepLabel = null, nextButton = null, hasBack = false,
            )
        }
        val address = "${info.ip}:${info.port}"
        val join = "http://$address/j/${info.secret}"
        val typed = "or open $address in a browser and type the PIN"
        val hotspot = state.hotspot
        if (state.mode == Mode.Hotspot && hotspot != null) {
            return when (step.coerceIn(0, LAST_STEP)) {
                0 -> ConnectUi(
                    qrText = WifiQr.build(hotspot.ssid, hotspot.password),
                    title = "Step 1 of 2 · Join the WiFi",
                    caption = "Scan with Google Lens, or Settings > WiFi > add network > scan icon. If your camera app only shows text, join ${hotspot.ssid} by hand with the password below.",
                    showPin = false, pin = info.pin, address = null, stepLabel = "1 of 2",
                    nextButton = "They're connected, next", hasBack = false,
                    wifiName = hotspot.ssid, wifiPassword = hotspot.password,
                )
                else -> ConnectUi(
                    qrText = join,
                    title = "Step 2 of 2 · Open JhatPat",
                    caption = typed,
                    showPin = true, pin = info.pin, address = address, stepLabel = "2 of 2",
                    nextButton = null, hasBack = true,
                )
            }
        }
        return ConnectUi(
            qrText = join, title = "Scan to join", caption = typed,
            showPin = true, pin = info.pin, address = address, stepLabel = null, nextButton = null, hasBack = false,
        )
    }
}
