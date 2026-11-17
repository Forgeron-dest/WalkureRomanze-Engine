package walkure.compression

import walkure.crypto.readIntLE
import walkure.crypto.readUInt16LE

/**
 * QLiE `1PC\xFF` (`0xFF435031`) Byte-Pair Encoding (BPE) Decompressor Reverse-engineered from
 * `WALKURE_ROMANZE.exe` at `0x00487ea4`.
 */
object QlieCompression {
  const val MAGIC_1PC: Int = -0x00bcafcf // 0xFF435031 ("1PC\xFF" little-endian)

  private const val MAX_DECOMPRESSED_SIZE = 64 * 1024 * 1024 // 64 MiB safety cap

  fun isCompressed(data: ByteArray): Boolean {
    return data.size >= 12 && readIntLE(data, 0) == MAGIC_1PC
  }

  fun decompress1PC(data: ByteArray): ByteArray {
    require(isCompressed(data)) { "Data does not start with 1PC\\xFF magic (0xFF435031)" }
    val flags = readIntLE(data, 4)
    val outSize = readIntLE(data, 8)
    require(outSize in 0..MAX_DECOMPRESSED_SIZE) {
      "Invalid or excessive decompressed size: $outSize (max $MAX_DECOMPRESSED_SIZE)"
    }

    val out = ByteArray(outSize)
    var outPos = 0
    var pos = 12

    val left = IntArray(256)
    val right = IntArray(256)
    val stack = IntArray(4096)

    while (pos < data.size) {
      for (i in 0 until 256) {
        left[i] = i
      }

      var c = 0
      while (c < 256) {
        val b = data[pos].toInt() and 0xFF
        pos += 1

        if (b > 0x7F) {
          c += b - 0x7F
          if (c >= 256) break
        }

        val runCount = if (b > 0x7F) 0 else b
        for (j in 0..runCount) {
          if (c >= 256) break
          val tok = data[pos].toInt() and 0xFF
          pos += 1
          left[c] = tok
          if (tok != c) {
            right[c] = data[pos].toInt() and 0xFF
            pos += 1
          }
          c += 1
        }
      }

      val blockLen: Int
      if ((flags and 1) != 0) {
        blockLen = readUInt16LE(data, pos)
        pos += 2
      } else {
        blockLen = readIntLE(data, pos)
        pos += 4
      }

      var rem = blockLen
      var sp = 0
      while (rem > 0 || sp > 0) {
        val tok: Int
        if (sp > 0) {
          sp -= 1
          tok = stack[sp]
        } else {
          tok = data[pos].toInt() and 0xFF
          pos += 1
          rem -= 1
        }

        if (left[tok] == tok) {
          if (outPos < outSize) {
            out[outPos] = tok.toByte()
            outPos += 1
          }
        } else {
          stack[sp] = right[tok]
          stack[sp + 1] = left[tok]
          sp += 2
        }
      }
    }

    return if (outPos == outSize) out else out.copyOf(outPos)
  }

  /**
   * Wraps [data] into a valid `1PC\xFF` container using stored identity BPE blocks (`flags = 1`,
   * 256-entry identity dictionary per chunk), readable by both `decompress1PC` and the native QLiE
   * decompressor (`0x00487ea4`).
   */
  fun compress1PC(data: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(data.size + 512)
    fun writeInt(v: Int) {
      out.write(v and 0xFF)
      out.write((v ushr 8) and 0xFF)
      out.write((v ushr 16) and 0xFF)
      out.write((v ushr 24) and 0xFF)
    }
    writeInt(MAGIC_1PC)
    writeInt(1) // flags bit 0 -> u16 block lengths
    writeInt(data.size)

    if (data.isNotEmpty()) {
      val chunkMax = 49152
      var offset = 0
      while (offset < data.size) {
        val chunkLen = minOf(chunkMax, data.size - offset)
        // Identity dictionary for tokens 0..127 and 128..255
        out.write(0x7F)
        for (i in 0 until 128) out.write(i)
        out.write(0x7F)
        for (i in 128 until 256) out.write(i)
        // u16 block length + literal payload
        out.write(chunkLen and 0xFF)
        out.write((chunkLen ushr 8) and 0xFF)
        out.write(data, offset, chunkLen)
        offset += chunkLen
      }
    }
    return out.toByteArray()
  }
}
