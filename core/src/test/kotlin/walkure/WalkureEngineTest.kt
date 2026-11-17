package walkure

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import walkure.config.SystemDatParser
import walkure.crypto.QlieCipher
import walkure.crypto.QlieKeyExtractor
import walkure.gdx.QlieVfsFileHandle
import walkure.image.AbmpDecoder
import walkure.image.ArgbDecoder
import walkure.pack.HonorificsMode
import walkure.pack.QliePackReader
import walkure.pack.QlieVirtualFileSystem
import walkure.save.SaveManager
import walkure.script.AssetAliasResolver
import walkure.script.CampaignConductor
import walkure.script.ScenarioParser
import walkure.script.ScenarioVm
import walkure.video.VideoPlayerPlatform

/**
 * Automated Verification & Regression Suite for the Walkure Romanze Kotlin Engine. Exercises every
 * reverse-engineered binary format and runtime subsystem against `GameData/data*.pack`.
 */
class WalkureEngineTest {
  init {
    com.badlogic.gdx.utils.GdxNativesLoader.load()
  }

  private val gameDir: File = run {
    val cwd = File(".").canonicalFile
    generateSequence(cwd) { it.parentFile }.firstOrNull { File(it, "GameData").isDirectory }
      ?: error("GameData was not found in the current directory or its parents.")
  }

  @Test
  fun testExternalKeyExtractionAndPortableKeyFile() {
    val keys = QlieKeyExtractor.loadFromGameDirectory(gameDir)
    assertTrue(keys.fkeyBytes.size >= 256, "DLL/key.fkey must be at least 256 bytes")
    assertTrue(
      keys.iconBytes.size >= 256,
      "TForm1.IconKeyImage TIcon payload must be at least 256 bytes",
    )
    // Verify TIcon header bytes (00 00 01 00 02 00 40 40)
    assertEquals(0x00, keys.iconBytes[0].toInt() and 0xFF)
    assertEquals(0x00, keys.iconBytes[1].toInt() and 0xFF)
    assertEquals(0x01, keys.iconBytes[2].toInt() and 0xFF)
    assertEquals(0x00, keys.iconBytes[3].toInt() and 0xFF)
    assertEquals(0x02, keys.iconBytes[4].toInt() and 0xFF)
    assertEquals(0x00, keys.iconBytes[5].toInt() and 0xFF)
    assertEquals(0x40, keys.iconBytes[6].toInt() and 0xFF)
    assertEquals(0x40, keys.iconBytes[7].toInt() and 0xFF)

    // Verify portable 512-byte walkure.keys export
    val tempPortableFile = Files.createTempFile("walkure_test_", ".keys").toFile()
    try {
      QlieKeyExtractor.exportPortableKeyFile(gameDir, tempPortableFile)
      assertEquals(
        512L,
        tempPortableFile.length(),
        "Portable walkure.keys must be exactly 512 bytes",
      )
      val rawPortable = tempPortableFile.readBytes()
      assertContentEquals(keys.fkeyBytes.copyOfRange(0, 256), rawPortable.copyOfRange(0, 256))
      assertContentEquals(keys.iconBytes.copyOfRange(0, 256), rawPortable.copyOfRange(256, 512))
    } finally {
      tempPortableFile.delete()
    }
  }

  @Test
  fun testAllFilePackVer3SignaturesAndIndices() {
    val keys = QlieKeyExtractor.loadFromGameDirectory(gameDir)
    val expectedCounts =
      mapOf(
        "data0.pack" to 8,
        "data1.pack" to 20793,
        "data2.pack" to 6717,
        "data3.pack" to 2927,
        "data4.pack" to 368,
        "data5.pack" to 511,
        "data6.pack" to 79,
        "data7.pack" to 833,
        "data8.pack" to 157,
        "data9.pack" to 610,
      )

    for ((packName, expectedCount) in expectedCounts) {
      val file = File(gameDir, "GameData/$packName")
      QliePackReader(file, keys).use { reader ->
        assertEquals("FilePackVer3.0", reader.headerInfo.magic)
        assertEquals(3, reader.headerInfo.version)
        assertTrue(reader.headerInfo.signatureValid, "Header signature must be valid for $packName")
        assertEquals(QlieCipher.EXPECTED_HEADER_SIGNATURE, reader.headerInfo.decryptedSignature)
        assertEquals(expectedCount, reader.entries.size, "Entry count mismatch for $packName")
      }
    }
  }

  @Test
  fun testFilePackVer1EnglishTranslationArchives() {
    val ver1Packs =
      mapOf(
        "data10.pack" to 500,
        "data10.pack_HON" to 500,
        "data10.pack_NOHON" to 500,
        "data11.pack" to 116,
        "data15.pack" to 1,
      )

    for ((packName, expectedCount) in ver1Packs) {
      val file = File(gameDir, "GameData/$packName")
      QliePackReader(file, null).use { reader ->
        assertEquals("FilePackVer1.0", reader.headerInfo.magic)
        assertEquals(1, reader.headerInfo.version)
        assertEquals(0xC4, reader.headerInfo.packKey)
        assertEquals(expectedCount, reader.entries.size, "Entry count mismatch for $packName")
      }
    }
  }

  @Test
  fun testMode4DecryptionAnd1PCDecompressionInData0() {
    val keys = QlieKeyExtractor.loadFromGameDirectory(gameDir)
    QliePackReader(File(gameDir, "GameData/data0.pack"), keys).use { reader ->
      val keyEntry = reader.findEntry("pack_keyfile_kfueheish15538fa9or.key")
      assertNotNull(keyEntry)
      assertTrue(reader.verifyStoredHash(keyEntry), "MMX hash on stored keyfile must match")
      val decryptedKeyFile = reader.readEntry(keyEntry, verifyHash = true)
      assertContentEquals(
        keys.fkeyBytes.copyOfRange(0, 256),
        decryptedKeyFile.copyOfRange(0, 256),
        "Decrypted keyfile in data0.pack must match key.fkey",
      )

      val startupEntry = reader.findEntry("startup.s")
      assertNotNull(startupEntry)
      val startupBytes = reader.readEntry(startupEntry, verifyHash = true)
      assertEquals(startupEntry.decompressedSize, startupBytes.size)
      val startupText = String(startupBytes, QlieCipher.SHIFT_JIS)
      assertTrue(startupText.contains("\\sub"), "startup.s must contain \\sub directives")

      val sysStartupEntry = reader.findEntry("sysstartup.s")
      assertNotNull(sysStartupEntry)
      val sysStartupBytes = reader.readEntry(sysStartupEntry, verifyHash = true)
      assertEquals(sysStartupEntry.decompressedSize, sysStartupBytes.size)
      val sysStartupText = String(sysStartupBytes, QlieCipher.SHIFT_JIS)
      assertTrue(sysStartupText.contains("\\sub"), "sysStartup.s must contain \\sub directives")
    }
  }

  @Test
  fun testArgbSpriteDecodingFromData2() {
    val keys = QlieKeyExtractor.loadFromGameDirectory(gameDir)
    QliePackReader(File(gameDir, "GameData/data2.pack"), keys).use { reader ->
      val argbEntry = reader.entries.first { it.path.endsWith(".argb", ignoreCase = true) }
      val rawArgb = reader.readEntry(argbEntry, verifyHash = true)
      assertTrue(
        ArgbDecoder.isArgbData(rawArgb),
        "Entry ${argbEntry.path} must have ARGBSaveData1 header",
      )

      val hdr = ArgbDecoder.parseHeader(rawArgb)
      assertEquals(3, hdr.mode)
      assertTrue(hdr.rgbSize > 0 && hdr.alphaSize > 0)

      val image = ArgbDecoder.decode(rawArgb)
      assertTrue(image.width > 64 && image.height > 64, "Decoded sprite dimensions must be valid")

      val pixmap = ArgbDecoder.decodePixmap(rawArgb)
      assertEquals(image.width, pixmap.width)
      assertEquals(image.height, pixmap.height)

      // Verify sample pixels match between BufferedImage and Pixmap
      for (step in 0 until 50) {
        val x = (step * 37) % image.width
        val y = (step * 53) % image.height
        val imgP = image.getRGB(x, y) // ARGB
        val pixP = pixmap.getPixel(x, y) // RGBA
        val imgA = (imgP ushr 24) and 0xFF
        val pixA = pixP and 0xFF
        assertEquals(imgA, pixA, "Alpha mismatch at ($x, $y)")
      }
      pixmap.dispose()

      val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
      val hasTransparent = pixels.any { (it ushr 24) == 0 }
      val hasOpaque = pixels.any { (it ushr 24) > 200 }
      assertTrue(
        hasTransparent && hasOpaque,
        "Composited ARGB sprite must have both transparent and opaque pixels",
      )
    }
  }

  @Test
  fun testAbmpUiDecodingAndNestedFlatteningFromData15() {
    QliePackReader(File(gameDir, "GameData/data15.pack"), null).use { reader ->
      val entry = reader.findEntry("スクリプト\\title\\back.b")
      assertNotNull(entry)
      val rawAbmp = reader.readEntry(entry)
      assertTrue(AbmpDecoder.isAbmpData(rawAbmp))

      val container = AbmpDecoder.parse(rawAbmp)
      assertEquals("abmp12", container.magic)
      assertTrue(container.abdataBytes.isNotEmpty(), "abdata script payload must not be empty")
      assertTrue(
        container.images.isNotEmpty(),
        "back.b must contain embedded abimage10 image layers",
      )

      val flattened = container.allFlattenedImages()
      assertTrue(
        flattened.size > 15,
        "Flattened back.b container must include nested abmp12 sub-layers (got ${flattened.size})",
      )
      val firstDecoded = container.images.firstNotNullOfOrNull { it.decodeBufferedImage() }
      assertNotNull(
        firstDecoded,
        "First nested layer (back_haikei) in back.b must decode to a BufferedImage",
      )
      assertTrue(firstDecoded.width >= 1024 && firstDecoded.height >= 287)
    }
  }

  @Test
  fun testDayTransitionAndCalendarAliasResolution() {
    QlieVirtualFileSystem(gameDir, HonorificsMode.HON).use { vfs ->
      val aliasEntry = vfs.resolveEntry("ライブラリ\\AVGシステム\\リスト\\日付Alias.txt")?.second
      assertNotNull(aliasEntry, "日付Alias.txt must be mounted in VFS")
      val aliasText = vfs.readTextShiftJis(aliasEntry.path)
      assertTrue(aliasText.contains("04/16=日付_4月:日付_1:日付_6:日付_金,-12"))
      assertTrue(aliasText.contains("04/01=日付_4月:日付_1::日付_木,23"))

      // Verify DPNG assets used by the day transition
      val b0 = vfs.readBytes("画像\\日付背景b_0.png")
      assertTrue(AbmpDecoder.isDpngData(b0), "日付背景b_0.png must be recognized as DPNG")
      val m0 = vfs.readBytes("画像\\日付背景m_0.png")
      assertTrue(AbmpDecoder.isDpngData(m0), "日付背景m_0.png must be recognized as DPNG")

      // Test ScenarioVm triggering day eyecatch
      val scriptContent =
        """
        @@@ライブラリ\AVGシステム\header.s
        @@MAIN
        ^savedate,"04/16"
        \sub,@@!EyeCatchDay,self,"04/16"
        none,Takahiro,「Hello world」
        """
          .trimIndent()
      val parsed = ScenarioParser.parse("test_day.s", scriptContent)
      val vm = ScenarioVm(parsed, AssetAliasResolver(vfs))
      val snap = vm.stepToNextStop()
      assertEquals("04/16", snap.inGameDate)
      assertEquals("04/16", snap.triggeredDayEyeCatch)
      assertEquals("Takahiro", snap.currentSpeaker)
    }
  }

  @Test
  fun testSystemDatAndAlbumMetadataParsing() {
    QlieVirtualFileSystem(gameDir, HonorificsMode.HON).use { vfs ->
      val gameDat = SystemDatParser.parseGameDat(vfs)
      assertEquals(1024, gameDat.screenWidth)
      assertEquals(768, gameDat.screenHeight)
      assertEquals(103, gameDat.thumWidth)
      assertEquals(77, gameDat.thumHeight)

      val msgDat = SystemDatParser.parseSystemMessages(vfs)
      assertTrue(msgDat.appCloseText.isNotBlank())

      val voiceChannels = SystemDatParser.parseVoiceChannels(vfs)
      assertEquals(
        15,
        voiceChannels.size,
        "voicedata.dat must define 15 act[0..14] speaker channels",
      )
      assertTrue(voiceChannels.any { it.speakerNames.contains("美桜") })
      assertTrue(voiceChannels.any { it.speakerNames.contains("スィーリア") })

      val albumLists = SystemDatParser.parseAlbumLists(vfs)
      assertEquals(5, albumLists.size, "Album mode must have 5 tabs (Celia, Mio, Noel, Lisa, Etc)")
      assertTrue(albumLists["Celia"]!!.isNotEmpty(), "Celia album tab must contain CG groups")

      val fontData = SystemDatParser.parseFontData(vfs)
      assertEquals(8, fontData.fontSlots.size, "fontdata.dat must define 8 Font[0..7] slots")
      assertEquals("system_fix", fontData.rubyFontSlot)

      val avgSettings = SystemDatParser.parseAvgSettings(vfs)
      assertTrue(avgSettings.defaultBgFadeMs > 0)
      assertTrue(avgSettings.defaultSpriteFadeMs > 0)
      assertTrue(avgSettings.defaultSoundFadeMs > 0)
    }
  }

  @Test
  fun testCampaignConductorAndSaveManager() {
    val tempGameDir = Files.createTempDirectory("walkure_saves_").toFile()
    try {
      QlieVirtualFileSystem(gameDir, HonorificsMode.HON).use { vfs ->
        val saveManager = SaveManager(tempGameDir)
        val resolver = AssetAliasResolver(vfs)
        val conductor = CampaignConductor(vfs)

        assertTrue(
          conductor.commonRouteSequence.isNotEmpty(),
          "Common route sequence from route.s must not be empty",
        )
        val firstStage =
          conductor.commonRouteSequence.first() as CampaignConductor.CampaignStage.ScenarioFile
        assertTrue(
          firstStage.vfsPath.endsWith("kyo_001_00.s"),
          "Common route must start at kyo_001_00.s",
        )
        assertTrue(
          conductor.commonRouteSequence.any {
            it is CampaignConductor.CampaignStage.MoviePlayback && it.movieFileName == "VR_OP.ogv"
          },
          "Common route sequence must include VR_OP.ogv opening movie trigger",
        )

        // Test post-OP movie stage transition (VR_OP.ogv -> kyo_002_00.s)
        val afterOp =
          conductor.nextStageAfter("VR_OP.ogv") as? CampaignConductor.CampaignStage.ScenarioFile
        assertNotNull(afterOp, "nextStageAfter(VR_OP.ogv) must resolve to kyo_002_00.s")
        assertTrue(afterOp.vfsPath.endsWith("kyo_002_00.s"))

        // Test ^select1 choice parsing, mioflag++ increment, and ^jmp in kyo_005_02.s
        val kyo005Path = "シナリオ\\共通\\kyo_005_02.s"
        val kyo005Parsed = ScenarioParser.parse(kyo005Path, vfs.readTextShiftJis(kyo005Path))
        val kyo005Vm = ScenarioVm(kyo005Parsed, resolver) { conductor.evaluateBranch(it) }
        val choiceStop = kyo005Vm.stepToNextStop()
        assertEquals(
          2,
          choiceStop.pendingChoices.size,
          "kyo_005_02.s ^select1 must present 2 choices",
        )
        assertEquals("Stop Celia-senpai", choiceStop.pendingChoices[0].text)
        assertEquals("@@kyo_005_02_sel1_0", choiceStop.pendingChoices[0].targetLabel)
        assertEquals("Stop Mio", choiceStop.pendingChoices[1].text)
        assertEquals("@@kyo_005_02_sel1_1", choiceStop.pendingChoices[1].targetLabel)

        // Choosing Option 1 ("Stop Mio") must invoke callback and increment mioflag
        val afterChoice1 = kyo005Vm.selectChoice(1) { conductor.applyChoiceLabelEffect(it) }
        assertEquals(
          1,
          conductor.routeFlags["mioflag"],
          "Choosing Stop Mio must increment mioflag to 1",
        )
        assertTrue(
          afterChoice1.currentText.contains("M-Mio"),
          "Must jump to @@kyo_005_02_sel1_1 dialogue",
        )

        // Test ^branch1 evaluation in kyo_011_08.s when mioflag == 3 vs mioflag < 3
        val kyo011Path = "シナリオ\\共通\\kyo_011_08.s"
        val kyo011Parsed = ScenarioParser.parse(kyo011Path, vfs.readTextShiftJis(kyo011Path))
        conductor.routeFlags["mioflag"] = 3
        val mioBranchVm = ScenarioVm(kyo011Parsed, resolver) { conductor.evaluateBranch(it) }
        var mioStop = mioBranchVm.stepToNextStop()
        while (!mioBranchVm.isFinished() && mioStop.triggeredRouteJumpLabel == null) {
          mioStop = mioBranchVm.stepToNextStop()
        }
        assertEquals("@@kyo_011_08_Branch1_0", mioStop.triggeredRouteJumpLabel)
        val mioStage =
          conductor.resolveRouteJumpLabel(mioStop.triggeredRouteJumpLabel!!)
            as? CampaignConductor.CampaignStage.ScenarioFile
        assertNotNull(mioStage)
        assertTrue(
          mioStage.vfsPath.contains("mio_001_00.s"),
          "mioflag==3 at kyo_011_08 must enter Mio's route",
        )

        // Execute kyo_001_00.s and test looping SE, ^bg1 overlay, sticky ^chara, and SaveManager
        // Kidoku + Slot round-trip
        val scriptText = vfs.readTextShiftJis(firstStage.vfsPath)
        val parsed = ScenarioParser.parse(firstStage.vfsPath, scriptText)
        val vm = ScenarioVm(parsed, resolver)
        val firstSnap = vm.stepToNextStop()
        assertTrue(firstSnap.currentText.isNotBlank())
        assertTrue(
          firstSnap.triggeredSeVfsPath?.contains("馬_走り_11") == true,
          "kyo_001_00.s first stop must trigger 馬_走り_11 SE",
        )
        assertTrue(firstSnap.triggeredSeLoop, "kyo_001_00.s 馬_走り_11 must have loop=1")
        assertNotNull(
          firstSnap.backgroundVfsPath,
          "kyo_001_00.s first stop must have ^bg0 backgroundVfsPath",
        )

        // Step through kyo_001_00.s until ^se0,,,1000 stops the looping horse SFX, ^bg1,桜 sets
        // overlayBgVfsPath, and Mio's sticky ^chara updates expression
        var sawSeStop = false
        var sawOverlayBg = firstSnap.weatherEffect == "sakura"
        while (!vm.isFinished()) {
          val stepSnap = vm.stepToNextStop()
          if (stepSnap.stopSeRequested) sawSeStop = true
          if (stepSnap.weatherEffect == "sakura" && stepSnap.backgroundVfsPath != null)
            sawOverlayBg = true
        }
        assertTrue(sawSeStop, "kyo_001_00.s ^se0,,,1000 must request stopping SE")
        assertTrue(
          sawOverlayBg,
          "kyo_001_00.s ^bg1,桜 must enable sakura weather without clearing backgroundVfsPath",
        )

        // Test sticky ^chara retention in kyo_001_01.s and kyo_002_00.s when pos/dist are omitted
        // (,,,)
        val kyo00101Path = "シナリオ\\共通\\kyo_001_01.s"
        val kyo00101Vm =
          ScenarioVm(
            ScenarioParser.parse(kyo00101Path, vfs.readTextShiftJis(kyo00101Path)),
            resolver,
          )
        var sawStickyMio = false
        while (!kyo00101Vm.isFinished()) {
          val stepSnap = kyo00101Vm.stepToNextStop()
          if (
            stepSnap.activeSprites.values.any {
              it.vfsPath.contains("_d04_") && it.distCode == "u" && it.centerX == 99999
            }
          ) {
            sawStickyMio = true
          }
        }
        assertTrue(
          sawStickyMio,
          "Subsequent ^chara commands in kyo_001_01.s with omitted pos/dist must retain Mio's u-distance and FACE=99999",
        )

        assertTrue(!saveManager.isLineRead(firstSnap.scriptPath, firstSnap.instructionPointer))
        saveManager.markLineRead(firstSnap.scriptPath, firstSnap.instructionPointer)
        assertTrue(saveManager.isLineRead(firstSnap.scriptPath, firstSnap.instructionPointer))

        saveManager.saveSlot(
          slotNumber = 1,
          snap = firstSnap,
          inGameDate = firstSnap.inGameDate,
          routeName = "スィーリア",
          routeFlags = mapOf("suiflag" to 2),
        )
        val loadedSlot = saveManager.loadSlot(1)
        assertNotNull(loadedSlot)
        assertEquals(firstSnap.scriptPath, loadedSlot.scriptPath)
        assertEquals("スィーリア", loadedSlot.routeName)
        assertEquals(2, loadedSlot.routeFlags["suiflag"])
      }
    } finally {
      tempGameDir.deleteRecursively()
    }
  }

  @Test
  fun testVirtualFileSystemAndScenarioVmExecution() {
    QlieVirtualFileSystem(gameDir, HonorificsMode.HON).use { vfs ->
      assertTrue(
        vfs.totalMountedFiles() > 30000,
        "VFS must mount >30,000 game files across data0..data15",
      )

      val scriptPath = "シナリオ\\スィーリア\\sue_001_00.s"
      val text = vfs.readTextShiftJis(scriptPath)
      val script = ScenarioParser.parse(scriptPath, text)

      assertTrue(script.nodes.size > 100, "sue_001_00.s should parse into >100 AST nodes")
      assertTrue(script.dialogueNodes().isNotEmpty(), "sue_001_00.s must contain dialogue blocks")

      val resolver = AssetAliasResolver(vfs)
      val vm = ScenarioVm(script, resolver)
      val firstStop = vm.stepToNextStop()
      assertEquals("Takahiro", firstStop.currentSpeaker)
      assertEquals("音楽\\bgm33.ogg", firstStop.currentBgmVfsPath)
      assertTrue(
        firstStop.currentText.isNotBlank(),
        "First VM dialogue stop must have non-blank text",
      )
      assertEquals(1, vm.backlog.size)

      // Verify QlieVfsFileHandle streams valid OggS bytes for both BGM and Character Voice
      val bgmHandle = QlieVfsFileHandle(vfs, firstStop.currentBgmVfsPath!!)
      assertTrue(bgmHandle.exists())
      assertEquals("ogg", bgmHandle.extension())
      val bgmHeader = ByteArray(4)
      bgmHandle.read().use { it.read(bgmHeader) }
      assertEquals(
        "OggS",
        String(bgmHeader, Charsets.US_ASCII),
        "Decrypted BGM stream must start with OggS magic",
      )

      // Step until Julianus's first spoken audio line (`sue_001_00_yur002`) and sprite (`ユリアーヌス`)
      var voicedStop = firstStop
      while (!vm.isFinished() && voicedStop.currentVoiceVfsPath == null) {
        voicedStop = vm.stepToNextStop()
      }
      assertEquals("ボイス\\sue_001_00_yur002.ogg", voicedStop.currentVoiceVfsPath)
      assertTrue(
        voicedStop.activeSprites.isNotEmpty(),
        "Julianus sprite must be active during sue_001_00_yur002",
      )
      val voiceHandle = QlieVfsFileHandle(vfs, voicedStop.currentVoiceVfsPath!!)
      val voiceHeader = ByteArray(4)
      voiceHandle.read().use { it.read(voiceHeader) }
      assertEquals(
        "OggS",
        String(voiceHeader, Charsets.US_ASCII),
        "Decrypted Voice stream must start with OggS magic",
      )

      // Verify kyo_001_00.s sakura petals activate weatherEffect="sakura" instead of stretching
      // overlayBgVfsPath
      val kyo001Entry =
        vfs.resolveEntry("シナリオ\\共通\\kyo_001_00.s")?.second ?: error("Missing kyo_001_00.s")
      val kyo001Vm =
        ScenarioVm(
          ScenarioParser.parse(kyo001Entry.path, vfs.readTextShiftJis(kyo001Entry.path)),
          resolver,
        )
      var sawSakuraWeather = false
      while (!kyo001Vm.isFinished()) {
        val snap = kyo001Vm.stepToNextStop()
        if (snap.weatherEffect == "sakura") {
          sawSakuraWeather = true
          assertEquals(
            null,
            snap.overlayBgVfsPath,
            "Sakura petals must not stretch across overlayBgVfsPath",
          )
        }
      }
      assertTrue(sawSakuraWeather, "kyo_001_00.s must trigger sakura weather particle effect")

      // Verify kyo_002_09.s keeps Mio (mi2) in casual outfit ('b') across all poses (a1b, b1b, b2b,
      // c1b, d1b)
      val kyo00209Entry =
        vfs.resolveEntry("シナリオ\\共通\\kyo_002_09.s")?.second ?: error("Missing kyo_002_09.s")
      val kyo00209Vm =
        ScenarioVm(
          ScenarioParser.parse(kyo00209Entry.path, vfs.readTextShiftJis(kyo00209Entry.path)),
          resolver,
        )
      val mioPathsIn00209 = ArrayList<String>()
      while (!kyo00209Vm.isFinished()) {
        val snap = kyo00209Vm.stepToNextStop()
        snap.activeSprites["mi2"]?.vfsPath?.let { path ->
          mioPathsIn00209.add(path)
          val fileStem = path.substringAfterLast('\\')
          assertTrue(
            Regex("_[a-d][0-9]b_").containsMatchIn(fileStem),
            "Expected casual costume 'b' for Mio in kyo_002_09.s, but got: $path",
          )
        }
      }
      assertTrue(
        mioPathsIn00209.size >= 10,
        "Mio must appear across kyo_002_09.s with sticky casual costume",
      )

      // Verify ^abg0 in kyo_006_02.s resolves ImageFile (args[1] = sui002a) instead of leaving
      // background black
      val kyo00602Entry =
        vfs.resolveEntry("シナリオ\\共通\\kyo_006_02.s")?.second ?: error("Missing kyo_006_02.s")
      val kyo00602Vm =
        ScenarioVm(
          ScenarioParser.parse(kyo00602Entry.path, vfs.readTextShiftJis(kyo00602Entry.path)),
          resolver,
        )
      var sawSui002a = false
      while (!kyo00602Vm.isFinished()) {
        val snap = kyo00602Vm.stepToNextStop()
        if (snap.backgroundVfsPath?.endsWith("sui002a.jpg", ignoreCase = true) == true) {
          sawSui002a = true
        }
      }
      assertTrue(sawSui002a, "kyo_006_02.s must display CG sui002a.jpg from ^abg0,白加算,sui002a,,70")
    }
  }

  @Test
  fun testTitleBackLayersAndSaveLoadAssets() {
    QlieVirtualFileSystem(gameDir, HonorificsMode.HON).use { vfs ->
      val titleBytes = vfs.readBytes("スクリプト\\title\\back.b")
      val titleAbmp = AbmpDecoder.parse(titleBytes)
      val layers = titleAbmp.allFlattenedImages()
      assertTrue(
        layers.any { it.name.contains("Ricotta") },
        "Title intro Scene01 must include Ricotta logo",
      )
      assertTrue(
        layers.any { it.name.contains("Celia") },
        "Title intro Scene02 must include Celia cut-ins",
      )
      assertTrue(
        layers.any { it.name.contains("Noel") },
        "Title intro Scene02 must include Noel cut-ins",
      )
      assertTrue(
        layers.any { it.name.contains("Lisa") },
        "Title intro Scene02 must include Lisa cut-ins",
      )
      assertTrue(
        layers.any { it.name.contains("Mio") },
        "Title intro Scene02 must include Mio cut-ins",
      )
      assertTrue(
        layers.any { it.name.contains("Front") },
        "Title Scene03 must include Front heroines group",
      )

      assertNotNull(
        vfs.resolveEntry("スクリプト\\saveload\\page01_01.png"),
        "page01_01.png must exist in VFS",
      )
      assertNotNull(
        vfs.resolveEntry("スクリプト\\saveload\\savepage\\underframe.b"),
        "underframe.b must exist in VFS",
      )
      assertNotNull(
        vfs.resolveEntry("スクリプト\\saveload\\savepage\\clock.b"),
        "savepage clock.b must exist in VFS",
      )
      assertNotNull(
        vfs.resolveEntry("スクリプト\\saveload\\loadpage\\clock.b"),
        "loadpage clock.b must exist in VFS",
      )
      assertNotNull(
        vfs.resolveEntry("スクリプト\\saveload\\savepage\\button_data.b"),
        "savepage button_data.b must exist in VFS",
      )
      assertNotNull(
        vfs.resolveEntry("スクリプト\\saveload\\loadpage\\button_data.b"),
        "loadpage button_data.b must exist in VFS",
      )

      val startBtn = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\button_start.b"))
      val specialBtn = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\button_special.b"))
      val cancelBtn = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\button_cancel.b"))
      val webBtn = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\button_web.b"))
      assertTrue(startBtn.allFlattenedImages().size >= 5, "button_start.b must have >= 5 layers")
      assertTrue(
        specialBtn.allFlattenedImages().size >= 5,
        "button_special.b must have >= 5 layers",
      )
      assertTrue(cancelBtn.allFlattenedImages().size >= 5, "button_cancel.b must have >= 5 layers")
      assertTrue(webBtn.allFlattenedImages().isNotEmpty(), "button_web.b must have layers")

      // Test AspectRatioMode enum cycling and SaveManager persistence
      val saveMgr = walkure.save.SaveManager(gameDir).apply { resetConfigToDefaults() }
      assertEquals(walkure.save.AspectRatioMode.ORIGINAL_4_3, saveMgr.aspectRatioMode)
      val nextMode = saveMgr.aspectRatioMode.next()
      assertEquals(walkure.save.AspectRatioMode.FILL_WIDE_PAN, nextMode)
      saveMgr.aspectRatioMode = nextMode
      saveMgr.saveSystemData()

      val reloadedMgr = walkure.save.SaveManager(gameDir)
      assertEquals(walkure.save.AspectRatioMode.FILL_WIDE_PAN, reloadedMgr.aspectRatioMode)
      // Reset back to original
      reloadedMgr.resetConfigToDefaults()
      assertEquals(walkure.save.AspectRatioMode.ORIGINAL_4_3, reloadedMgr.aspectRatioMode)

      println("=== layout_back.s ===")
      println(vfs.readTextShiftJis("スクリプト\\title\\layout_back.s"))
      println("=== layout_normal.s ===")
      println(vfs.readTextShiftJis("スクリプト\\title\\layout_normal.s"))
      println("=== main.s ===")
      println(vfs.readTextShiftJis("スクリプト\\title\\main.s"))
    }
  }

  @Test
  fun testVideoPlayerPlatformAndLogoExport() {
    QlieVirtualFileSystem(gameDir, HonorificsMode.HON).use { vfs ->
      val resolved = VideoPlayerPlatform.resolveMovieFile(vfs, "VR_OP.ogv")
      assertNotNull(resolved, "VR_OP.ogv must resolve in GameData/movie")
      assertTrue(resolved.exists())

      val logoEntry = vfs.resolveEntry("スクリプト\\logo\\ricotta_logo.ogv")
      assertNotNull(logoEntry, "ricotta_logo.ogv must exist in data7.pack")
      val logoFile = VideoPlayerPlatform.resolveMovieFile(vfs, "スクリプト\\logo\\ricotta_logo.ogv")
      assertNotNull(logoFile, "ricotta_logo.ogv must resolve to temporary or game file")
      assertTrue(logoFile.exists())

      // Find android assets directory (either relative to core or project root)
      val candidates =
        listOf(
          File("android/src/main/assets"),
          File("../android/src/main/assets"),
        )
      val assetsDir = candidates.firstOrNull { it.isDirectory }
      if (assetsDir != null) {
        val targetMp4 = File(assetsDir, "ricotta_logo.mp4")
        if (!targetMp4.exists() || targetMp4.length() < 1024L) {
          runCatching {
            val proc =
              ProcessBuilder(
                  "ffmpeg",
                  "-y",
                  "-i",
                  logoFile.absolutePath,
                  "-map",
                  "0:v:0",
                  "-map",
                  "0:a:0?",
                  "-c:v",
                  "libx264",
                  "-preset",
                  "fast",
                  "-crf",
                  "22",
                  "-c:a",
                  "aac",
                  "-b:a",
                  "128k",
                  "-movflags",
                  "+faststart",
                  targetMp4.absolutePath,
                )
                .start()
            proc.waitFor()
          }
        }
        if (targetMp4.exists()) {
          assertTrue(targetMp4.length() > 1024L, "Bundled ricotta_logo.mp4 must be valid")
        }
      }
    }
  }

  @Test
  fun testAbmotionParserAndSemanticButtonLayers() {
    QlieVirtualFileSystem(gameDir, HonorificsMode.HON).use { vfs ->
      // 1. Inspect back.b abdata10..13 tracks
      val backRaw = vfs.readBytes("スクリプト\\title\\back.b")
      val back = AbmpDecoder.parse(backRaw)
      val backDoc = walkure.image.AbmotionParser.parseAbdata(back.decompressedAbdataBytes)
      assertNotNull(backDoc, "back.b must parse abdata motion document")
      assertTrue(backDoc.tracks.isNotEmpty(), "back.b abdata must contain animation tracks")
      println(
        "back.b duration=${backDoc.durationMs}ms tracks=${backDoc.tracks.joinToString { "${it.name}(${it.layers.size} layers)" }}",
      )
      backDoc.defaultTrack()?.layers?.forEach { layer ->
        val img = layer.keyframes.firstOrNull()?.imageIndex ?: 0
        val sz =
          back.images.getOrNull(img)?.let {
            "${it.name}:${if (it.nestedAbmp != null) "child(${it.nestedAbmp.images.size})" else "${it.payload.size}B"}"
          }
        println(
          "  back layer: ${layer.name} role=${layer.groupRole} en=${layer.enabled} kf=${layer.keyframes.size} img=$img($sz) kfs=${layer.keyframes.take(3).map { "t=${it.timeMs}@(${it.x},${it.y}) sc=(${it.scaleX},${it.scaleY}) piv=(${it.pivotX},${it.pivotY}) a=${it.alpha} cs=${it.childStartMs}..${it.childEndMs}" }}",
        )
      }

      // 2. Semantic lookup for button_start.b and button_music.b
      listOf(
          "スクリプト\\title\\button_start.b",
          "スクリプト\\title\\button_load.b",
          "スクリプト\\title\\button_music.b",
        )
        .forEach { path ->
          val cont = AbmpDecoder.parse(vfs.readBytes(path))
          val resolved = walkure.image.AbmotionParser.resolveButtonLayerIndices(cont)
          val flat = cont.allFlattenedImages()
          val doc = walkure.image.AbmotionParser.parseAbdata(cont.decompressedAbdataBytes)
          val childInfos =
            cont.images.mapNotNull { img ->
              img.nestedAbmp?.let { c ->
                val cdoc = walkure.image.AbmotionParser.parseAbdata(c.decompressedAbdataBytes)
                val firstKfs =
                  cdoc?.tracks?.firstOrNull()?.layers?.map {
                    "${it.name}:${it.keyframes.size}@(${it.keyframes.firstOrNull()?.x},${it.keyframes.firstOrNull()?.y}) piv=(${it.keyframes.firstOrNull()?.pivotX},${it.keyframes.firstOrNull()?.pivotY})"
                  }
                "${img.name}(${cdoc?.width}x${cdoc?.height},dur=${cdoc?.durationMs},layers=$firstKfs,imgs=${c.images.map { ci -> ci.name }})"
              }
            }
          val btnOnMouse =
            doc?.findTrack("*OnMouse")?.layers?.map {
              "${it.name}:img=${it.keyframes.firstOrNull()?.imageIndex}@(${it.keyframes.firstOrNull()?.x},${it.keyframes.firstOrNull()?.y})->(${it.keyframes.getOrNull(1)?.x},${it.keyframes.getOrNull(1)?.y})"
            }
          println("$path: resolved=$resolved btnOnMouse=$btnOnMouse children=$childInfos")
          assertTrue(resolved.normalTileIndex >= 0, "$path must resolve normalTileIndex")
          assertNotNull(resolved.iconStripIndex, "$path must resolve iconStripIndex")
          assertTrue(
            resolved.normalTileIndex != resolved.maskIndex,
            "$path normal tile must not collide with mask",
          )
          assertTrue(
            resolved.iconStripIndex != resolved.maskIndex,
            "$path icon strip must not collide with mask",
          )
        }

      // 3. Verify clock.b & icon_config.b abdata
      val clockCont = AbmpDecoder.parse(vfs.readBytes("スクリプト\\saveload\\savepage\\clock.b"))
      val clockDoc = walkure.image.AbmotionParser.parseAbdata(clockCont.decompressedAbdataBytes)
      println(
        "clock.b layers=${clockCont.allFlattenedImages().mapIndexed { i, l -> "$i:${l.name}" }} tracks=${clockDoc?.tracks?.map { "${it.name}:${it.layers.map { ly -> ly.name }}" }}",
      )

      val cfgIcon = AbmpDecoder.parse(vfs.readBytes("スクリプト\\config\\icon_config.b"))
      val cfgDoc = walkure.image.AbmotionParser.parseAbdata(cfgIcon.decompressedAbdataBytes)
      println(
        "icon_config.b layers=${cfgIcon.allFlattenedImages().mapIndexed { i, l -> "$i:${l.name}" }} tracks=${cfgDoc?.tracks?.map { "${it.name}:${it.layers.map { ly -> ly.name }}" }}",
      )
    }
  }

  @Test
  fun testQlieBufferCipherSaveInteropAndStageContinuity() {
    // 1. Buffer Cipher + 1PC roundtrip + IMOSYSDATA12 / IMOSAVE110 roundtrip
    val sig = walkure.save.QlieSaveInterop.SIGNATURE_PLAIN.toByteArray(Charsets.US_ASCII)
    val enc = walkure.crypto.QlieBufferCipher.encryptCopy(sig)
    val dec = walkure.crypto.QlieBufferCipher.decryptCopy(enc)
    assertContentEquals(sig, dec, "QlieBufferCipher encrypt/decrypt roundtrip must match")

    val rawPayload =
      "ScriptName=シナリオ\\共通\\kyo_001_03.s;Flag=42;Text=Testing 1PC BPE roundtrip"
        .repeat(5)
        .toByteArray(Charsets.UTF_8)
    val compressed = walkure.compression.QlieCompression.compress1PC(rawPayload)
    val decompressed = walkure.compression.QlieCompression.decompress1PC(compressed)
    assertContentEquals(rawPayload, decompressed, "compress1PC/decompress1PC roundtrip must match")

    val tempDir = Files.createTempDirectory("walkure_interop_test_").toFile()
    try {
      val mgr = SaveManager(tempDir)
      assertEquals(
        false,
        mgr.isSpecialUnlocked(),
        "Fresh SaveManager must lock Extras/Special until progression",
      )
      mgr.markCgViewed("画像\\ev01_01a.png")
      mgr.markBgmPlayed("音楽\\bgm01.ogg")
      mgr.markRouteCleared("mio")
      assertTrue(mgr.isSpecialUnlocked())
      assertTrue(mgr.isCgUnlocked("ev01_01a"))
      assertTrue(mgr.isBgmUnlocked("音楽\\bgm01.ogg"))

      val script =
        ScenarioParser.parse(
          "シナリオ\\共通\\kyo_001_02.s",
          "@@@header.s\n@@MAIN\n^bg0,bg01a\n「Before jump」\n",
        )
      val snap =
        ScenarioVm(script, AssetAliasResolver(QlieVirtualFileSystem(gameDir, HonorificsMode.HON)))
          .stepToNextStop()
      mgr.saveSlot(slotNumber = 3, snap = snap, routeFlags = mapOf("mio" to 5))

      assertTrue(
        File(tempDir, "SaveData/SystemData.dat").exists(),
        "SystemData.dat must be written",
      )
      assertTrue(
        File(tempDir, "SaveData/SaveData0003.svd").exists(),
        "SaveData0003.svd must be written",
      )

      // Remove kotlin properties to prove native .dat / .svd alone restore state
      File(tempDir, "SaveData/kotlin_sysdata.properties").delete()
      File(tempDir, "SaveData/slot_03.properties").delete()

      val mgr2 = SaveManager(tempDir)
      assertTrue(mgr2.isSpecialUnlocked())
      assertTrue(mgr2.isCgUnlocked("ev01_01a"))
      assertTrue(mgr2.isBgmUnlocked("bgm01.ogg"))
      val restoredSlot = mgr2.loadSlot(3)
      assertNotNull(restoredSlot, "SaveData0003.svd must load without kotlin .properties")
      assertEquals("シナリオ\\共通\\kyo_001_02.s", restoredSlot.scriptPath)
      assertEquals(5, restoredSlot.routeFlags["mio"])
    } finally {
      tempDir.deleteRecursively()
    }

    // 2. ScenarioVm.continueWithScript preserves stage background across seamless jumps (kyo_001_02
    // -> kyo_001_03)
    QlieVirtualFileSystem(gameDir, HonorificsMode.HON).use { vfs ->
      val vm =
        ScenarioVm(
          ScenarioParser.parse(
            "シナリオ\\共通\\kyo_001_02.s",
            "@@MAIN\n^bg0,bg01a_0\n\\fdi,crossfade,400\n「First scene」\n",
          ),
          AssetAliasResolver(vfs),
        )
      val snap1 = vm.stepToNextStop()
      assertNotNull(snap1.backgroundVfsPath, "Initial scene must resolve background VFS path")
      assertEquals("crossfade", snap1.transitionSpec?.effectName)

      val nextParsed =
        ScenarioParser.parse(
          "シナリオ\\共通\\kyo_001_03.s",
          "@@MAIN\n^voicedir,mio\n\\fdi,モザイク,600,3\n「I'm glad you enjoyed it. Was it enough?」\n",
        )
      vm.continueWithScript(nextParsed)
      val snap2 = vm.stepToNextStop()
      assertEquals(
        snap1.backgroundVfsPath,
        snap2.backgroundVfsPath,
        "continueWithScript must preserve active background",
      )
      assertEquals(4, snap2.transitionSpec?.mode, "モザイク transition must parse as mode=4")
    }
  }
}
