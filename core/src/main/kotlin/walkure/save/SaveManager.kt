package walkure.save

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import walkure.pack.HonorificsMode
import walkure.script.ScenarioVm

data class SaveSlotMetadata(
  val slotNumber: Int,
  val scriptPath: String,
  val instructionPointer: Int,
  val routeScriptPath: String,
  val routeInstructionPointer: Int,
  val inGameDate: String,
  val routeName: String,
  val realTimestamp: String,
  val speaker: String?,
  val dialoguePreview: String,
  val backgroundSymbol: String?,
  val backgroundVfsPath: String?,
  val bgmSymbol: String?,
  val bgmVfsPath: String?,
  val spritesSerialized: String,
  val routeFlags: Map<String, Int>,
  val thumbnailFile: File?,
) {
  fun parseSprites(): Map<String, ScenarioVm.SpriteSlotState> {
    if (spritesSerialized.isBlank()) return emptyMap()
    val map = LinkedHashMap<String, ScenarioVm.SpriteSlotState>()
    for (entry in spritesSerialized.split(';')) {
      val parts = entry.split('|')
      if (parts.size == 3) {
        val key = parts[0]
        val path = parts[1]
        val cx = parts[2].toIntOrNull() ?: 512
        map[key] = ScenarioVm.SpriteSlotState(key, path, cx)
      }
    }
    return map
  }
}

enum class AspectRatioMode(val displayName: String, val shortName: String) {
  ORIGINAL_4_3("4:3 Original (Fit)", "4:3"),
  FILL_WIDE_PAN("Wide Pan & Scan", "Pan"),
  EXTEND_WIDE("Extended Wide", "Extend"),
  STRETCH_FULL("Full Stretch", "Stretch");

  fun next(): AspectRatioMode {
    val vals = entries
    return vals[(ordinal + 1) % vals.size]
  }
}

/**
 * Persistent Save/Load Manager for *Walkure Romanze*.
 *
 * Manages:
 * 1. **Global System Save (`SaveData/kotlin_sysdata.properties`)**:
 *     - Audio volumes (`bgmVolume`, `voiceVolume`, `seVolume`), per-actor voice mutes
 *       (`act[0..14]`)
 *     - Text & auto-advance speeds, skip mode (`skipAll`), fullscreen preference, honorifics (`HON`
 *       vs `NOHON`)
 *     - Aspect ratio & wide display mode (`ORIGINAL_4_3`, `FILL_WIDE_PAN`, `EXTEND_WIDE`,
 *       `STRETCH_FULL`)
 *     - Read-text (`kidoku`) tracking (`scriptPath#lineNumber`) and unlocked CG / route clear flags
 * 2. **Paginated Save Slots (`SaveData/slot_00.properties` .. `slot_48.properties` + `slot_XX.png`
 *    103x77 thumbnails)**:
 *     - Slot 0 = QuickSave; Slots 1..48 = 4 pages × 12 slots.
 */
class SaveManager(val gameDir: File) {
  private val saveDir = File(gameDir, "SaveData").also { it.mkdirs() }
  private val sysFile = File(saveDir, "kotlin_sysdata.properties")
  private val nativeSysFile = File(saveDir, "SystemData.dat")
  var codeKeySeed: Int = walkure.crypto.QlieBufferCipher.DEFAULT_CODE_KEY

  var bgmVolume: Float = 0.55f
  var voiceVolume: Float = 0.90f
  var seVolume: Float = 0.75f
  var textSpeed: Float = 1.0f
  var autoSpeedSec: Float = 1.8f
  var skipAllUnread: Boolean = false
  var fullscreen: Boolean = false
  var honorificsMode: HonorificsMode = HonorificsMode.HON
  var aspectRatioMode: AspectRatioMode = AspectRatioMode.ORIGINAL_4_3

  val mutedVoiceActors = HashSet<Int>() // indices into voicedata.dat act[0..14]
  val clearedRoutes = HashSet<String>() // "mio", "sui", "noe", "ris"
  private val readLineKeys = HashSet<String>()
  val unlockedCgs = HashSet<String>()
  val unlockedBgm = HashSet<String>()

  init {
    loadSystemData()
  }

  fun isSpecialUnlocked(): Boolean =
    clearedRoutes.isNotEmpty() || unlockedCgs.isNotEmpty() || unlockedBgm.isNotEmpty()

  fun isLineRead(scriptPath: String, lineNumber: Int): Boolean {
    return readLineKeys.contains("${scriptPath.lowercase()}#$lineNumber")
  }

  fun markLineRead(scriptPath: String, lineNumber: Int) {
    if (readLineKeys.add("${scriptPath.lowercase()}#$lineNumber")) {
      if (readLineKeys.size % 10 == 0) {
        saveSystemData()
      }
    }
  }

  fun markCgViewed(cgPath: String) {
    if (unlockedCgs.add(cgPath.lowercase())) {
      saveSystemData()
    }
  }

  fun markBgmPlayed(bgmPath: String) {
    val clean = bgmPath.lowercase().substringAfterLast('\\').substringAfterLast('/')
    if (unlockedBgm.add(clean)) {
      saveSystemData()
    }
  }

  fun markRouteCleared(routeId: String) {
    val clean = routeId.trim().lowercase()
    if (clean.isNotEmpty() && clearedRoutes.add(clean)) {
      saveSystemData()
    }
  }

  fun isCgUnlocked(cgPath: String): Boolean {
    val clean = cgPath.lowercase().replace('\\', '/')
    val stem = clean.substringAfterLast('/').substringBeforeLast('.')
    return unlockedCgs.any { unlocked ->
      val u = unlocked.lowercase().replace('\\', '/')
      val uStem = u.substringAfterLast('/').substringBeforeLast('.')
      u == clean || u.endsWith("/$stem") || uStem == stem
    }
  }

  fun isBgmUnlocked(bgmPath: String): Boolean {
    val clean = bgmPath.lowercase().substringAfterLast('\\').substringAfterLast('/')
    return unlockedBgm.contains(clean) ||
      unlockedBgm.any {
        it.substringBeforeLast('.') == clean.substringBeforeLast('.')
      }
  }

  fun resetConfigToDefaults() {
    bgmVolume = 0.55f
    voiceVolume = 0.90f
    seVolume = 0.75f
    textSpeed = 1.0f
    autoSpeedSec = 1.8f
    skipAllUnread = false
    fullscreen = false
    aspectRatioMode = AspectRatioMode.ORIGINAL_4_3
    mutedVoiceActors.clear()
    saveSystemData()
  }

  fun loadSystemData() {
    val candidateSysDat = buildList {
      if (nativeSysFile.exists()) add(nativeSysFile)
      QlieSaveInterop.resolveNativeSaveDirectories(gameDir).forEach { dir ->
        val f = File(dir, "SystemData.dat")
        if (f.exists()) add(f)
      }
    }
      .maxByOrNull { it.lastModified() }

    val props = Properties()
    if (sysFile.exists()) {
      runCatching { sysFile.inputStream().use { props.load(it) } }
    }
    val nativeParsed = candidateSysDat?.let { file ->
      runCatching { QlieSaveInterop.parseSystemData(file.readBytes(), codeKeySeed) }.getOrNull()
    }
    val useNativeProps =
      nativeParsed?.kotlinProps != null &&
        (!sysFile.exists() || candidateSysDat.lastModified() > sysFile.lastModified())
    if (useNativeProps) {
      nativeParsed.kotlinProps.stringPropertyNames().forEach { k ->
        props.setProperty(k, nativeParsed.kotlinProps.getProperty(k))
      }
    }

    if (props.isEmpty && nativeParsed == null) return
    runCatching {
      bgmVolume = props.getProperty("bgmVolume")?.toFloatOrNull() ?: 0.55f
      voiceVolume = props.getProperty("voiceVolume")?.toFloatOrNull() ?: 0.90f
      seVolume = props.getProperty("seVolume")?.toFloatOrNull() ?: 0.75f
      textSpeed = props.getProperty("textSpeed")?.toFloatOrNull() ?: 1.0f
      autoSpeedSec = props.getProperty("autoSpeedSec")?.toFloatOrNull() ?: 1.8f
      skipAllUnread = props.getProperty("skipAllUnread")?.toBoolean() ?: false
      fullscreen = props.getProperty("fullscreen")?.toBoolean() ?: false
      aspectRatioMode =
        runCatching {
            AspectRatioMode.valueOf(
              props.getProperty("aspectRatioMode", AspectRatioMode.ORIGINAL_4_3.name),
            )
          }
          .getOrDefault(AspectRatioMode.ORIGINAL_4_3)
      honorificsMode =
        runCatching {
            HonorificsMode.valueOf(props.getProperty("honorificsMode", "HON"))
          }
          .getOrDefault(HonorificsMode.HON)

      mutedVoiceActors.clear()
      props
        .getProperty("mutedVoiceActors", "")
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .forEach { mutedVoiceActors.add(it) }

      clearedRoutes.clear()
      props
        .getProperty("clearedRoutes", "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { clearedRoutes.add(it) }

      unlockedCgs.clear()
      props
        .getProperty("unlockedCgs", "")
        .split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { unlockedCgs.add(it) }

      unlockedBgm.clear()
      props
        .getProperty("unlockedBgm", "")
        .split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { unlockedBgm.add(it) }

      readLineKeys.clear()
      props
        .getProperty("readLines", "")
        .split(';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { readLineKeys.add(it) }

      // Merge seen CGs/BGM/routes from native SystemData.dat if present
      if (nativeParsed != null) {
        nativeParsed.seenCgs.forEach { entry ->
          val clean = entry.lowercase().trim()
          if (clean.endsWith(".ogg") || clean.startsWith("bgm")) {
            unlockedBgm.add(clean.substringAfterLast('\\').substringAfterLast('/'))
          } else if (clean.length >= 3) {
            unlockedCgs.add(clean)
          }
        }
        nativeParsed.readEntries.forEach { readLineKeys.add(it) }
        nativeParsed.vars["SysClearedRoutes"]
          ?.strValue
          ?.split(',')
          ?.map { it.trim() }
          ?.filter { it.isNotEmpty() }
          ?.forEach { clearedRoutes.add(it) }
      }
    }
  }

  private fun buildSystemProperties(): Properties =
    Properties().apply {
      setProperty("bgmVolume", bgmVolume.toString())
      setProperty("voiceVolume", voiceVolume.toString())
      setProperty("seVolume", seVolume.toString())
      setProperty("textSpeed", textSpeed.toString())
      setProperty("autoSpeedSec", autoSpeedSec.toString())
      setProperty("skipAllUnread", skipAllUnread.toString())
      setProperty("fullscreen", fullscreen.toString())
      setProperty("aspectRatioMode", aspectRatioMode.name)
      setProperty("honorificsMode", honorificsMode.name)
      setProperty("mutedVoiceActors", mutedVoiceActors.joinToString(","))
      setProperty("clearedRoutes", clearedRoutes.joinToString(","))
      setProperty("unlockedCgs", unlockedCgs.joinToString(";"))
      setProperty("unlockedBgm", unlockedBgm.joinToString(";"))
      setProperty("readLines", readLineKeys.joinToString(";"))
    }

  fun saveSystemData() {
    runCatching {
      val props = buildSystemProperties()
      sysFile.outputStream().use { props.store(it, "Walkure Romanze System Data") }

      val nativeVars =
        listOf(
          QlieSaveInterop.NativeVarRecord("SysBgmVol", 1, 0, (bgmVolume * 100).toInt(), ""),
          QlieSaveInterop.NativeVarRecord("SysVoiceVol", 1, 0, (voiceVolume * 100).toInt(), ""),
          QlieSaveInterop.NativeVarRecord("SysSeVol", 1, 0, (seVolume * 100).toInt(), ""),
          QlieSaveInterop.NativeVarRecord("SysMsgSpeed", 1, 0, (textSpeed * 100).toInt(), ""),
          QlieSaveInterop.NativeVarRecord("SysAutoSpeed", 1, 0, (autoSpeedSec * 1000).toInt(), ""),
          QlieSaveInterop.NativeVarRecord("SysSkipUnread", 1, 0, if (skipAllUnread) 1 else 0, ""),
          QlieSaveInterop.NativeVarRecord("SysFullscreen", 1, 0, if (fullscreen) 1 else 0, ""),
          QlieSaveInterop.NativeVarRecord(
            "SysClearedRoutes",
            2,
            0,
            clearedRoutes.size,
            clearedRoutes.joinToString(","),
          ),
        )
      val seenAll = unlockedCgs + unlockedBgm
      val nativeBytes =
        QlieSaveInterop.writeSystemData(
          vars = nativeVars,
          seenCgs = seenAll,
          readEntries = readLineKeys,
          kotlinProps = props,
          keySeed = codeKeySeed,
        )
      nativeSysFile.writeBytes(nativeBytes)
      QlieSaveInterop.resolveNativeSaveDirectories(gameDir).forEach { dir ->
        runCatching { File(dir, "SystemData.dat").writeBytes(nativeBytes) }
      }
    }
  }

  fun saveSlot(
    slotNumber: Int,
    snap: ScenarioVm.VmSnapshot,
    routeScriptPath: String = "シナリオ\\route.s",
    routeIp: Int = 0,
    inGameDate: String = "04/16",
    routeName: String = "通常",
    routeFlags: Map<String, Int> = emptyMap(),
    thumbnailPngBytes: ByteArray? = null,
  ): SaveSlotMetadata {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
    val spritesStr =
      snap.activeSprites.values.joinToString(";") { "${it.charKey}|${it.vfsPath}|${it.centerX}" }
    val flagsStr = routeFlags.entries.joinToString(",") { "${it.key}=${it.value}" }

    val slotFile = slotFile(slotNumber)
    val thumbFile = slotThumbnailFile(slotNumber)

    val props =
      Properties().apply {
        setProperty("slotNumber", slotNumber.toString())
        setProperty("scriptPath", snap.scriptPath)
        setProperty("ip", snap.instructionPointer.toString())
        setProperty("routeScriptPath", routeScriptPath)
        setProperty("routeIp", routeIp.toString())
        setProperty("inGameDate", inGameDate)
        setProperty("routeName", routeName)
        setProperty("realTimestamp", timestamp)
        setProperty("speaker", snap.currentSpeaker.orEmpty())
        setProperty("dialoguePreview", snap.currentText.replace('\n', ' ').take(90))
        setProperty("bgSymbol", snap.backgroundSymbol.orEmpty())
        setProperty("bgVfsPath", snap.backgroundVfsPath.orEmpty())
        setProperty("bgmSymbol", snap.currentBgmSymbol.orEmpty())
        setProperty("bgmVfsPath", snap.currentBgmVfsPath.orEmpty())
        setProperty("sprites", spritesStr)
        setProperty("routeFlags", flagsStr)
      }
    slotFile.outputStream().use { props.store(it, "Walkure Romanze Save Slot $slotNumber") }
    if (thumbnailPngBytes != null && thumbnailPngBytes.isNotEmpty()) {
      thumbFile.writeBytes(thumbnailPngBytes)
    }
    val metaForSvd =
      SaveSlotMetadata(
        slotNumber = slotNumber,
        scriptPath = snap.scriptPath,
        instructionPointer = snap.instructionPointer,
        routeScriptPath = routeScriptPath,
        routeInstructionPointer = routeIp,
        inGameDate = inGameDate,
        routeName = routeName,
        realTimestamp = timestamp,
        speaker = snap.currentSpeaker,
        dialoguePreview = snap.currentText.replace('\n', ' ').take(90),
        backgroundSymbol = snap.backgroundSymbol,
        backgroundVfsPath = snap.backgroundVfsPath,
        bgmSymbol = snap.currentBgmSymbol,
        bgmVfsPath = snap.currentBgmVfsPath,
        spritesSerialized = spritesStr,
        routeFlags = routeFlags,
        thumbnailFile = thumbFile.takeIf { it.exists() },
      )
    runCatching {
      val svdBytes =
        QlieSaveInterop.writeSaveFile(metaForSvd, thumbnailPngBytes, props, codeKeySeed)
      nativeSlotFile(slotNumber).writeBytes(svdBytes)
      QlieSaveInterop.resolveNativeSaveDirectories(gameDir).forEach { dir ->
        runCatching {
          File(dir, String.format("SaveData%04d.svd", slotNumber)).writeBytes(svdBytes)
        }
      }
    }
    saveSystemData()

    return loadSlot(slotNumber)!!
  }

  fun loadSlot(slotNumber: Int): SaveSlotMetadata? {
    val file = slotFile(slotNumber)
    val svdFile = buildList {
      QlieSaveInterop.findNativeSlotFile(saveDir, slotNumber)?.let { add(it) }
      QlieSaveInterop.resolveNativeSaveDirectories(gameDir).forEach { dir ->
        QlieSaveInterop.findNativeSlotFile(dir, slotNumber)?.let { add(it) }
      }
    }
      .maxByOrNull { it.lastModified() }

    if (!file.exists() && svdFile == null) return null
    return runCatching {
      val props = Properties()
      if (file.exists()) {
        file.inputStream().use { props.load(it) }
      }
      var nativeParsed: QlieSaveInterop.ParsedSaveSlot? = null
      if (svdFile != null && (!file.exists() || svdFile.lastModified() > file.lastModified())) {
        nativeParsed = QlieSaveInterop.parseSaveFile(svdFile.readBytes(), codeKeySeed)
        nativeParsed?.kotlinProps?.let { np ->
          np.stringPropertyNames().forEach { k -> props.setProperty(k, np.getProperty(k)) }
        }
      }

      if (props.isEmpty && nativeParsed == null) return null
      if (props.isEmpty && nativeParsed != null) {
        // Imported from original WALKURE_ROMANZE.exe SaveData*.svd without Kotlin trailer
        val thumbFile = slotThumbnailFile(slotNumber)
        if (
          nativeParsed.thumbnailBytes != null &&
            nativeParsed.thumbnailBytes.isNotEmpty() &&
            !thumbFile.exists()
        ) {
          runCatching { thumbFile.writeBytes(nativeParsed.thumbnailBytes) }
        }
        val flags = HashMap<String, Int>()
        nativeParsed.vars.values
          .filter { it.kind == 1 || it.kind == 3 }
          .forEach {
            flags[it.name] = it.intValue
          }
        return SaveSlotMetadata(
          slotNumber = slotNumber,
          scriptPath =
            nativeParsed.detectedScriptPath
              ?: nativeParsed.vars["ScriptName"]?.strValue?.takeIf { it.isNotBlank() }
              ?: "シナリオ\\共通\\kyo_001_00.s",
          instructionPointer = (nativeParsed.vars["ScriptName"]?.intValue ?: 1).coerceAtLeast(1),
          routeScriptPath = "シナリオ\\route.s",
          routeInstructionPointer = 0,
          inGameDate = nativeParsed.headerCaption.takeIf { it.isNotBlank() } ?: "Native",
          routeName = "EXE Save",
          realTimestamp = nativeParsed.headerTimestamp,
          speaker = null,
          dialoguePreview = nativeParsed.detectedDialogue ?: nativeParsed.headerCaption,
          backgroundSymbol = null,
          backgroundVfsPath = null,
          bgmSymbol = null,
          bgmVfsPath = null,
          spritesSerialized = "",
          routeFlags = flags,
          thumbnailFile = thumbFile.takeIf { it.exists() },
        )
      }

      val flags = HashMap<String, Int>()
      props.getProperty("routeFlags", "").split(',').forEach { pair ->
        val eq = pair.indexOf('=')
        if (eq > 0) {
          val k = pair.substring(0, eq).trim()
          val v = pair.substring(eq + 1).trim().toIntOrNull()
          if (v != null) flags[k] = v
        }
      }
      val thumb = slotThumbnailFile(slotNumber).takeIf { it.exists() }
      SaveSlotMetadata(
        slotNumber = slotNumber,
        scriptPath = props.getProperty("scriptPath", "シナリオ\\共通\\kyo_001_00.s"),
        instructionPointer = props.getProperty("ip", "1").toIntOrNull() ?: 1,
        routeScriptPath = props.getProperty("routeScriptPath", "シナリオ\\route.s"),
        routeInstructionPointer = props.getProperty("routeIp", "0").toIntOrNull() ?: 0,
        inGameDate = props.getProperty("inGameDate", "04/16"),
        routeName = props.getProperty("routeName", "通常"),
        realTimestamp = props.getProperty("realTimestamp", ""),
        speaker = props.getProperty("speaker")?.takeIf { it.isNotEmpty() },
        dialoguePreview = props.getProperty("dialoguePreview", ""),
        backgroundSymbol = props.getProperty("bgSymbol")?.takeIf { it.isNotEmpty() },
        backgroundVfsPath = props.getProperty("bgVfsPath")?.takeIf { it.isNotEmpty() },
        bgmSymbol = props.getProperty("bgmSymbol")?.takeIf { it.isNotEmpty() },
        bgmVfsPath = props.getProperty("bgmVfsPath")?.takeIf { it.isNotEmpty() },
        spritesSerialized = props.getProperty("sprites", ""),
        routeFlags = flags,
        thumbnailFile = thumb,
      )
    }
      .getOrNull()
  }

  fun deleteSlot(slotNumber: Int): Boolean {
    slotThumbnailFile(slotNumber).delete()
    nativeSlotFile(slotNumber).delete()
    QlieSaveInterop.findNativeSlotFile(saveDir, slotNumber)?.delete()
    return slotFile(slotNumber).delete()
  }

  fun listPageSlots(pageIndex: Int, slotsPerPage: Int = 25): List<SaveSlotMetadata?> {
    val base = pageIndex * slotsPerPage + 1
    return (0 until slotsPerPage).map { offset -> loadSlot(base + offset) }
  }

  private fun slotFile(slotNumber: Int): File =
    File(saveDir, String.format("slot_%02d.properties", slotNumber))

  private fun nativeSlotFile(slotNumber: Int): File =
    File(saveDir, String.format("SaveData%04d.svd", slotNumber))

  private fun slotThumbnailFile(slotNumber: Int): File =
    File(saveDir, String.format("slot_%02d.png", slotNumber))
}
