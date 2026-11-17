package walkure.crypto

/**
 * QLiE Engine Buffer Stream Cipher Reverse-engineered from `WALKURE_ROMANZE.exe`:
 * - Encrypt: `0x00487d7c`
 * - Decrypt: `0x00487dfc`
 * - Dispatcher: `0x00487e7c` (`cl == 0` encrypt, `cl == 1` decrypt)
 *
 * Used for `SystemData.dat` (`IMOSYSDATA12`), `SaveData%d.svd` (`IMOSAVE110`), and the 32-byte
 * header integrity signature `"we8wq8q9fdgv2dfg8tryt95hj5iukt5w"`.
 */
object QlieBufferCipher {
  const val DEFAULT_CODE_KEY: Int = 0x00B5A346

  fun parseCodeKey(codeKeyString: String?): Int {
    if (codeKeyString.isNullOrBlank()) return DEFAULT_CODE_KEY
    val cleaned = codeKeyString.trim().removePrefix("\$").removePrefix("0x").removePrefix("0X")
    return cleaned.toIntOrNull(16) ?: cleaned.toIntOrNull() ?: DEFAULT_CODE_KEY
  }

  /**
   * Exact Kotlin equivalent of `0x00487dfc` (decrypt when [encrypt] is false) and `0x00487d7c`
   * (encrypt when [encrypt] is true). Operates in-place on full 8-byte blocks; trailing `< 8` bytes
   * are left untouched.
   */
  fun cryptInPlace(
    data: ByteArray,
    offset: Int = 0,
    length: Int = data.size - offset,
    keySeed: Int = DEFAULT_CODE_KEY,
    encrypt: Boolean = false,
  ) {
    if (length < 8 || offset < 0 || offset + length > data.size) return
    val nQwords = length ushr 3
    val seed32 = (length + keySeed) xor -0x1368ac2 // 0xfec9753e

    var mm5Lo = seed32
    var mm5Hi = seed32
    val mm6Lo = -0x31db0add // 0xce24f523
    val mm6Hi = -0x31db0add
    var mm7Lo = -0x58c3a063 // 0xa73c5f9d
    var mm7Hi = -0x58c3a063

    var pos = offset
    for (i in 0 until nQwords) {
      // paddd mm7, mm6; pxor mm7, mm5
      mm7Lo = (mm7Lo + mm6Lo) xor mm5Lo
      mm7Hi = (mm7Hi + mm6Hi) xor mm5Hi

      val inLo = readIntLE(data, pos)
      val inHi = readIntLE(data, pos + 4)

      if (encrypt) {
        mm5Lo = inLo
        mm5Hi = inHi
        writeIntLE(data, pos, inLo xor mm7Lo)
        writeIntLE(data, pos + 4, inHi xor mm7Hi)
      } else {
        val plainLo = inLo xor mm7Lo
        val plainHi = inHi xor mm7Hi
        mm5Lo = plainLo
        mm5Hi = plainHi
        writeIntLE(data, pos, plainLo)
        writeIntLE(data, pos + 4, plainHi)
      }
      pos += 8
    }
  }

  fun decryptCopy(
    data: ByteArray,
    offset: Int = 0,
    length: Int = data.size - offset,
    keySeed: Int = DEFAULT_CODE_KEY,
  ): ByteArray =
    data.copyOfRange(offset, offset + length).also {
      cryptInPlace(it, 0, it.size, keySeed = keySeed, encrypt = false)
    }

  fun encryptCopy(
    data: ByteArray,
    offset: Int = 0,
    length: Int = data.size - offset,
    keySeed: Int = DEFAULT_CODE_KEY,
  ): ByteArray =
    data.copyOfRange(offset, offset + length).also {
      cryptInPlace(it, 0, it.size, keySeed = keySeed, encrypt = true)
    }
}
