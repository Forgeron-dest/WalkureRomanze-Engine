package walkure

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import walkure.config.SystemDatParser
import walkure.crypto.QlieKeyExtractor
import walkure.gdx.WalkureGdxGame
import walkure.image.AbmpDecoder
import walkure.image.ArgbDecoder
import walkure.pack.HonorificsMode
import walkure.pack.QlieVirtualFileSystem
import walkure.script.AssetAliasResolver
import walkure.script.ScenarioParser
import walkure.script.ScenarioVm
import walkure.ui.WalkureDesktopApp
import java.io.File
import javax.imageio.ImageIO

/**
 * CLI & Desktop Runtime Entry Point for the Walkure Romanze Kotlin / LibGDX Engine.
 *
 * Commands:
 * - `verify [--game-dir PATH]`
 * - `export-keys [--game-dir PATH]`
 * - `list [--game-dir PATH] [--filter SUBSTR] [--limit N]`
 * - `extract <vfs-query> <output-dir> [--decode-images]`
 * - `inspect-script <vfs-query>`
 * - `play [--script <vfs-query>] [--honorifics HON|NOHON|DEFAULT] [--legacy-swing]`
 */
fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: "verify"
    val gameDir = resolveGameDirectory(args)
    val honorifics = resolveHonorificsMode(args)

    when (cmd) {
        "verify" -> runVerify(gameDir, honorifics)
        "export-keys" -> {
            val outFile = QlieKeyExtractor.exportPortableKeyFile(gameDir)
            println("Exported portable 512-byte key file -> ${outFile.absolutePath} (${outFile.length()} bytes)")
        }
        "list" -> runList(gameDir, honorifics, args)
        "extract" -> runExtract(gameDir, honorifics, args)
        "inspect-script" -> runInspectScript(gameDir, honorifics, args)
        "play" -> {
            val scriptQuery = findOptionValue(args, "--script")
            val debugMode = args.contains("--debug") || args.contains("--debug-hud")
            val vfs = QlieVirtualFileSystem(gameDir, honorifics)
            if (args.contains("--legacy-swing")) {
                WalkureDesktopApp(vfs, scriptQuery ?: "sue_001_00.s").showWindow()
            } else {
                launchDesktop(vfs, scriptQuery, debugMode)
            }
        }
        else -> printUsage()
    }
}

fun launchDesktop(vfs: QlieVirtualFileSystem, initialScriptQuery: String? = null, showDebugHud: Boolean = false) {
    val gameDat = SystemDatParser.parseGameDat(vfs)
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle(gameDat.gameTitle)
        setWindowedMode(gameDat.screenWidth, gameDat.screenHeight)
        useVsync(true)
        setForegroundFPS(60)
    }
    Lwjgl3Application(WalkureGdxGame(vfs, initialScriptQuery, initialShowDebugHud = showDebugHud), config)
}

private fun resolveGameDirectory(args: Array<String>): File {
    findOptionValue(args, "--game-dir")?.let { return File(it).canonicalFile }
    val cwd = File(".").canonicalFile
    return generateSequence(cwd) { it.parentFile }
        .firstOrNull { File(it, "GameData").isDirectory }
        ?: cwd
}

private fun resolveHonorificsMode(args: Array<String>): HonorificsMode {
    return when (findOptionValue(args, "--honorifics")?.uppercase()) {
        "HON" -> HonorificsMode.HON
        "NOHON" -> HonorificsMode.NOHON
        else -> HonorificsMode.DEFAULT
    }
}

private fun findOptionValue(args: Array<String>, flag: String): String? {
    val idx = args.indexOf(flag)
    return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
}

private fun runVerify(gameDir: File, honorifics: HonorificsMode) {
    println("=== Walkure Romanze Kotlin / LibGDX Engine — Archive & Asset Verification ===")
    println("Game Directory : ${gameDir.absolutePath}")

    QlieVirtualFileSystem(gameDir, honorifics).use { vfs ->
        println("External Keys  : key.fkey (${vfs.keyMaterial.fkeyBytes.size} bytes), IconKeyImage (${vfs.keyMaterial.iconBytes.size} bytes)")
        println("--------------------------------------------------------------------------------")
        println(String.format("%-14s | %-15s | %7s | %-10s | %-10s", "Pack", "Magic", "Files", "PackKey", "Signature"))
        println("--------------------------------------------------------------------------------")
        for (reader in vfs.packs) {
            val info = reader.headerInfo
            val sigStatus = if (info.version >= 3) {
                if (info.signatureValid) "VALID" else "INVALID"
            } else {
                "N/A (v1.0)"
            }
            println(
                String.format(
                    "%-14s | %-15s | %7d | 0x%08x | %-10s",
                    info.packName,
                    info.magic,
                    info.fileCount,
                    info.packKey,
                    sigStatus
                )
            )
        }
        println("--------------------------------------------------------------------------------")
        println("Total unique virtual files mounted: ${vfs.totalMountedFiles()}")

        val gameDat = SystemDatParser.parseGameDat(vfs)
        val voiceChannels = SystemDatParser.parseVoiceChannels(vfs)
        val albumLists = SystemDatParser.parseAlbumLists(vfs)
        println("System Config  : '${gameDat.gameTitle}' (${gameDat.screenWidth}x${gameDat.screenHeight}, ${voiceChannels.size} voice channels, ${albumLists.values.sumOf { it.size }} CG groups)")

        val sueScriptPath = "シナリオ\\スィーリア\\sue_001_00.s"
        val sueText = vfs.readTextShiftJis(sueScriptPath)
        val parsed = ScenarioParser.parse(sueScriptPath, sueText)
        println("Parsed $sueScriptPath : ${parsed.nodes.size} AST nodes, ${parsed.dialogueNodes().size} dialogue blocks")
    }
}

private fun runList(gameDir: File, honorifics: HonorificsMode, args: Array<String>) {
    val filter = findOptionValue(args, "--filter")
    val limit = findOptionValue(args, "--limit")?.toIntOrNull() ?: 50
    QlieVirtualFileSystem(gameDir, honorifics).use { vfs ->
        val matches = vfs.listEntries(filter)
        println("Matched ${matches.size} entries (showing up to $limit):")
        for (entry in matches.take(limit)) {
            println(
                String.format(
                    "[%-11s] %-52s | stored=%8d | comp=%d | enc=%d",
                    entry.packName,
                    entry.path,
                    entry.storedSize,
                    if (entry.isCompressed) 1 else 0,
                    entry.encryptionMode
                )
            )
        }
    }
}

private fun runExtract(gameDir: File, honorifics: HonorificsMode, args: Array<String>) {
    require(args.size >= 3) { "Usage: extract <vfs-query> <output-dir> [--decode-images]" }
    val query = args[1]
    val outDir = File(args[2])
    val decodeImages = args.contains("--decode-images")
    outDir.mkdirs()

    QlieVirtualFileSystem(gameDir, honorifics).use { vfs ->
        val entries = vfs.listEntries(query)
        println("Extracting ${entries.size} matching entries to ${outDir.absolutePath}...")
        for (entry in entries) {
            val data = vfs.readBytes(entry.path)
            val relPath = entry.path.replace('\\', '/')
            val targetFile = File(outDir, relPath)
            targetFile.parentFile?.mkdirs()

            if (decodeImages && ArgbDecoder.isArgbData(data)) {
                val img = ArgbDecoder.decode(data)
                val pngFile = File(targetFile.parentFile, targetFile.nameWithoutExtension + ".png")
                ImageIO.write(img, "PNG", pngFile)
                println("Decoded ARGB -> ${pngFile.path} (${img.width}x${img.height})")
            } else if (decodeImages && AbmpDecoder.isAbmpData(data)) {
                val abmp = AbmpDecoder.parse(data)
                targetFile.writeBytes(data)
                for (layer in abmp.allFlattenedImages()) {
                    val img = layer.decodeBufferedImage() ?: continue
                    val pngFile = File(targetFile.parentFile, "${targetFile.nameWithoutExtension}_${layer.index}_${layer.name}.png")
                    ImageIO.write(img, "PNG", pngFile)
                    println("Decoded ABMP layer -> ${pngFile.path} (${img.width}x${img.height})")
                }
            } else {
                targetFile.writeBytes(data)
                println("Extracted -> ${targetFile.path} (${data.size} bytes)")
            }
        }
    }
}

private fun runInspectScript(gameDir: File, honorifics: HonorificsMode, args: Array<String>) {
    val query = args.getOrNull(1) ?: "sue_001_00.s"
    QlieVirtualFileSystem(gameDir, honorifics).use { vfs ->
        val (_, entry) = vfs.resolveEntry(query)
            ?: error("Could not find scenario matching '$query'")
        val content = vfs.readTextShiftJis(entry.path)
        val script = ScenarioParser.parse(entry.path, content)
        val resolver = AssetAliasResolver(vfs)

        if (args.contains("--raw")) {
            println("--- Raw Script Content ---")
            println(content)
            return
        }

        println("=== Scenario Inspection: ${entry.path} (from ${entry.packName}) ===")
        println("Total AST Nodes : ${script.nodes.size}")
        println("Dialogue Blocks : ${script.dialogueNodes().size}")
        println("AVG Commands    : ${script.commandNodes().size}")
        println("Jump Labels     : ${script.labelMap().keys}")
        println("\n--- First 10 Dialogue Steps via ScenarioVm ---")

        val vm = ScenarioVm(script, resolver)
        for (step in 1..10) {
            if (vm.isFinished()) break
            val snap = vm.stepToNextStop()
            val speakerTag = snap.currentSpeaker?.let { "[$it] " } ?: ""
            val voiceTag = snap.currentVoiceVfsPath?.let { " (voice=$it)" } ?: ""
            val bgmTag = snap.currentBgmVfsPath?.let { " [bgm=$it]" } ?: ""
            println("$step. $speakerTag${snap.currentText.replace('\n', ' ')}$voiceTag$bgmTag")
        }
    }
}

private fun printUsage() {
    println(
        """
        Walkure Romanze — Kotlin / LibGDX + KTX Visual Novel Engine & QLiE Toolkit
        Usage:
          verify [--game-dir PATH] [--honorifics DEFAULT|HON|NOHON]
          export-keys [--game-dir PATH]
          list [--filter SUBSTR] [--limit N]
          extract <vfs-query> <output-dir> [--decode-images]
          inspect-script <vfs-query>
          play [--script <vfs-query>] [--honorifics DEFAULT|HON|NOHON] [--legacy-swing]
        """.trimIndent()
    )
}
