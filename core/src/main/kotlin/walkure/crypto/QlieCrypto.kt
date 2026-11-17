package walkure.crypto

import java.io.File
import java.nio.charset.Charset

/**
 * QLiE Engine Cryptographic, Checksum, and PRNG Routines Reverse-engineered from
 * WALKURE_ROMANZE.exe:
 * - MMX Hash: 0x00488834
 * - Custom 64-word Mersenne Twister (N=64, M=39): 0x00487300 - 0x00487578
 * - Header Signature Decryptor: 0x00487dfc
 * - Filename Decryptor: 0x00487c20
 * - Mode 4 Entry Payload Decryptor: 0x0048b3bc & 0x0048b610
 * - External Key Loader (DLL/key.fkey + TForm1.IconKeyImage): 0x005635c8
 */
object QlieHash {
  /**
   * Exact implementation of QLiE's MMX 16-bit lane checksum at 0x00488834. Operates on 8-byte
   * (64-bit) blocks as four 16-bit little-endian lanes.
   */
  fun hashMmx(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
    val nQwords = length ushr 3
    if (nQwords == 0) return 0

    var mm0_0 = 0
    var mm0_1 = 0
    var mm0_2 = 0
    var mm0_3 = 0

    var mm2_0 = 0
    var mm2_1 = 0
    var mm2_2 = 0
    var mm2_3 = 0

    var pos = offset
    for (i in 0 until nQwords) {
      val w0 = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
      val w1 = (data[pos + 2].toInt() and 0xFF) or ((data[pos + 3].toInt() and 0xFF) shl 8)
      val w2 = (data[pos + 4].toInt() and 0xFF) or ((data[pos + 5].toInt() and 0xFF) shl 8)
      val w3 = (data[pos + 6].toInt() and 0xFF) or ((data[pos + 7].toInt() and 0xFF) shl 8)
      pos += 8

      mm2_0 = (mm2_0 + 0x0307) and 0xFFFF
      mm2_1 = (mm2_1 + 0x0307) and 0xFFFF
      mm2_2 = (mm2_2 + 0x0307) and 0xFFFF
      mm2_3 = (mm2_3 + 0x0307) and 0xFFFF

      mm0_0 = (mm0_0 + (w0 xor mm2_0)) and 0xFFFF
      mm0_1 = (mm0_1 + (w1 xor mm2_1)) and 0xFFFF
      mm0_2 = (mm0_2 + (w2 xor mm2_2)) and 0xFFFF
      mm0_3 = (mm0_3 + (w3 xor mm2_3)) and 0xFFFF
    }

    val lo = mm0_0 or (mm0_1 shl 16)
    val hi = mm0_2 or (mm0_3 shl 16)
    return lo xor hi
  }
}

/** QLiE's custom 64-word Mersenne Twister variant (N = 64, M = 39) at 0x00487300 - 0x00487578. */
class QlieMersenneTwister(seed: Int, fkeyBytes: ByteArray?, iconBytes: ByteArray?) {
  private val state = IntArray(64)
  private var idx = 0

  init {
    initSeed(seed)
    if (fkeyBytes != null && fkeyBytes.isNotEmpty()) {
      xorBytes(fkeyBytes)
    }
    if (iconBytes != null && iconBytes.isNotEmpty()) {
      xorBytes(iconBytes)
    }
  }

  /** 0x00487488: Linear congruential state initialization with multiplier 0x6611BC19. */
  private fun initSeed(seed: Int) {
    state[0] = seed
    for (i in 1 until 64) {
      val prev = state[i - 1]
      val x = prev xor (prev ushr 30)
      state[i] = x * 0x6611BC19 + i
    }
    idx = 0
  }

  /** 0x004874dc: XORs up to 64 uint32_le words (256 bytes) into state[0..63]. */
  private fun xorBytes(buf: ByteArray) {
    val nDwords = minOf(buf.size ushr 2, 64)
    for (i in 0 until nDwords) {
      val word = readIntLE(buf, i * 4)
      state[i] = state[i] xor word
    }
  }

  /**
   * 0x00487308: Custom twist step. Note that `(u and 0x80000000)` is NOT shifted right in QLiE's
   * assembly (`and eax, 0x80000000`), while `(v and 0x7FFFFFFF)` is shifted right by 1 (`shr edx,
   * 1`).
   */
  private fun twist(u: Int, v: Int): Int {
    val mag = if ((v and 1) != 0) -0x66f74f21 /* 0x9908B0DF */ else 0
    return ((u and -0x80000000) or ((v and 0x7FFFFFFF) ushr 1)) xor mag
  }

  /** 0x00487448: Reloads all 64 state words (N = 64, M = 39, N - M = 25). */
  private fun reload() {
    for (i in 0 until 25) {
      state[i] = twist(state[i], state[i + 1]) xor state[i + 39]
    }
    for (i in 25 until 63) {
      state[i] = twist(state[i], state[i + 1]) xor state[i - 25]
    }
    state[63] = twist(state[63], state[0]) xor state[38]
    idx = 64
  }

  /** 0x00487510: Extracts and tempers the next 32-bit pseudorandom integer. */
  fun nextU32(): Int {
    if (idx <= 0) {
      reload()
    }
    idx -= 1
    var y = state[63 - idx]
    y = y xor (y ushr 11)
    y = y xor ((y shl 7) and -0x63b0771d /* 0x9C4F88E3 */)
    y = y xor ((y shl 15) and -0x18090000 /* 0xE7F70000 */)
    y = y xor (y ushr 18)
    return y
  }
}

/**
 * Extracts external key material required by FilePackVer3.0 Mode 4 decryption:
 * 1. `DLL/key.fkey`
 * 2. `TForm1.IconKeyImage.Picture.Data` (`TIcon` payload) embedded in `WALKURE_ROMANZE.exe`
 *    `.rsrc`.
 */
object QlieKeyExtractor {
  const val PORTABLE_KEY_FILENAME = "walkure.keys"

  data class KeyMaterial(
    val fkeyBytes: ByteArray,
    val iconBytes: ByteArray,
  )

  fun loadFromGameDirectory(gameDir: File): KeyMaterial {
    val portableKeyFile =
      File(gameDir, "GameData/$PORTABLE_KEY_FILENAME").takeIf { it.exists() }
        ?: File(gameDir, PORTABLE_KEY_FILENAME).takeIf { it.exists() }
        ?: File(gameDir.parentFile ?: gameDir, "GameData/$PORTABLE_KEY_FILENAME").takeIf {
          it.exists()
        }
        ?: File(gameDir.parentFile ?: gameDir, PORTABLE_KEY_FILENAME).takeIf { it.exists() }
    if (portableKeyFile != null && portableKeyFile.length() >= 512L) {
      val raw = portableKeyFile.readBytes()
      return KeyMaterial(
        fkeyBytes = raw.copyOfRange(0, 256),
        iconBytes = raw.copyOfRange(256, 512),
      )
    }

    val fkeyFile =
      File(gameDir, "DLL/key.fkey").takeIf { it.exists() } ?: File(gameDir, "DLL/Key.fkey")
    require(fkeyFile.exists()) {
      "Missing key.fkey at ${fkeyFile.absolutePath} (and no $PORTABLE_KEY_FILENAME found)"
    }
    val fkeyBytes = fkeyFile.readBytes()

    val exeFile = File(gameDir, "WALKURE_ROMANZE.exe")
    require(exeFile.exists()) { "Missing WALKURE_ROMANZE.exe at ${exeFile.absolutePath}" }
    val iconBytes = extractIconKeyFromExe(exeFile)

    return KeyMaterial(fkeyBytes = fkeyBytes, iconBytes = iconBytes)
  }

  /**
   * Exports the 256-byte `fkey` and 256-byte `TIcon` key (`512` bytes total) to
   * `GameData/walkure.keys` so mobile (`.apk`) and standalone distributions do not need
   * `WALKURE_ROMANZE.exe` or `DLL/key.fkey`.
   */
  fun exportPortableKeyFile(
    gameDir: File,
    targetFile: File = File(gameDir, "GameData/$PORTABLE_KEY_FILENAME"),
  ): File {
    val keys = loadFromGameDirectory(gameDir)
    val combined = ByteArray(512)
    System.arraycopy(keys.fkeyBytes, 0, combined, 0, minOf(keys.fkeyBytes.size, 256))
    System.arraycopy(keys.iconBytes, 0, combined, 256, minOf(keys.iconBytes.size, 256))
    targetFile.parentFile?.mkdirs()
    targetFile.writeBytes(combined)
    return targetFile
  }

  /**
   * Locates `IconKeyImage` inside the Delphi DFM resource of `WALKURE_ROMANZE.exe` (0x005635c8) and
   * extracts the `TIcon` stream bytes following `\x0cPicture.Data\x0a`.
   */
  fun extractIconKeyFromExe(exeFile: File): ByteArray {
    val exeBytes = exeFile.readBytes()
    val marker1 = "\u000cIconKeyImage".toByteArray(Charsets.US_ASCII)
    val idx1 = indexOfBytes(exeBytes, marker1, 0)
    require(idx1 >= 0) { "Could not find IconKeyImage DFM property in ${exeFile.name}" }

    val marker2 = "\u000cPicture.Data\n".toByteArray(Charsets.US_ASCII)
    val idx2 = indexOfBytes(exeBytes, marker2, idx1)
    require(idx2 >= 0) {
      "Could not find Picture.Data property after IconKeyImage in ${exeFile.name}"
    }

    val dataStart = idx2 + marker2.size
    val totalLen = readIntLE(exeBytes, dataStart)
    val classNameLen = exeBytes[dataStart + 4].toInt() and 0xFF
    val iconPayloadOffset = dataStart + 5 + classNameLen
    val iconPayloadLength = totalLen - 1 - classNameLen
    require(iconPayloadLength >= 256 && iconPayloadOffset + iconPayloadLength <= exeBytes.size) {
      "Invalid IconKeyImage payload bounds: offset=$iconPayloadOffset, length=$iconPayloadLength"
    }
    return exeBytes.copyOfRange(iconPayloadOffset, iconPayloadOffset + iconPayloadLength)
  }

  private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, startIndex: Int): Int {
    if (needle.isEmpty() || haystack.size - startIndex < needle.size) return -1
    val limit = haystack.size - needle.size
    outer@ for (i in startIndex..limit) {
      for (j in needle.indices) {
        if (haystack[i + j] != needle[j]) continue@outer
      }
      return i
    }
    return -1
  }
}

object QlieCipher {
  val SHIFT_JIS: Charset = Charset.forName("MS932")
  const val EXPECTED_HEADER_SIGNATURE = "8hr48uky,8ugi8ewra4g8d5vbf5hb5s6"

  /**
   * 0x00487dfc: Decrypts the 32-byte FilePackVer3.0 header signature at `file_size - 0x440`.
   * Verified against `"8hr48uky,8ugi8ewra4g8d5vbf5hb5s6"`.
   */
  fun decryptHeaderSignature(encSig: ByteArray, packKey: Int): String {
    require(encSig.size >= 32) { "Header signature must be at least 32 bytes" }
    val seed = (0x20 + packKey) xor -0x01368ac2 /* 0xFEC9753E */
    var mm7_0 = -0x58c3a063 /* 0xA73C5F9D */
    var mm7_1 = -0x58c3a063 /* 0xA73C5F9D */
    val mm6_0 = -0x31db0add /* 0xCE24F523 */
    val mm6_1 = -0x31db0add /* 0xCE24F523 */
    var mm5_0 = seed
    var mm5_1 = seed

    val out = ByteArray(32)
    for (i in 0 until 4) {
      val c0 = readIntLE(encSig, i * 8)
      val c1 = readIntLE(encSig, i * 8 + 4)

      mm7_0 = (mm7_0 + mm6_0) xor mm5_0
      mm7_1 = (mm7_1 + mm6_1) xor mm5_1

      val p0 = c0 xor mm7_0
      val p1 = c1 xor mm7_1

      mm5_0 = p0
      mm5_1 = p1

      writeIntLE(out, i * 8, p0)
      writeIntLE(out, i * 8 + 4, p1)
    }
    return String(out, Charsets.US_ASCII)
  }

  /**
   * 0x00487c20: Decrypts an entry filename in the pack index.
   * - For FilePackVer1.0 / 2.0, `packKey` is fixed at `0xC4`.
   * - For FilePackVer3.0, `packKey` is `hashMmx(keyTable) and 0x0FFFFFFF`.
   */
  fun decryptFileName(rawNameBytes: ByteArray, version: Int, packKey: Int): String {
    val effectiveKey = if (version >= 3) packKey else 0xC4
    val len = rawNameBytes.size
    val k = (len + (effectiveKey xor 0x3E)) and 0xFF
    val decrypted = ByteArray(len)
    for (i in 1..len) {
      val mask = ((i xor k) + i) and 0xFF
      decrypted[i - 1] = ((rawNameBytes[i - 1].toInt() and 0xFF) xor mask).toByte()
    }
    return String(decrypted, SHIFT_JIS)
  }

  /**
   * 0x0048b3bc & 0x0048b610: Decrypts a FilePackVer3.0 Mode 4 entry payload in-place or into a new
   * buffer.
   */
  fun decryptEntryMode4(
    cipherData: ByteArray,
    decryptedNameBytes: ByteArray,
    packKey: Int,
    fkeyBytes: ByteArray,
    iconBytes: ByteArray,
  ): ByteArray {
    val sz = cipherData.size
    var edx = 0x85F532
    var ebx = 0x33F641

    for (i in decryptedNameBytes.indices) {
      val b = decryptedNameBytes[i].toInt() and 0xFF
      edx += b * (i and 0xFF)
      ebx = ebx xor edx
    }

    val eax = (((sz xor 0x8F32DC) xor edx) + edx + sz + 7 * (sz and 0xFFFFFF)) xor packKey
    ebx += eax
    val seed = ((ebx and 0xFFFFFF) * 9) xor 0x453A

    val mt = QlieMersenneTwister(seed, fkeyBytes, iconBytes)
    val tbl = IntArray(41) { mt.nextU32() }
    var mm7_0 = mt.nextU32()
    var mm7_1 = mt.nextU32()

    val nQwords = sz ushr 3
    val out = cipherData.copyOf()
    if (nQwords == 0) return out

    var tIdx = (mt.nextU32() and 0xF) * 2
    var pos = 0

    for (i in 0 until nQwords) {
      val c0 = readIntLE(out, pos)
      val c1 = readIntLE(out, pos + 4)

      val mm6_0 = tbl[tIdx]
      val mm6_1 = tbl[tIdx + 1]

      mm7_0 = (mm7_0 xor mm6_0) + mm6_0
      mm7_1 = (mm7_1 xor mm6_1) + mm6_1

      val p0 = c0 xor mm7_0
      val p1 = c1 xor mm7_1

      writeIntLE(out, pos, p0)
      writeIntLE(out, pos + 4, p1)
      pos += 8

      // MMX state update: paddb -> pxor -> pslld 1 -> paddw
      val addb0 = paddb32(mm7_0, p0)
      val addb1 = paddb32(mm7_1, p1)

      val xor0 = addb0 xor p0
      val xor1 = addb1 xor p1

      val shl0 = xor0 shl 1
      val shl1 = xor1 shl 1

      mm7_0 = paddw32(shl0, p0)
      mm7_1 = paddw32(shl1, p1)

      tIdx = (tIdx + 2) and 0x1F
    }

    return out
  }

  /** 4-lane 8-bit wrapping addition (MMX `paddb` on a 32-bit half). */
  private fun paddb32(a: Int, b: Int): Int {
    val b0 = ((a and 0xFF) + (b and 0xFF)) and 0xFF
    val b1 = (((a ushr 8) and 0xFF) + ((b ushr 8) and 0xFF)) and 0xFF
    val b2 = (((a ushr 16) and 0xFF) + ((b ushr 16) and 0xFF)) and 0xFF
    val b3 = (((a ushr 24) and 0xFF) + ((b ushr 24) and 0xFF)) and 0xFF
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
  }

  /** 2-lane 16-bit wrapping addition (MMX `paddw` on a 32-bit half). */
  private fun paddw32(a: Int, b: Int): Int {
    val w0 = ((a and 0xFFFF) + (b and 0xFFFF)) and 0xFFFF
    val w1 = (((a ushr 16) and 0xFFFF) + ((b ushr 16) and 0xFFFF)) and 0xFFFF
    return w0 or (w1 shl 16)
  }
}

internal fun readIntLE(buf: ByteArray, offset: Int): Int {
  return (buf[offset].toInt() and 0xFF) or
    ((buf[offset + 1].toInt() and 0xFF) shl 8) or
    ((buf[offset + 2].toInt() and 0xFF) shl 16) or
    ((buf[offset + 3].toInt() and 0xFF) shl 24)
}

internal fun readUInt16LE(buf: ByteArray, offset: Int): Int {
  return (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
}

internal fun readLongLE(buf: ByteArray, offset: Int): Long {
  return (readIntLE(buf, offset).toLong() and 0xFFFFFFFFL) or
    ((readIntLE(buf, offset + 4).toLong() and 0xFFFFFFFFL) shl 32)
}

internal fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
  buf[offset] = (value and 0xFF).toByte()
  buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
  buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
  buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}
