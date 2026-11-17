package walkure.pack

import walkure.compression.QlieCompression
import walkure.crypto.QlieCipher
import walkure.crypto.QlieHash
import walkure.crypto.QlieKeyExtractor
import walkure.crypto.readIntLE
import walkure.crypto.readLongLE
import walkure.crypto.readUInt16LE
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

data class QliePackEntry(
    val packName: String,
    val index: Int,
    val path: String,
    val normalizedPath: String,
    val rawDecryptedNameBytes: ByteArray,
    val offset: Long,
    val storedSize: Int,
    val decompressedSize: Int,
    val isCompressed: Boolean,
    val encryptionMode: Int,
    val hash: Int
)

data class QliePackHeaderInfo(
    val packName: String,
    val magic: String,
    val version: Int,
    val fileCount: Int,
    val indexOffset: Long,
    val packKey: Int,
    val decryptedSignature: String?,
    val signatureValid: Boolean
)

/**
 * Reader for QLiE `FilePackVer1.0`, `FilePackVer2.0`, and `FilePackVer3.0` archives.
 * Reverse-engineered from `WALKURE_ROMANZE.exe` (`0x0048b9f4` - `0x0048c06c`).
 */
class QliePackReader(
    val packFile: File,
    private val keyMaterial: QlieKeyExtractor.KeyMaterial? = null
) : Closeable {
    private val raf = RandomAccessFile(packFile, "r")
    val headerInfo: QliePackHeaderInfo
    val entries: List<QliePackEntry>
    private val entriesByNormalizedPath: Map<String, QliePackEntry>

    init {
        val fileLen = raf.length()
        require(fileLen >= 28) { "Pack file too small: ${packFile.absolutePath}" }

        // Read 28-byte footer at fileLen - 28
        val footerBytes = ByteArray(28)
        raf.seek(fileLen - 28)
        raf.readFully(footerBytes)

        val magicStr = String(footerBytes, 0, 16, Charsets.US_ASCII).trimEnd('\u0000')
        val version = when (magicStr) {
            "FilePackVer1.0" -> 1
            "FilePackVer2.0" -> 2
            "FilePackVer3.0" -> 3
            else -> throw IllegalArgumentException("Unsupported QLiE pack magic '$magicStr' in ${packFile.name}")
        }

        val fileCount = readIntLE(footerBytes, 16)
        val indexOffset = readLongLE(footerBytes, 20)
        require(fileCount >= 0 && indexOffset in 0..fileLen) {
            "Invalid footer in ${packFile.name}: fileCount=$fileCount, indexOffset=$indexOffset"
        }

        val packKey: Int
        val decryptedSig: String?
        val sigValid: Boolean

        if (version >= 3) {
            require(fileLen >= 0x440) { "FilePackVer3.0 requires at least 0x440 bytes in ${packFile.name}" }
            val hdrBlock = ByteArray(0x440)
            raf.seek(fileLen - 0x440)
            raf.readFully(hdrBlock)

            val keyTable = hdrBlock.copyOfRange(0x24, 0x124)
            packKey = QlieHash.hashMmx(keyTable) and 0x0FFFFFFF
            val encSig = hdrBlock.copyOfRange(0x00, 0x20)
            decryptedSig = QlieCipher.decryptHeaderSignature(encSig, packKey)
            sigValid = decryptedSig == QlieCipher.EXPECTED_HEADER_SIGNATURE
        } else {
            packKey = 0xC4
            decryptedSig = null
            sigValid = true
        }

        headerInfo = QliePackHeaderInfo(
            packName = packFile.name,
            magic = magicStr,
            version = version,
            fileCount = fileCount,
            indexOffset = indexOffset,
            packKey = packKey,
            decryptedSignature = decryptedSig,
            signatureValid = sigValid
        )

        // Read index region between indexOffset and (fileLen - 0x440 for Ver3.0 or fileLen - 28 for Ver1.0)
        val indexEnd = if (version >= 3) (fileLen - 0x440).coerceAtLeast(indexOffset) else (fileLen - 28)
        val indexBufSize = (fileLen - indexOffset).toInt()
        val indexBuf = ByteArray(indexBufSize)
        raf.seek(indexOffset)
        raf.readFully(indexBuf)

        val parsedEntries = ArrayList<QliePackEntry>(fileCount)
        var pos = 0
        for (i in 0 until fileCount) {
            val nameLen = readUInt16LE(indexBuf, pos)
            pos += 2
            val rawName = indexBuf.copyOfRange(pos, pos + nameLen)
            pos += nameLen

            val decodedName = QlieCipher.decryptFileName(rawName, version, packKey)
            val decryptedNameBytes = decodedName.toByteArray(QlieCipher.SHIFT_JIS)

            val entryOffset = readLongLE(indexBuf, pos)
            val storedSize = readIntLE(indexBuf, pos + 8)
            val decompressedSize = readIntLE(indexBuf, pos + 12)
            val isCompressed = readIntLE(indexBuf, pos + 16) != 0
            val encMode = readIntLE(indexBuf, pos + 20)
            val entryHash = readIntLE(indexBuf, pos + 24)
            pos += 28

            parsedEntries.add(
                QliePackEntry(
                    packName = packFile.name,
                    index = i,
                    path = decodedName,
                    normalizedPath = normalizeVfsPath(decodedName),
                    rawDecryptedNameBytes = decryptedNameBytes,
                    offset = entryOffset,
                    storedSize = storedSize,
                    decompressedSize = decompressedSize,
                    isCompressed = isCompressed,
                    encryptionMode = encMode,
                    hash = entryHash
                )
            )
        }

        entries = parsedEntries
        entriesByNormalizedPath = parsedEntries.associateBy { it.normalizedPath }
    }

    fun findEntry(path: String): QliePackEntry? {
        return entriesByNormalizedPath[normalizeVfsPath(path)]
    }

    /**
     * Reads the raw stored bytes of an entry from disk without decrypting or decompressing.
     */
    @Synchronized
    fun readStoredBytes(entry: QliePackEntry): ByteArray {
        val buf = ByteArray(entry.storedSize)
        raf.seek(entry.offset)
        raf.readFully(buf)
        return buf
    }

    /**
     * Verifies the stored entry checksum (`0x00488834`) before decryption.
     */
    fun verifyStoredHash(entry: QliePackEntry): Boolean {
        if (entry.hash == 0) return true
        val raw = readStoredBytes(entry)
        return QlieHash.hashMmx(raw) == entry.hash
    }

    /**
     * Reads, decrypts, and (if flagged) decompresses an entry payload.
     */
    fun readEntry(entry: QliePackEntry, verifyHash: Boolean = false): ByteArray {
        val raw = readStoredBytes(entry)
        if (verifyHash && entry.hash != 0) {
            val computed = QlieHash.hashMmx(raw)
            check(computed == entry.hash) {
                "Checksum mismatch for ${entry.path} in ${entry.packName}: expected=0x${entry.hash.toUInt().toString(16)}, actual=0x${computed.toUInt().toString(16)}"
            }
        }

        val decrypted = when (entry.encryptionMode) {
            0 -> raw
            4 -> {
                val keys = requireNotNull(keyMaterial) {
                    "KeyMaterial (DLL/key.fkey + WALKURE_ROMANZE.exe IconKeyImage) is required for Mode 4 entry ${entry.path}"
                }
                QlieCipher.decryptEntryMode4(
                    cipherData = raw,
                    decryptedNameBytes = entry.rawDecryptedNameBytes,
                    packKey = headerInfo.packKey,
                    fkeyBytes = keys.fkeyBytes,
                    iconBytes = keys.iconBytes
                )
            }
            else -> throw UnsupportedOperationException("Unsupported encryption mode ${entry.encryptionMode} on ${entry.path}")
        }

        return if (QlieCompression.isCompressed(decrypted)) {
            QlieCompression.decompress1PC(decrypted)
        } else {
            decrypted
        }
    }

    override fun close() {
        raf.close()
    }

    companion object {
        fun normalizeVfsPath(path: String): String {
            return path
                .replace('/', '\\')
                .trimStart('\\')
                .lowercase()
        }
    }
}
