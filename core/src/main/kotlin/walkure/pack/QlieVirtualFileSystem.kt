package walkure.pack

import walkure.crypto.QlieCipher
import walkure.crypto.QlieKeyExtractor
import java.io.Closeable
import java.io.File

enum class HonorificsMode {
    DEFAULT, // Uses data10.pack
    HON,     // Uses data10.pack_HON if present
    NOHON    // Uses data10.pack_NOHON if present
}

/**
 * Multi-Archive Virtual File System for QLiE (`data0.pack` .. `data15.pack`).
 * Higher-numbered `.pack` archives override lower-numbered archives, matching
 * `WALKURE_ROMANZE.exe`'s patch and translation override hierarchy.
 */
class QlieVirtualFileSystem(
    val gameDir: File,
    val honorificsMode: HonorificsMode = HonorificsMode.DEFAULT
) : Closeable {
    val keyMaterial: QlieKeyExtractor.KeyMaterial = QlieKeyExtractor.loadFromGameDirectory(gameDir)
    val packs: List<QliePackReader>
    private val resolvedIndex: Map<String, Pair<QliePackReader, QliePackEntry>>

    init {
        val gameDataDir = when {
            File(gameDir, "GameData").isDirectory -> File(gameDir, "GameData")
            gameDir.listFiles()?.any { it.name.startsWith("data", ignoreCase = true) && it.name.endsWith(".pack", ignoreCase = true) } == true -> gameDir
            else -> File(gameDir, "GameData")
        }
        require(gameDataDir.isDirectory) { "Missing GameData directory at ${gameDataDir.absolutePath}" }

        val numberedPackRegex = Regex("""^data(\d+)\.pack$""", RegexOption.IGNORE_CASE)
        val discovered = gameDataDir.listFiles()
            ?.mapNotNull { file ->
                val m = numberedPackRegex.matchEntire(file.name) ?: return@mapNotNull null
                val num = m.groupValues[1].toInt()
                num to file
            }
            ?.sortedBy { it.first }
            ?: emptyList()

        val loadedPacks = ArrayList<QliePackReader>()
        val map = LinkedHashMap<String, Pair<QliePackReader, QliePackEntry>>()

        for ((num, file) in discovered) {
            val effectiveFile = if (num == 10) {
                when (honorificsMode) {
                    HonorificsMode.HON -> File(gameDataDir, "data10.pack_HON").takeIf { it.exists() } ?: file
                    HonorificsMode.NOHON -> File(gameDataDir, "data10.pack_NOHON").takeIf { it.exists() } ?: file
                    HonorificsMode.DEFAULT -> file
                }
            } else {
                file
            }

            val reader = QliePackReader(effectiveFile, keyMaterial)
            loadedPacks.add(reader)
            // Higher numbered packs overwrite lower numbered entries in `map`
            for (entry in reader.entries) {
                map[entry.normalizedPath] = reader to entry
            }
        }

        packs = loadedPacks
        resolvedIndex = map
    }

    fun totalMountedFiles(): Int = resolvedIndex.size

    fun listEntries(filterSubstring: String? = null): List<QliePackEntry> {
        val needle = filterSubstring?.let { QliePackReader.normalizeVfsPath(it) }
        return resolvedIndex.values
            .map { it.second }
            .filter { entry -> needle == null || entry.normalizedPath.contains(needle) }
    }

    fun exists(path: String): Boolean {
        return resolvedIndex.containsKey(QliePackReader.normalizeVfsPath(path))
    }

    fun resolveEntry(path: String): Pair<QliePackReader, QliePackEntry>? {
        val norm = QliePackReader.normalizeVfsPath(path)
        resolvedIndex[norm]?.let { return it }
        // Fallback suffix search if caller omitted top-level folder
        return resolvedIndex.entries.firstOrNull { (k, _) ->
            k.endsWith("\\$norm")
        }?.value
    }

    fun readBytes(path: String, verifyHash: Boolean = false): ByteArray {
        val (reader, entry) = resolveEntry(path)
            ?: throw IllegalArgumentException("VFS file not found: $path")
        return reader.readEntry(entry, verifyHash)
    }

    fun readTextShiftJis(path: String): String {
        val bytes = readBytes(path)
        return String(bytes, QlieCipher.SHIFT_JIS)
    }

    override fun close() {
        packs.forEach { it.close() }
    }
}
