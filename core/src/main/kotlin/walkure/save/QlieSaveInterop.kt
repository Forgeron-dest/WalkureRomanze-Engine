package walkure.save

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.Properties
import walkure.compression.QlieCompression
import walkure.crypto.QlieBufferCipher
import walkure.crypto.readIntLE
import walkure.crypto.readUInt16LE
import walkure.crypto.writeIntLE

/**
 * Binary interoperability with native `WALKURE_ROMANZE.exe` save files:
 * - `SystemData.dat` (`IMOSYSDATA12`, 0x130-byte header + Chunk1 vars + Chunk2 seen CGs + Chunk3
 *   read lines)
 * - `SaveData*.svd` (`IMOSAVE110`, 0x400-byte header + thumbnail + compressed/encrypted payload)
 *
 * Uses `QlieBufferCipher` (0x00487d7c / 0x00487dfc) and `QlieCompression` (`1PC\xFF`),
 * reverse-engineered from `WALKURE_ROMANZE.exe` (`0x0052dc78..0x0052e1e4`,
 * `0x005281a4..0x00528775`, `0x0052105c..0x0052119b`, `0x00525018..0x00525250`).
 */
object QlieSaveInterop {
  const val MAGIC_SYSDATA = "IMOSYSDATA12"
  const val MAGIC_SAVE = "IMOSAVE110"
  const val SIGNATURE_PLAIN = "we8wq8q9fdgv2dfg8tryt95hj5iukt5w"
  const val KOTLIN_TRAILER_MAGIC = "WKTS"

  private const val SYSDATA_HEADER_SIZE = 0x130 // 304 bytes
  private const val SAVE_HEADER_SIZE = 0x400 // 1024 bytes
  private const val SAVE_SIG_DISK_OFFSET = 0x29A // 0x2aa object offset - 0x10 header start
  private const val SAVE_VER_DISK_OFFSET = 0x2BA // 0x2ca object offset - 0x10 header start
  private const val SAVE_HAS_PAYLOAD_OFFSET = 0x10
  private const val SAVE_IS_COMPRESSED_OFFSET = 0x11

  private val SHIFT_JIS: Charset = runCatching {
    Charset.forName("MS932")
  }
    .getOrElse { Charsets.UTF_8 }

  data class NativeVarRecord(
    val name: String,
    val kind: Int,
    val flags: Int,
    val intValue: Int,
    val strValue: String,
  )

  data class ParsedSystemData(
    val vars: Map<String, NativeVarRecord>,
    val seenCgs: Set<String>,
    val readEntries: Set<String>,
    val kotlinProps: Properties?,
  )

  data class ParsedSaveSlot(
    val headerCaption: String,
    val headerTimestamp: String,
    val detectedScriptPath: String?,
    val detectedDialogue: String?,
    val thumbnailBytes: ByteArray?,
    val vars: Map<String, NativeVarRecord>,
    val kotlinProps: Properties?,
  )

  fun parseSystemData(
    bytes: ByteArray,
    keySeed: Int = QlieBufferCipher.DEFAULT_CODE_KEY,
  ): ParsedSystemData? {
    if (bytes.size < SYSDATA_HEADER_SIZE + 12) return null
    val magic = String(bytes, 0, MAGIC_SYSDATA.length, Charsets.US_ASCII)
    if (magic != MAGIC_SYSDATA) return null

    val sigDec = QlieBufferCipher.decryptCopy(bytes, 0x10, 0x20, keySeed)
    if (String(sigDec, Charsets.US_ASCII) != SIGNATURE_PLAIN) return null

    var pos = SYSDATA_HEADER_SIZE
    fun readChunk(): ByteArray? {
      if (pos + 4 > bytes.size) return null
      val len = readIntLE(bytes, pos)
      pos += 4
      if (len < 0 || pos + len > bytes.size) return null
      val chunk = bytes.copyOfRange(pos, pos + len)
      pos += len
      return chunk
    }

    val chunk1Raw = readChunk() ?: return null
    val chunk1Dec = QlieBufferCipher.decryptCopy(chunk1Raw, 0, chunk1Raw.size, keySeed)
    val chunk1Plain =
      if (QlieCompression.isCompressed(chunk1Dec)) {
        runCatching { QlieCompression.decompress1PC(chunk1Dec) }.getOrDefault(chunk1Dec)
      } else {
        chunk1Dec
      }
    val vars = parseVarStore(chunk1Plain)

    val chunk2Raw = readChunk()
    val seenCgs = if (chunk2Raw != null) parseStringListChunk(chunk2Raw) else emptySet()

    val chunk3Raw = readChunk()
    val readEntries = if (chunk3Raw != null) parseStringListChunk(chunk3Raw) else emptySet()

    val kotlinProps = extractKotlinTrailer(bytes, pos)
    return ParsedSystemData(
      vars = vars,
      seenCgs = seenCgs,
      readEntries = readEntries,
      kotlinProps = kotlinProps,
    )
  }

  fun writeSystemData(
    vars: List<NativeVarRecord>,
    seenCgs: Collection<String>,
    readEntries: Collection<String>,
    kotlinProps: Properties,
    keySeed: Int = QlieBufferCipher.DEFAULT_CODE_KEY,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    val header = ByteArray(SYSDATA_HEADER_SIZE)
    val magicBytes = MAGIC_SYSDATA.toByteArray(Charsets.US_ASCII)
    System.arraycopy(magicBytes, 0, header, 0, magicBytes.size)
    val sigEnc =
      QlieBufferCipher.encryptCopy(
        SIGNATURE_PLAIN.toByteArray(Charsets.US_ASCII),
        keySeed = keySeed,
      )
    System.arraycopy(sigEnc, 0, header, 0x10, sigEnc.size)
    out.write(header)

    fun writeInt(v: Int) {
      out.write(v and 0xFF)
      out.write((v ushr 8) and 0xFF)
      out.write((v ushr 16) and 0xFF)
      out.write((v ushr 24) and 0xFF)
    }

    val varBytes = serializeVarStore(vars)
    val varCompressed = QlieCompression.compress1PC(varBytes)
    val varEncrypted = QlieBufferCipher.encryptCopy(varCompressed, keySeed = keySeed)
    writeInt(varEncrypted.size)
    out.write(varEncrypted)

    val cgChunk = serializeStringListChunk(seenCgs)
    writeInt(cgChunk.size)
    out.write(cgChunk)

    val readChunk = serializeStringListChunk(readEntries)
    writeInt(readChunk.size)
    out.write(readChunk)

    appendKotlinTrailer(out, kotlinProps)
    return out.toByteArray()
  }

  fun parseSaveFile(
    bytes: ByteArray,
    keySeed: Int = QlieBufferCipher.DEFAULT_CODE_KEY,
  ): ParsedSaveSlot? {
    if (bytes.size < SAVE_HEADER_SIZE + 8) return null
    val magic = String(bytes, 0, MAGIC_SAVE.length, Charsets.US_ASCII)
    if (magic != MAGIC_SAVE) return null

    val sigDec = QlieBufferCipher.decryptCopy(bytes, SAVE_SIG_DISK_OFFSET, 0x20, keySeed)
    if (String(sigDec, Charsets.US_ASCII) != SIGNATURE_PLAIN) return null

    val version = readIntLE(bytes, SAVE_VER_DISK_OFFSET)
    if (version > 5) return null

    val hasPayload = (bytes[SAVE_HAS_PAYLOAD_OFFSET].toInt() and 0xFF) != 0
    val isCompressed = (bytes[SAVE_IS_COMPRESSED_OFFSET].toInt() and 0xFF) != 0

    var pos = SAVE_HEADER_SIZE
    val thumbLen = readIntLE(bytes, pos)
    pos += 4
    if (thumbLen < 0 || pos + thumbLen > bytes.size) return null
    val thumbBytes = if (thumbLen > 0) bytes.copyOfRange(pos, pos + thumbLen) else null
    pos += thumbLen

    if (pos + 4 > bytes.size) return null
    val payloadLen = readIntLE(bytes, pos)
    pos += 4
    if (payloadLen < 0 || pos + payloadLen > bytes.size) return null
    val rawPayload = bytes.copyOfRange(pos, pos + payloadLen)
    pos += payloadLen

    val decPayload =
      if (hasPayload) {
        QlieBufferCipher.decryptCopy(rawPayload, 0, rawPayload.size, keySeed)
      } else {
        rawPayload
      }
    val plainPayload =
      if (isCompressed && QlieCompression.isCompressed(decPayload)) {
        runCatching { QlieCompression.decompress1PC(decPayload) }.getOrDefault(decPayload)
      } else {
        decPayload
      }

    val kotlinProps = extractKotlinTrailer(bytes, pos)
    val asciiHeaderStrings = extractReadableStrings(bytes, 0x20, SAVE_SIG_DISK_OFFSET)
    val payloadStrings =
      extractReadableStrings(plainPayload, 0, minOf(plainPayload.size, 64 * 1024))
    val detectedScript = payloadStrings.firstOrNull {
      it.endsWith(".s", ignoreCase = true) &&
        (it.contains("\\") || it.contains("/") || it.length >= 5)
    }
    val headerTimestamp =
      asciiHeaderStrings.firstOrNull { it.contains("/") && it.contains(":") } ?: ""
    val headerCaption =
      asciiHeaderStrings.firstOrNull { it != headerTimestamp && it.length >= 2 } ?: ""
    val detectedDialogue = payloadStrings.lastOrNull {
      it != detectedScript &&
        it.length >= 4 &&
        !it.endsWith(".png") &&
        !it.endsWith(".b") &&
        !it.endsWith(".ogg")
    }

    val vars = parseVarStore(plainPayload)
    return ParsedSaveSlot(
      headerCaption = headerCaption,
      headerTimestamp = headerTimestamp,
      detectedScriptPath = detectedScript,
      detectedDialogue = detectedDialogue,
      thumbnailBytes = thumbBytes,
      vars = vars,
      kotlinProps = kotlinProps,
    )
  }

  fun writeSaveFile(
    meta: SaveSlotMetadata,
    thumbnailBytes: ByteArray?,
    kotlinProps: Properties,
    keySeed: Int = QlieBufferCipher.DEFAULT_CODE_KEY,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    val header = ByteArray(SAVE_HEADER_SIZE)
    val magicBytes = MAGIC_SAVE.toByteArray(Charsets.US_ASCII)
    System.arraycopy(magicBytes, 0, header, 0, magicBytes.size)
    header[SAVE_HAS_PAYLOAD_OFFSET] = 1
    header[SAVE_IS_COMPRESSED_OFFSET] = 1

    // Store readable caption & timestamp in header region before signature (0x40..0x280)
    val tsBytes = meta.realTimestamp.toByteArray(Charsets.US_ASCII)
    if (tsBytes.isNotEmpty() && tsBytes.size <= 32) {
      System.arraycopy(tsBytes, 0, header, 0x40, tsBytes.size)
    }
    val capBytes = "${meta.inGameDate} ${meta.routeName}".trim().toByteArray(SHIFT_JIS)
    if (capBytes.isNotEmpty() && capBytes.size <= 128) {
      System.arraycopy(capBytes, 0, header, 0x80, capBytes.size)
    }

    val sigEnc =
      QlieBufferCipher.encryptCopy(
        SIGNATURE_PLAIN.toByteArray(Charsets.US_ASCII),
        keySeed = keySeed,
      )
    System.arraycopy(sigEnc, 0, header, SAVE_SIG_DISK_OFFSET, sigEnc.size)
    writeIntLE(header, SAVE_VER_DISK_OFFSET, 5)
    out.write(header)

    fun writeInt(v: Int) {
      out.write(v and 0xFF)
      out.write((v ushr 8) and 0xFF)
      out.write((v ushr 16) and 0xFF)
      out.write((v ushr 24) and 0xFF)
    }

    val thumb = thumbnailBytes ?: ByteArray(0)
    writeInt(thumb.size)
    if (thumb.isNotEmpty()) {
      out.write(thumb)
    }

    val vars = buildList {
      meta.routeFlags.forEach { (k, v) ->
        add(NativeVarRecord(name = k, kind = 1, flags = 0, intValue = v, strValue = ""))
      }
      add(
        NativeVarRecord(
          "ScriptName",
          kind = 2,
          flags = 0,
          intValue = meta.instructionPointer,
          strValue = meta.scriptPath,
        ),
      )
      add(
        NativeVarRecord("SaveDate", kind = 2, flags = 0, intValue = 0, strValue = meta.inGameDate),
      )
      add(
        NativeVarRecord("RouteName", kind = 2, flags = 0, intValue = 0, strValue = meta.routeName),
      )
    }
    val payloadPlain = serializeVarStore(vars)
    val payloadCompressed = QlieCompression.compress1PC(payloadPlain)
    val payloadEncrypted = QlieBufferCipher.encryptCopy(payloadCompressed, keySeed = keySeed)
    writeInt(payloadEncrypted.size)
    out.write(payloadEncrypted)

    appendKotlinTrailer(out, kotlinProps)
    return out.toByteArray()
  }

  /**
   * Resolves QLiE `SaveData/SavePath.txt` (`SavePath=<MYDOCUMENTS>\Ricotta_ワルキューレロマンツェ\`) to
   * candidate native save directories on Linux / Wine / Windows / Android.
   */
  fun resolveNativeSaveDirectories(gameDir: File): List<File> {
    val candidates = LinkedHashSet<File>()
    val localSaveDir = File(gameDir, "SaveData")
    val savePathTxt = File(localSaveDir, "SavePath.txt")
    val home = System.getProperty("user.home").orEmpty()
    val user = System.getProperty("user.name").orEmpty()

    val myDocRoots = buildList {
      if (home.isNotBlank()) {
        add(File(home, "Documents"))
        add(File(home, "My Documents"))
        if (user.isNotBlank()) {
          add(File(home, ".wine/drive_c/users/$user/Documents"))
          add(File(home, ".wine/drive_c/users/$user/My Documents"))
        }
      }
    }

    if (savePathTxt.exists()) {
      val text = runCatching { savePathTxt.readBytes().toString(SHIFT_JIS) }.getOrDefault("")
      for (line in text.lines()) {
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim()
        if (!key.equals("SavePath", ignoreCase = true)) continue
        val raw = line.substring(eq + 1).trim().trim('"')
        val expanded = raw.replace('\\', File.separatorChar).trimEnd(File.separatorChar)
        if (expanded.contains("<MYDOCUMENTS>", ignoreCase = true)) {
          val tail = expanded.replace(Regex("(?i)<MYDOCUMENTS>[\\\\/]?"), "")
          for (docRoot in myDocRoots) {
            candidates.add(File(docRoot, tail))
          }
        } else if (expanded.isNotEmpty()) {
          val f = File(expanded)
          candidates.add(if (f.isAbsolute) f else File(gameDir, expanded))
        }
      }
    }
    return candidates.filter { it.exists() && it.isDirectory }
  }

  fun findNativeSlotFile(dir: File, slotNumber: Int): File? {
    if (!dir.isDirectory) return null
    val names =
      listOf(
        String.format("SaveData%04d.svd", slotNumber),
        String.format("SaveData%03d.svd", slotNumber),
        String.format("SaveData%02d.svd", slotNumber),
        "SaveData$slotNumber.svd",
      )
    return names.map { File(dir, it) }.firstOrNull { it.exists() && it.isFile }
  }

  private fun serializeVarStore(vars: List<NativeVarRecord>): ByteArray {
    val out = ByteArrayOutputStream()
    fun writeInt(v: Int) {
      out.write(v and 0xFF)
      out.write((v ushr 8) and 0xFF)
      out.write((v ushr 16) and 0xFF)
      out.write((v ushr 24) and 0xFF)
    }
    fun writeShort(v: Int) {
      out.write(v and 0xFF)
      out.write((v ushr 8) and 0xFF)
    }
    fun writeStr(s: String) {
      val b = s.toByteArray(SHIFT_JIS)
      val len = minOf(b.size, 0xFFFF)
      writeShort(len)
      if (len > 0) out.write(b, 0, len)
    }

    writeInt(vars.size)
    for (v in vars) {
      writeShort(0) // +0x28
      writeShort(0) // +0x2a
      writeInt(v.intValue) // +0x2c
      out.write(v.kind and 0xFF) // kind (3 => no sub-object payload, 0/1/2 otherwise)
      out.write(v.flags and 0xFF) // flag18
      writeInt(v.intValue) // +0x1c
      writeStr(v.name)
      writeStr(v.strValue)
    }
    return out.toByteArray()
  }

  private fun parseVarStore(data: ByteArray): Map<String, NativeVarRecord> {
    val map = LinkedHashMap<String, NativeVarRecord>()
    if (data.size < 4) return map
    var pos = 0
    val count = readIntLE(data, pos)
    pos += 4
    if (count !in 0..50000) return map

    fun readStr(): String? {
      if (pos + 2 > data.size) return null
      val len = readUInt16LE(data, pos)
      pos += 2
      if (pos + len > data.size) return null
      val s = if (len > 0) String(data, pos, len, SHIFT_JIS) else ""
      pos += len
      return s
    }

    for (i in 0 until count) {
      if (pos + 14 > data.size) break
      pos += 4 // f28, f2a
      val hashOrVal = readIntLE(data, pos)
      pos += 4
      val kind = data[pos].toInt() and 0xFF
      val flags = data[pos + 1].toInt() and 0xFF
      pos += 2
      val intVal = readIntLE(data, pos)
      pos += 4
      val name = readStr() ?: break
      val strVal = readStr() ?: break
      if (name.isNotBlank()) {
        val effectiveInt = if (kind == 1 || kind == 3) intVal else hashOrVal
        map[name] = NativeVarRecord(name, kind, flags, effectiveInt, strVal)
      }
      if (kind != 3 && kind != 0 && kind != 1 && kind != 2) {
        break // unknown sub-object serialization
      }
    }
    return map
  }

  private fun parseStringListChunk(chunk: ByteArray): Set<String> {
    val set = LinkedHashSet<String>()
    if (chunk.size < 8) return set
    val text = extractReadableStrings(chunk, 0, chunk.size)
    for (s in text) {
      if (s.length >= 3) set.add(s)
    }
    return set
  }

  private fun serializeStringListChunk(items: Collection<String>): ByteArray {
    val out = ByteArrayOutputStream()
    val entries = items.toList()
    fun writeInt(v: Int) {
      out.write(v and 0xFF)
      out.write((v ushr 8) and 0xFF)
      out.write((v ushr 16) and 0xFF)
      out.write((v ushr 24) and 0xFF)
    }
    writeInt(entries.size)
    for (item in entries) {
      val b = item.toByteArray(Charsets.UTF_8)
      val len = minOf(b.size, 0xFFFF)
      out.write(len and 0xFF)
      out.write((len ushr 8) and 0xFF)
      out.write(b, 0, len)
    }
    return out.toByteArray()
  }

  private fun extractReadableStrings(data: ByteArray, start: Int, end: Int): List<String> {
    val out = ArrayList<String>()
    val safeEnd = minOf(end, data.size)
    var i = maxOf(0, start)
    val buf = ByteArrayOutputStream()
    while (i < safeEnd) {
      val b = data[i].toInt() and 0xFF
      if (b in 0x20..0x7E || b >= 0x81) {
        buf.write(b)
      } else {
        if (buf.size() >= 3) {
          val s = runCatching { buf.toString(SHIFT_JIS.name()) }.getOrDefault("")
          if (s.isNotBlank()) out.add(s)
        }
        buf.reset()
      }
      i++
    }
    if (buf.size() >= 3) {
      val s = runCatching { buf.toString(SHIFT_JIS.name()) }.getOrDefault("")
      if (s.isNotBlank()) out.add(s)
    }
    return out
  }

  private fun appendKotlinTrailer(out: ByteArrayOutputStream, props: Properties) {
    val propBytes = ByteArrayOutputStream().also { props.store(it, "WKTS") }.toByteArray()
    out.write(KOTLIN_TRAILER_MAGIC.toByteArray(Charsets.US_ASCII))
    val len = propBytes.size
    out.write(len and 0xFF)
    out.write((len ushr 8) and 0xFF)
    out.write((len ushr 16) and 0xFF)
    out.write((len ushr 24) and 0xFF)
    out.write(propBytes)
  }

  private fun extractKotlinTrailer(bytes: ByteArray, searchFrom: Int): Properties? {
    val magicBytes = KOTLIN_TRAILER_MAGIC.toByteArray(Charsets.US_ASCII)
    val start = maxOf(0, searchFrom)
    val limit = bytes.size - 8
    for (i in start..limit) {
      if (
        bytes[i] == magicBytes[0] &&
          bytes[i + 1] == magicBytes[1] &&
          bytes[i + 2] == magicBytes[2] &&
          bytes[i + 3] == magicBytes[3]
      ) {
        val len = readIntLE(bytes, i + 4)
        if (len in 1..(bytes.size - (i + 8))) {
          return runCatching {
            Properties().apply { load(bytes.inputStream(i + 8, len)) }
          }
            .getOrNull()
        }
      }
    }
    return null
  }
}
