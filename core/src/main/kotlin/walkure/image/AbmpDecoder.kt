package walkure.image

import com.badlogic.gdx.graphics.Pixmap
import walkure.compression.QlieCompression
import walkure.crypto.QlieCipher
import walkure.crypto.readIntLE
import walkure.crypto.readUInt16LE
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Parser and Image Extractor for QLiE `abmp10`, `abmp11`, and `abmp12` (`.b`) UI/animation containers.
 * Reverse-engineered from `WALKURE_ROMANZE.exe` at `0x004e7300` - `0x004e7800` and `0x004e5da8` - `0x004e6050`.
 *
 * Supports recursive `abmp10`/`11`/`12` sub-containers (`imgType == 5` or `6` with `"abmp1*"` magic, as used in `スクリプト\title\back.b`)
 * and `1PC\xFF` BPE-compressed `abdata` layout payloads.
 */
object AbmpDecoder {

    data class AbmpImageLayer(
        val index: Int,
        val subVersion: Int,
        val name: String,
        val filename: String,
        val imageType: Int,
        val extensionHint: String,
        val offsetX: Int,
        val offsetY: Int,
        val extra: Int,
        val payload: ByteArray,
        val nestedAbmp: AbmpContainer? = null
    ) {
        fun decodePixmap(): Pixmap? {
            if (payload.isEmpty()) return null
            if (nestedAbmp != null) {
                return nestedAbmp.allFlattenedImages().firstNotNullOfOrNull { it.decodePixmap() }
            }
            if (ArgbDecoder.isArgbData(payload)) {
                return ArgbDecoder.decodePixmap(payload)
            }
            if (isDpngData(payload)) {
                return runCatching { Pixmap(payload, 48, payload.size - 48) }.getOrNull()
            }
            return runCatching { Pixmap(payload, 0, payload.size) }.getOrNull()
        }

        fun decodeBufferedImage(): BufferedImage? {
            if (payload.isEmpty()) return null
            if (nestedAbmp != null) {
                return nestedAbmp.allFlattenedImages().firstNotNullOfOrNull { it.decodeBufferedImage() }
            }
            if (ArgbDecoder.isArgbData(payload)) {
                return ArgbDecoder.decode(payload)
            }
            if (isDpngData(payload)) {
                return runCatching { ImageIO.read(ByteArrayInputStream(payload, 48, payload.size - 48)) }.getOrNull()
            }
            return runCatching { ImageIO.read(ByteArrayInputStream(payload)) }.getOrNull()
        }
    }

    fun isDpngData(bytes: ByteArray): Boolean {
        return bytes.size >= 56 &&
                bytes[0] == 'D'.code.toByte() &&
                bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() &&
                bytes[3] == 'G'.code.toByte()
    }

    data class AbmpContainer(
        val magic: String,
        val version: Int,
        val abdataTag: String,
        val abdataBytes: ByteArray,
        val decompressedAbdataBytes: ByteArray,
        val images: List<AbmpImageLayer>
    ) {
        /**
         * Returns all leaf image layers, flattening any nested `abmp10/11/12` sub-containers.
         */
        fun allFlattenedImages(): List<AbmpImageLayer> {
            val result = ArrayList<AbmpImageLayer>()
            fun visit(container: AbmpContainer, prefix: String) {
                for (layer in container.images) {
                    val qualifiedName = if (prefix.isEmpty()) layer.name else "${prefix}_${layer.name}"
                    if (layer.nestedAbmp != null) {
                        visit(layer.nestedAbmp, qualifiedName)
                    } else if (layer.payload.isNotEmpty()) {
                        result.add(layer.copy(name = qualifiedName))
                    }
                }
            }
            visit(this, "")
            return result
        }
    }

    fun isAbmpData(data: ByteArray): Boolean {
        if (data.size < 36) return false
        val magic = String(data, 0, 6, Charsets.US_ASCII)
        return magic == "abmp10" || magic == "abmp11" || magic == "abmp12"
    }

    fun parse(data: ByteArray): AbmpContainer {
        require(isAbmpData(data)) { "Not a valid abmp10/11/12 container" }
        val magic = String(data, 0, 16, Charsets.US_ASCII).trimEnd('\u0000')
        val version = magic.removePrefix("abmp").toIntOrNull() ?: 10

        val abdataTag = String(data, 16, 16, Charsets.US_ASCII).trimEnd('\u0000')
        val abdataSize = readIntLE(data, 32)
        require(abdataSize >= 0 && 36 + abdataSize <= data.size) {
            "Invalid abdata size $abdataSize in $magic"
        }
        val abdataBytes = data.copyOfRange(36, 36 + abdataSize)
        val decompressedAbdata = if (QlieCompression.isCompressed(abdataBytes)) {
            runCatching { QlieCompression.decompress1PC(abdataBytes) }.getOrDefault(abdataBytes)
        } else {
            abdataBytes
        }

        var pos = 36 + abdataSize
        val images = ArrayList<AbmpImageLayer>()

        while (pos + 16 <= data.size) {
            val sectionTag = String(data, pos, 16, Charsets.US_ASCII).trimEnd('\u0000')
            pos += 16

            if (sectionTag == "abimage10") {
                if (pos >= data.size) break
                val frameCount = data[pos].toInt() and 0xFF
                pos += 1

                for (i in 0 until frameCount) {
                    if (pos + 16 > data.size) break
                    val imgMagic = String(data, pos, 16, Charsets.US_ASCII).trimEnd('\u0000')
                    val subVer = when (imgMagic) {
                        "abimgdat10" -> 10
                        "abimgdat11" -> 11
                        "abimgdat12" -> 12
                        "abimgdat13" -> 13
                        else -> break
                    }
                    pos += 16

                    val nameLen = readUInt16LE(data, pos)
                    pos += 2
                    val name = String(data, pos, nameLen, QlieCipher.SHIFT_JIS)
                    pos += nameLen

                    val filename = if (subVer >= 11) {
                        val fLen = readUInt16LE(data, pos)
                        pos += 2
                        val s = String(data, pos, fLen, QlieCipher.SHIFT_JIS)
                        pos += fLen
                        s
                    } else {
                        ""
                    }

                    val imgType = data[pos].toInt() and 0xFF
                    pos += 1

                    var offsetX = 0
                    var offsetY = 0
                    var extra = 0
                    if (subVer >= 12) {
                        offsetX = readIntLE(data, pos)
                        offsetY = readIntLE(data, pos + 4)
                        pos += 8
                    }
                    if (subVer >= 13) {
                        extra = readIntLE(data, pos)
                        pos += 4
                    }

                    val payloadSize = readIntLE(data, pos)
                    pos += 4
                    if (payloadSize < 0 || pos + payloadSize > data.size) break

                    val payload = data.copyOfRange(pos, pos + payloadSize)
                    pos += payloadSize

                    val nested = if (isAbmpData(payload)) {
                        runCatching { parse(payload) }.getOrNull()
                    } else {
                        null
                    }

                    val extHint = when {
                        nested != null -> ".b"
                        imgType == 0 -> ".bmp"
                        imgType == 1 -> ".jpg"
                        imgType == 2 || imgType == 3 -> ".png"
                        imgType == 4 -> ".argb"
                        imgType == 5 -> ".b"
                        imgType == 6 -> ".ogv"
                        imgType == 7 -> ".mdl"
                        else -> ".bin"
                    }

                    images.add(
                        AbmpImageLayer(
                            index = i,
                            subVersion = subVer,
                            name = name,
                            filename = filename,
                            imageType = imgType,
                            extensionHint = extHint,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            extra = extra,
                            payload = payload,
                            nestedAbmp = nested
                        )
                    )
                }
            } else {
                break
            }
        }

        return AbmpContainer(
            magic = magic,
            version = version,
            abdataTag = abdataTag,
            abdataBytes = abdataBytes,
            decompressedAbdataBytes = decompressedAbdata,
            images = images
        )
    }
}
