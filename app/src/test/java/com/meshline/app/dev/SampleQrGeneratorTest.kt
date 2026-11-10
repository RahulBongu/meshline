package com.meshline.app.dev

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.meshline.app.security.IdentityFactory
import com.meshline.app.security.QrPayload
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Dev-only sample QR generator (D1 scan testing with one phone).
 *
 * Lives in the JVM `test` source set, so it is **never packaged into any APK**
 * (debug, release, or releaseSmoke). It creates a throwaway identity and writes
 * a scannable PNG for the laptop screen.
 *
 * Run:
 *   ./gradlew :app:testDebugUnitTest --tests "*SampleQrGeneratorTest*"
 *
 * Output: `app/build/reports/sample-contact-qr.png`
 * The identity is fresh on every run; the contact it adds will never come
 * online, which is exactly what a scan-only test needs.
 *
 * The PNG is encoded by hand (8-bit grayscale, `Deflater` + `CRC32`) because the
 * Android unit-test compile classpath deliberately does not include
 * `java.awt`/`javax.imageio`. No third-party image library is added.
 */
class SampleQrGeneratorTest {

    @Test
    fun `write sample contact qr png`() {
        val (identity, _, _) = IdentityFactory.generate()
        val payload = QrPayload.encode(
            QrPayload.ContactCard(
                ed25519PublicKey = identity.ed25519PublicKey,
                x25519PublicKey = identity.x25519PublicKey,
                avatarIndex = 0,
                nickname = "sample",
            )
        )

        val size = 768
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.MARGIN to 4,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        // 0 = black, 255 = white (PNG 8-bit grayscale, color type 0).
        val gray = ByteArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if (matrix.get(x, y)) 0 else 255.toByte()
        }

        val out = File("build/reports/sample-contact-qr.png")
        out.parentFile.mkdirs()
        writeGrayscalePng(out, size, size, gray)

        assertTrue("empty PNG: $out", out.length() > 0)
        println("sample QR: ${out.absolutePath}")
        println("throwaway fingerprint: ${identity.fingerprint}")
        println("payload: $payload")
    }

    private fun writeGrayscalePng(file: File, width: Int, height: Int, gray: ByteArray) {
        require(gray.size == width * height) { "pixel buffer size mismatch" }
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

            val ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
                .putInt(width)
                .putInt(height)
                .put(8)   // bit depth
                .put(0)   // color type: grayscale
                .put(0)   // compression
                .put(0)   // filter
                .put(0)   // interlace
                .array()
            writeChunk(out, "IHDR", ihdr)

            // Each scanline is one filter byte (0 = none) followed by width pixels.
            val raw = ByteArray((width + 1) * height)
            for (y in 0 until height) {
                raw[y * (width + 1)] = 0
                System.arraycopy(gray, y * width, raw, y * (width + 1) + 1, width)
            }
            val compressed = ByteArrayOutputStream()
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            try {
                deflater.setInput(raw)
                deflater.finish()
                val buf = ByteArray(16 * 1024)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buf)
                    compressed.write(buf, 0, n)
                }
            } finally {
                deflater.end()
            }
            writeChunk(out, "IDAT", compressed.toByteArray())
            writeChunk(out, "IEND", ByteArray(0))
        }
    }

    private fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        out.writeInt(crc.value.toInt())
    }
}
