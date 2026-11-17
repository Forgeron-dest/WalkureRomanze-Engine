package walkure.image

import com.badlogic.gdx.graphics.Pixmap
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import walkure.crypto.readIntLE

/**
 * Decoder for QLiE `ARGBSaveData1` (`.argb`) composite sprites and faces. Reverse-engineered from
 * `WALKURE_ROMANZE.exe` at `0x004c7194` - `0x004c7354` and `0x004c6cb8`.
 *
 * Binary layout:
 * - `0x00..0x0F`: `"ARGBSaveData1\x00\x00\x00"` (16 bytes)
 * - `0x10`: `uint8` mode (`3` = JPEG RGB payload + PNG Alpha mask payload)
 * - `0x11..0x14`: `uint32_le` `rgbSize`
 * - `0x15..0x18`: `uint32_le` `alphaSize`
 * - `0x19..0x19+rgbSize`: RGB image (JPEG)
 * - `0x19+rgbSize..0x19+rgbSize+alphaSize`: Grayscale/RGB Alpha mask image (PNG)
 */
object ArgbDecoder {
  const val MAGIC_PREFIX = "ARGBSaveData1"

  data class ArgbHeader(
    val mode: Int,
    val rgbSize: Int,
    val alphaSize: Int,
    val rgbOffset: Int = 25,
    val alphaOffset: Int = 25 + rgbSize,
  )

  fun isArgbData(data: ByteArray): Boolean {
    if (data.size < 25) return false
    val magic = String(data, 0, 13, Charsets.US_ASCII)
    return magic == MAGIC_PREFIX
  }

  fun parseHeader(data: ByteArray): ArgbHeader {
    require(isArgbData(data)) { "Invalid ARGBSaveData1 magic header" }
    val mode = data[16].toInt() and 0xFF
    val rgbSize = readIntLE(data, 17)
    val alphaSize = readIntLE(data, 21)
    require(rgbSize > 0 && alphaSize >= 0 && 25 + rgbSize + alphaSize <= data.size) {
      "Invalid ARGBSaveData1 payload sizes: mode=$mode, rgbSize=$rgbSize, alphaSize=$alphaSize, total=${data.size}"
    }
    return ArgbHeader(mode = mode, rgbSize = rgbSize, alphaSize = alphaSize)
  }

  /**
   * High-Performance Cross-Platform LibGDX [Pixmap] decoder (`RGBA8888`).
   *
   * Decodes JPEG RGB and PNG Alpha streams, then merges the green channel of the PNG mask into the
   * RGBA8888 alpha byte using direct [java.nio.ByteBuffer] chunks. This avoids millions of
   * individual JNI bridge calls ([Pixmap.getPixel] / [Pixmap.drawPixel]), drastically reducing CPU
   * spikes and thermal load during scene/graphic transitions.
   */
  fun decodePixmap(data: ByteArray): Pixmap {
    val hdr = parseHeader(data)
    val rgbPixmap = Pixmap(data, hdr.rgbOffset, hdr.rgbSize)
    val width = rgbPixmap.width
    val height = rgbPixmap.height

    val outPixmap = Pixmap(width, height, Pixmap.Format.RGBA8888)
    outPixmap.blending = Pixmap.Blending.None

    val totalPixels = width * height
    val outBuf = outPixmap.pixels.duplicate()
    outBuf.rewind()

    if (hdr.alphaSize > 0) {
      val alphaPixmap = Pixmap(data, hdr.alphaOffset, hdr.alphaSize)
      try {
        val rgbBuf = rgbPixmap.pixels.duplicate().apply { rewind() }
        val alphaBuf = alphaPixmap.pixels.duplicate().apply { rewind() }
        val rgbBpp = if (rgbPixmap.format == Pixmap.Format.RGBA8888) 4 else 3
        val alphaBpp = if (alphaPixmap.format == Pixmap.Format.RGBA8888) 4 else 3

        val chunkPixels = 1024
        val rgbChunk = ByteArray(chunkPixels * rgbBpp)
        val alphaChunk = ByteArray(chunkPixels * alphaBpp)
        val outChunk = ByteArray(chunkPixels * 4)

        var remaining = totalPixels
        while (remaining > 0) {
          val count = minOf(remaining, chunkPixels)
          rgbBuf.get(rgbChunk, 0, count * rgbBpp)
          alphaBuf.get(alphaChunk, 0, count * alphaBpp)

          for (i in 0 until count) {
            val rgbBase = i * rgbBpp
            val alphaBase = i * alphaBpp
            val outBase = i * 4

            outChunk[outBase] = rgbChunk[rgbBase] // R
            outChunk[outBase + 1] = rgbChunk[rgbBase + 1] // G
            outChunk[outBase + 2] = rgbChunk[rgbBase + 2] // B
            // Green channel of mask is at alphaBase + 1
            outChunk[outBase + 3] = alphaChunk[alphaBase + 1] // A
          }
          outBuf.put(outChunk, 0, count * 4)
          remaining -= count
        }
      } finally {
        alphaPixmap.dispose()
      }
    } else {
      val rgbBuf = rgbPixmap.pixels.duplicate().apply { rewind() }
      val rgbBpp = if (rgbPixmap.format == Pixmap.Format.RGBA8888) 4 else 3
      val chunkPixels = 1024
      val rgbChunk = ByteArray(chunkPixels * rgbBpp)
      val outChunk = ByteArray(chunkPixels * 4)

      var remaining = totalPixels
      while (remaining > 0) {
        val count = minOf(remaining, chunkPixels)
        rgbBuf.get(rgbChunk, 0, count * rgbBpp)
        for (i in 0 until count) {
          val rgbBase = i * rgbBpp
          val outBase = i * 4
          outChunk[outBase] = rgbChunk[rgbBase]
          outChunk[outBase + 1] = rgbChunk[rgbBase + 1]
          outChunk[outBase + 2] = rgbChunk[rgbBase + 2]
          outChunk[outBase + 3] = 0xFF.toByte()
        }
        outBuf.put(outChunk, 0, count * 4)
        remaining -= count
      }
    }

    rgbPixmap.dispose()
    outBuf.rewind()
    return outPixmap
  }

  /**
   * Decodes an `ARGBSaveData1` byte buffer into a standard `TYPE_INT_ARGB` `BufferedImage` (used
   * for CLI PNG export & tests). Matches `0x004c6cb8` which extracts `(alphaMaskPixel shr 8) and
   * 0xFF` (green channel) as the 8-bit alpha channel `(alpha shl 24) or (rgbPixel and 0x00FFFFFF)`.
   */
  fun decode(data: ByteArray): BufferedImage {
    val hdr = parseHeader(data)
    val rgbImage =
      ImageIO.read(ByteArrayInputStream(data, hdr.rgbOffset, hdr.rgbSize))
        ?: throw IllegalArgumentException("Failed to decode RGB JPEG stream in ARGBSaveData1")

    val width = rgbImage.width
    val height = rgbImage.height
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    val rgbPixels = rgbImage.getRGB(0, 0, width, height, null, 0, width)

    if (hdr.alphaSize > 0) {
      val alphaImage =
        ImageIO.read(ByteArrayInputStream(data, hdr.alphaOffset, hdr.alphaSize))
          ?: throw IllegalArgumentException("Failed to decode Alpha PNG stream in ARGBSaveData1")
      require(alphaImage.width == width && alphaImage.height == height) {
        "RGB ($width x $height) and Alpha (${alphaImage.width} x ${alphaImage.height}) dimensions do not match"
      }
      val alphaPixels = alphaImage.getRGB(0, 0, width, height, null, 0, width)
      for (i in rgbPixels.indices) {
        val a = (alphaPixels[i] ushr 8) and 0xFF
        rgbPixels[i] = (a shl 24) or (rgbPixels[i] and 0x00FFFFFF)
      }
    } else {
      for (i in rgbPixels.indices) {
        rgbPixels[i] = (-0x1000000) or (rgbPixels[i] and 0x00FFFFFF)
      }
    }

    out.setRGB(0, 0, width, height, rgbPixels, 0, width)
    return out
  }
}
