package de.aikitori.partybuttons.server

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostNetworkTest {
    @Test
    fun `QR-Code lässt sich wieder lesen`() {
        val url = "http://192.168.178.23:8080/j/QDAX3"
        val qr = QrCode.encode(url)
        // jedes Modul 4×4 Pixel, schwarz auf weiß
        val scale = 4
        val px = qr.size * scale
        val pixels = IntArray(px * px) { i ->
            if (qr[(i % px) / scale, (i / px) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(px, px, pixels)))
        assertEquals(url, QRCodeReader().decode(bitmap).text)
    }

    @Test
    fun `WLAN und Hotspot zuerst, mobile Daten und VPN nicht`() {
        val ranked = LanAddresses.rank(
            listOf(
                InterfaceAddress("rmnet_data0", "10.45.3.2"),
                InterfaceAddress("eth0", "192.168.0.20"),
                InterfaceAddress("tun0", "10.8.0.2"),
                InterfaceAddress("swlan0", "192.168.43.1"),
                InterfaceAddress("wlan0", "192.168.178.23"),
                InterfaceAddress("dummy0", "192.168.99.1"),
            ),
        )
        assertEquals(listOf("192.168.43.1", "192.168.178.23", "192.168.0.20"), ranked)
    }

    @Test
    fun `nur private Adressen`() {
        val ranked = LanAddresses.rank(
            listOf(
                InterfaceAddress("wlan0", "100.64.0.5"),
                InterfaceAddress("wlan0", "172.20.1.4"),
                InterfaceAddress("ap0", "10.0.0.1"),
                InterfaceAddress("wlan0", "8.8.8.8"),
            ),
        )
        assertEquals(listOf("172.20.1.4", "10.0.0.1"), ranked)
    }
}
