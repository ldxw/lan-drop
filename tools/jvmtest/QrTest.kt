import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

class MatrixSource(private val m: BitMatrix) : LuminanceSource(m.width, m.height) {
    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val out = if (row != null && row.size >= width) row else ByteArray(width)
        for (x in 0 until width) out[x] = if (m.get(x, y)) 0 else 127
        return out
    }

    override fun getMatrix(): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) out[y * width + x] = if (m.get(x, y)) 0 else 127
        return out
    }
}

fun main(args: Array<String>) {
    val url = args.getOrElse(0) { "http://192.168.1.23:8888" }
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
    )
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 720, 720, hints)
    println("MATRIX ${matrix.width}x${matrix.height}")
    val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(MatrixSource(matrix)))).text
    println("DECODED $decoded")
    println(if (decoded == url) "QR_OK" else "QR_FAIL")
}
