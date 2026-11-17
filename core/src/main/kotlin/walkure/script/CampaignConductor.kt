package walkure.script

import walkure.pack.QlieVirtualFileSystem

/**
 * Master Campaign Conductor that parses `シナリオ\route.s`, `シナリオ\route*.s`, and `シナリオ\select.s`
 * to orchestrate scene-to-scene transitions, opening/ending movie triggers (`VR_OP.ogv`, `VR_ED_*.ogv`),
 * mid-script conditional branches (`^branch1`), external choice route jumps (`"Route"` in `select.s`),
 * and route flag evaluation (`mioflag`, `suiflag`, `noeflag`, `risflag`, `Game_RouteFlag[N]`).
 */
class CampaignConductor(val vfs: QlieVirtualFileSystem) {

    sealed interface CampaignStage {
        data class ScenarioFile(val vfsPath: String) : CampaignStage
        data class MoviePlayback(val movieFileName: String) : CampaignStage
        data class HeroineRouteEntry(val heroineKey: String) : CampaignStage
    }

    data class BranchOutcome(
        val targetLabel: String,
        val isExternalRouteJump: Boolean
    )

    val commonRouteSequence: List<CampaignStage>
    val heroineRoutes: Map<String, List<CampaignStage>>
    val selectChoiceEffects: Map<String, String>
    val selectFlagAssignments: Map<String, Pair<String, Int>>

    val routeFlags = LinkedHashMap<String, Int>().apply {
        put("mioflag", 0)
        put("suiflag", 0)
        put("noeflag", 0)
        put("risflag", 0)
        put("game_routeflag_0", 0)
        put("game_routeflag_10", 0)
    }

    init {
        commonRouteSequence = parseRouteSequence("シナリオ\\route.s")
        heroineRoutes = mapOf(
            "mio" to parseHeroineRoute("シナリオ\\route美桜.s", "VR_ED_MIO.ogv"),
            "sui" to parseHeroineRoute("シナリオ\\routeスィーリア.s", "VR_ED_Sui.ogv"),
            "noe" to parseHeroineRoute("シナリオ\\routeノエル.s", "VR_ED_Noel.ogv"),
            "ris" to parseHeroineRoute("シナリオ\\routeリサ.s", "VR_ED_Risa.ogv")
        )
        val parsedSelect = parseSelectEffects("シナリオ\\select.s")
        selectChoiceEffects = parsedSelect.first
        selectFlagAssignments = parsedSelect.second
    }

    /**
     * Evaluates a mid-script `^branch1` command (e.g. `kyo_011_08_Branch1` or `kyo_013_06_Branch1`)
     * according to `シナリオ\select.s`.
     */
    fun evaluateBranch(branchName: String): BranchOutcome? {
        val clean = branchName.removePrefix("@@").trim().lowercase()
        return when (clean) {
            "kyo_011_08_branch1" -> {
                if ((routeFlags["mioflag"] ?: 0) >= 3) {
                    BranchOutcome("@@kyo_011_08_Branch1_0", isExternalRouteJump = true)
                } else {
                    BranchOutcome("@@kyo_011_08_Branch1_1", isExternalRouteJump = false)
                }
            }

            "kyo_013_06_branch1" -> {
                if ((routeFlags["game_routeflag_0"] ?: 0) == 0) {
                    BranchOutcome("@@kyo_013_06_Branch1_0", isExternalRouteJump = true)
                } else {
                    BranchOutcome("@@kyo_013_06_Branch1_1", isExternalRouteJump = false)
                }
            }

            else -> null
        }
    }

    /**
     * Resolves an external route label jump triggered by `^branch1` or `^select1` into the next [CampaignStage].
     */
    fun resolveRouteJumpLabel(labelName: String): CampaignStage? {
        val clean = labelName.removePrefix("@@").trim().lowercase()
        return when (clean) {
            "kyo_011_08_branch1_0" -> heroineRoutes["mio"]?.firstOrNull()
            "kyo_013_06_branch1_0", "kyo_013_06_sel1_1" -> CampaignStage.ScenarioFile("シナリオ\\共通\\kyo_013_09.s")
            "kyo_014a_04_sel1_0" -> CampaignStage.ScenarioFile("シナリオ\\共通\\kyo_014c_00.s")
            "kyo_014a_04_sel1_1" -> CampaignStage.ScenarioFile("シナリオ\\共通\\kyo_014b_00.s")
            else -> null
        }
    }

    /**
     * Given the current scenario script path or movie file name, determines the next [CampaignStage]
     * in the campaign (`シナリオ\route.s` or active heroine route).
     */
    fun nextStageAfter(currentScriptOrMovie: String): CampaignStage? {
        val normCurrent = currentScriptOrMovie.replace('/', '\\').lowercase()
        val fileOnly = normCurrent.substringAfterLast('\\')

        // Handle explicit end-of-branch transitions in route.s
        when (fileOnly) {
            "vr_op.ogv" -> return CampaignStage.ScenarioFile("シナリオ\\共通\\kyo_002_00.s")
            "kyo_013_06.s" -> return CampaignStage.ScenarioFile("シナリオ\\共通\\kyo_013_07.s")
            "kyo_013_07.s" -> return CampaignStage.ScenarioFile("シナリオ\\共通\\kyo_013_08.s")
            "kyo_013_08.s" -> return heroineRoutes["sui"]?.firstOrNull()
            "kyo_014b_02.s" -> return heroineRoutes["noe"]?.firstOrNull()
            "kyo_014c_09.s" -> return heroineRoutes["ris"]?.firstOrNull()
        }

        // 1. Check common route sequence
        val commonIdx = commonRouteSequence.indexOfFirst { stage ->
            when (stage) {
                is CampaignStage.ScenarioFile -> stage.vfsPath.lowercase() == normCurrent || stage.vfsPath.lowercase().endsWith("\\$fileOnly")
                is CampaignStage.MoviePlayback -> stage.movieFileName.equals(fileOnly, ignoreCase = true)
                is CampaignStage.HeroineRouteEntry -> false
            }
        }
        if (commonIdx >= 0 && commonIdx + 1 < commonRouteSequence.size) {
            return unwrapStage(commonRouteSequence[commonIdx + 1])
        }

        // 2. Check heroine routes
        for ((_, stages) in heroineRoutes) {
            val idx = stages.indexOfFirst { stage ->
                when (stage) {
                    is CampaignStage.ScenarioFile -> stage.vfsPath.lowercase() == normCurrent || stage.vfsPath.lowercase().endsWith("\\$fileOnly")
                    is CampaignStage.MoviePlayback -> stage.movieFileName.equals(fileOnly, ignoreCase = true)
                    is CampaignStage.HeroineRouteEntry -> false
                }
            }
            if (idx >= 0 && idx + 1 < stages.size) {
                return unwrapStage(stages[idx + 1])
            }
        }

        // 3. Fallback alphabetical progression within the same directory
        val allScenarios = vfs.listEntries("シナリオ\\")
            .filter { it.path.endsWith(".s", ignoreCase = true) && it.path.count { c -> c == '\\' } >= 2 }
            .sortedBy { it.normalizedPath }
        val curIdx = allScenarios.indexOfFirst { it.normalizedPath == normCurrent }
        return if (curIdx >= 0 && curIdx + 1 < allScenarios.size) {
            CampaignStage.ScenarioFile(allScenarios[curIdx + 1].path)
        } else {
            null
        }
    }

    private fun unwrapStage(stage: CampaignStage): CampaignStage? {
        return when (stage) {
            is CampaignStage.HeroineRouteEntry -> heroineRoutes[stage.heroineKey]?.firstOrNull()
            else -> stage
        }
    }

    fun applyChoiceLabelEffect(labelName: String) {
        val clean = labelName.removePrefix("@@").trim().lowercase()
        selectChoiceEffects[clean]?.let { flag ->
            routeFlags[flag] = (routeFlags[flag] ?: 0) + 1
        }
        selectFlagAssignments[clean]?.let { (key, value) ->
            routeFlags[key] = value
        }
    }

    fun selectWinningHeroine(): String {
        val heroineKeys = listOf("mioflag", "suiflag", "noeflag", "risflag")
        return heroineKeys.maxByOrNull { routeFlags[it] ?: 0 }?.removeSuffix("flag") ?: "sui"
    }

    private fun parseRouteSequence(routeVfsPath: String): List<CampaignStage> {
        if (!vfs.exists(routeVfsPath)) return emptyList()
        val text = vfs.readTextShiftJis(routeVfsPath)
        val stages = ArrayList<CampaignStage>()
        val jmpRegex = Regex("""\\(?:jmp|sub)\s*,\s*@@TOP\s*,\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("@@@Route美桜", ignoreCase = true) -> stages.add(CampaignStage.HeroineRouteEntry("mio"))
                trimmed.startsWith("@@@Routeスィーリア", ignoreCase = true) -> stages.add(CampaignStage.HeroineRouteEntry("sui"))
                trimmed.startsWith("@@@Routeノエル", ignoreCase = true) -> stages.add(CampaignStage.HeroineRouteEntry("noe"))
                trimmed.startsWith("@@@Routeリサ", ignoreCase = true) -> stages.add(CampaignStage.HeroineRouteEntry("ris"))
                else -> {
                    val match = jmpRegex.find(trimmed) ?: continue
                    val target = match.groupValues[1].replace('/', '\\')
                    if (target.endsWith("OP_Movie.s", ignoreCase = true)) {
                        stages.add(CampaignStage.MoviePlayback("VR_OP.ogv"))
                    } else if (vfs.exists(target)) {
                        stages.add(CampaignStage.ScenarioFile(target))
                    }
                }
            }
        }
        return stages
    }

    private fun parseHeroineRoute(routeVfsPath: String, edMovieName: String): List<CampaignStage> {
        if (!vfs.exists(routeVfsPath)) return emptyList()
        val text = vfs.readTextShiftJis(routeVfsPath)
        val stages = ArrayList<CampaignStage>()
        val jmpRegex = Regex("""\\(?:jmp|sub)\s*,\s*@@TOP\s*,\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            val match = jmpRegex.find(trimmed) ?: continue
            val target = match.groupValues[1].replace('/', '\\')
            if (target.endsWith("ED_Movie.s", ignoreCase = true)) {
                stages.add(CampaignStage.MoviePlayback(edMovieName))
            } else if (vfs.exists(target)) {
                stages.add(CampaignStage.ScenarioFile(target))
            }
        }
        if (stages.none { it is CampaignStage.MoviePlayback }) {
            stages.add(CampaignStage.MoviePlayback(edMovieName))
        }
        return stages
    }

    private fun parseSelectEffects(selectVfsPath: String): Pair<Map<String, String>, Map<String, Pair<String, Int>>> {
        if (!vfs.exists(selectVfsPath)) return emptyMap<String, String>() to emptyMap()
        val text = vfs.readTextShiftJis(selectVfsPath)
        val incMap = HashMap<String, String>()
        val assignMap = HashMap<String, Pair<String, Int>>()
        var currentLabel: String? = null
        val flagIncRegex = Regex("""\\cal\s*,\s*(mioflag|suiflag|noeflag|risflag)\+\+""", RegexOption.IGNORE_CASE)
        val flagAssignRegex = Regex("""\\cal\s*,\s*Game_RouteFlag\[(\d+)\]\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("@@")) {
                currentLabel = trimmed.removePrefix("@@").trim().lowercase()
            } else if (currentLabel != null) {
                flagIncRegex.find(trimmed)?.let { m ->
                    incMap[currentLabel] = m.groupValues[1].lowercase()
                }
                flagAssignRegex.find(trimmed)?.let { m ->
                    val idx = m.groupValues[1]
                    val value = m.groupValues[2].toIntOrNull() ?: 0
                    assignMap[currentLabel] = "game_routeflag_$idx" to value
                }
            }
        }
        return incMap to assignMap
    }
}
