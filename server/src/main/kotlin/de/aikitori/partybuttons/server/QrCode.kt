package de.aikitori.partybuttons.server

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR-Code als Raster: [get] ist true für ein dunkles Feld. Mit Ruhezone, damit Kameras ihn sicher finden. */
class QrCode private constructor(val size: Int, private val cells: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = cells[y * size + x]

    companion object {
        private const val QUIET_ZONE = 2

        fun encode(text: String): QrCode {
            val hints = mapOf(
                // M reicht für kurze Adressen und bleibt grob genug für schlechte Kameras
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to QUIET_ZONE,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            // Größe 0: ZXing nimmt ein Feld pro Modul
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            val size = matrix.width
            return QrCode(size, BooleanArray(size * size) { matrix[it % size, it / size] })
        }
    }
}
