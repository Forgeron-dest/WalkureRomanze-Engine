package walkure.script

import walkure.pack.QlieVirtualFileSystem

/**
 * AST Representation of QLiE `.s` Scenario Scripts (`data5.pack`, `data6.pack`, `data7.pack`, `data10.pack`).
 */
sealed interface ScenarioNode {
    val lineNumber: Int

    data class Label(
        override val lineNumber: Int,
        val name: String
    ) : ScenarioNode

    data class Include(
        override val lineNumber: Int,
        val targetPath: String
    ) : ScenarioNode

    data class Command(
        override val lineNumber: Int,
        val name: String,
        val args: List<String>
    ) : ScenarioNode

    data class EngineCall(
        override val lineNumber: Int,
        val directive: String,
        val rawBody: String
    ) : ScenarioNode

    data class Dialogue(
        override val lineNumber: Int,
        val voiceId: String?,
        val speaker: String?,
        val text: String
    ) : ScenarioNode
}

data class ScenarioScript(
    val path: String,
    val nodes: List<ScenarioNode>
) {
    fun dialogueNodes(): List<ScenarioNode.Dialogue> =
        nodes.filterIsInstance<ScenarioNode.Dialogue>()

    fun commandNodes(): List<ScenarioNode.Command> =
        nodes.filterIsInstance<ScenarioNode.Command>()

    fun labelMap(): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        nodes.forEachIndexed { idx, node ->
            if (node is ScenarioNode.Label) {
                map[node.name] = idx
            }
        }
        return map
    }
}

/**
 * Parser for QLiE `.s` scenario scripts.
 *
 * Handles:
 * - `//` comments
 * - `@@@path` script includes
 * - `@@label` jump targets
 * - `\keyword(...)` engine macros (`\sub`, `\if`, `\else`, `\endif`, `\let`, `\call`)
 * - `^cmd,arg1,arg2,...` AVG system commands (`^bg0`, `^abg0`, `^mbg0`, `^chara`, `^music`, `^se0`, `^select`, `^wait`)
 * - `voiceId,Speaker,「Dialogue」` inline voice+speaker lines as well as `%voice_id`, `[voice]`, and `【Speaker】` tags.
 */
object ScenarioParser {
    private val bracketTagRegex = Regex("""^\[([^\]]+)\]$""")
    private val jpSpeakerRegex = Regex("""^【([^】]+)】(.*)$""")
    private val inlineVoiceSpeakerRegex = Regex("""^([A-Za-z0-9_]+),([^,「"]+),(.*)$""")

    fun parse(scriptPath: String, content: String): ScenarioScript {
        val rawLines = content.lines()
        val nodes = ArrayList<ScenarioNode>()

        var pendingVoice: String? = null
        var pendingSpeaker: String? = null
        val pendingDialogueLines = ArrayList<String>()
        var dialogueStartLine = 1

        fun flushPendingDialogue() {
            if (pendingDialogueLines.isNotEmpty()) {
                val joined = pendingDialogueLines.joinToString("\n").trim()
                if (joined.isNotEmpty()) {
                    nodes.add(
                        ScenarioNode.Dialogue(
                            lineNumber = dialogueStartLine,
                            voiceId = pendingVoice,
                            speaker = pendingSpeaker,
                            text = joined
                        )
                    )
                }
                pendingVoice = null
                pendingSpeaker = null
                pendingDialogueLines.clear()
            }
        }

        for ((idx, rawLine) in rawLines.withIndex()) {
            val lineNum = idx + 1
            val trimmed = rawLine.trim()

            if (trimmed.isEmpty()) {
                flushPendingDialogue()
                continue
            }

            if (trimmed.startsWith("//") || trimmed.startsWith(";")) {
                continue
            }

            when {
                trimmed.startsWith("@@@") -> {
                    flushPendingDialogue()
                    nodes.add(ScenarioNode.Include(lineNum, trimmed.removePrefix("@@@").trim()))
                }

                trimmed.startsWith("@@") -> {
                    flushPendingDialogue()
                    nodes.add(ScenarioNode.Label(lineNum, trimmed.removePrefix("@@").trim()))
                }

                trimmed.startsWith("^") -> {
                    flushPendingDialogue()
                    val body = trimmed.removePrefix("^").trim()
                    val parts = body.split(',').map { it.trim().trim('"') }
                    val cmdName = parts.firstOrNull().orEmpty()
                    val cmdArgs = if (parts.size > 1) parts.subList(1, parts.size) else emptyList()
                    nodes.add(ScenarioNode.Command(lineNum, cmdName, cmdArgs))
                }

                trimmed.startsWith("\\") -> {
                    flushPendingDialogue()
                    val body = trimmed.removePrefix("\\").trim()
                    val splitIdx = body.indexOfFirst { it == ' ' || it == '(' || it == ',' }
                    val dir = if (splitIdx > 0) body.substring(0, splitIdx) else body
                    val rest = if (splitIdx > 0) body.substring(splitIdx).trim() else ""
                    nodes.add(ScenarioNode.EngineCall(lineNum, dir, rest))
                }

                trimmed.startsWith("%") -> {
                    flushPendingDialogue()
                    pendingVoice = trimmed.removePrefix("%").trim()
                    dialogueStartLine = lineNum
                }

                else -> {
                    val bracketMatch = bracketTagRegex.matchEntire(trimmed)
                    if (bracketMatch != null) {
                        val tag = bracketMatch.groupValues[1].trim()
                        if (looksLikeVoiceId(tag) && pendingVoice == null && pendingDialogueLines.isEmpty()) {
                            pendingVoice = tag
                            dialogueStartLine = lineNum
                        } else if (pendingSpeaker == null && pendingDialogueLines.isEmpty()) {
                            pendingSpeaker = tag
                            dialogueStartLine = lineNum
                        } else {
                            if (pendingDialogueLines.isEmpty()) dialogueStartLine = lineNum
                            pendingDialogueLines.add(trimmed)
                        }
                    } else {
                        val jpMatch = jpSpeakerRegex.matchEntire(trimmed)
                        val inlineMatch = inlineVoiceSpeakerRegex.matchEntire(trimmed)
                        if (inlineMatch != null && pendingDialogueLines.isEmpty()) {
                            val rawVoice = inlineMatch.groupValues[1].trim()
                            pendingVoice = rawVoice.takeUnless { it.equals("none", ignoreCase = true) }
                            pendingSpeaker = inlineMatch.groupValues[2].trim()
                            dialogueStartLine = lineNum
                            pendingDialogueLines.add(inlineMatch.groupValues[3].trim())
                        } else if (jpMatch != null && pendingDialogueLines.isEmpty()) {
                            pendingSpeaker = jpMatch.groupValues[1].trim()
                            dialogueStartLine = lineNum
                            val remainder = jpMatch.groupValues[2].trim()
                            if (remainder.isNotEmpty()) {
                                pendingDialogueLines.add(remainder)
                            }
                        } else {
                            if (pendingDialogueLines.isEmpty()) dialogueStartLine = lineNum
                            pendingDialogueLines.add(trimmed)
                        }
                    }
                }
            }
        }

        flushPendingDialogue()
        return ScenarioScript(path = scriptPath, nodes = nodes)
    }

    private fun looksLikeVoiceId(tag: String): Boolean {
        return tag.contains('_') && tag.any { it.isDigit() } && !tag.contains(' ')
    }
}

/**
 * Resolves symbolic asset names used in `.s` scripts (`^bg0`, `^abg0`, `^mbg0`, `^chara`, `^music`, `^se0`, voice IDs)
 * against the mounted [QlieVirtualFileSystem] and `data6.pack` lookup tables (`ライブラリ\avgシステム\リスト\*.txt`).
 */
class AssetAliasResolver(private val vfs: QlieVirtualFileSystem) {
    private val charaNameAliases = HashMap<String, String>()
    private val distanceAliases = HashMap<String, String>()
    private val positionXAliases = HashMap<String, Int>()
    private val fileAliases = HashMap<String, String>()
    private val faceCostumeAliases = HashMap<String, String>()
    private val bgLocationMap = HashMap<String, String>()
    private val spriteAdjustXMap = HashMap<String, Int>()

    private val charSpritesByCode: Map<String, List<walkure.pack.QliePackEntry>>
    private val faceSpritesByCode: Map<String, List<walkure.pack.QliePackEntry>>

    init {
        loadKeyValueTable("ライブラリ\\avgシステム\\リスト\\名前alias.txt") { k, v ->
            charaNameAliases[k] = v.lowercase()
            charaNameAliases[k.lowercase()] = v.lowercase()
        }
        loadKeyValueTable("ライブラリ\\avgシステム\\リスト\\距離alias.txt") { k, v ->
            distanceAliases[k] = v.lowercase()
        }
        loadKeyValueTable("ライブラリ\\avgシステム\\リスト\\場所alias.txt") { k, v ->
            v.toIntOrNull()?.let { positionXAliases[k.lowercase()] = it }
        }
        loadKeyValueTable("ライブラリ\\avgシステム\\リスト\\ファイル名エイリアス.txt") { k, v ->
            fileAliases[k.lowercase()] = v
        }
        loadKeyValueTable("ライブラリ\\avgシステム\\リスト\\フェイス服装alias.txt") { k, v ->
            faceCostumeAliases[k.lowercase()] = v.lowercase()
        }
        loadKeyValueTable("ライブラリ\\avgシステム\\リスト\\背景ロケーションリスト.txt") { k, v ->
            bgLocationMap[k.lowercase()] = v.lowercase()
        }
        loadKeyValueTable("ライブラリ\\avgシステム\\リスト\\立ち絵位置調整リスト.txt") { k, v ->
            v.toIntOrNull()?.let { spriteAdjustXMap[k.lowercase()] = it }
        }

        charSpritesByCode = vfs.listEntries("立ち絵\\")
            .filter { it.normalizedPath.endsWith(".argb") }
            .groupBy { entry ->
                entry.normalizedPath.substringAfterLast('\\').substringBefore('_')
            }

        faceSpritesByCode = vfs.listEntries("フェイス\\")
            .filter { it.normalizedPath.endsWith(".argb") }
            .groupBy { entry ->
                entry.normalizedPath.substringAfterLast('\\').substringBefore('_')
            }
    }

    private fun loadKeyValueTable(vfsPath: String, onPair: (String, String) -> Unit) {
        if (!vfs.exists(vfsPath)) return
        runCatching {
            val text = vfs.readTextShiftJis(vfsPath)
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith(";")) continue
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val key = trimmed.substring(0, eqIdx).trim().trim('"')
                    val value = trimmed.substring(eqIdx + 1).trim().trim('"')
                    onPair(key, value)
                } else if (trimmed.contains(',')) {
                    val cols = trimmed.split(',').map { it.trim().trim('"') }
                    if (cols.size >= 2 && cols[0].isNotEmpty() && cols[1].isNotEmpty()) {
                        onPair(cols[0], cols[1])
                    }
                }
            }
        }
    }

    fun resolveBackgroundPath(symbolOrName: String): String? {
        val clean = symbolOrName.trim().trim('"').substringBefore(':')
        if (clean.isEmpty() || clean == "キャプチャ") return null
        val lower = clean.lowercase()
        val mapped = fileAliases[lower]
        if (mapped != null) {
            if (vfs.exists(mapped)) return mapped
            val directImg = "画像\\$mapped"
            if (vfs.exists(directImg)) return directImg
        }

        val searchTerms = listOfNotNull(mapped?.lowercase(), lower).distinct()
        for (term in searchTerms) {
            for (ext in listOf(".jpg", ".png", ".b", ".argb")) {
                val direct = "画像\\$term$ext"
                val resolved = vfs.resolveEntry(direct)?.second
                if (resolved != null) return resolved.path
            }
            val candidates = vfs.listEntries(term).filter {
                it.normalizedPath.endsWith(".jpg") ||
                    it.normalizedPath.endsWith(".png") ||
                    it.normalizedPath.endsWith(".argb") ||
                    it.normalizedPath.endsWith(".b")
            }
            val match = candidates.firstOrNull {
                val fileNameNoExt = it.normalizedPath.substringAfterLast('\\').substringBeforeLast('.')
                fileNameNoExt == term
            }?.path ?: candidates.firstOrNull()?.path
            if (match != null) return match
        }
        return null
    }

    /**
     * Resolves QLiE `BgLocate` (`d` = day/indoor, `e` = evening, `n` = night) for a background symbol or VFS path
     * using `背景ロケーションリスト.txt` (`avgsystem_images.s` lines 11386-11409).
     */
    fun resolveBgLocate(symbolOrPath: String?): String? {
        if (symbolOrPath.isNullOrBlank()) return null
        val clean = symbolOrPath.trim().trim('"').substringBefore(':')
        val mapped = fileAliases[clean.lowercase()] ?: clean
        val stem = mapped
            .substringAfterLast('\\')
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .lowercase()
            .removeSuffix("_l")
        bgLocationMap[stem]?.let { return it }
        return when {
            stem.contains("_e_") -> "e"
            stem.contains("_n_") -> "n"
            stem.contains("_d_") -> "d"
            else -> null
        }
    }

    data class CharaParamState(
        val charKey: String,
        val rawName: String,
        val pose: String = "a1",
        val blush: String = "f",
        val posName: String = "center",
        val distRaw: String = "中",
        val costume: String = "a"
    )

    data class ResolvedSprite(
        val charKey: String,
        val visible: Boolean,
        val vfsPath: String,
        val faceVfsPath: String?,
        val centerX: Int,
        val faceOffsetX: Int,
        val distCode: String,
        val updatedParamState: CharaParamState
    )

    fun resolveCharKey(rawCharName: String): String {
        val clean = rawCharName.trim()
        return charaNameAliases[clean] ?: charaNameAliases[clean.lowercase()] ?: clean.lowercase()
    }

    /**
     * Resolves QLiE `^chara` arguments according to `イメージ設定_立ち絵.txt` and `avgsystem_chara.s`:
     * - `args[0]` (`名前`): Character name (e.g. `美桜`, `美桜ロング`, `スィーリア`)
     * - `args[1]` (`表情`): Expression (`a01`, `b06`, etc.). Cleared on every call; if empty, hides the sprite while preserving sticky state.
     * - `args[2]` (`ポーズ`): Pose (`a1`, `b2`, `c1`, etc.) — sticky per character unless `@`.
     * - `args[3]` (`ほほ染め`): Blush (`f`, `t`, `h`) — sticky per character unless `@`.
     * - `args[4]` (`位置`): Screen position (`LEFT`, `CENTER`, `RIGHT`, `FACE`, etc.) — sticky per character unless `@`.
     * - `args[5]` (`距離`): Distance (`遠`=`f`, `中`=`u`, `近`=`n`) — sticky per character unless `@`.
     * - `args[6]` (`服装`): Costume (`a`=school, `b`=casual, `c`=armor, etc.) — sticky per character unless `@`.
     *
     * Path format:
     * - Standing: `立ち絵\${bgLocate}\${distCode}\${charCode}_${bgLocate}_${distCode}_${pose}${costume}_${expr}_${blush}.argb`
     * - Face: `フェイス\${charCode}_w_${pose}${faceCostume}_${expr}_${blush}.argb`
     */
    fun resolveSprite(
        charaArgs: List<String>,
        existingState: CharaParamState? = null,
        existingCenterX: Int? = null,
        existingDistCode: String? = null,
        sceneBgLocate: String = "d"
    ): ResolvedSprite? {
        if (charaArgs.isEmpty()) return null
        val rawCharName = charaArgs.getOrNull(0)?.trim().orEmpty()
        if (rawCharName.isEmpty() || rawCharName == "消去" || rawCharName.equals("off", ignoreCase = true)) {
            return null
        }

        val charCode = resolveCharKey(rawCharName)

        fun mergeSticky(argIdx: Int, prev: String, defaultVal: String): String {
            val raw = charaArgs.getOrNull(argIdx)?.trim().orEmpty()
            return when {
                raw == "@" -> defaultVal
                raw.isNotEmpty() -> raw
                prev.isNotEmpty() -> prev
                else -> defaultVal
            }
        }

        val expr = charaArgs.getOrNull(1)?.trim()?.lowercase().orEmpty()
        val pose = mergeSticky(2, existingState?.pose.orEmpty(), "a1").lowercase()
        val blush = mergeSticky(3, existingState?.blush.orEmpty(), "f").lowercase()
        val posName = mergeSticky(4, existingState?.posName.orEmpty(), "center").lowercase()
        val distRaw = mergeSticky(5, existingState?.distRaw.orEmpty(), "中")
        val costume = mergeSticky(6, existingState?.costume.orEmpty(), "a").lowercase()

        val updatedState = CharaParamState(
            charKey = charCode,
            rawName = rawCharName,
            pose = pose,
            blush = blush,
            posName = posName,
            distRaw = distRaw,
            costume = costume
        )

        val distCode = distanceAliases[distRaw]
            ?: distRaw.lowercase().takeIf { it in setOf("f", "u", "n") }
            ?: existingDistCode
            ?: "u"

        val basePosX = positionXAliases[posName]
            ?: posName.toIntOrNull()
            ?: existingCenterX
            ?: 512

        val offsetX = spriteAdjustXMap["${charCode}_${distCode}_${pose}"] ?: 0
        val centerX = if (basePosX > 2000) basePosX else (basePosX - offsetX).coerceIn(64, 960)
        val faceOffsetX = -(spriteAdjustXMap["${charCode}_w_${pose}"] ?: 0)

        // In QLiE (avgsystem_chara.s lines 7533-7535), if 表情 (args[1]) is empty, Visible = 0
        if (expr.isEmpty()) {
            return ResolvedSprite(
                charKey = charCode,
                visible = false,
                vfsPath = "",
                faceVfsPath = null,
                centerX = centerX,
                faceOffsetX = faceOffsetX,
                distCode = distCode,
                updatedParamState = updatedState
            )
        }

        val bgLocate = sceneBgLocate.lowercase().ifEmpty { "d" }
        val exactStandingPath = "立ち絵\\$bgLocate\\$distCode\\${charCode}_${bgLocate}_${distCode}_${pose}${costume}_${expr}_${blush}.argb"
        val resolvedStanding = vfs.resolveEntry(exactStandingPath)?.second?.path ?: run {
            val allCharSprites = charSpritesByCode[charCode].orEmpty()
            if (allCharSprites.isEmpty()) return null
            val poseCostume = "${pose}${costume}"
            allCharSprites.maxByOrNull { entry ->
                val file = entry.normalizedPath.substringAfterLast('\\')
                var score = 0
                if (file.startsWith("${charCode}_${bgLocate}_")) score += 16
                if (file.contains("_${distCode}_")) score += 32
                if (file.contains("_${poseCostume}_")) score += 128
                else if (file.contains("_${pose}")) score += 24
                if (file.contains("_${expr}_")) score += 64
                if (file.endsWith("_${blush}.argb")) score += 8
                score
            }?.path ?: return null
        }

        val faceCostume = faceCostumeAliases[costume] ?: costume
        val exactFacePath = "フェイス\\${charCode}_w_${pose}${faceCostume}_${expr}_${blush}.argb"
        val resolvedFace = vfs.resolveEntry(exactFacePath)?.second?.path ?: run {
            val allFaces = faceSpritesByCode[charCode].orEmpty()
            val poseFaceCostume = "${pose}${faceCostume}"
            allFaces.maxByOrNull { entry ->
                val file = entry.normalizedPath.substringAfterLast('\\')
                var score = 0
                if (file.contains("_w_${poseFaceCostume}_")) score += 128
                else if (file.contains("_w_${pose}")) score += 24
                if (file.contains("_${expr}_")) score += 64
                if (file.endsWith("_${blush}.argb")) score += 8
                score
            }?.path
        }

        return ResolvedSprite(
            charKey = charCode,
            visible = true,
            vfsPath = resolvedStanding,
            faceVfsPath = resolvedFace,
            centerX = centerX,
            faceOffsetX = faceOffsetX,
            distCode = distCode,
            updatedParamState = updatedState
        )
    }

    fun resolveBgmPath(bgmSymbol: String): String? {
        val clean = bgmSymbol.trim().trim('"').removeSuffix(".ogg").lowercase()
        if (clean.isEmpty() || clean == "消去" || clean == "off") return null
        val direct = "音楽\\$clean.ogg"
        if (vfs.exists(direct)) return direct
        return vfs.listEntries("音楽\\")
            .firstOrNull { it.normalizedPath.endsWith("$clean.ogg") || it.normalizedPath.contains(clean) }
            ?.path
    }

    fun resolveSePath(seSymbol: String): String? {
        val clean = seSymbol.trim().trim('"').removeSuffix(".ogg").lowercase()
        if (clean.isEmpty() || clean == "消去" || clean == "off") return null
        val direct = "効果音\\$clean.ogg"
        if (vfs.exists(direct)) return direct
        return vfs.listEntries(".ogg")
            .firstOrNull { it.normalizedPath.endsWith("\\$clean.ogg") }
            ?.path
    }

    fun resolveVoicePath(voiceId: String): String? {
        val clean = voiceId.trim().trim('"').removeSuffix(".ogg").lowercase()
        if (clean.isEmpty() || clean == "none") return null
        val direct = "ボイス\\$clean.ogg"
        if (vfs.exists(direct)) return direct
        return vfs.listEntries("ボイス\\")
            .firstOrNull { it.normalizedPath.endsWith("$clean.ogg") || it.normalizedPath.contains(clean) }
            ?.path
    }
}
