package walkure.script

/**
 * Deterministic Virtual Machine for stepping through a parsed [ScenarioScript]. Maintains current
 * background (`^bg0`, `^abg0`, `^mbg0`), layered overlays (`^bg1..9`, `^abg1..9`), weather/particle
 * effects (`桜`, `桜s`, `桜ss`, `雨`, `雨弱`), active character sprites (with sticky QLiE
 * pose/costume/blush/distance/position state, face window portraits, and `^achara` actions), BGM
 * track, sound effects (including `loop=1` and `^se0,,,1000` stop), character voice track, in-game
 * calendar date (`^savedate`), route name (`^saveroute`), triggered `.ogv` video (`^movie`),
 * transition specs (`^clear,,wfade*`), camera shake (`^Effect,quake`), dialogue history (backlog),
 * mid-script conditional branches (`^branch1`), and interactive choices (`^select1`).
 */
class ScenarioVm(
  var currentScript: ScenarioScript,
  private val aliasResolver: AssetAliasResolver? = null,
  private val branchEvaluator: ((String) -> CampaignConductor.BranchOutcome?)? = null,
) {
  data class ChoiceOption(
    val text: String,
    val targetLabel: String,
  )

  data class SpriteSlotState(
    val charKey: String,
    val vfsPath: String,
    val centerX: Int,
    val distCode: String = "u",
    val faceVfsPath: String? = null,
    val faceOffsetX: Int = 0,
  )

  data class TransitionSpec(
    val effectName: String,
    val durationSec: Float,
    val mode: Int =
      0, // 0/1 = crossfade / rule / abmotion .b, 2 = color fade, 3 = alt rule, 4 = モザイク
    val feather: Int = 3, // 0..7 rule softness
    val fadeColorRgb: String? = null,
  )

  data class BacklogEntry(
    val scriptPath: String,
    val lineNumber: Int,
    val speaker: String?,
    val text: String,
    val voiceId: String?,
    val voiceVfsPath: String?,
  )

  data class VmSnapshot(
    val scriptPath: String,
    val instructionPointer: Int,
    val currentLineNumber: Int,
    val inGameDate: String,
    val routeName: String,
    val backgroundSymbol: String?,
    val backgroundVfsPath: String?,
    val overlayBgVfsPath: String?,
    val weatherEffect: String? = null,
    val flashTriggered: Boolean = false,
    val activeSprites: Map<String, SpriteSlotState>,
    val currentFaceVfsPath: String? = null,
    val currentFaceOffsetX: Int = 0,
    val currentBgmSymbol: String?,
    val currentBgmVfsPath: String?,
    val triggeredSeVfsPath: String?,
    val triggeredSeLoop: Boolean,
    val stopSeRequested: Boolean,
    val triggeredMovieFile: String?,
    val triggeredRouteJumpLabel: String?,
    val triggeredDayEyeCatch: String? = null,
    val triggeredActionCharKey: String?,
    val triggeredActionName: String?,
    val screenShakeDurationSec: Float,
    val transitionSpec: TransitionSpec?,
    val currentSpeaker: String?,
    val currentText: String,
    val currentVoiceId: String?,
    val currentVoiceVfsPath: String?,
    val pendingChoices: List<ChoiceOption>,
  )

  var instructionPointer: Int = 0
    private set

  var currentLineNumber: Int = 1
    private set

  var inGameDate: String = "04/16"
    private set

  var routeName: String = "通常"
    private set

  var backgroundSymbol: String? = null
    private set

  var backgroundVfsPath: String? = null
    private set

  private var savedGroupBgSymbol: String? = null
  private var savedGroupBgVfsPath: String? = null
  private var isAbg0Active: Boolean = false
  private var inSpGroup: Boolean = false

  private var sceneBgLocate: String = "d"

  private val overlaySlots = LinkedHashMap<Int, String>()
  private val weatherSlots = LinkedHashMap<Int, String>()

  val overlayBgVfsPath: String?
    get() = overlaySlots.entries.maxByOrNull { it.key }?.value

  val weatherEffect: String?
    get() = weatherSlots.entries.maxByOrNull { it.key }?.value

  private val spriteSlots = LinkedHashMap<String, SpriteSlotState>()
  private val charaParamStates = LinkedHashMap<String, AssetAliasResolver.CharaParamState>()
  private val lastCharFacePath = LinkedHashMap<String, Pair<String, Int>>()

  var currentFaceVfsPath: String? = null
    private set

  var currentFaceOffsetX: Int = 0
    private set

  var currentBgm: String? = null
    private set

  var currentBgmVfsPath: String? = null
    private set

  var lastTriggeredSeVfsPath: String? = null
    private set

  var lastTriggeredSeLoop: Boolean = false
    private set

  var lastStopSeRequested: Boolean = false
    private set

  var lastTriggeredMovieFile: String? = null
    private set

  var lastTriggeredRouteJumpLabel: String? = null
    private set

  var lastTriggeredDayEyeCatch: String? = null
    private set

  var lastTriggeredActionCharKey: String? = null
    private set

  var lastTriggeredActionName: String? = null
    private set

  var lastScreenShakeDurationSec: Float = 0f
    private set

  var lastFlashTriggered: Boolean = false
    private set

  var lastTransitionSpec: TransitionSpec? = null
    private set

  var currentSpeaker: String? = null
    private set

  var currentText: String = ""
    private set

  var currentVoiceId: String? = null
    private set

  var currentVoiceVfsPath: String? = null
    private set

  private val choices = ArrayList<ChoiceOption>()
  val backlog = ArrayList<BacklogEntry>()

  fun isFinished(): Boolean = instructionPointer >= currentScript.nodes.size

  fun activeSprites(): Map<String, SpriteSlotState> = spriteSlots.toMap()

  fun pendingChoices(): List<ChoiceOption> = choices.toList()

  fun scriptStem(): String =
    currentScript.path.substringAfterLast('\\').substringAfterLast('/').substringBeforeLast('.')

  /**
   * Advances the VM until the next user-visible stop (a `Dialogue` line, a `^select1` choice
   * prompt, a `^movie` video trigger, an external route jump from `^branch1`, or the end of the
   * script).
   */
  fun stepToNextStop(): VmSnapshot {
    lastTriggeredSeVfsPath = null
    lastTriggeredSeLoop = false
    lastStopSeRequested = false
    lastTriggeredMovieFile = null
    lastTriggeredRouteJumpLabel = null
    lastTriggeredDayEyeCatch = null
    lastTriggeredActionCharKey = null
    lastTriggeredActionName = null
    lastScreenShakeDurationSec = 0f
    lastFlashTriggered = false
    lastTransitionSpec = null

    if (choices.isNotEmpty()) {
      return snapshot()
    }

    while (instructionPointer < currentScript.nodes.size) {
      val node = currentScript.nodes[instructionPointer]
      instructionPointer += 1
      currentLineNumber = node.lineNumber

      when (node) {
        is ScenarioNode.Label,
        is ScenarioNode.Include -> Unit

        is ScenarioNode.EngineCall -> {
          handleEngineCall(node)
        }

        is ScenarioNode.Command -> {
          handleCommand(node)
          if (
            choices.isNotEmpty() ||
              lastTriggeredMovieFile != null ||
              lastTriggeredRouteJumpLabel != null
          ) {
            return snapshot()
          }
        }

        is ScenarioNode.Dialogue -> {
          currentSpeaker = node.speaker
          currentText = node.text
          currentVoiceId = node.voiceId
          currentVoiceVfsPath =
            node.voiceId?.let {
              if (aliasResolver != null) aliasResolver.resolveVoicePath(it) else "ボイス\\$it.ogg"
            }
          updateSpeakerFace(node.speaker)
          backlog.add(
            BacklogEntry(
              scriptPath = currentScript.path,
              lineNumber = node.lineNumber,
              speaker = node.speaker,
              text = node.text,
              voiceId = node.voiceId,
              voiceVfsPath = currentVoiceVfsPath,
            ),
          )
          return snapshot()
        }
      }
    }

    return snapshot()
  }

  private fun updateSpeakerFace(speaker: String?) {
    if (speaker.isNullOrBlank()) {
      currentFaceVfsPath = null
      currentFaceOffsetX = 0
      return
    }
    val cleanSpeaker = speaker.substringBefore('（').substringBefore('(').trim()
    val candidates =
      listOfNotNull(
          aliasResolver?.resolveCharKey(cleanSpeaker),
          aliasResolver?.resolveCharKey(speaker.trim()),
          if (cleanSpeaker == "美桜") "mi2" else null,
          if (cleanSpeaker == "美桜") "mio" else null,
        )
        .distinct()

    for (key in candidates) {
      val activeSlot = spriteSlots[key]
      if (activeSlot?.faceVfsPath != null) {
        currentFaceVfsPath = activeSlot.faceVfsPath
        currentFaceOffsetX = activeSlot.faceOffsetX
        return
      }
    }
    for (key in candidates) {
      val lastFace = lastCharFacePath[key]
      if (lastFace != null) {
        currentFaceVfsPath = lastFace.first
        currentFaceOffsetX = lastFace.second
        return
      }
    }
    currentFaceVfsPath = null
    currentFaceOffsetX = 0
  }

  /**
   * Selects choice [index], always invoking [onChoiceSelected] so `select.s` route flags
   * (`mioflag++`, `Game_RouteFlag[0]=...`) are updated, and either jumps to the target label inside
   * the current script or signals an external route jump (`lastTriggeredRouteJumpLabel`) to
   * `route.s`.
   */
  fun selectChoice(index: Int, onChoiceSelected: ((String) -> Unit)? = null): VmSnapshot {
    require(index in choices.indices) { "Choice index $index out of bounds" }
    val chosen = choices[index]
    choices.clear()
    onChoiceSelected?.invoke(chosen.targetLabel)
    if (!jumpToLabel(chosen.targetLabel)) {
      lastTriggeredRouteJumpLabel = chosen.targetLabel
      instructionPointer = currentScript.nodes.size
      return snapshot()
    }
    return stepToNextStop()
  }

  fun jumpToLabel(labelName: String): Boolean {
    val clean = labelName.removePrefix("@@").trim()
    val labelMap = currentScript.labelMap()
    val targetIdx =
      labelMap[clean]
        ?: labelMap.entries.firstOrNull { it.key.equals(clean, ignoreCase = true) }?.value
        ?: return false
    instructionPointer = targetIdx
    return true
  }

  /**
   * Continues execution seamlessly into [newScript] (e.g., `kyo_001_02.s -> Route.s ->
   * kyo_001_03.s`) while preserving all active stage state (backgrounds, overlays, weather,
   * sprites, faces, BGM) until the new script explicitly issues `^clear` or overrides them.
   */
  fun continueWithScript(newScript: ScenarioScript) {
    currentScript = newScript
    instructionPointer = 0
    currentLineNumber = 1
    choices.clear()
    lastTriggeredSeVfsPath = null
    lastTriggeredSeLoop = false
    lastStopSeRequested = false
    lastTriggeredMovieFile = null
    lastTriggeredRouteJumpLabel = null
    lastTriggeredDayEyeCatch = null
    lastTriggeredActionCharKey = null
    lastTriggeredActionName = null
    lastScreenShakeDurationSec = 0f
    lastFlashTriggered = false
    lastTransitionSpec = null
  }

  fun loadSnapshot(
    script: ScenarioScript,
    ip: Int,
    bgSymbol: String?,
    bgPath: String?,
    sprites: Map<String, SpriteSlotState>,
    bgmSymbol: String?,
    bgmPath: String?,
    dateStr: String = "04/16",
    routeStr: String = "通常",
  ) {
    currentScript = script
    instructionPointer = ip.coerceIn(0, script.nodes.size)
    inGameDate = dateStr
    routeName = routeStr
    backgroundSymbol = bgSymbol
    backgroundVfsPath = bgPath
    aliasResolver?.resolveBgLocate(bgPath ?: bgSymbol)?.let { sceneBgLocate = it }
    overlaySlots.clear()
    weatherSlots.clear()
    spriteSlots.clear()
    spriteSlots.putAll(sprites)
    charaParamStates.clear()
    lastCharFacePath.clear()
    currentFaceVfsPath = null
    currentFaceOffsetX = 0
    currentBgm = bgmSymbol
    currentBgmVfsPath = bgmPath
    choices.clear()
  }

  fun snapshot(): VmSnapshot =
    VmSnapshot(
      scriptPath = currentScript.path,
      instructionPointer = instructionPointer,
      currentLineNumber = currentLineNumber,
      inGameDate = inGameDate,
      routeName = routeName,
      backgroundSymbol = backgroundSymbol,
      backgroundVfsPath = backgroundVfsPath,
      overlayBgVfsPath = overlayBgVfsPath,
      weatherEffect = weatherEffect,
      flashTriggered = lastFlashTriggered,
      activeSprites = spriteSlots.toMap(),
      currentFaceVfsPath = currentFaceVfsPath,
      currentFaceOffsetX = currentFaceOffsetX,
      currentBgmSymbol = currentBgm,
      currentBgmVfsPath = currentBgmVfsPath,
      triggeredSeVfsPath = lastTriggeredSeVfsPath,
      triggeredSeLoop = lastTriggeredSeLoop,
      stopSeRequested = lastStopSeRequested,
      triggeredMovieFile = lastTriggeredMovieFile,
      triggeredRouteJumpLabel = lastTriggeredRouteJumpLabel,
      triggeredDayEyeCatch = lastTriggeredDayEyeCatch,
      triggeredActionCharKey = lastTriggeredActionCharKey,
      triggeredActionName = lastTriggeredActionName,
      screenShakeDurationSec = lastScreenShakeDurationSec,
      transitionSpec = lastTransitionSpec,
      currentSpeaker = currentSpeaker,
      currentText = currentText,
      currentVoiceId = currentVoiceId,
      currentVoiceVfsPath = currentVoiceVfsPath,
      pendingChoices = choices.toList(),
    )

  private fun classifyWeatherEffect(symbolOrPath: String): String? {
    val clean = symbolOrPath.substringAfterLast('\\').substringBeforeLast('.').lowercase()
    return when {
      clean == "桜" || clean == "桜s" || clean == "桜ss" -> "sakura"
      clean == "雨" || clean == "雨弱" -> "rain"
      else -> null
    }
  }

  private fun isFlashEffectSymbol(symbolOrPath: String): Boolean {
    val clean = symbolOrPath.substringAfterLast('\\').substringBeforeLast('.').lowercase()
    return clean == "フラッシュ" ||
      clean == "フラッシュmh" ||
      clean == "フラッシュmh2" ||
      clean == "白加算" ||
      clean == "白加算アウト" ||
      clean == "ホワイトアウト"
  }

  private fun updateBgSlot0(
    baseSymbol: String?,
    fadeName: String?,
    fadeMs: Float,
    fromAbgOrMbg: Boolean = false,
    fadeParam: String? = null,
  ) {
    val rawFade = fadeName?.trim()?.trim('"').orEmpty()
    val isCut = rawFade.equals("cut", ignoreCase = true) || rawFade.equals("カット", ignoreCase = true)
    val isMosaic =
      rawFade.equals("モザイク", ignoreCase = true) || rawFade.equals("mosaic", ignoreCase = true)
    val isWfade =
      rawFade.startsWith("wfade", ignoreCase = true) ||
        rawFade.startsWith("motion", ignoreCase = true) ||
        rawFade.endsWith(".b", ignoreCase = true)
    val featherVal = fadeParam?.trim()?.toIntOrNull()?.coerceIn(0, 7) ?: 3
    val effDuration =
      when {
        isCut -> 0.01f
        isWfade -> (fadeMs / 1000f).coerceIn(0.4f, 3.5f)
        else -> (fadeMs / 1000f).coerceIn(0.1f, 2.5f)
      }
    val effName =
      when {
        isMosaic -> "モザイク"
        isCut -> "cut"
        rawFade.isNotEmpty() -> rawFade
        else -> "crossfade"
      }
    val effMode = if (isMosaic) 4 else 0
    if (isWfade || isMosaic || lastTransitionSpec == null) {
      lastTransitionSpec =
        TransitionSpec(
          effectName = effName,
          durationSec = effDuration,
          mode = effMode,
          feather = featherVal,
        )
    }

    val cleanSym = baseSymbol?.trim()?.trim('"')?.substringBefore(':').orEmpty()
    if (
      cleanSym.isEmpty() ||
        cleanSym.equals("消去", ignoreCase = true) ||
        cleanSym.equals("off", ignoreCase = true)
    ) {
      if (!isWfade && (isAbg0Active || inSpGroup) && savedGroupBgVfsPath != null) {
        backgroundSymbol = savedGroupBgSymbol
        backgroundVfsPath = savedGroupBgVfsPath
        isAbg0Active = false
      } else {
        backgroundSymbol = "bg_bk"
        backgroundVfsPath = null
        savedGroupBgSymbol = null
        savedGroupBgVfsPath = null
        isAbg0Active = false
      }
      return
    }
    if (
      cleanSym.equals("bg_bk", ignoreCase = true) || cleanSym.equals("bg_wh", ignoreCase = true)
    ) {
      backgroundSymbol = cleanSym.lowercase()
      backgroundVfsPath = aliasResolver?.resolveBackgroundPath(cleanSym)
      savedGroupBgSymbol = null
      savedGroupBgVfsPath = null
      isAbg0Active = false
      return
    }

    val resolved = aliasResolver?.resolveBackgroundPath(cleanSym)
    backgroundSymbol = cleanSym
    backgroundVfsPath = resolved ?: cleanSym
    if (fromAbgOrMbg) {
      isAbg0Active = true
    } else {
      isAbg0Active = false
      savedGroupBgSymbol = backgroundSymbol
      savedGroupBgVfsPath = backgroundVfsPath
    }
    aliasResolver?.resolveBgLocate(resolved ?: cleanSym)?.let { sceneBgLocate = it }
  }

  private fun updateOverlaySlot(slotIdx: Int, symbol: String?) {
    val cleanSym = symbol?.trim()?.trim('"')?.substringBefore(':').orEmpty()
    if (
      cleanSym.isEmpty() ||
        cleanSym.equals("消去", ignoreCase = true) ||
        cleanSym.equals("off", ignoreCase = true)
    ) {
      overlaySlots.remove(slotIdx)
      weatherSlots.remove(slotIdx)
      return
    }

    val weather = classifyWeatherEffect(cleanSym)
    if (weather != null) {
      weatherSlots[slotIdx] = weather
      overlaySlots.remove(slotIdx)
      return
    }

    if (
      isFlashEffectSymbol(cleanSym) || (slotIdx >= 7 && cleanSym.equals("bg_wh", ignoreCase = true))
    ) {
      lastFlashTriggered = true
      overlaySlots.remove(slotIdx)
      return
    }

    val resolved = aliasResolver?.resolveBackgroundPath(cleanSym)
    if (resolved != null) {
      val resolvedWeather = classifyWeatherEffect(resolved)
      if (resolvedWeather != null) {
        weatherSlots[slotIdx] = resolvedWeather
        overlaySlots.remove(slotIdx)
      } else if (isFlashEffectSymbol(resolved)) {
        lastFlashTriggered = true
        overlaySlots.remove(slotIdx)
      } else {
        overlaySlots[slotIdx] = resolved
        if (slotIdx == 1) {
          aliasResolver.resolveBgLocate(resolved)?.let { sceneBgLocate = it }
        }
      }
    }
  }

  private fun applyFdiOrFade(isFdi: Boolean, args: List<String>) {
    if (isFdi) {
      val raw0 = args.getOrNull(0)?.trim()?.trim('"').orEmpty()
      val parsedMode = raw0.toIntOrNull()
      if (parsedMode != null) {
        val ms = args.getOrNull(1)?.trim()?.toFloatOrNull() ?: 400f
        val fileOrRule = args.getOrNull(2)?.trim()?.trim('"').orEmpty()
        val paramVal =
          args.getOrNull(4)?.trim()?.toIntOrNull()?.coerceIn(0, 7)
            ?: args.getOrNull(3)?.trim()?.toIntOrNull()?.coerceIn(0, 7)
            ?: 3
        val isMosaic =
          parsedMode == 4 ||
            fileOrRule.equals("モザイク", ignoreCase = true) ||
            fileOrRule.equals("mosaic", ignoreCase = true)
        val effName =
          when {
            isMosaic -> "モザイク"
            fileOrRule.isNotEmpty() -> fileOrRule
            parsedMode == 2 -> "colorfade"
            else -> "crossfade"
          }
        lastTransitionSpec =
          TransitionSpec(
            effectName = effName,
            durationSec = (ms / 1000f).coerceIn(0.05f, 3.5f),
            mode = if (isMosaic) 4 else parsedMode,
            feather = paramVal,
          )
        return
      }
    }
    val effArg = args.getOrNull(0)?.trim()?.trim('"').orEmpty()
    val ms = args.getOrNull(1)?.trim()?.toFloatOrNull() ?: 400f
    val featherVal = args.getOrNull(2)?.trim()?.toIntOrNull()?.coerceIn(0, 7) ?: 3
    val isMosaic =
      effArg.equals("モザイク", ignoreCase = true) || effArg.equals("mosaic", ignoreCase = true)
    val isCut = effArg.equals("cut", ignoreCase = true) || effArg.equals("カット", ignoreCase = true)
    lastTransitionSpec =
      TransitionSpec(
        effectName = effArg.ifEmpty { "crossfade" },
        durationSec = if (isCut) 0.01f else (ms / 1000f).coerceIn(0.05f, 3.5f),
        mode = if (isMosaic) 4 else 0,
        feather = featherVal,
      )
  }

  private fun handleEngineCall(call: ScenarioNode.EngineCall) {
    val dir = call.directive.lowercase()
    when {
      dir == "fdi" || dir == "fade" -> {
        val rawArgs =
          call.rawBody
            .trim()
            .let {
              if (it.startsWith("(") && it.endsWith(")")) it.substring(1, it.length - 1) else it
            }
            .removePrefix(",")
            .split(',')
            .map { it.trim() }
        applyFdiOrFade(isFdi = (dir == "fdi"), args = rawArgs)
      }

      dir == "sub" && call.rawBody.contains("EyeCatchDay", ignoreCase = true) -> {
        val parts = call.rawBody.split(',').map { it.trim().trim('"') }
        val dateArg =
          parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: parts.lastOrNull { it.isNotBlank() && it != "self" && !it.contains("EyeCatchDay") }
            ?: inGameDate
        inGameDate = dateArg
        lastTriggeredDayEyeCatch = dateArg
      }
    }
  }

  private fun handleCommand(cmd: ScenarioNode.Command) {
    val nameLower = cmd.name.lowercase()
    when {
      nameLower == "savedate" -> {
        cmd.args
          .firstOrNull()
          ?.takeIf { it.isNotBlank() }
          ?.let { dateArg ->
            lastTriggeredDayEyeCatch = dateArg
            inGameDate = dateArg
          }
      }

      nameLower == "sub" -> {
        val subName = cmd.args.firstOrNull()?.trim()?.trim('"').orEmpty()
        if (subName.contains("EyeCatchDay", ignoreCase = true)) {
          val dateArg =
            cmd.args.getOrNull(2)?.trim()?.trim('"')
              ?: cmd.args.getOrNull(1)?.trim()?.trim('"')
              ?: inGameDate
          inGameDate = dateArg
          lastTriggeredDayEyeCatch = dateArg
        }
      }

      nameLower == "saveroute" -> {
        cmd.args.firstOrNull()?.takeIf { it.isNotBlank() }?.let { routeName = it }
      }

      nameLower == "jmp" -> {
        val target = cmd.args.firstOrNull { it.startsWith("@@") } ?: cmd.args.firstOrNull()
        if (!target.isNullOrBlank()) {
          jumpToLabel(target)
        }
      }

      nameLower.startsWith("branch") && nameLower != "branchjmp" -> {
        val branchNum = nameLower.removePrefix("branch").toIntOrNull() ?: 1
        val branchLabel = "${scriptStem()}_Branch$branchNum"
        val outcome = branchEvaluator?.invoke(branchLabel)
        if (outcome != null) {
          if (outcome.isExternalRouteJump) {
            lastTriggeredRouteJumpLabel = outcome.targetLabel
            instructionPointer = currentScript.nodes.size
          } else {
            jumpToLabel(outcome.targetLabel)
          }
        }
      }

      nameLower == "movie" -> {
        val movieArg = cmd.args.firstOrNull()?.trim()?.trim('"')
        if (!movieArg.isNullOrEmpty()) {
          lastTriggeredMovieFile =
            if (movieArg.endsWith(".ogv", ignoreCase = true)) {
              movieArg
            } else {
              "VR_OP.ogv"
            }
        }
      }

      nameLower == "clear" -> {
        // QLiE avgsystem_clear.s:
        // ParamStr[0]: "" / "all" -> clear both music & graphics
        //              "music" / "mus" / "音楽" -> clear music & SE only
        //              "graphic" / "gra" / "画像" -> clear graphics only
        //              "グループ" -> clear group only
        val targetScope = cmd.args.getOrNull(0)?.trim()?.lowercase().orEmpty()
        val effectName = cmd.args.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "fade"
        val ms = cmd.args.getOrNull(2)?.trim()?.toFloatOrNull() ?: 800f

        val clearMusic =
          targetScope.isEmpty() ||
            targetScope == "all" ||
            targetScope == "music" ||
            targetScope == "mus" ||
            targetScope == "音楽"
        val clearGraphics =
          targetScope.isEmpty() ||
            targetScope == "all" ||
            targetScope == "graphic" ||
            targetScope == "gra" ||
            targetScope == "画像"

        if (clearMusic) {
          currentBgm = null
          currentBgmVfsPath = null
          lastStopSeRequested = true
          lastTriggeredSeVfsPath = null
          lastTriggeredSeLoop = false
        }
        if (clearGraphics) {
          lastTransitionSpec = TransitionSpec(effectName, (ms / 1000f).coerceIn(0.15f, 3.5f))
          spriteSlots.clear()
          lastCharFacePath.clear()
          currentFaceVfsPath = null
          currentFaceOffsetX = 0
          overlaySlots.clear()
          weatherSlots.clear()
          backgroundSymbol = "bg_bk"
          backgroundVfsPath = null
          savedGroupBgSymbol = null
          savedGroupBgVfsPath = null
          isAbg0Active = false
          inSpGroup = false
        }
        if (targetScope == "グループ") {
          inSpGroup = false
        }
      }

      nameLower == "spgroup" || nameLower == "group" -> {
        val arg0 = cmd.args.firstOrNull()?.trim().orEmpty()
        inSpGroup = arg0.isNotEmpty() && arg0 != "消去" && !arg0.equals("off", ignoreCase = true)
      }

      nameLower == "abg" || nameLower.startsWith("abg") -> {
        // イメージ設定_背景.txt: パラメータ引数abg = BaseFile,ImageFile,外部変数,Speed,SoundFile,SPParam
        val slotIdx = nameLower.removePrefix("abg").toIntOrNull() ?: 0
        val motionFile = cmd.args.getOrNull(0)?.trim().orEmpty()
        val imageFileArg = cmd.args.getOrNull(1)?.trim().orEmpty()
        val speedMs = cmd.args.getOrNull(3)?.trim()?.toFloatOrNull() ?: 250f
        val soundFileArg = cmd.args.getOrNull(4)?.trim()?.substringBefore(':').orEmpty()

        if (soundFileArg.isNotEmpty()) {
          aliasResolver?.resolveSePath(soundFileArg)?.let { sePath ->
            lastTriggeredSeVfsPath = sePath
            lastTriggeredSeLoop = false
            lastStopSeRequested = false
          }
        }

        if (isFlashEffectSymbol(motionFile) || motionFile.startsWith("戦闘瞬間")) {
          lastFlashTriggered = true
          if (motionFile.startsWith("戦闘瞬間")) {
            lastScreenShakeDurationSec = 0.25f
          }
        }

        val primaryImage =
          imageFileArg.split(':').map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "キャプチャ" }

        if (slotIdx == 0) {
          if (motionFile.startsWith("戦闘瞬間")) {
            // Transient ~80ms battle spark/clash effect: triggers flash + shake + SE without
            // overwriting persistent bg0
          } else if (primaryImage != null) {
            updateBgSlot0(primaryImage, "crossfade", speedMs, fromAbgOrMbg = true)
          } else if (
            motionFile.isEmpty() ||
              motionFile == "消去" ||
              motionFile.equals("off", ignoreCase = true)
          ) {
            updateBgSlot0("", "crossfade", speedMs, fromAbgOrMbg = false)
          }
        } else {
          if (motionFile.startsWith("戦闘瞬間")) {
            // Transient spark flash on overlay slot
          } else if (primaryImage != null) {
            updateOverlaySlot(slotIdx, primaryImage)
          } else {
            updateOverlaySlot(slotIdx, motionFile)
          }
        }
      }

      nameLower == "mbg" || nameLower.startsWith("mbg") -> {
        // イメージ設定_背景.txt: パラメータ引数mbg = BaseFile,ImageFile,SoundFile,Speed,SPParam
        val slotIdx = nameLower.removePrefix("mbg").toIntOrNull() ?: 0
        val motionFile = cmd.args.getOrNull(0)?.trim().orEmpty()
        val imageFileArg = cmd.args.getOrNull(1)?.trim().orEmpty()
        val soundFileArg = cmd.args.getOrNull(2)?.trim()?.substringBefore(':').orEmpty()
        val speedMs = cmd.args.getOrNull(3)?.trim()?.toFloatOrNull() ?: 300f

        if (soundFileArg.isNotEmpty()) {
          aliasResolver?.resolveSePath(soundFileArg)?.let { sePath ->
            lastTriggeredSeVfsPath = sePath
            lastTriggeredSeLoop = false
            lastStopSeRequested = false
          }
        }

        if (isFlashEffectSymbol(motionFile)) {
          lastFlashTriggered = true
        }

        val primaryImage =
          imageFileArg.split(':').map { it.trim() }.firstOrNull { it.isNotEmpty() && it != "キャプチャ" }

        if (slotIdx == 0) {
          if (primaryImage != null) {
            updateBgSlot0(primaryImage, "crossfade", speedMs, fromAbgOrMbg = true)
          }
        } else {
          if (primaryImage != null) {
            updateOverlaySlot(slotIdx, primaryImage)
          } else {
            updateOverlaySlot(slotIdx, motionFile)
          }
        }
      }

      nameLower == "fdi" || nameLower == "fade" -> {
        applyFdiOrFade(isFdi = (nameLower == "fdi"), args = cmd.args)
      }

      nameLower == "bg" || nameLower == "bg0" || nameLower.startsWith("bg") -> {
        // イメージ設定_背景.txt: パラメータ引数bg = BaseFile,Fade,Time,FadeParam,SPParam
        val slotIdx = nameLower.removePrefix("bg").toIntOrNull() ?: 0
        val baseFile = cmd.args.getOrNull(0)?.trim().orEmpty()
        val fadeName = cmd.args.getOrNull(1)?.trim().orEmpty()
        val timeArg = cmd.args.getOrNull(2)?.trim()?.toFloatOrNull()
        val rawFadeParam = cmd.args.getOrNull(3)?.trim()
        val fadeParam = cmd.args.firstOrNull { it.contains("FadeTime=", ignoreCase = true) }
        val fadeMs =
          timeArg
            ?: fadeParam?.substringAfter("FadeTime=", "")?.substringBefore(';')?.toFloatOrNull()
            ?: 400f

        if (slotIdx == 0) {
          updateBgSlot0(baseFile, fadeName, fadeMs, fromAbgOrMbg = false, fadeParam = rawFadeParam)
        } else {
          updateOverlaySlot(slotIdx, baseFile)
        }
      }

      nameLower == "achara" -> {
        val rawChar = cmd.args.getOrNull(0)?.trim().orEmpty()
        val action = cmd.args.getOrNull(1)?.trim().orEmpty()
        if (rawChar.isNotEmpty() && action.isNotEmpty()) {
          lastTriggeredActionCharKey = aliasResolver?.resolveCharKey(rawChar) ?: rawChar.lowercase()
          lastTriggeredActionName = action
        }
      }

      nameLower.startsWith("effect") -> {
        val firstArg = cmd.args.firstOrNull()?.trim().orEmpty()
        if (firstArg.equals("quake", ignoreCase = true)) {
          val ms = cmd.args.getOrNull(1)?.toFloatOrNull() ?: 250f
          lastScreenShakeDurationSec = (ms / 1000f).coerceIn(0.15f, 1.5f)
        }
      }

      nameLower.startsWith("chara") || nameLower.startsWith("ch") -> {
        val firstArg = cmd.args.firstOrNull()?.trim().orEmpty()
        if (firstArg.isEmpty() || firstArg == "消去" || firstArg.equals("off", ignoreCase = true)) {
          spriteSlots.clear()
          lastCharFacePath.clear()
        } else {
          val charKey = aliasResolver?.resolveCharKey(firstArg) ?: firstArg.lowercase()
          val existingSlot = spriteSlots[charKey]
          val existingParam = charaParamStates[charKey]
          val resolved =
            aliasResolver?.resolveSprite(
              charaArgs = cmd.args,
              existingState = existingParam,
              existingCenterX = existingSlot?.centerX,
              existingDistCode = existingSlot?.distCode,
              sceneBgLocate = sceneBgLocate,
            )
          if (resolved != null) {
            charaParamStates[resolved.charKey] = resolved.updatedParamState
            if (!resolved.visible) {
              spriteSlots.remove(resolved.charKey)
            } else {
              if (resolved.faceVfsPath != null) {
                lastCharFacePath[resolved.charKey] = resolved.faceVfsPath to resolved.faceOffsetX
              }
              spriteSlots[resolved.charKey] =
                SpriteSlotState(
                  charKey = resolved.charKey,
                  vfsPath = resolved.vfsPath,
                  centerX = resolved.centerX,
                  distCode = resolved.distCode,
                  faceVfsPath = resolved.faceVfsPath,
                  faceOffsetX = resolved.faceOffsetX,
                )
            }
          }
        }
      }

      nameLower.startsWith("music") || nameLower.startsWith("bgm") -> {
        val track = cmd.args.firstOrNull { it.isNotEmpty() && !it.all { c -> c.isDigit() } }
        if (
          track == null ||
            track == "消去" ||
            track.equals("off", ignoreCase = true) ||
            track.equals("stop", ignoreCase = true)
        ) {
          currentBgm = null
          currentBgmVfsPath = null
        } else {
          currentBgm = track
          currentBgmVfsPath = aliasResolver?.resolveBgmPath(track) ?: "音楽\\$track.ogg"
        }
      }

      nameLower.startsWith("select") && nameLower != "selectjmp" -> {
        choices.clear()
        val hasExplicitLabels = cmd.args.any { it.startsWith("@@") }
        if (hasExplicitLabels) {
          var i = 0
          while (i + 1 < cmd.args.size) {
            val text = cmd.args[i].replace('_', ' ')
            val label = cmd.args[i + 1]
            if (text.isNotEmpty() && label.isNotEmpty()) {
              choices.add(ChoiceOption(text = text, targetLabel = label))
            }
            i += 2
          }
        } else {
          val selNum = nameLower.removePrefix("select").toIntOrNull() ?: 1
          val stem = scriptStem()
          cmd.args
            .filter { it.isNotBlank() }
            .forEachIndexed { idx, rawOption ->
              val displayText = rawOption.replace('_', ' ').trim()
              val targetLabel = "@@${stem}_sel${selNum}_$idx"
              choices.add(ChoiceOption(text = displayText, targetLabel = targetLabel))
            }
        }
      }

      nameLower == "se" ||
        nameLower.startsWith("se0") ||
        nameLower.startsWith("se1") ||
        nameLower.startsWith("se2") -> {
        val seArg = cmd.args.firstOrNull()?.trim()
        val isLoop = cmd.args.any { it.contains("loop=1", ignoreCase = true) }
        if (
          seArg.isNullOrEmpty() ||
            seArg == "消去" ||
            seArg.equals("off", ignoreCase = true) ||
            seArg.equals("stop", ignoreCase = true)
        ) {
          lastStopSeRequested = true
          lastTriggeredSeVfsPath = null
          lastTriggeredSeLoop = false
        } else if (!seArg.all { c -> c.isDigit() } && !seArg.contains('=')) {
          lastTriggeredSeVfsPath = aliasResolver?.resolveSePath(seArg)
          lastTriggeredSeLoop = isLoop
          lastStopSeRequested = false
        }
      }
    }
  }
}
