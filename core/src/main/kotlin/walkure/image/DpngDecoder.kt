package walkure.image

import com.badlogic.gdx.graphics.Pixmap
import walkure.crypto.readIntLE

/**
 * Decoder for QLiE `DPNG` ("difference PNG") images, used by `画像\日付背景m_N.png` and `画像\日付背景b_N.png`.
 *
 * A DPNG is a vertically tiled PNG container:
 * - 48-byte header: `"DPNG"` magic, version, bit depth, full width, full height, then the first
 *   strip's width, height and PNG byte length.
 * - The first PNG strip immediately follows the 48-byte header.
 * - Every subsequent strip is preceded by a 28-byte record `[0, yOffset, stripW, stripH, pngSize,
 *   0, 0]` followed by that strip's standalone PNG.
 *
 * The full image is reconstructed by compositing each strip at its `yOffset`. Decoding only the
 * first strip (as the previous code did) yields a 24/96-pixel-tall texture that is then stretched,
 * which is what produced the unfilled calendar background.
 */
object DpngDecoder {
  const val MAGIC = "DPNG"
  private const val HEADER_SIZE = 48
  private const val STRIP_RECORD_SIZE = 28

  fun isDpngData(data: ByteArray): Boolean {
    return data.size >= HEADER_SIZE + 8 &&
      data[0] == 'D'.code.toByte() &&
      data[1] == 'P'.code.toByte() &&
      data[2] == 'N'.code.toByte() &&
      data[3] == 'G'.code.toByte()
  }

  fun decodePixmap(data: ByteArray): Pixmap? {
    if (!isDpngData(data)) return null
    val fullW = readIntLE(data, 12)
    val fullH = readIntLE(data, 16)
    if (fullW <= 0 || fullH <= 0) return null

    val out = Pixmap(fullW, fullH, Pixmap.Format.RGBA8888)
    out.blending = Pixmap.Blending.None

    var off = HEADER_SIZE
    var first = true
    try {
      while (off + 8 <= data.size) {
        val pngSize: Int
        val yOffset: Int
        if (first) {
          pngSize = readIntLE(data, 36)
          yOffset = 0
          first = false
        } else {
          if (off + STRIP_RECORD_SIZE > data.size) break
          yOffset = readIntLE(data, off + 4)
          pngSize = readIntLE(data, off + 16)
          off += STRIP_RECORD_SIZE
        }
        if (pngSize <= 0 || off + pngSize > data.size) break
        val strip = runCatching { Pixmap(data, off, pngSize) }.getOrNull() ?: break
        try {
          if (yOffset >= 0 && yOffset < fullH) {
            out.drawPixmap(strip, 0, yOffset)
          }
        } finally {
          strip.dispose()
        }
        off += pngSize
      }
      return out
    } catch (t: Throwable) {
      out.dispose()
      return null
    }
  }
}
