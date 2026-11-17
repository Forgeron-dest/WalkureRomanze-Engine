package walkure.config

import walkure.pack.QlieVirtualFileSystem

data class GameDatConfig(
  val screenWidth: Int = 1024,
  val screenHeight: Int = 768,
  val gameTitle: String = "Walkure Romanze",
  val formCaption: String = "Walkure Romanze",
  val mulBgmVolume: Int = 256,
  val mulVoiceVolume: Int = 256,
  val mulSeVolume: Int = 256,
  val thumWidth: Int = 103,
  val thumHeight: Int = 77,
  val codeKey: String = "\$B5A346",
)

data class SystemMessages(
  val appCloseText: String = "Game will close. Are you sure?",
  val appCloseExit: String = "Yes(Y)",
  val appCloseReset: String = "Return To Title(R)",
  val appCloseCancel: String = "Cancel(N)",
  val overSaveText: String = "Overwrite existing save data?",
  val deleteText: String = "Delete selected save data?",
  val quickLoadText: String = "QuickLoad state?",
  val configResetText: String = "Reset configuration to defaults?",
)

data class FontDataConfig(
  val fontSlots: Map<Int, String>,
  val rubyFontSlot: String?,
)

data class AvgSystemSettings(
  val defaultBgFadeMs: Int = 800,
  val defaultSpriteFadeMs: Int = 150,
  val defaultSoundFadeMs: Int = 1000,
  val spriteActionOffsetX: Int = 20,
  val spriteActionOffsetY: Int = -20,
)

data class VoiceActorChannel(
  val index: Int,
  val speakerNames: List<String>,
) {
  val displayLabel: String
    get() =
      when (val first = speakerNames.firstOrNull().orEmpty()) {
        "美桜" -> "Mio Kisaki (美桜)"
        "スィーリア" -> "Celia Cumani Aintree (スィーリア)"
        "ノエル" -> "Noel Marres Ascot (ノエル)"
        "リサ" -> "Lisa Eostre (リサ)"
        "茜" -> "Akane Ryuzoji (茜)"
        "玲奈" -> "Reina (玲奈)"
        "フィオナ" -> "Fiona (フィオナ)"
        "ベルティーユ" -> "Bertille (ベルティーユ)"
        "ミレイユ" -> "Mireille (ミレイユ)"
        "綾子" -> "Ayako Hiiragi (綾子)"
        "カイル" -> "Kyle (カイル)"
        "ジェイムス" -> "James (ジェイムス)"
        "ユリアーヌス" -> "Julianus (ユリアーヌス)"
        "グレイトフル" -> "Grateful (グレイトフル)"
        "*" -> "Other Characters (*)"
        else -> first
      }
}

data class AlbumCgGroup(
  val category: String,
  val thumbnailPath: String,
  val cgVariants: List<String>,
)

/**
 * Parser for all 4 QLiE `system\**\*.dat` configuration files, `ライブラリ\avgシステム\設定\*.txt` engine
 * settings, and `スクリプト\albummode\list_*.txt` CG gallery tables.
 */
object SystemDatParser {

  fun parseGameDat(vfs: QlieVirtualFileSystem): GameDatConfig {
    val map = readIniMap(vfs, "system\\etc\\game.dat")
    return GameDatConfig(
      screenWidth = map["ScreenWidth"]?.toIntOrNull() ?: 1024,
      screenHeight = map["ScreenHeight"]?.toIntOrNull() ?: 768,
      gameTitle = map["GameTitle"] ?: "Walkure Romanze",
      formCaption = map["FormCaption"]?.trim() ?: "Walkure Romanze",
      mulBgmVolume = map["MulBGMVolume"]?.toIntOrNull() ?: 256,
      mulVoiceVolume = map["MulVOICEVolume"]?.toIntOrNull() ?: 256,
      mulSeVolume = map["MulSEVolume"]?.toIntOrNull() ?: 256,
      thumWidth = map["ThumWidth"]?.toIntOrNull() ?: 103,
      thumHeight = map["ThumHeight"]?.toIntOrNull() ?: 77,
      codeKey = map["CodeKey"] ?: "\$B5A346",
    )
  }

  fun parseSystemMessages(vfs: QlieVirtualFileSystem): SystemMessages {
    val map = readIniMap(vfs, "system\\etc\\message.dat")
    return SystemMessages(
      appCloseText =
        map["APPCLOSE_Text"]?.replace("%%%n", "\n") ?: "Game will close. Are you sure?",
      appCloseExit = map["APPCLOSE_Exit"] ?: "Yes(Y)",
      appCloseReset = map["APPCLOSE_Reset"] ?: "Return To Title(R)",
      appCloseCancel = map["APPCLOSE_Cancel"] ?: "Cancel(N)",
      overSaveText = map["OVERSAVE_Text"]?.replace("%%%n", "\n") ?: "Overwrite existing save data?",
      deleteText = map["DELETE_Text"]?.replace("%%%n", "\n") ?: "Delete selected save data?",
      quickLoadText = map["QuickLoad_Text"]?.replace("%%%n", "\n") ?: "QuickLoad state?",
      configResetText =
        map["CONFIGRESET_Text"]?.replace("%%%n", "\n") ?: "Reset configuration to defaults?",
    )
  }

  fun parseFontData(vfs: QlieVirtualFileSystem): FontDataConfig {
    val map = readIniMap(vfs, "system\\font\\fontdata.dat")
    val fontRegex = Regex("""^Font\[(\d+)\](?:\.List\[\d+\])?$""", RegexOption.IGNORE_CASE)
    val slots = LinkedHashMap<Int, String>()
    for ((k, v) in map) {
      val match = fontRegex.matchEntire(k) ?: continue
      val idx = match.groupValues[1].toInt()
      slots[idx] = v
    }
    return FontDataConfig(
      fontSlots = slots,
      rubyFontSlot = slots[7],
    )
  }

  fun parseAvgSettings(vfs: QlieVirtualFileSystem): AvgSystemSettings {
    val defFade = readIniMap(vfs, "ライブラリ\\avgシステム\\設定\\フェード設定_デフォルト.txt")
    val charaFade = readIniMap(vfs, "ライブラリ\\avgシステム\\設定\\フェード設定_立ち絵.txt")
    val sndFade = readIniMap(vfs, "ライブラリ\\avgシステム\\設定\\サウンド設定_フェード.txt")
    val charaAction = readIniMap(vfs, "ライブラリ\\avgシステム\\設定\\イメージ設定_立ち絵アクション.txt")

    return AvgSystemSettings(
      defaultBgFadeMs = defFade["FadeTime_def"]?.toIntOrNull() ?: 800,
      defaultSpriteFadeMs = charaFade["FadeTime_def"]?.toIntOrNull() ?: 150,
      defaultSoundFadeMs = sndFade["Time_def"]?.toIntOrNull() ?: 1000,
      spriteActionOffsetX = charaAction["X_addS"]?.toIntOrNull() ?: 20,
      spriteActionOffsetY = charaAction["Y_addS"]?.toIntOrNull() ?: -20,
    )
  }

  fun parseVoiceChannels(vfs: QlieVirtualFileSystem): List<VoiceActorChannel> {
    val map = readIniMap(vfs, "system\\voice\\voicedata.dat")
    val actRegex = Regex("""^act\[(\d+)\]$""", RegexOption.IGNORE_CASE)
    val channels = ArrayList<VoiceActorChannel>()

    for ((k, v) in map) {
      val match = actRegex.matchEntire(k) ?: continue
      val idx = match.groupValues[1].toInt()
      val names = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
      if (names.isNotEmpty()) {
        channels.add(VoiceActorChannel(index = idx, speakerNames = names))
      }
    }
    return channels.sortedBy { it.index }
  }

  fun parseAlbumLists(vfs: QlieVirtualFileSystem): Map<String, List<AlbumCgGroup>> {
    val categories =
      listOf(
        "Celia" to "スクリプト\\albummode\\list_celia.txt",
        "Mio" to "スクリプト\\albummode\\list_mio.txt",
        "Noel" to "スクリプト\\albummode\\list_noel.txt",
        "Lisa" to "スクリプト\\albummode\\list_lisa.txt",
        "Etc" to "スクリプト\\albummode\\list_etc.txt",
      )

    val result = LinkedHashMap<String, List<AlbumCgGroup>>()
    for ((catName, path) in categories) {
      if (!vfs.exists(path)) continue
      val text = runCatching { vfs.readTextShiftJis(path) }.getOrDefault("")
      val groups = ArrayList<AlbumCgGroup>()
      for (line in text.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith(";")) continue
        val cols = trimmed.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        if (cols.isNotEmpty()) {
          val thumb = cols.first()
          val variants = if (cols.size > 1) cols.subList(1, cols.size) else listOf(thumb)
          groups.add(AlbumCgGroup(category = catName, thumbnailPath = thumb, cgVariants = variants))
        }
      }
      result[catName] = groups
    }
    return result
  }

  private fun readIniMap(vfs: QlieVirtualFileSystem, vfsPath: String): Map<String, String> {
    if (!vfs.exists(vfsPath)) return emptyMap()
    val text = runCatching { vfs.readTextShiftJis(vfsPath) }.getOrDefault("")
    val map = LinkedHashMap<String, String>()
    for (line in text.lineSequence()) {
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith(";")) continue
      val eq = trimmed.indexOf('=')
      if (eq > 0) {
        val k = trimmed.substring(0, eq).trim()
        val v = trimmed.substring(eq + 1).trim()
        map[k] = v
      }
    }
    return map
  }
}
