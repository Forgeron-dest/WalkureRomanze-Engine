package walkure.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.badlogic.gdx.utils.viewport.FitViewport
import com.badlogic.gdx.utils.viewport.StretchViewport
import com.badlogic.gdx.utils.viewport.Viewport
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import ktx.app.KtxApplicationAdapter
import ktx.app.clearScreen
import ktx.graphics.use
import walkure.config.AlbumCgGroup
import walkure.config.AvgSystemSettings
import walkure.config.GameDatConfig
import walkure.config.SystemDatParser
import walkure.config.SystemMessages
import walkure.config.VoiceActorChannel
import walkure.crypto.QlieBufferCipher
import walkure.image.AbmotionParser
import walkure.pack.QlieVirtualFileSystem
import walkure.save.AspectRatioMode
import walkure.save.SaveManager
import walkure.save.SaveSlotMetadata
import walkure.script.AssetAliasResolver
import walkure.script.CampaignConductor
import walkure.script.CampaignConductor.CampaignStage
import walkure.script.ScenarioParser
import walkure.script.ScenarioVm
import walkure.video.OgvVideoPlayer
import walkure.video.VideoPlayerPlatform

/**
 * Full-Featured Cross-Platform Visual Novel Engine for *Walkure Romanze* built on **LibGDX + KTX**.
 *
 * Implements:
 * - **Title Screen (`EngineMode.TITLE`)**: Renders composite `スクリプト\title\back.b` layers
 *   (`Scene01a_bg01`, `Scene03_Front`, `WhiteUnder`, `Logo_馬`, `Logo_title`, `Logo_SubTitle`),
 *   falling sakura petals, and animated vertical sprite-strip `.b` buttons (`data7.pack`) +
 *   `音楽\op_inst.ogg`.
 * - **Master Campaign & Gameplay (`EngineMode.GAMEPLAY`)**: Authentic QLiE `MessageWindow.b`,
 *   `FaceImage.b` + character `.argb` face portraits, animated `CursorImage.b`, animated
 *   `WindowButton_*.b` HUD controls, `SelectButton01.b` choice bars, `.argb` sprites with sticky
 *   pose/costume/blush, `^abg`/`^mbg` CGs & cut-ins, sakura/rain particle systems, and debug `Prev
 *   Scene` / `Next Scene` buttons.
 * - **Multi-Page Save / Load (`EngineMode.SAVE_LOAD`)**: Authentic `button_data.b` cards,
 *   `underframe.b`, `page01..04.png`, `103×77` framebuffer thumbnails, and heroine route badges
 *   (`chara_*.png`).
 * - **Config / Options (`EngineMode.CONFIG`)**: Authentic `back_frame.png`, `frame_basic.png`,
 *   `frame_voice.png`, animated `icon_config.b`, `scroll_bar01.b`, `button_onoff.b`, and
 *   per-character voice mutes (`voicedata.dat` `act[0..14]`).
 * - **Album / CG Gallery & Music Room (`EngineMode.ALBUM_MUSIC`)**: CG viewer (`list_*.txt` from
 *   `data9.pack`) and 35-track BGM Jukebox.
 * - **Ogg Theora Video Player (`EngineMode.VIDEO`)**: Streams `ricotta_logo.ogv`, `VR_OP.ogv`, and
 *   `VR_ED_*.ogv`.
 */
class WalkureGdxGame(
  private val vfs: QlieVirtualFileSystem,
  private val initialScriptQuery: String? = null,
  private val customVideoPlayer: VideoPlayerPlatform? = null,
  private val initialShowDebugHud: Boolean = false,
) : KtxApplicationAdapter {

  enum class EngineMode {
    TITLE,
    GAMEPLAY,
    SAVE_LOAD,
    CONFIG,
    ALBUM_MUSIC,
    VIDEO,
  }

  companion object {
    const val VIRTUAL_WIDTH = 1024f
    const val VIRTUAL_HEIGHT = 768f
  }

  private lateinit var gameDat: GameDatConfig
  private lateinit var sysMessages: SystemMessages
  private lateinit var voiceChannels: List<VoiceActorChannel>
  private lateinit var albumLists: Map<String, List<AlbumCgGroup>>
  private lateinit var avgSettings: AvgSystemSettings

  private lateinit var saveManager: SaveManager
  private lateinit var campaign: CampaignConductor
  private lateinit var aliasResolver: AssetAliasResolver

  private lateinit var camera: OrthographicCamera
  private lateinit var viewport: Viewport
  private lateinit var uiCamera: OrthographicCamera
  private lateinit var uiViewport: Viewport
  var panOffsetX: Float = 0f
  var panOffsetY: Float = 0f
  private var isDraggingPan: Boolean = false
  private var lastDragScreenX: Float = 0f
  private var lastDragScreenY: Float = 0f
  private var dragStartX: Float = 0f
  private var dragStartY: Float = 0f
  private var hasDragged: Boolean = false
  private var mouseWorld = Vector2()
  private var mouseUi = Vector2()
  private lateinit var batch: SpriteBatch
  private lateinit var shapes: ShapeRenderer
  private lateinit var textureLoader: QlieGdxTextureLoader
  private lateinit var audioManager: QlieAudioManager
  private lateinit var videoPlayer: VideoPlayerPlatform

  private var fontGenerator: FreeTypeFontGenerator? = null
  private lateinit var dialogueFont: BitmapFont
  private lateinit var speakerFont: BitmapFont
  private lateinit var hudFont: BitmapFont
  private val glyphLayout = GlyphLayout()

  private var mode: EngineMode = EngineMode.TITLE
  private var previousMode: EngineMode = EngineMode.TITLE
  private var isSaveScreenMode: Boolean = false

  private val scenarioEntries =
    vfs
      .listEntries(".s")
      .filter { it.path.contains("シナリオ") && it.path.endsWith(".s", ignoreCase = true) }
      .sortedBy { it.path }

  private var currentScenarioIndex = 0
  private lateinit var vm: ScenarioVm

  private var uiAnimTimeSec: Float = 0f

  // Authentic Title Launch Intro animation state (back.b 16000ms timeline)
  private var titleIntroTimerSec: Float = 0f
  private var titleIntroSkipped: Boolean = false

  // Transition state (modes 0=crossfade, 1/3=rule, 2=color, 4=mosaic, plus .b abmotion overlays)
  private var transitionShader: ShaderProgram? = null
  private var mosaicShader: ShaderProgram? = null
  private var ruleMaskTex: Texture? = null
  private var solidBlackTex: Texture? = null
  private var solidWhiteTex: Texture? = null
  private var transitionDurationSec: Float = 0.4f
  private var transitionProgressSec: Float = 0.4f // >= duration means finished
  private var isTransitionActive: Boolean = false
  private var activeTransitionMode: Int = 0
  private var activeTransitionFeather: Float = 0.08f
  private var activeTransitionAbmp: QlieGdxTextureLoader.AbmpRuntimeNode? = null
  /** Per-character film strip substituted for the shared `*Link,0` placeholder of a wfade. */
  private var activeTransitionLinkedTex: Texture? = null
  private var prevSpriteTextures: List<RenderedSprite> = emptyList()
  private var prevOverlayTex: Texture? = null
  /** Set when a fade arms; the outgoing scene is rendered into [prevSceneFbo] on the next frame. */
  private var pendingSceneCapture = false
  /**
   * The spec instance most recently armed. The VM reports its last fade in every snapshot because
   * the spec is part of the saved visual state, so identity — not nullness — is what separates a
   * newly requested transition from the sticky copy that rides along with later lines.
   */
  private var lastAppliedTransitionSpec: ScenarioVm.TransitionSpec? = null
  // --- Temporary instrumentation for the transition performance investigation (EngineTrace) ---
  private var traceSyncCallCount: Int = 0
  private var traceTransitionStartNanos: Long = 0L
  private var traceTransitionFrames: Int = 0
  private var traceSlowFramesLogged: Int = 0
  private var prevSceneFbo: FrameBuffer? = null

  // UI Screen transition overlay (fadein01.b / fadeout01.b)
  private var uiOverlayAbmp: QlieGdxTextureLoader.AbmpRuntimeNode? = null
  private var uiOverlayTimerSec: Float = 0f
  private var uiOverlayDurationSec: Float = 0f

  // Config tab & slide animations
  private var configTab: Int = 0 // 0=System, 1=Voice, 2=Text, 3=Display/Frame
  private var configPrevTab: Int = 0
  private var configTabExitTimerSec: Float = 1f
  private var configTabEnterTimerSec: Float = 1f

  private var prevBackgroundTex: Texture? = null
  private var currentBackgroundTex: Texture? = null
  private var overlayBackgroundTex: Texture? = null
  private var currentFaceTex: Texture? = null
  private var currentFaceOffsetX: Int = 0
  private var currentWeatherEffect: String? = null
  private var flashTimerSec: Float = 0f
  private var bgFadeAlpha: Float = 1f
  private var bgFadeSpeed: Float = 2.5f
  private var solidBgColor: Color? = null

  var showDebugHud: Boolean = initialShowDebugHud

  private var eyeCatchBannerTex: Texture? = null
  private var eyeCatchTimer: Float = 0f

  data class DateAliasEntry(
    val monthFile: String,
    val digit1File: String,
    val digit2File: String?,
    val weekdayFile: String,
    val xOffset: Float,
  )

  private val dateAliasMap = LinkedHashMap<String, DateAliasEntry>()
  private var dayEyeCatchActive: Boolean = false
  private var dayEyeCatchTimer: Float = 0f
  /** `モーション\日付アイキャッチ.b` — the authentic 7.5 s calendar cut-in. */
  private var dayEyeCatchAbmp: QlieGdxTextureLoader.AbmpRuntimeNode? = null
  private var dayEyeCatchTotalSec: Float = 7.5f
  private var dayMonthTex: Texture? = null
  private var dayDigit1Tex: Texture? = null
  private var dayDigit2Tex: Texture? = null
  private var dayWeekdayTex: Texture? = null
  private var dayBlurBgTex: Texture? = null
  private var dayMiniBgTex: Texture? = null

  private var activeActionCharKey: String? = null
  private var activeActionName: String? = null
  private var actionTimerSec: Float = 0f
  private var screenShakeTimerSec: Float = 0f

  private var activeModalPrompt: String? = null
  private var activeModalEnglishTexPath: String? = null
  private var activeModalOnConfirm: (() -> Unit)? = null

  private data class RenderedSprite(
    val charKey: String,
    val texture: Texture,
    val centerX: Float,
  )

  private var activeSpriteTextures: List<RenderedSprite> = emptyList()

  private data class SakuraParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var size: Float,
    var phase: Float,
    val variant: Int,
  )

  private val sakuraParticles = ArrayList<SakuraParticle>()

  private var isAutoMode = false
  private var isSkipMode = false
  private var hideMessageWindow = false
  private var autoTimerSec = 0f
  private var skipTimerSec = 0f
  private var showBacklog = false
  private var backlogScrollOffset = 0
  private var statusMessage = "Ready"

  // Save/Load Screen state (25 slots/page x 4 pages with damped Clock cursor & curved slots)
  private var currentSavePage = 0 // 0..3
  private var saveScrollOffset: Float = 0f // 0f..(25 - 7) slots scrollable within current page
  private var savePageExitTimerSec: Float = 1f
  private var savePageEnterTimerSec: Float = 1f
  private var clockNeedleAngleDeg: Float = 36f // Damped needle: _cp1 = (_cp1*3 + _cp2) / 4
  private var clockNeedleTargetDeg: Float = 36f
  private val slotThumbnailTextures = HashMap<Int, Texture>()

  private data class TitleButtonAnimState(
    var hoverTimeSec: Float = 0f,
    var isHovered: Boolean = false,
  )

  private val titleButtonAssetsCache = HashMap<String, QlieGdxTextureLoader.LoadedButtonAssets?>()
  private val titleButtonAnimStates = HashMap<String, TitleButtonAnimState>()
  private var titlePageTransitionTimerSec: Float = 1f
  private var titlePrevPage: Int = 0

  // Album & Music Room state
  private var currentAlbumTab = "Celia"
  private var currentAlbumPage = 0
  private var fullScreenCgPath: String? = null
  private val bgmTracks =
    vfs
      .listEntries("音楽\\")
      .filter { it.normalizedPath.endsWith(".ogg") }
      .sortedBy { it.normalizedPath }

  private var onVideoFinished: (() -> Unit)? = null

  private data class UiButton(
    val label: () -> String,
    val bounds: Rectangle,
    val abmpPath: String? = null,
    val isActiveToggle: () -> Boolean = { false },
    val onClick: () -> Unit,
  )

  private val debugHudButtons = ArrayList<UiButton>()
  private val windowSystemButtons = ArrayList<UiButton>()
  private val windowRadialButtons = ArrayList<UiButton>()
  private var titlePage: Int = 0 // 0 = Normal, 1 = Extra
  private val titleNormalButtons = ArrayList<UiButton>()
  private val titleExtraButtons = ArrayList<UiButton>()

  private fun activeTitleButtons(): List<UiButton> =
    if (titlePage == 1) titleExtraButtons else titleNormalButtons

  fun setAspectRatioMode(newMode: AspectRatioMode) {
    val oldMode = saveManager.aspectRatioMode
    saveManager.aspectRatioMode = newMode
    saveManager.saveSystemData()
    if (oldMode == AspectRatioMode.FILL_WIDE_PAN || newMode != AspectRatioMode.FILL_WIDE_PAN) {
      resetPan()
    }
    recreateViewport(Gdx.graphics.width, Gdx.graphics.height)
  }

  fun recreateViewport(width: Int = Gdx.graphics.width, height: Int = Gdx.graphics.height) {
    val mode = saveManager.aspectRatioMode
    viewport =
      when (mode) {
        AspectRatioMode.ORIGINAL_4_3 -> FitViewport(VIRTUAL_WIDTH, VIRTUAL_HEIGHT, camera)
        AspectRatioMode.FILL_WIDE_PAN -> PanScanViewport(VIRTUAL_WIDTH, VIRTUAL_HEIGHT, camera)
        AspectRatioMode.EXTEND_WIDE -> ExtendViewport(VIRTUAL_WIDTH, VIRTUAL_HEIGHT, camera)
        AspectRatioMode.STRETCH_FULL -> StretchViewport(VIRTUAL_WIDTH, VIRTUAL_HEIGHT, camera)
      }
    viewport.update(width, height, true)

    uiCamera.setToOrtho(false, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
    uiCamera.position.set(VIRTUAL_WIDTH / 2f, VIRTUAL_HEIGHT / 2f, 0f)
    uiCamera.update()

    uiViewport.update(width, height, true)
    updateCameraPan()
  }

  fun unprojectUi(screenX: Float, screenY: Float, out: Vector2 = Vector2()): Vector2 {
    return uiViewport.unproject(out.set(screenX, screenY))
  }

  fun updateCameraPan() {
    if (saveManager.aspectRatioMode != AspectRatioMode.FILL_WIDE_PAN) {
      panOffsetX = 0f
      panOffsetY = 0f
    } else {
      val maxPanX = ((VIRTUAL_WIDTH - viewport.worldWidth) / 2f).coerceAtLeast(0f)
      val maxPanY = ((VIRTUAL_HEIGHT - viewport.worldHeight) / 2f).coerceAtLeast(0f)

      panOffsetX = panOffsetX.coerceIn(-maxPanX, maxPanX)
      panOffsetY = panOffsetY.coerceIn(-maxPanY, maxPanY)
    }

    camera.position.set(VIRTUAL_WIDTH / 2f + panOffsetX, VIRTUAL_HEIGHT / 2f + panOffsetY, 0f)
    camera.update()
  }

  fun resetPan() {
    panOffsetX = 0f
    panOffsetY = 0f
    updateCameraPan()
  }

  /** Times one startup step and reports it, so the slow part of boot is attributable. */
  private fun <T> bootStep(label: String, block: () -> T): T {
    val t0 = walkure.EngineTrace.nowNanos()
    val result = block()
    walkure.EngineTrace.log("BOOT $label=${walkure.EngineTrace.msSince(t0)}ms")
    return result
  }

  override fun create() {
    walkure.EngineTrace.log(
      "BOOT create() entered ${walkure.EngineTrace.msSinceProcessStart()}ms after process start " +
        "(before this: JVM start + VFS mount) tracing=${walkure.EngineTrace.enabled} " +
        "slowFrameMs=${walkure.EngineTrace.slowFrameThresholdMs()} (disable with -Dwalkure.trace=off)",
    )
    gameDat = bootStep("parseGameDat") { SystemDatParser.parseGameDat(vfs) }
    sysMessages = bootStep("parseSystemMessages") { SystemDatParser.parseSystemMessages(vfs) }
    voiceChannels = bootStep("parseVoiceChannels") { SystemDatParser.parseVoiceChannels(vfs) }
    albumLists = bootStep("parseAlbumLists") { SystemDatParser.parseAlbumLists(vfs) }
    avgSettings = bootStep("parseAvgSettings") { SystemDatParser.parseAvgSettings(vfs) }

    saveManager =
      bootStep("SaveManager+loadSystemData") {
        SaveManager(vfs.gameDir).apply {
          codeKeySeed = QlieBufferCipher.parseCodeKey(gameDat.codeKey)
          loadSystemData()
        }
      }
    campaign = bootStep("CampaignConductor") { CampaignConductor(vfs) }
    val aliasT0 = walkure.EngineTrace.nowNanos()
    aliasResolver = AssetAliasResolver(vfs)
    walkure.EngineTrace.log(
      "BOOT AssetAliasResolver init=${walkure.EngineTrace.msSince(aliasT0)}ms " +
        "(fade alias tables loaded here)",
    )

    camera =
      OrthographicCamera().apply {
        setToOrtho(false, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
      }
    uiCamera =
      OrthographicCamera().apply {
        setToOrtho(false, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
      }
    uiViewport = FitViewport(VIRTUAL_WIDTH, VIRTUAL_HEIGHT, uiCamera)
    recreateViewport(Gdx.graphics.width, Gdx.graphics.height)

    batch = SpriteBatch()
    shapes = ShapeRenderer()
    textureLoader = QlieGdxTextureLoader(vfs)
    transitionShader = TransitionShaders.createRuleWipeShader()
    mosaicShader = TransitionShaders.createMosaicTransitionShader()
    solidBlackTex = createSolidTexture(Color(0.02f, 0.02f, 0.04f, 1f))
    solidWhiteTex = createSolidTexture(Color.WHITE)
    prevSceneFbo =
      runCatching {
        FrameBuffer(
          Pixmap.Format.RGBA8888,
          VIRTUAL_WIDTH.toInt(),
          VIRTUAL_HEIGHT.toInt(),
          false,
        )
      }
        .getOrNull()
    audioManager =
      QlieAudioManager(vfs).apply {
        bgmVolume = saveManager.bgmVolume
        voiceVolume = saveManager.voiceVolume
        seVolume = saveManager.seVolume
      }
    videoPlayer = customVideoPlayer ?: OgvVideoPlayer(vfs, audioManager)

    bootStep("initFonts") { initFonts() }
    bootStep("initParticles") { initParticles() }
    bootStep("initDateCalendarAlias") { initDateCalendarAlias() }
    bootStep("initTitleButtons") { initTitleButtons() }
    bootStep("initGameplayHudButtons") { initGameplayHudButtons() }
    bootStep("initInput") { initInput() }

    val defaultEntry =
      bootStep("scenarioEntries(${scenarioEntries.size})") {
        scenarioEntries.firstOrNull {
          it.path.contains(initialScriptQuery ?: "kyo_001_00.s", ignoreCase = true)
        } ?: scenarioEntries.first()
      }
    currentScenarioIndex = scenarioEntries.indexOf(defaultEntry).coerceAtLeast(0)
    vm =
      ScenarioVm(
        ScenarioParser.parse(defaultEntry.path, vfs.readTextShiftJis(defaultEntry.path)),
        aliasResolver,
        branchEvaluator = { branchId -> campaign.evaluateBranch(branchId) },
        avgSettings = avgSettings,
      )

    if (initialScriptQuery != null) {
      enterGameplayWithScript(defaultEntry.path)
    } else {
      playVideo("スクリプト\\logo\\ricotta_logo.ogv") {
        enterTitleScreen()
      }
    }
  }

  private fun initParticles() {
    sakuraParticles.clear()
    val rng = Random(42)
    for (i in 0 until 28) {
      sakuraParticles.add(
        SakuraParticle(
          x = rng.nextFloat() * VIRTUAL_WIDTH,
          y = rng.nextFloat() * VIRTUAL_HEIGHT,
          vx = -35f - rng.nextFloat() * 45f,
          vy = -40f - rng.nextFloat() * 55f,
          size = 24f + rng.nextFloat() * 22f,
          phase = rng.nextFloat() * 6.28f,
          variant = i % 3,
        ),
      )
    }
  }

  private fun updateAndDrawWeatherParticles(dt: Float, forceSakura: Boolean = false) {
    val effect = if (forceSakura) "sakura" else currentWeatherEffect ?: return
    if (effect == "sakura") {
      val petal0 = textureLoader.loadAbmpLayerTexture("画像\\桜.b", 0)
      val petal1 = textureLoader.loadAbmpLayerTexture("画像\\桜.b", 1)
      val petal2 = textureLoader.loadAbmpLayerTexture("画像\\桜.b", 2)
      for (p in sakuraParticles) {
        p.phase += dt * 2.2f
        p.x += (p.vx + sin(p.phase) * 22f) * dt
        p.y += p.vy * dt
        if (p.y < -40f) {
          p.y = VIRTUAL_HEIGHT + 20f
          p.x = (p.x + VIRTUAL_WIDTH * 0.4f) % VIRTUAL_WIDTH
        }
        if (p.x < -40f) {
          p.x += VIRTUAL_WIDTH + 60f
        }
        val tex =
          when (p.variant) {
            0 -> petal0
            1 -> petal1
            else -> petal2
          }
        if (tex != null) {
          batch.setColor(1f, 1f, 1f, 0.88f)
          batch.draw(tex, p.x, p.y, p.size, p.size)
        }
      }
      batch.setColor(1f, 1f, 1f, 1f)
    } else if (effect == "rain") {
      val rainTex = textureLoader.loadAbmpLayerTexture("モーション\\雨.b", 0)
      if (rainTex != null) {
        batch.setColor(0.85f, 0.90f, 1f, 0.55f)
        for (i in 0 until 36) {
          val rx =
            ((i * 97f - uiAnimTimeSec * 220f) % VIRTUAL_WIDTH + VIRTUAL_WIDTH) % VIRTUAL_WIDTH
          val ry =
            ((i * 131f - uiAnimTimeSec * 980f) % (VIRTUAL_HEIGHT + 300f) +
              (VIRTUAL_HEIGHT + 300f)) % (VIRTUAL_HEIGHT + 300f) - 200f
          batch.draw(rainTex, rx, ry, 4f, 240f)
        }
        batch.setColor(1f, 1f, 1f, 1f)
      }
    }
  }

  /**
   * Draws a single animation frame from a QLiE vertical sprite strip (`80 x (80*N)`, `120 x
   * (120*N)`, `141 x (141*N)`).
   */
  private fun drawVerticalStripFrame(
    tex: Texture,
    x: Float,
    y: Float,
    drawW: Float,
    drawH: Float,
    animTime: Float,
    fps: Float = 24f,
    isPlaying: Boolean = true,
    frameWOverride: Int? = null,
    frameHOverride: Int? = null,
    cropInsetPx: Int = 0,
  ) {
    val frameW = (frameWOverride?.takeIf { it in 1..tex.width } ?: tex.width).coerceAtLeast(1)
    val frameH = (frameHOverride?.takeIf { it in 1..tex.height } ?: frameW).coerceAtLeast(1)
    val cols = (tex.width / frameW).coerceAtLeast(1)
    val rows = (tex.height / frameH).coerceAtLeast(1)
    val frameCount = (cols * rows).coerceAtLeast(1)
    val frameIdx = if (isPlaying) (((animTime * fps).toInt().coerceAtLeast(0)) % frameCount) else 0
    val col = frameIdx % cols
    val row = frameIdx / cols

    val inset = cropInsetPx.coerceIn(0, minOf(frameW, frameH) / 3)
    val srcX = col * frameW + inset
    val srcY = row * frameH + inset
    val srcW = (frameW - inset * 2).coerceAtLeast(1)
    val srcH = (frameH - inset * 2).coerceAtLeast(1)

    // Draw cropped strictly to the target frame bounds (drawW x drawH)
    val scale = minOf(drawW / srcW.toFloat(), drawH / srcH.toFloat(), 1.0f)
    val renderW = srcW * scale
    val renderH = srcH * scale
    val drawX = x + (drawW - renderW) / 2f
    val drawY = y + (drawH - renderH) / 2f

    batch.draw(tex, drawX, drawY, renderW, renderH, srcX, srcY, srcW, srcH, false, false)
  }

  private fun initFonts() {
    val fontHandle =
      when {
        File(vfs.gameDir, "WalkureCustomV4.ttf").exists() ->
          FileHandle(File(vfs.gameDir, "WalkureCustomV4.ttf"))
        File(vfs.gameDir, "GameData/WalkureCustomV4.ttf").exists() ->
          FileHandle(File(vfs.gameDir, "GameData/WalkureCustomV4.ttf"))
        Gdx.files != null && Gdx.files.internal("WalkureCustomV4.ttf").exists() ->
          Gdx.files.internal("WalkureCustomV4.ttf")
        else -> null
      }
    if (fontHandle != null) {
      val gen = FreeTypeFontGenerator(fontHandle)
      fontGenerator = gen

      dialogueFont =
        gen.generateFont(
          FreeTypeFontParameter().apply {
            size = 22
            color = Color(0.96f, 0.97f, 1.0f, 1f)
            borderWidth = 1.6f
            borderColor = Color(0.06f, 0.07f, 0.14f, 0.92f)
            shadowOffsetX = 1
            shadowOffsetY = 1
            shadowColor = Color(0f, 0f, 0f, 0.75f)
            incremental = true
          },
        )

      speakerFont =
        gen.generateFont(
          FreeTypeFontParameter().apply {
            size = 24
            color = Color(0.99f, 0.88f, 0.56f, 1f)
            borderWidth = 1.8f
            borderColor = Color(0.10f, 0.07f, 0.04f, 0.95f)
            shadowOffsetX = 1
            shadowOffsetY = 1
            shadowColor = Color(0f, 0f, 0f, 0.8f)
            incremental = true
          },
        )

      hudFont =
        gen.generateFont(
          FreeTypeFontParameter().apply {
            size = 15
            color = Color(0.92f, 0.94f, 0.99f, 1f)
            borderWidth = 1.1f
            borderColor = Color(0.04f, 0.05f, 0.12f, 0.92f)
            incremental = true
          },
        )
    } else {
      dialogueFont = BitmapFont().apply { data.setScale(1.4f) }
      speakerFont = BitmapFont().apply { data.setScale(1.6f) }
      hudFont = BitmapFont()
    }
  }

  private fun getButtonTileColor(path: String?): Color {
    val lower = path?.lowercase().orEmpty()
    return when {
      lower.contains("start") -> Color(0.90f, 0.71f, 0.30f, 1f) // Gold #E5B44C
      lower.contains("load") -> Color(0.39f, 0.63f, 0.76f, 1f) // Sky Blue #63A1C3
      lower.contains("config") -> Color(0.42f, 0.51f, 0.78f, 1f) // Indigo #6B81C7
      lower.contains("special") -> Color(0.38f, 0.72f, 0.32f, 1f) // Emerald Green #62B751
      lower.contains("quit") -> Color(0.81f, 0.36f, 0.43f, 1f) // Rose Crimson #CF5C6E
      lower.contains("memory") -> Color(0.83f, 0.47f, 0.30f, 1f) // Amber Orange #D3774C
      lower.contains("album") -> Color(0.32f, 0.68f, 0.65f, 1f) // Teal #52ADA7
      lower.contains("music") -> Color(0.61f, 0.69f, 0.43f, 1f) // Olive Lime #9BB16D
      lower.contains("cancel") -> Color(0.77f, 0.34f, 0.53f, 1f) // Pink Magenta #C45787
      else -> Color.WHITE
    }
  }

  private fun switchTitlePage(newPage: Int) {
    if (titlePage == newPage) return
    titlePrevPage = titlePage
    titlePage = newPage
    titlePageTransitionTimerSec = 0f
    titleButtonAnimStates.clear()
  }

  private fun openConfigScreen(prev: EngineMode) {
    previousMode = prev
    mode = EngineMode.CONFIG
    configPrevTab = configTab
    configTabExitTimerSec = 1f
    configTabEnterTimerSec = 0f
    triggerUiOverlay("スクリプト\\config\\fadein01.b")
  }

  private fun initTitleButtons() {
    // Authentic Title Buttons from スクリプト\title\layout_normal.s and layout_extra.s
    titleNormalButtons.clear()
    titleExtraButtons.clear()
    val y = 26f
    val size = 80f

    // Normal Layout: Start, Load, Config, Extra (Special), Quit
    titleNormalButtons.add(
      UiButton({ "START" }, Rectangle(310f, y, size, size), "スクリプト\\title\\button_start.b") {
        audioManager.playSe("misc\\Click01.wav")
        enterGameplayWithScript("シナリオ\\共通\\kyo_001_00.s")
      },
    )
    titleNormalButtons.add(
      UiButton({ "LOAD" }, Rectangle(410f, y, size, size), "スクリプト\\title\\button_load.b") {
        audioManager.playSe("misc\\Click01.wav")
        openSaveLoadScreen(saveMode = false)
      },
    )
    titleNormalButtons.add(
      UiButton({ "CONFIG" }, Rectangle(510f, y, size, size), "スクリプト\\title\\button_config.b") {
        audioManager.playSe("misc\\Click01.wav")
        openConfigScreen(EngineMode.TITLE)
      },
    )
    titleNormalButtons.add(
      UiButton({ "EXTRA" }, Rectangle(610f, y, size, size), "スクリプト\\title\\button_special.b") {
        if (saveManager.isSpecialUnlocked()) {
          audioManager.playSe("misc\\Click01.wav")
          switchTitlePage(1)
        } else {
          audioManager.playSe("misc\\Cancel01.WAV")
          statusMessage = "Extras locked — view CGs, play BGM, or clear a route to unlock"
        }
      },
    )
    titleNormalButtons.add(
      UiButton({ "QUIT" }, Rectangle(710f, y, size, size), "スクリプト\\title\\button_quit.b") {
        showConfirmModal(
          sysMessages.appCloseText,
          "スクリプト\\dialog\\english\\ゲ-ムを終了します。よろしいですか？.png",
        ) {
          Gdx.app.exit()
        }
      },
    )

    // Extra Layout: Memory, Album, Music, Cancel
    titleExtraButtons.add(
      UiButton({ "MEMORY" }, Rectangle(350f, y, size, size), "スクリプト\\title\\button_memory.b") {
        audioManager.playSe("misc\\Click01.wav")
        previousMode = EngineMode.TITLE
        currentAlbumTab = "Memory"
        currentAlbumPage = 0
        mode = EngineMode.ALBUM_MUSIC
      },
    )
    titleExtraButtons.add(
      UiButton({ "ALBUM" }, Rectangle(450f, y, size, size), "スクリプト\\title\\button_album.b") {
        audioManager.playSe("misc\\Click01.wav")
        previousMode = EngineMode.TITLE
        currentAlbumTab = "Celia"
        currentAlbumPage = 0
        mode = EngineMode.ALBUM_MUSIC
      },
    )
    titleExtraButtons.add(
      UiButton({ "MUSIC" }, Rectangle(550f, y, size, size), "スクリプト\\title\\button_music.b") {
        audioManager.playSe("misc\\Click01.wav")
        previousMode = EngineMode.TITLE
        currentAlbumTab = "Music Room"
        currentAlbumPage = 0
        mode = EngineMode.ALBUM_MUSIC
      },
    )
    titleExtraButtons.add(
      UiButton({ "CANCEL" }, Rectangle(650f, y, size, size), "スクリプト\\title\\button_cancel.b") {
        audioManager.playSe("misc\\Cancel01.WAV")
        switchTitlePage(0)
      },
    )
  }

  private fun initDateCalendarAlias() {
    dateAliasMap.clear()
    val entry = vfs.resolveEntry("ライブラリ\\AVGシステム\\リスト\\日付Alias.txt")?.second ?: return
    val text = runCatching { vfs.readTextShiftJis(entry.path) }.getOrNull() ?: return
    for (line in text.lines()) {
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue
      val key = trimmed.substringBefore('=').trim()
      val value = trimmed.substringAfter('=').trim()
      val commaParts = value.split(',')
      val xOff = commaParts.getOrNull(1)?.trim()?.toFloatOrNull() ?: 0f
      val colons = commaParts[0].split(':')
      val m = colons.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: "日付_4月"
      val d1 = colons.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "日付_1"
      val d2 = colons.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
      val w = colons.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() } ?: "日付_月"
      dateAliasMap[key] =
        DateAliasEntry(
          monthFile = "画像\\$m.png",
          digit1File = "画像\\$d1.png",
          digit2File = d2?.let { "画像\\$it.png" },
          weekdayFile = "画像\\$w.png",
          xOffset = xOff,
        )
    }
  }

  private fun triggerDayEyeCatch(dateKey: String) {
    val cleanKey = dateKey.trim().trim('"')
    val spec =
      dateAliasMap[cleanKey]
        ?: dateAliasMap.entries
          .firstOrNull { it.key.contains(cleanKey) || cleanKey.contains(it.key) }
          ?.value
        ?: fallbackDateAliasEntry(cleanKey)

    dayMonthTex = textureLoader.loadTexture(spec.monthFile)
    dayDigit1Tex = textureLoader.loadTexture(spec.digit1File)
    dayDigit2Tex = spec.digit2File?.let { textureLoader.loadTexture(it) }
    dayWeekdayTex = textureLoader.loadTexture(spec.weekdayFile)

    // The original engine plays `モーション\日付アイキャッチ.b` (25 layers, 7.5 s) and binds the
    // date images into its `日付/*` layers at runtime; the container ships with placeholder April
    // art. Draw that timeline instead of hand-composing a strip, substituting the date textures
    // for the layers whose keyframes are marked type 23.
    dayEyeCatchAbmp = textureLoader.loadAbmpRuntime("モーション\\日付アイキャッチ.b")

    // The original engine uses a deterministic Mersenne Twister (TMTRandom + RandomTable.dat),
    // not ThreadLocalRandom. Seed java.util.Random from the date key so the same date always
    // shows the same calendar background images.
    val rnd = java.util.Random(dateKey.hashCode().toLong()).nextInt(4)
    dayBlurBgTex = textureLoader.loadTexture("画像\\日付背景b_$rnd.png")
    dayMiniBgTex = textureLoader.loadTexture("画像\\日付背景m_$rnd.png")

    dayEyeCatchActive = true
    dayEyeCatchTimer = dayEyeCatchTotalSec
    audioManager.playSe("音楽\\ec1.ogg")
  }

  private fun fallbackDateAliasEntry(dateKey: String): DateAliasEntry {
    val parts = dateKey.split('/', '-').filter { it.isNotBlank() }
    val m = parts.firstOrNull()?.toIntOrNull() ?: 4
    val d = parts.getOrNull(1)?.toIntOrNull() ?: 16
    val d1 = d / 10
    val d2 = d % 10
    return DateAliasEntry(
      monthFile = "画像\\日付_${m}月.png",
      digit1File = if (d < 10) "画像\\日付_${d}.png" else "画像\\日付_${d1}.png",
      digit2File = if (d >= 10) "画像\\日付_${d2}.png" else null,
      weekdayFile = "画像\\日付_金.png",
      xOffset = -12f,
    )
  }

  private fun initGameplayHudButtons() {
    debugHudButtons.clear()
    windowSystemButtons.clear()
    windowRadialButtons.clear()

    // 1. Debug & Display buttons kept at top-right corner
    debugHudButtons.add(
      UiButton(
        { "< Prev Scene" },
        Rectangle(VIRTUAL_WIDTH - 386f, VIRTUAL_HEIGHT - 34f, 96f, 26f),
      ) {
        if (scenarioEntries.isNotEmpty()) {
          currentScenarioIndex =
            (currentScenarioIndex - 1 + scenarioEntries.size) % scenarioEntries.size
          enterGameplayWithScript(scenarioEntries[currentScenarioIndex].path)
        }
      },
    )
    debugHudButtons.add(
      UiButton(
        { "Next Scene >" },
        Rectangle(VIRTUAL_WIDTH - 286f, VIRTUAL_HEIGHT - 34f, 96f, 26f),
      ) {
        if (scenarioEntries.isNotEmpty()) {
          currentScenarioIndex = (currentScenarioIndex + 1) % scenarioEntries.size
          enterGameplayWithScript(scenarioEntries[currentScenarioIndex].path)
        }
      },
    )
    debugHudButtons.add(
      UiButton(
        { "[${saveManager.aspectRatioMode.shortName}]" },
        Rectangle(VIRTUAL_WIDTH - 186f, VIRTUAL_HEIGHT - 34f, 96f, 26f),
      ) {
        setAspectRatioMode(saveManager.aspectRatioMode.next())
      },
    )
    debugHudButtons.add(
      UiButton({ "Title" }, Rectangle(VIRTUAL_WIDTH - 86f, VIRTUAL_HEIGHT - 34f, 76f, 26f)) {
        showConfirmModal(
          sysMessages.appCloseReset,
          "スクリプト\\dialog\\english\\タイトルに戻ります。よろしいですか？.png",
        ) {
          enterTitleScreen()
        }
      },
    )
    debugHudButtons.add(
      UiButton({ "Center" }, Rectangle(VIRTUAL_WIDTH - 186f, VIRTUAL_HEIGHT - 64f, 96f, 24f)) {
        resetPan()
      },
    )

    // 2. Authentic Bottom System Buttons from ライブラリ\avgシステム\window\layout_window01.s
    // In QLiE top-down: Save \p,350,770, Load \p,430,770, Config \p,510,770, QSave \p,590,770,
    // QLoad \p,670,770
    // Translated to LibGDX bottom-up (y = 4f, w = 72f, h = 26f spaced comfortably with 80px
    // intervals)
    val sysCoords =
      listOf(
        Triple("SAVE", "ライブラリ\\avgシステム\\window\\windowbutton_save.b", 314f) to
          {
            openSaveLoadScreen(saveMode = true)
          },
        Triple("LOAD", "ライブラリ\\avgシステム\\window\\windowbutton_load.b", 394f) to
          {
            openSaveLoadScreen(saveMode = false)
          },
        Triple("CONFIG", "ライブラリ\\avgシステム\\window\\windowbutton_config.b", 474f) to
          {
            openConfigScreen(EngineMode.GAMEPLAY)
          },
        Triple("Q.SAVE", "ライブラリ\\avgシステム\\window\\windowbutton_qsave.b", 554f) to { quickSave() },
        Triple("Q.LOAD", "ライブラリ\\avgシステム\\window\\windowbutton_qload.b", 634f) to { quickLoad() },
      )
    for ((data, action) in sysCoords) {
      val (label, path, bx) = data
      windowSystemButtons.add(
        UiButton(
          label = { label },
          bounds = Rectangle(bx, 4f, 72f, 26f),
          abmpPath = path,
          onClick = action,
        ),
      )
    }

    // 3. Authentic Right Radial Cluster Buttons from layout_window01.s
    // Close (\p,991,605), Skip (\p,962,627), Auto (\p,953,665), BackLog (\p,958,696), Voice
    // (\p,991,712)
    windowRadialButtons.add(
      UiButton(
        label = { "×" },
        bounds = Rectangle(968f, 146f, 44f, 26f),
        abmpPath = "ライブラリ\\avgシステム\\window\\windowbutton_close.b",
        onClick = { hideMessageWindow = true },
      ),
    )
    windowRadialButtons.add(
      UiButton(
        label = { "SKIP" },
        bounds = Rectangle(926f, 122f, 58f, 24f),
        abmpPath = "ライブラリ\\avgシステム\\window\\windowbutton_skip.b",
        isActiveToggle = { isSkipMode },
        onClick = {
          isSkipMode = !isSkipMode
          skipTimerSec = 0f
        },
      ),
    )
    windowRadialButtons.add(
      UiButton(
        label = { "AUTO" },
        bounds = Rectangle(916f, 90f, 58f, 24f),
        abmpPath = "ライブラリ\\avgシステム\\window\\windowbutton_auto.b",
        isActiveToggle = { isAutoMode },
        onClick = {
          isAutoMode = !isAutoMode
          autoTimerSec = 0f
        },
      ),
    )
    windowRadialButtons.add(
      UiButton(
        label = { "LOG" },
        bounds = Rectangle(922f, 58f, 58f, 24f),
        abmpPath = "ライブラリ\\avgシステム\\window\\windowbutton_backlog.b",
        isActiveToggle = { showBacklog },
        onClick = {
          showBacklog = !showBacklog
          backlogScrollOffset = 0
        },
      ),
    )
    windowRadialButtons.add(
      UiButton(
        label = { "VOICE" },
        bounds = Rectangle(958f, 30f, 56f, 24f),
        abmpPath = "ライブラリ\\avgシステム\\window\\windowbutton_voice.b",
        onClick = { audioManager.replayLastVoice() },
      ),
    )
  }

  private fun showConfirmModal(
    prompt: String,
    englishPngPath: String? = null,
    onConfirm: () -> Unit,
  ) {
    activeModalPrompt = prompt
    activeModalEnglishTexPath = englishPngPath
    activeModalOnConfirm = onConfirm
  }

  private fun enterTitleScreen() {
    videoPlayer.stop()
    audioManager.stopAllVoiceAndSe()
    mode = EngineMode.TITLE
    titleIntroTimerSec = 0f
    titleIntroSkipped = false
    isAutoMode = false
    isSkipMode = false
    showBacklog = false
    activeModalPrompt = null
    activeModalEnglishTexPath = null
    activeModalOnConfirm = null
    audioManager.playBgm("音楽\\op_inst.ogg")
  }

  private fun createSolidTexture(color: Color): Texture {
    val px =
      Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
        setColor(color)
        fill()
      }
    return Texture(px).also {
      it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
      px.dispose()
    }
  }

  private fun triggerUiOverlay(vfsPath: String) {
    val node = textureLoader.loadAbmpRuntime(vfsPath) ?: return
    uiOverlayAbmp = node
    uiOverlayTimerSec = 0f
    val durMs = node.document?.durationMs?.takeIf { it > 0 } ?: 350
    uiOverlayDurationSec = (durMs / 1000f).coerceIn(0.18f, 0.7f)
  }

  /**
   * True when the image a fade layer binds is the engine's stand-in for the captured game screen.
   * `avgsystem_fade.s` overwrites those slots with `$$$GameThreadImageZ,1001.bmp` / `999.bmp`, and
   * the container ships a grey/white test pattern instead. The layer is named `Capture` while the
   * image behind it is named `Dammy1024`, so the decision has to be made on the image name — other
   * fade containers (`wfade01a`) bind real art, such as an 800x600 wipe rule, under the same shape.
   */
  private fun isScreenPlaceholderImage(
    node: QlieGdxTextureLoader.AbmpRuntimeNode,
    imageIndex: Int,
  ): Boolean {
    val slot = node.slots.getOrNull(imageIndex) ?: return false
    val name =
      listOfNotNull(slot.layer.name, slot.layer.filename).firstOrNull { it.isNotBlank() }
        ?: return false
    val isDammy =
      name.startsWith("Dammy", ignoreCase = true) || name.startsWith("Dummy", ignoreCase = true)
    val isCapture = name.equals("Capture", ignoreCase = true)
    // avgsystem_fade.s overwrites placeholder slots with $$$GameThreadImageZ,1001.bmp / 999.bmp
    val isNumericPlaceholder =
      name.all { it.isDigit() || it == '.' } && name.contains('.').not() && name.length >= 3
    return isDammy || isCapture || isNumericPlaceholder
  }

  /**
   * Returns the per-character film strip of a `wfade02*` container.
   *
   * These containers keep the character's 1112x230 strip in a slot the timeline never references,
   * while the referenced `*Link,0` slot carries a baked copy of the artist's default strip (which
   * is Lisa's). Picking the largest unreferenced non-placeholder image restores the right character
   * instead of drawing Lisa for everyone.
   */
  private fun resolveUnreferencedStrip(
    node: QlieGdxTextureLoader.AbmpRuntimeNode?,
  ): Texture? {
    val n = node ?: return null
    val doc = n.document ?: return null
    val referenced = HashSet<Int>()
    for (track in doc.tracks) {
      for (layer in track.layers) {
        for (kf in layer.keyframes) referenced.add(kf.imageIndex)
      }
    }
    return n.slots
      .withIndex()
      .filter { (i, slot) ->
        i !in referenced &&
          slot.texture != null &&
          slot.childNode == null &&
          !isScreenPlaceholderImage(n, i)
      }
      .mapNotNull { it.value.texture }
      .maxByOrNull { it.width.toLong() * it.height.toLong() }
  }

  /**
   * Evaluates an `abdata10..13` timeline [node] at [timeMs] and renders all active layers using
   * QLiE's top-left `(1024x768)` coordinate space, pivots, scales (`100 = 1.0x`), clockwise
   * rotation, tint/alpha, and additive blend mode (`blendMode == 1`).
   */
  private fun drawAbmpNode(
    node: QlieGdxTextureLoader.AbmpRuntimeNode,
    timeMs: Float,
    trackName: String? = null,
    trackIndex: Int? = null,
    originX: Float = 0f,
    originY: Float = VIRTUAL_HEIGHT,
    parentScaleX: Float = 1f,
    parentScaleY: Float = 1f,
    alphaMul: Float = 1f,
    skipLayerPredicate: ((AbmotionParser.EvaluatedLayerState) -> Boolean)? = null,
    textureOverride: ((AbmotionParser.EvaluatedLayerState) -> Texture?)? = null,
    textureFlipY: Boolean = false,
    linkedTexture: Texture? = null,
  ) {
    val doc = node.document
    if (doc == null) {
      val firstTex = node.slots.firstNotNullOfOrNull { it.texture } ?: return
      batch.setColor(1f, 1f, 1f, alphaMul.coerceIn(0f, 1f))
      batch.draw(
        firstTex,
        originX,
        originY - firstTex.height,
        firstTex.width.toFloat(),
        firstTex.height.toFloat(),
      )
      batch.setColor(1f, 1f, 1f, 1f)
      return
    }
    val track =
      trackIndex?.let { doc.tracks.getOrNull(it) }
        ?: trackName?.let { doc.findTrack(it) }
        ?: doc.defaultTrack()
        ?: return
    val evaluated = AbmotionParser.evaluateTrack(track, timeMs)
    for (ev in evaluated) {
      if (skipLayerPredicate?.invoke(ev) == true) continue
      val slot = node.slots.getOrNull(ev.imageIndex) ?: continue
      val layerAlpha = (ev.alpha * alphaMul).coerceIn(0f, 1f)
      if (layerAlpha <= 0.005f) continue

      // kfType == 1 marks a "fill" layer: it has no artwork of its own — the image slot index is
      // meaningless (usually 0, which points at an unrelated nested container) — and
      // scaleX/scaleY are the target size in *pixels*, not a percentage. `FrameUp` is a 1024x110
      // white bar, `White_Fill` a 1024x200 slab, `Back_White` a full 1024x768 card, `Back` a
      // 1024x550 backdrop. Dividing those by 100 drew them 10x too large, spilling outside the
      // frame; this is the intro-overflow bug.
      val pixelSized = ev.kfType == 1
      val sx = (ev.scaleXPercent / 100f) * parentScaleX
      val sy = (ev.scaleYPercent / 100f) * parentScaleY

      if (!pixelSized && slot.childNode != null) {
        // kfType == 20 with a nested container is a particle emitter (sakura petals, grass
        // tufts, sparkles): scatter the child timeline as a deterministic field instead of one
        // stretched sprite. Plain-image type 20 layers (the calendar `Dot` glow) keep the
        // single-sprite path below via the leaf branch.
        if (ev.kfType == 20) {
          drawParticleField(
            node = slot.childNode,
            ev = ev,
            wallTimeMs = timeMs,
            originX = originX,
            originY = originY,
            parentScaleX = parentScaleX,
            parentScaleY = parentScaleY,
            alphaMul = layerAlpha,
          )
          continue
        }
        val childDoc = slot.childNode.document
        val cw = (childDoc?.width?.takeIf { it > 0 } ?: 1024).toFloat()
        val ch = (childDoc?.height?.takeIf { it > 0 } ?: 768).toFloat()
        val childTopLeftX = originX + ev.x * parentScaleX - cw * ev.pivotX * sx
        val childTopLeftY = originY - ev.y * parentScaleY + ch * ev.pivotY * sy
        val childDur = (childDoc?.durationMs ?: 0).coerceAtLeast(1000)
        val childTime =
          if (
            ev.layerName.equals("Particle", ignoreCase = true) ||
              slot.layer.name.contains("hana", ignoreCase = true)
          ) {
            (timeMs % childDur.toFloat())
          } else {
            ev.childTimeMs.coerceAtLeast(0f)
          }
        if (walkure.EngineTrace.nestedTraceEnabled) {
          val nestedImgs =
            slot.childNode.container.images
              .mapIndexed { ii, im ->
                "$ii=${im.name}(${im.filename.take(8)}) nested=${im.nestedAbmp != null}"
              }
              .joinToString(" | ")
          walkure.EngineTrace.log(
            "EYECATCH-NESTED layer=\"${slot.layer.name}\" childTime=${childTime.toString().take(6)}ms " +
              "images=[$nestedImgs]",
          )
        }
        drawAbmpNode(
          node = slot.childNode,
          timeMs = childTime,
          trackName = null,
          originX = childTopLeftX,
          originY = childTopLeftY,
          parentScaleX = sx,
          parentScaleY = sy,
          alphaMul = layerAlpha,
          skipLayerPredicate = null,
          linkedTexture = linkedTexture,
        )
      } else {
        // A pixel-sized layer is a solid fill in the layer's tint colour; its image index is
        // unused. In `Scene01a` the fill `Back` and the art layer `BG01` both name image 0, but
        // only `BG01` (kfType 4) draws `bg01` — the fill is the white matte behind it. Drawing
        // the slot's art for these layers stretched captions and the Celia container across the
        // backdrop, and drew wfade03's Dammy1024 test stripes over half the screen.
        val fill = if (pixelSized) solidWhiteTex else null
        // `*Link,0` slots inside a wfade film strip are a placeholder holding a copy of the
        // artist's default strip (always Lisa's). The real per-character strip is the sibling
        // image the timeline never references, so substitute it here.
        val linked =
          if (linkedTexture != null && slot.layer.name.startsWith("*Link")) linkedTexture else null
        val tex =
          if (fill != null) fill
          else textureOverride?.invoke(ev) ?: linked ?: slot.texture ?: continue
        val tw = tex.width.toFloat()
        val th = tex.height.toFloat()
        val glOriginX = tw * ev.pivotX
        val glOriginY = th * (1f - ev.pivotY)
        val drawX = originX + ev.x * parentScaleX - glOriginX
        val drawY = originY - ev.y * parentScaleY - glOriginY
        // A pixel-sized (kfType==1) layer gives its final size in pixels, not a percentage.
        val dsx = if (pixelSized && tw > 0f) (ev.scaleXPercent * parentScaleX) / tw else sx
        val dsy = if (pixelSized && th > 0f) (ev.scaleYPercent * parentScaleY) / th else sy

        if (ev.blendMode == 1) {
          batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
        }
        batch.setColor(ev.tintR, ev.tintG, ev.tintB, layerAlpha)
        batch.draw(
          tex,
          drawX,
          drawY,
          glOriginX,
          glOriginY,
          tw,
          th,
          dsx,
          dsy,
          -ev.angleDeg,
          0,
          0,
          tex.width,
          tex.height,
          false,
          textureFlipY,
        )
        if (ev.blendMode == 1) {
          batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        }
      }
    }
    batch.setColor(1f, 1f, 1f, 1f)
  }

  /**
   * Draws a `kfType == 20` particle emitter as a deterministic field of nested-timeline sprites
   * (see [AbmotionParser.particleFieldLayout]). The emitter's scale sizes the emission rect, not
   * the petals, so children keep the ancestors' scale; each petal loops the child timeline with its
   * own variant track and phase offset. The emitter's own angle is ignored (petals carry their own
   * rotation inside the nested timeline).
   */
  private fun drawParticleField(
    node: QlieGdxTextureLoader.AbmpRuntimeNode,
    ev: AbmotionParser.EvaluatedLayerState,
    wallTimeMs: Float,
    originX: Float,
    originY: Float,
    parentScaleX: Float,
    parentScaleY: Float,
    alphaMul: Float,
  ) {
    val childDoc = node.document
    val trackCount = childDoc?.tracks?.size ?: 0
    val childDur = (childDoc?.durationMs ?: 0).coerceAtLeast(1000)
    val placements =
      AbmotionParser.particleFieldLayout(
        ev,
        "${ev.layerName}#${ev.imageIndex}",
        trackCount,
        childDur,
      )
    for (p in placements) {
      drawAbmpNode(
        node = node,
        timeMs = (wallTimeMs + p.timeOffsetMs) % childDur.toFloat(),
        trackIndex = p.trackIndex,
        originX = originX + p.x * parentScaleX,
        originY = originY - p.y * parentScaleY,
        parentScaleX = parentScaleX,
        parentScaleY = parentScaleY,
        alphaMul = alphaMul,
      )
    }
  }

  private fun enterGameplayWithScript(vfsPath: String, preserveVisualState: Boolean = false) {
    val resolved = vfs.resolveEntry(vfsPath)?.second ?: return
    if (!preserveVisualState) {
      audioManager.stopAllVoiceAndSe()
    } else {
      audioManager.stopVoice()
    }
    currentScenarioIndex =
      scenarioEntries.indexOfFirst { it.normalizedPath == resolved.normalizedPath }.coerceAtLeast(0)
    val content = vfs.readTextShiftJis(resolved.path)
    val parsed = ScenarioParser.parse(resolved.path, content)
    if (preserveVisualState && ::vm.isInitialized) {
      vm.continueWithScript(parsed)
    } else {
      vm =
        ScenarioVm(
          parsed,
          aliasResolver,
          branchEvaluator = { branchId -> campaign.evaluateBranch(branchId) },
          avgSettings = avgSettings,
        )
      prevBackgroundTex = null
      currentBackgroundTex = null
      overlayBackgroundTex = null
      currentFaceTex = null
      currentWeatherEffect = null
      activeSpriteTextures = emptyList()
    }
    mode = EngineMode.GAMEPLAY
    showBacklog = false
    advanceStep()
  }

  private fun openSaveLoadScreen(saveMode: Boolean) {
    isSaveScreenMode = saveMode
    previousMode = mode
    mode = EngineMode.SAVE_LOAD
    saveScrollOffset = 0f
    savePageExitTimerSec = 1f
    savePageEnterTimerSec = 0f
    clockNeedleTargetDeg = 36f + currentSavePage * 72f
    clockNeedleAngleDeg = clockNeedleTargetDeg
    refreshSlotThumbnails()
    triggerUiOverlay("スクリプト\\saveload\\fadein01.b")
  }

  private fun refreshSlotThumbnails() {
    slotThumbnailTextures.values.forEach { runCatching { it.dispose() } }
    slotThumbnailTextures.clear()
    val slots =
      saveManager.listPageSlots(currentSavePage, slotsPerPage = 25) +
        listOfNotNull(saveManager.loadSlot(0))
    for (slot in slots.filterNotNull()) {
      val f = slot.thumbnailFile ?: continue
      if (f.exists()) {
        runCatching {
          slotThumbnailTextures[slot.slotNumber] = Texture(FileHandle(f))
        }
      }
    }
  }

  private fun playVideo(movieFileName: String, onComplete: () -> Unit) {
    audioManager.stopAllVoiceAndSe()
    onVideoFinished = onComplete
    mode = EngineMode.VIDEO
    if (videoPlayer.isExternalOverlay) {
      val started =
        videoPlayer.startMovie(movieFileName) {
          Gdx.app.postRunnable {
            onVideoFinished = null
            onComplete()
          }
        }
      if (!started) {
        onVideoFinished = null
        onComplete()
      }
    } else {
      if (!videoPlayer.startMovie(movieFileName, onComplete)) {
        onVideoFinished = null
        onComplete()
      }
    }
  }

  private fun isTouchOverUi(ux: Float, uy: Float): Boolean {
    if (activeModalPrompt != null) return true
    when (mode) {
      EngineMode.TITLE -> {
        if (!titleIntroSkipped && titleIntroTimerSec < 16.0f) return true
        if (Rectangle(780f, 20f, 150f, 36f).contains(ux, uy)) return true
        for (btn in activeTitleButtons()) {
          if (btn.bounds.contains(ux, uy)) return true
        }
        return false
      }
      EngineMode.GAMEPLAY -> {
        if (showBacklog) return true
        for (btn in debugHudButtons) {
          if (btn.bounds.contains(ux, uy)) return true
        }
        if (hideMessageWindow) return false
        val choices = vm.pendingChoices()
        if (choices.isNotEmpty()) {
          val boxW = 660f
          val boxH = 42f
          val startX = (VIRTUAL_WIDTH - boxW) / 2f
          val startY = 480f
          for (i in choices.indices) {
            val by = startY - i * (boxH + 18f)
            if (ux in startX..(startX + boxW) && uy in by..(by + boxH)) return true
          }
        }
        for (btn in windowSystemButtons) {
          if (btn.bounds.contains(ux, uy)) return true
        }
        for (btn in windowRadialButtons) {
          if (btn.bounds.contains(ux, uy)) return true
        }
        if (uy in 0f..200f) return true
        return false
      }
      EngineMode.SAVE_LOAD,
      EngineMode.CONFIG,
      EngineMode.ALBUM_MUSIC,
      EngineMode.VIDEO -> return true
    }
  }

  private fun handleGameplaySceneTap(button: Int): Boolean {
    if (button == Input.Buttons.LEFT) {
      if (showBacklog) {
        showBacklog = false
        return true
      }
      if (hideMessageWindow) {
        hideMessageWindow = false
        return true
      }
      if (vm.pendingChoices().isEmpty()) {
        isSkipMode = false
        advanceStep()
        return true
      }
    } else if (button == Input.Buttons.RIGHT) {
      hideMessageWindow = !hideMessageWindow
      return true
    }
    return false
  }

  private fun initInput() {
    Gdx.input.inputProcessor =
      object : InputAdapter() {
        override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
          mouseWorld = viewport.unproject(Vector2(screenX.toFloat(), screenY.toFloat()))
          unprojectUi(screenX.toFloat(), screenY.toFloat(), mouseUi)
          return false
        }

        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
          dragStartX = screenX.toFloat()
          dragStartY = screenY.toFloat()
          lastDragScreenX = screenX.toFloat()
          lastDragScreenY = screenY.toFloat()
          hasDragged = false

          mouseWorld = viewport.unproject(Vector2(screenX.toFloat(), screenY.toFloat()))
          unprojectUi(screenX.toFloat(), screenY.toFloat(), mouseUi)

          if (activeModalPrompt != null) {
            val yesRect = Rectangle(VIRTUAL_WIDTH / 2f - 150f, VIRTUAL_HEIGHT / 2f - 76f, 130f, 70f)
            val noRect = Rectangle(VIRTUAL_WIDTH / 2f + 20f, VIRTUAL_HEIGHT / 2f - 76f, 130f, 70f)
            if (yesRect.contains(mouseUi.x, mouseUi.y)) {
              val cb = activeModalOnConfirm
              activeModalPrompt = null
              activeModalEnglishTexPath = null
              activeModalOnConfirm = null
              cb?.invoke()
            } else if (noRect.contains(mouseUi.x, mouseUi.y) || button == Input.Buttons.RIGHT) {
              activeModalPrompt = null
              activeModalEnglishTexPath = null
              activeModalOnConfirm = null
            }
            return true
          }

          val hitUi = isTouchOverUi(mouseUi.x, mouseUi.y)
          if (hitUi) {
            isDraggingPan = false
            return when (mode) {
              EngineMode.TITLE -> handleTitleTouch(mouseUi, button)
              EngineMode.GAMEPLAY -> handleGameplayTouch(mouseUi, button)
              EngineMode.SAVE_LOAD -> handleSaveLoadTouch(mouseUi, button)
              EngineMode.CONFIG -> handleConfigTouch(mouseUi, button)
              EngineMode.ALBUM_MUSIC -> handleAlbumTouch(mouseUi, button)
              EngineMode.VIDEO -> {
                videoPlayer.stop()
                onVideoFinished?.invoke()
                true
              }
            }
          } else {
            isDraggingPan =
              (button == Input.Buttons.MIDDLE ||
                button == Input.Buttons.RIGHT ||
                saveManager.aspectRatioMode == AspectRatioMode.FILL_WIDE_PAN)
            return false
          }
        }

        override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
          mouseWorld = viewport.unproject(Vector2(screenX.toFloat(), screenY.toFloat()))
          unprojectUi(screenX.toFloat(), screenY.toFloat(), mouseUi)
          val distSq =
            (screenX - dragStartX) * (screenX - dragStartX) +
              (screenY - dragStartY) * (screenY - dragStartY)
          if (distSq > 36f) {
            hasDragged = true
          }
          if (isDraggingPan && hasDragged) {
            val scaleX = camera.viewportWidth / Gdx.graphics.width.toFloat().coerceAtLeast(1f)
            val scaleY = camera.viewportHeight / Gdx.graphics.height.toFloat().coerceAtLeast(1f)
            val dx = (screenX - lastDragScreenX) * scaleX
            val dy = (screenY - lastDragScreenY) * scaleY
            panOffsetX -= dx
            panOffsetY += dy
            lastDragScreenX = screenX.toFloat()
            lastDragScreenY = screenY.toFloat()
            updateCameraPan()
            return true
          }
          return false
        }

        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
          val wasDragging = isDraggingPan && hasDragged
          isDraggingPan = false
          if (!wasDragging && !hasDragged) {
            if (mode == EngineMode.GAMEPLAY) {
              return handleGameplaySceneTap(button)
            }
          }
          return false
        }

        override fun keyDown(keycode: Int): Boolean {
          if (keycode == Input.Keys.F12) {
            showDebugHud = !showDebugHud
            statusMessage = if (showDebugHud) "Debug HUD enabled" else "Debug HUD disabled"
            return true
          }
          if (keycode == Input.Keys.F11) {
            setAspectRatioMode(saveManager.aspectRatioMode.next())
            return true
          }
          if (keycode == Input.Keys.R) {
            resetPan()
            return true
          }

          if (activeModalPrompt != null) {
            if (keycode == Input.Keys.ENTER || keycode == Input.Keys.Y) {
              val cb = activeModalOnConfirm
              activeModalPrompt = null
              activeModalEnglishTexPath = null
              activeModalOnConfirm = null
              cb?.invoke()
              return true
            } else if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.N) {
              activeModalPrompt = null
              activeModalEnglishTexPath = null
              activeModalOnConfirm = null
              return true
            }
            return false
          }

          if (mode == EngineMode.VIDEO) {
            if (
              keycode == Input.Keys.ESCAPE ||
                keycode == Input.Keys.SPACE ||
                keycode == Input.Keys.ENTER
            ) {
              videoPlayer.stop()
              onVideoFinished?.invoke()
              return true
            }
            return false
          }

          if (mode == EngineMode.TITLE) {
            if (!titleIntroSkipped && titleIntroTimerSec < 16.0f) {
              if (
                keycode == Input.Keys.ESCAPE ||
                  keycode == Input.Keys.SPACE ||
                  keycode == Input.Keys.ENTER
              ) {
                titleIntroSkipped = true
                titleIntroTimerSec = 16.0f
                return true
              }
            }
            if (keycode == Input.Keys.ESCAPE && titlePage == 1) {
              audioManager.playSe("misc\\Cancel01.WAV")
              titlePage = 0
              return true
            }
            if (keycode == Input.Keys.LEFT) {
              panOffsetX -= 40f
              updateCameraPan()
              return true
            }
            if (keycode == Input.Keys.RIGHT) {
              panOffsetX += 40f
              updateCameraPan()
              return true
            }
            if (keycode == Input.Keys.UP) {
              panOffsetY += 40f
              updateCameraPan()
              return true
            }
            if (keycode == Input.Keys.DOWN) {
              panOffsetY -= 40f
              updateCameraPan()
              return true
            }
            return false
          }

          if (mode != EngineMode.GAMEPLAY) {
            if (keycode == Input.Keys.ESCAPE) {
              mode = previousMode
              return true
            }
            return false
          }

          when (keycode) {
            Input.Keys.SPACE,
            Input.Keys.ENTER -> {
              if (dayEyeCatchActive && dayEyeCatchTimer > 0.5f) {
                dayEyeCatchTimer = 0.5f
                return true
              }
              if (showBacklog) showBacklog = false
              else if (hideMessageWindow) hideMessageWindow = false else advanceStep()
              return true
            }
            Input.Keys.V -> {
              audioManager.replayLastVoice()
              return true
            }
            Input.Keys.A -> {
              isAutoMode = !isAutoMode
              autoTimerSec = 0f
              return true
            }
            Input.Keys.S,
            Input.Keys.CONTROL_LEFT,
            Input.Keys.CONTROL_RIGHT -> {
              isSkipMode = !isSkipMode
              skipTimerSec = 0f
              return true
            }
            Input.Keys.L -> {
              showBacklog = !showBacklog
              backlogScrollOffset = 0
              return true
            }
            Input.Keys.C -> {
              openConfigScreen(EngineMode.GAMEPLAY)
              return true
            }
            Input.Keys.M -> {
              audioManager.isMuted = !audioManager.isMuted
              return true
            }
            Input.Keys.H -> {
              hideMessageWindow = !hideMessageWindow
              return true
            }
            Input.Keys.F5 -> {
              quickSave()
              return true
            }
            Input.Keys.F9 -> {
              quickLoad()
              return true
            }
            Input.Keys.LEFT -> {
              panOffsetX -= 40f
              updateCameraPan()
              return true
            }
            Input.Keys.RIGHT -> {
              panOffsetX += 40f
              updateCameraPan()
              return true
            }
            Input.Keys.UP -> {
              if (showBacklog) backlogScrollOffset += 1
              else {
                panOffsetY += 40f
                updateCameraPan()
              }
              return true
            }
            Input.Keys.DOWN -> {
              if (showBacklog) backlogScrollOffset = (backlogScrollOffset - 1).coerceAtLeast(0)
              else {
                panOffsetY -= 40f
                updateCameraPan()
              }
              return true
            }
            Input.Keys.ESCAPE -> {
              if (showBacklog) showBacklog = false
              else if (hideMessageWindow) hideMessageWindow = false else enterTitleScreen()
              return true
            }
          }
          return false
        }

        override fun scrolled(amountX: Float, amountY: Float): Boolean {
          if (mode == EngineMode.SAVE_LOAD) {
            saveScrollOffset = (saveScrollOffset + amountY * 1.25f).coerceIn(0f, 18f)
            return true
          }
          if (mode == EngineMode.GAMEPLAY) {
            if (showBacklog) {
              if (amountY < 0) backlogScrollOffset += 1
              else if (amountY > 0) backlogScrollOffset = (backlogScrollOffset - 1).coerceAtLeast(0)
              return true
            } else if (amountY < 0) {
              showBacklog = true
              backlogScrollOffset = 0
              return true
            }
          }
          return false
        }
      }
  }

  private fun handleTitleTouch(ui: Vector2, button: Int = Input.Buttons.LEFT): Boolean {
    if (!titleIntroSkipped && titleIntroTimerSec < 16.0f) {
      // Click to skip intro directly to Scene03 title screen
      titleIntroSkipped = true
      titleIntroTimerSec = 16.0f
      return true
    }
    if (button == Input.Buttons.RIGHT) {
      if (titlePage == 1) {
        audioManager.playSe("misc\\Cancel01.WAV")
        switchTitlePage(0)
        return true
      }
    }
    // Web button at (780f, 20f)
    if (Rectangle(780f, 20f, 150f, 36f).contains(ui.x, ui.y)) {
      audioManager.playSe("misc\\Click01.wav")
      Gdx.net.openURI("http://www.ricotta-soft.jp/")
      return true
    }
    for (btn in activeTitleButtons()) {
      if (btn.bounds.contains(ui.x, ui.y)) {
        btn.onClick()
        return true
      }
    }
    return false
  }

  private fun handleGameplayTouch(ui: Vector2, button: Int): Boolean {
    if (dayEyeCatchActive && dayEyeCatchTimer > 0.5f) {
      dayEyeCatchTimer = 0.5f
      return true
    }

    if (showDebugHud) {
      for (btn in debugHudButtons) {
        if (btn.bounds.contains(ui.x, ui.y)) {
          btn.onClick()
          return true
        }
      }
    }

    if (!hideMessageWindow && !showBacklog) {
      for (btn in windowSystemButtons) {
        if (btn.bounds.contains(ui.x, ui.y)) {
          btn.onClick()
          return true
        }
      }
      for (btn in windowRadialButtons) {
        if (btn.bounds.contains(ui.x, ui.y)) {
          btn.onClick()
          return true
        }
      }
    }

    if (showBacklog) {
      showBacklog = false
      return true
    }

    if (hideMessageWindow) {
      hideMessageWindow = false
      return true
    }

    val choices = vm.pendingChoices()
    if (choices.isNotEmpty()) {
      val idx = findClickedChoice(ui.x, ui.y, choices.size)
      if (idx >= 0) {
        audioManager.playSe("misc\\Click01.wav")
        val snap =
          vm.selectChoice(idx) { chosenLabel ->
            campaign.applyChoiceLabelEffect(chosenLabel)
          }
        if (snap.triggeredRouteJumpLabel != null) {
          val targetStage =
            campaign.resolveRouteJumpLabel(snap.triggeredRouteJumpLabel)
              ?: campaign.nextStageAfter(vm.currentScript.path)
          transitionToCampaignStage(targetStage)
        } else {
          syncStateWithVm(snap)
        }
        return true
      }
      return false
    }

    if (button == Input.Buttons.LEFT) {
      isSkipMode = false
      advanceStep()
      return true
    } else if (button == Input.Buttons.RIGHT) {
      hideMessageWindow = !hideMessageWindow
      return true
    }
    return false
  }

  private fun transitionToCampaignStage(stage: CampaignStage?) {
    walkure.EngineTrace.log(
      "RENDER transitionToCampaignStage stage=$stage wasGameplay=${mode == EngineMode.GAMEPLAY} " +
        "pendingSpec=${walkure.EngineTrace.id(vm.lastTransitionSpec)}",
    )
    val wasGameplay = (mode == EngineMode.GAMEPLAY)
    when (stage) {
      is CampaignStage.ScenarioFile ->
        enterGameplayWithScript(stage.vfsPath, preserveVisualState = wasGameplay)
      is CampaignStage.MoviePlayback -> {
        val afterMovie = campaign.nextStageAfter(stage.movieFileName)
        playVideo(stage.movieFileName) {
          if (afterMovie is CampaignStage.ScenarioFile) {
            enterGameplayWithScript(afterMovie.vfsPath, preserveVisualState = false)
          } else {
            enterTitleScreen()
          }
        }
      }
      is CampaignStage.HeroineRouteEntry -> {
        val firstHeroineStage = campaign.heroineRoutes[stage.heroineKey]?.firstOrNull()
        transitionToCampaignStage(firstHeroineStage)
      }
      null -> {
        if (::vm.isInitialized) {
          saveManager.markRouteCleared(vm.routeName)
        }
        enterTitleScreen()
      }
    }
  }

  private fun advanceStep() {
    if (vm.isFinished()) {
      val nextStage = campaign.nextStageAfter(vm.currentScript.path)
      transitionToCampaignStage(nextStage)
      return
    }

    val prevDate = vm.inGameDate
    val snap = vm.stepToNextStop()
    if (snap.triggeredDayEyeCatch != null || snap.inGameDate != prevDate) {
      triggerDayEyeCatch(snap.triggeredDayEyeCatch ?: snap.inGameDate)
    }

    if (snap.triggeredRouteJumpLabel != null) {
      val targetStage =
        campaign.resolveRouteJumpLabel(snap.triggeredRouteJumpLabel)
          ?: campaign.nextStageAfter(vm.currentScript.path)
      transitionToCampaignStage(targetStage)
      return
    }

    if (snap.triggeredMovieFile != null) {
      playVideo(snap.triggeredMovieFile) {
        mode = EngineMode.GAMEPLAY
        advanceStep()
      }
      return
    }

    if (snap.isFinishedAndEmpty()) {
      val nextStage = campaign.nextStageAfter(vm.currentScript.path)
      transitionToCampaignStage(nextStage)
      return
    }

    syncStateWithVm(snap)
  }

  private fun ScenarioVm.VmSnapshot.isFinishedAndEmpty(): Boolean =
    vm.isFinished() &&
      pendingChoices.isEmpty() &&
      triggeredMovieFile == null &&
      triggeredRouteJumpLabel == null

  private fun syncStateWithVm(snap: ScenarioVm.VmSnapshot) {
    saveManager.markLineRead(snap.scriptPath, snap.currentLineNumber)

    val transSpec = snap.transitionSpec
    val syncT0 = walkure.EngineTrace.nowNanos()
    traceSyncCallCount++
    val isNewSpec = transSpec != null && transSpec !== lastAppliedTransitionSpec
    if (walkure.EngineTrace.enabled) {
      val priorPct =
        if (transitionDurationSec > 0f)
          ((transitionProgressSec / transitionDurationSec) * 100f).toInt()
        else 100
      val specTag = if (isNewSpec) "NEW" else "STICKY(repeat of last)"
      val canReArm = !isTransitionActive || transitionProgressSec >= transitionDurationSec
      walkure.EngineTrace.log(
        "RENDER sync#$traceSyncCallCount ${snap.scriptPath}:${snap.currentLineNumber} " +
          "spec=${walkure.EngineTrace.id(transSpec)} $specTag name=${transSpec?.effectName} " +
          "mode=${transSpec?.mode} dur=${transSpec?.durationSec} feather=${transSpec?.feather} " +
          "priorActive=$isTransitionActive atProgress=${priorPct}% " +
          "canReArm=$canReArm " +
          "arm=${if (isNewSpec && canReArm) "YES" else "NO - retrigger avoided"}",
      )
    }
    // Keep the previous spec reference when transSpec is null (dialogue lines etc.) so the
    // sticky comparison in isNewSpec still works. Clearing it here loses the reference and
    // causes every subsequent ^fdi/^fade to re-arm an in-progress transition, producing black
    // frames and a stalled wipe under skip mode.
    if (transSpec != null) {
      lastAppliedTransitionSpec = transSpec
    }

    // Only a freshly requested fade may re-arm the wipe, and only when no transition is
    // currently in progress. Everything below used to run on every call, which restarted a
    // full-screen blend on each click and, under skip mode, left the transition permanently
    // at progress ~0: the observed black frames with no animation.
    if (isNewSpec && (!isTransitionActive || transitionProgressSec >= transitionDurationSec)) {
      val transDur = transSpec.durationSec
      bgFadeSpeed = (1f / transDur).coerceIn(0.4f, 8.0f)
      transitionDurationSec = transDur
      transitionProgressSec = 0f
      isTransitionActive = true
      pendingSceneCapture = true
      traceTransitionStartNanos = walkure.EngineTrace.nowNanos()
      traceTransitionFrames = 0
      activeTransitionMode = transSpec.mode
      activeTransitionFeather = 0.02f + transSpec.feather * 0.025f

      val effName = transSpec.effectName
      val abmpT0 = walkure.EngineTrace.nowNanos()
      activeTransitionAbmp =
        if (
          effName.endsWith(".b", ignoreCase = true) ||
            effName.startsWith("wfade", ignoreCase = true) ||
            effName.startsWith("motion", ignoreCase = true)
        ) {
          textureLoader.loadAbmpRuntime(effName)
            ?: textureLoader.loadAbmpRuntime("フェード\\$effName.b")
        } else {
          null
        }
      val abmpMs = walkure.EngineTrace.msSince(abmpT0)
      // Every `wfade02*` container carries the character strip in a slot the timeline never
      // references, and the referenced `*Link,0` slot holds a copy of Lisa's strip. Pick the
      // unreferenced 1112x230 layer as the real film strip so each character shows itself.
      activeTransitionLinkedTex = resolveUnreferencedStrip(activeTransitionAbmp)

      val maskT0 = walkure.EngineTrace.nowNanos()
      ruleMaskTex =
        if (
          effName.isNotEmpty() &&
            activeTransitionMode != 2 &&
            activeTransitionMode != 4 &&
            !effName.equals("crossfade", ignoreCase = true) &&
            !effName.equals("fade", ignoreCase = true) &&
            !effName.equals("cut", ignoreCase = true) &&
            !effName.equals("colorfade", ignoreCase = true) &&
            !effName.equals("モザイク", ignoreCase = true)
        ) {
          textureLoader.loadTransitionMaskTexture(effName)
        } else {
          null
        }
      val maskMs = walkure.EngineTrace.msSince(maskT0)
      if (walkure.EngineTrace.enabled) {
        walkure.EngineTrace.log(
          "RENDER loads eff=\"$effName\" abmp=${abmpMs}ms(hit=${activeTransitionAbmp != null}) " +
            "mask=${maskMs}ms(hit=${ruleMaskTex != null})",
        )
        // One-shot geometry dump: shows how each eyecatch layer is placed, which is what decides
        // whether a filmstrip layer is being scrolled or simply painted whole.
        val abmpNode = activeTransitionAbmp
        val doc = abmpNode?.document
        if (doc != null && walkure.EngineTrace.enabled) {
          val track = doc.defaultTrack()
          val midMs = (doc.durationMs / 2f).coerceAtLeast(1f)
          val layers =
            track
              ?.let {
                AbmotionParser.evaluateTrack(it, 0f) + AbmotionParser.evaluateTrack(it, midMs)
              }
              .orEmpty()
          walkure.EngineTrace.log(
            "EYECATCH durMs=${doc.durationMs} slots=${abmpNode.slots.size} " +
              "layers(n=${layers.size}) " +
              layers.joinToString(" | ") { ev ->
                "${ev.layerName}#img${ev.imageIndex} mask=${ev.maskIndex} z=${ev.zPriority} " +
                  "pos=${ev.x},${ev.y} sc=${ev.scaleXPercent},${ev.scaleYPercent} a=${ev.alpha}"
              },
          )
          // Keyframes are what decide whether a filmstrip layer selects a cell through a child
          // clip (childStartMs/childEndMs) or by switching imageIndex; without this the whole strip
          // gets painted as one texture.
          track?.layers?.forEachIndexed { li, layer ->
            val slot = abmpNode.slots.getOrNull(layer.keyframes.firstOrNull()?.imageIndex ?: -1)
            walkure.EngineTrace.log(
              "EYECATCH-LAYER[$li] name=\"${layer.name}\" enabled=${layer.enabled} " +
                "role=${layer.groupRole} kfs=${layer.keyframes.size} " +
                "childNode=${slot?.childNode != null} " +
                layer.keyframes.joinToString(" ") { kf ->
                  "@${kf.timeMs}ms img=${kf.imageIndex} mask=${kf.maskIndex} " +
                    "pos=${kf.x},${kf.y} sc=${kf.scaleX},${kf.scaleY} a=${kf.alpha} " +
                    "child=${kf.childStartMs}..${kf.childEndMs} ease=${kf.easeType}"
                },
            )
          }
          abmpNode.container.images.forEachIndexed { ii, img ->
            walkure.EngineTrace.log(
              "EYECATCH-IMG[$ii] name=\"${img.name}\" file=\"${img.filename}\" " +
                "${img.offsetX},${img.offsetY} nested=${img.nestedAbmp != null}",
            )
          }
        }
      }

      if (effName.startsWith("wfade", ignoreCase = true)) {
        val charTag =
          effName
            .removePrefix("wfade02")
            .removePrefix("wfade03")
            .removePrefix("wfade01")
            .replace("ロング", "long")
        val eyePngPath = "スクリプト\\eyecatch\\01\\image\\$charTag.png"
        val wfadeAbmpPath = "フェード\\${effName}.b"
        eyeCatchBannerTex =
          if (activeTransitionAbmp != null) {
            // The ABMP motion container is the authoritative eyecatch. Loading the flat banner as
            // well made both draw on top of each other — the "largest layer" of these containers is
            // the Dammy1024 placeholder, so the placeholder animation appeared over the real one.
            null
          } else {
            textureLoader.loadTexture(eyePngPath)
              ?: textureLoader.loadLargestAbmpLayerTexture(wfadeAbmpPath)
          }
        eyeCatchTimer = transDur.coerceIn(1.5f, 3.0f)
        audioManager.playSe("音楽\\ec1.ogg")
      }
    }

    if (isNewSpec) {
      // Snapshot the outgoing scene layers so the wipe can fade them out together with the
      // background. Without this the live sprite set is painted at full opacity over the incoming
      // scene, which showed two characters at once during eyecatch fades.
      prevSpriteTextures = activeSpriteTextures
      prevOverlayTex = overlayBackgroundTex
      if (walkure.EngineTrace.enabled) {
        walkure.EngineTrace.log(
          "RENDER spriteSnapshot prev=[${prevSpriteTextures.joinToString(",") { it.charKey }}] " +
            "incoming=[${snap.activeSprites.keys.joinToString(",")}]",
        )
      }
    }

    val bgT0 = walkure.EngineTrace.nowNanos()
    val bgSym = snap.backgroundSymbol?.lowercase()
    val outgoingBg =
      currentBackgroundTex
        ?: if (solidBgColor != null && solidBgColor!!.r > 0.5f) solidWhiteTex else solidBlackTex
    when (bgSym) {
      "bg_wh" -> {
        solidBgColor = Color(0.96f, 0.96f, 0.98f, 1f)
        prevBackgroundTex = outgoingBg
        currentBackgroundTex = solidWhiteTex
        bgFadeAlpha = 0f
      }
      "bg_bk",
      "消去",
      "off" -> {
        solidBgColor = Color(0.04f, 0.05f, 0.08f, 1f)
        prevBackgroundTex = outgoingBg
        currentBackgroundTex = solidBlackTex
        bgFadeAlpha = 0f
      }
      else -> {
        val newTex =
          snap.backgroundVfsPath?.let { path ->
            saveManager.markCgViewed(path)
            textureLoader.loadTexture(path)
          }
        if (newTex != null && newTex !== currentBackgroundTex) {
          prevBackgroundTex = outgoingBg
          currentBackgroundTex = newTex
          bgFadeAlpha = 0f
          solidBgColor = null
        }
      }
    }

    currentWeatherEffect = snap.weatherEffect
    if (snap.flashTriggered) {
      flashTimerSec = 0.32f
    }

    overlayBackgroundTex =
      snap.overlayBgVfsPath?.let { path ->
        if (path.endsWith(".b", ignoreCase = true)) {
          textureLoader.loadCompositedAbmpTexture(path)
        } else {
          textureLoader.loadTexture(path)
        }
      }

    val bgMs = walkure.EngineTrace.msSince(bgT0)
    val spriteT0 = walkure.EngineTrace.nowNanos()
    activeSpriteTextures =
      snap.activeSprites.values.mapNotNull { slot ->
        val tex = textureLoader.loadTexture(slot.vfsPath) ?: return@mapNotNull null
        RenderedSprite(charKey = slot.charKey, texture = tex, centerX = slot.centerX.toFloat())
      }
    val spriteMs = walkure.EngineTrace.msSince(spriteT0)
    val faceT0 = walkure.EngineTrace.nowNanos()
    currentFaceTex = snap.currentFaceVfsPath?.let { textureLoader.loadTexture(it) }
    val faceMs = walkure.EngineTrace.msSince(faceT0)
    if (walkure.EngineTrace.enabled) {
      walkure.EngineTrace.log(
        "RENDER loads2 bg=${bgMs}ms(swap=${snap.backgroundSymbol}) " +
          "sprites=${spriteMs}ms(n=${activeSpriteTextures.size}) " +
          "face=${faceMs}ms(hit=${currentFaceTex != null})",
      )
    }
    currentFaceOffsetX = snap.currentFaceOffsetX

    if (snap.triggeredActionCharKey != null) {
      activeActionCharKey = snap.triggeredActionCharKey
      activeActionName = snap.triggeredActionName
      actionTimerSec = 0.45f
    }
    if (snap.screenShakeDurationSec > 0f) {
      screenShakeTimerSec = snap.screenShakeDurationSec
    }

    val audioT0 = walkure.EngineTrace.nowNanos()
    if (snap.currentBgmVfsPath != null) {
      saveManager.markBgmPlayed(snap.currentBgmVfsPath)
    }
    audioManager.playBgm(snap.currentBgmVfsPath)
    if (snap.stopSeRequested) {
      audioManager.stopSe()
    } else if (snap.triggeredSeVfsPath != null) {
      audioManager.playSe(snap.triggeredSeVfsPath, loop = snap.triggeredSeLoop)
    }

    val speaker = snap.currentSpeaker
    val isActorMuted = isSpeakerVoiceMuted(speaker)
    if (!isActorMuted && !isSkipMode) {
      audioManager.playVoice(snap.currentVoiceVfsPath)
    } else {
      audioManager.stopVoice()
    }
    val audioMs = walkure.EngineTrace.msSince(audioT0)

    statusMessage =
      "[${snap.inGameDate}] ${snap.scriptPath.substringAfterLast('\\')} (${snap.instructionPointer}/${vm.currentScript.nodes.size}) | BGM: ${audioManager.activeBgmName() ?: "off"}"

    if (walkure.EngineTrace.enabled) {
      walkure.EngineTrace.log(
        "RENDER loads3 audio=${audioMs}ms(bgm=${snap.currentBgmVfsPath} " +
          "voice=${snap.currentVoiceVfsPath}) " +
          "syncStateWithVm TOTAL=${walkure.EngineTrace.msSince(syncT0)}ms",
      )
    }
  }

  private fun isSpeakerVoiceMuted(speaker: String?): Boolean {
    if (speaker.isNullOrBlank()) return false
    val ch =
      voiceChannels.firstOrNull { channel ->
        channel.speakerNames.any { speaker.contains(it, ignoreCase = true) }
      } ?: voiceChannels.lastOrNull()
    return ch != null && saveManager.mutedVoiceActors.contains(ch.index)
  }

  override fun render() {
    val frameT0 = walkure.EngineTrace.nowNanos()
    val dt = Gdx.graphics.deltaTime
    uiAnimTimeSec += dt
    clearScreen(red = 0.04f, green = 0.05f, blue = 0.09f, alpha = 1f)
    viewport.apply()
    batch.projectionMatrix = camera.combined
    shapes.projectionMatrix = camera.combined

    when (mode) {
      EngineMode.TITLE -> renderTitleScreen(dt)
      EngineMode.GAMEPLAY -> renderGameplayScreen(dt)
      EngineMode.SAVE_LOAD -> renderSaveLoadScreen(dt)
      EngineMode.CONFIG -> renderConfigScreen(dt)
      EngineMode.ALBUM_MUSIC -> renderAlbumMusicScreen()
      EngineMode.VIDEO -> renderVideoScreen()
    }

    val uiOverlay = uiOverlayAbmp
    if (uiOverlay != null) {
      uiOverlayTimerSec += dt
      if (uiOverlayTimerSec >= uiOverlayDurationSec) {
        uiOverlayAbmp = null
      } else {
        uiViewport.apply()
        batch.projectionMatrix = uiCamera.combined
        shapes.projectionMatrix = uiCamera.combined
        batch.use {
          drawAbmpNode(uiOverlay, (uiOverlayTimerSec * 1000f).coerceAtLeast(0f))
        }
      }
    }

    if (activeModalPrompt != null) {
      renderModalOverlay()
    }

    traceSlowFrame(frameT0)
  }

  /** Reports frames that exceed the slow-frame threshold along with the transition state. */
  private fun traceSlowFrame(frameT0: Long) {
    if (!walkure.EngineTrace.enabled) return
    val ms = walkure.EngineTrace.msSince(frameT0)
    if (ms < walkure.EngineTrace.slowFrameThresholdMs()) return
    // Cap output so a sustained stall cannot flood the log.
    if (traceSlowFramesLogged >= 500) return
    traceSlowFramesLogged++
    val pct =
      ((transitionProgressSec / transitionDurationSec.coerceAtLeast(0.0001f)) * 100f).toInt()
    walkure.EngineTrace.log(
      "SLOW-FRAME ${ms}ms mode=${mode} transitionActive=${isTransitionActive} " +
        "prog=${pct}% dur=${transitionDurationSec}s modeId=${activeTransitionMode} " +
        "mask=${ruleMaskTex != null} abmp=${activeTransitionAbmp != null} " +
        "bgFadeAlpha=${bgFadeAlpha} sprites=${activeSpriteTextures.size}",
    )
  }

  private fun renderModalOverlay() {
    val prompt = activeModalPrompt ?: return
    uiViewport.apply()
    batch.projectionMatrix = uiCamera.combined
    shapes.projectionMatrix = uiCamera.combined

    Gdx.gl.glEnable(GL20.GL_BLEND)
    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    shapes.begin(ShapeRenderer.ShapeType.Filled)
    shapes.setColor(0.02f, 0.03f, 0.06f, 0.72f)
    shapes.rect(0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
    shapes.end()
    Gdx.gl.glDisable(GL20.GL_BLEND)

    val dialogWinTex = textureLoader.loadAbmpLayerTexture("スクリプト\\dialog\\messagewindow.b", 0)
    val engPromptTex =
      activeModalEnglishTexPath?.let { textureLoader.loadTexture(it) }
        ?: textureLoader.loadTexture("スクリプト\\dialog\\english\\実行します。よろしいですか？.png")

    val yesRect = Rectangle(VIRTUAL_WIDTH / 2f - 150f, VIRTUAL_HEIGHT / 2f - 76f, 130f, 70f)
    val noRect = Rectangle(VIRTUAL_WIDTH / 2f + 20f, VIRTUAL_HEIGHT / 2f - 76f, 130f, 70f)
    val yesHover = yesRect.contains(mouseUi.x, mouseUi.y)
    val noHover = noRect.contains(mouseUi.x, mouseUi.y)

    batch.use {
      if (dialogWinTex != null) {
        batch.draw(dialogWinTex, 0f, VIRTUAL_HEIGHT / 2f - 84f, VIRTUAL_WIDTH, 168f)
      }
      if (engPromptTex != null) {
        batch.draw(
          engPromptTex,
          (VIRTUAL_WIDTH - engPromptTex.width) / 2f,
          VIRTUAL_HEIGHT / 2f + 16f,
        )
      } else {
        dialogueFont.draw(
          batch,
          prompt,
          64f,
          VIRTUAL_HEIGHT / 2f + 48f,
          VIRTUAL_WIDTH - 128f,
          Align.center,
          true,
        )
      }

      // Authentic animated Yes / No buttons from スクリプト\dialog\button_yes.b & button_no.b
      renderDialogChoiceButton(
        "スクリプト\\dialog\\button_yes.b",
        yesRect.x + 25f,
        yesRect.y,
        yesHover,
        "YES",
      )
      renderDialogChoiceButton(
        "スクリプト\\dialog\\button_no.b",
        noRect.x + 25f,
        noRect.y,
        noHover,
        "NO",
      )
    }
  }

  private fun renderDialogChoiceButton(
    abmpPath: String,
    x: Float,
    y: Float,
    hovered: Boolean,
    fallbackLabel: String,
  ) {
    val baseTex = textureLoader.loadAbmpLayerTexture(abmpPath, if (hovered) 1 else 0)
    val stripTex = textureLoader.loadAbmpLayerTexture(abmpPath, 3)
    val textTex = textureLoader.loadAbmpLayerTexture(abmpPath, if (hovered) 5 else 4)
    if (baseTex != null) {
      batch.draw(baseTex, x + 8f, y + 6f, 64f, 64f)
    }
    if (stripTex != null) {
      drawVerticalStripFrame(
        stripTex,
        x,
        y,
        80f,
        80f,
        uiAnimTimeSec,
        if (hovered) 28f else 14f,
        isPlaying = hovered,
      )
    }
    if (textTex != null) {
      batch.draw(textTex, x, y - 4f, 80f, 26f)
    } else {
      hudFont.draw(batch, fallbackLabel, x, y + 14f, 80f, Align.center, false)
    }
  }

  private fun drawTitleButtonAnimated(
    btn: UiButton,
    dt: Float,
    menuAlpha: Float,
    transitionProgress: Float,
    isOutgoing: Boolean,
  ) {
    val path = btn.abmpPath ?: return
    val assets =
      titleButtonAssetsCache.getOrPut(path) { textureLoader.loadButtonAssets(path) } ?: return
    val locked = path.contains("special", ignoreCase = true) && !saveManager.isSpecialUnlocked()
    val hovered = !isOutgoing && !locked && btn.bounds.contains(mouseUi.x, mouseUi.y)

    val state = titleButtonAnimStates.getOrPut(path) { TitleButtonAnimState() }
    if (hovered && !state.isHovered) {
      state.hoverTimeSec = 0f
    }
    state.isHovered = hovered
    if (hovered) {
      state.hoverTimeSec += dt
    } else {
      state.hoverTimeSec = (state.hoverTimeSec - dt * 2f).coerceAtLeast(0f)
    }

    // QLiE *Enable (250ms ease-out 60%->100%) / Normal<->Extra outgoing fade
    val t = transitionProgress.coerceIn(0f, 1f)
    val ease = 1f - (1f - t) * (1f - t) * (1f - t)
    val scale: Float
    val alpha: Float
    val slideY: Float
    if (isOutgoing) {
      scale = 1f - 0.2f * ease
      alpha = (1f - ease) * menuAlpha
      slideY = -18f * ease
    } else {
      scale = 0.6f + 0.4f * ease
      alpha = ease * menuAlpha
      slideY = (1f - ease) * -12f
    }
    if (alpha <= 0.01f) return

    // *OnMouse lift (+10px in Y-up matches QLiE -10px in Y-down)
    val hoverFactor = if (hovered) (state.hoverTimeSec / 0.14f).coerceIn(0f, 1f) else 0f
    val hoverEase = 1f - (1f - hoverFactor) * (1f - hoverFactor)
    val hoverLift = hoverEase * 10f

    val tileSize = 64f * scale
    val cx = btn.bounds.x + 40f
    val cy = btn.bounds.y + 40f + slideY + hoverLift
    val tileX = cx - tileSize / 2f
    val tileY = cy - tileSize / 2f

    val tileTex =
      when {
        locked -> assets.downTile ?: assets.normalTile
        hovered -> assets.hoverTile ?: assets.normalTile
        else -> assets.normalTile
      }

    // 1. Soft Mirror reflection beneath tile (*OnMouse / *Enable Mirror layer)
    if (!locked && tileTex != null) {
      batch.setColor(1f, 1f, 1f, alpha * 0.16f)
      batch.draw(
        tileTex,
        tileX,
        tileY - tileSize * 0.36f,
        tileSize,
        tileSize * 0.42f,
        0,
        0,
        tileTex.width,
        tileTex.height,
        false,
        true,
      )
    }

    // 2. Glossy colored button tile
    batch.setColor(1f, 1f, 1f, alpha * (if (locked) 0.42f else 1f))
    if (tileTex != null) {
      batch.draw(tileTex, tileX, tileY, tileSize, tileSize)
    }

    // 3. Icon sprite strip: static frame 0 at rest, plays smoothly ONLY while hovered/tapped,
    // cropped in UV space inside the inner safe tile margin so edges never overflow.
    val iconStrip = assets.iconStrip
    if (iconStrip != null) {
      val childDoc = assets.iconChildNode?.document
      val fw = childDoc?.width?.takeIf { it > 0 } ?: 80
      val fh = childDoc?.height?.takeIf { it > 0 } ?: 80
      val baseCol = getButtonTileColor(path)
      val tintMult = if (locked) 0.55f else 1.32f
      batch.setColor(
        (baseCol.r * tintMult).coerceAtMost(1f),
        (baseCol.g * tintMult).coerceAtMost(1f),
        (baseCol.b * tintMult).coerceAtMost(1f),
        alpha * (if (locked) 0.5f else 1f),
      )
      val innerSize = 54f * scale
      drawVerticalStripFrame(
        tex = iconStrip,
        x = cx - innerSize / 2f,
        y = cy - innerSize / 2f,
        drawW = innerSize,
        drawH = innerSize,
        animTime = if (hovered) state.hoverTimeSec else 0f,
        fps = 24f,
        isPlaying = hovered,
        frameWOverride = fw,
        frameHOverride = fh,
        cropInsetPx = 10,
      )
    }

    // 4. Label underneath (t01 resting / t02 hover)
    batch.setColor(1f, 1f, 1f, alpha * (if (locked) 0.48f else 1f))
    val labelTex =
      if (hovered && !locked) (assets.labelHover ?: assets.labelNormal) else assets.labelNormal
    if (labelTex != null) {
      val lw = labelTex.width.toFloat() * scale
      val lh = labelTex.height.toFloat() * scale
      batch.draw(labelTex, cx - lw / 2f, btn.bounds.y - 10f + slideY, lw, lh)
    } else {
      hudFont.draw(
        batch,
        if (locked) "${btn.label()} (LOCKED)" else btn.label(),
        btn.bounds.x - 12f,
        btn.bounds.y - 2f + slideY,
        btn.bounds.width + 24f,
        Align.center,
        false,
      )
    }
    batch.setColor(1f, 1f, 1f, 1f)
  }

  private fun renderTitleScreen(dt: Float) {
    val titleAbmpPath = "スクリプト\\title\\back.b"
    val backNode = textureLoader.loadAbmpRuntime(titleAbmpPath)

    titleIntroTimerSec += dt
    titlePageTransitionTimerSec = (titlePageTransitionTimerSec + dt).coerceAtMost(0.25f)

    val inIntro = !titleIntroSkipped && titleIntroTimerSec < 16.0f
    val evalTimeMs = if (inIntro) (titleIntroTimerSec * 1000f).coerceIn(0f, 15999f) else 16000f

    // 1. World Viewport: authentic back.b abdata10..13 timeline (Scene01a -> Scene01b -> Scene02 ->
    // Scene03 -> Logo + sakura)
    batch.use {
      if (backNode != null) {
        drawAbmpNode(
          node = backNode,
          timeMs = evalTimeMs,
          skipLayerPredicate = { ev ->
            ev.layerName.equals("White_Under", ignoreCase = true) ||
              ev.layerName.equals("White_Fill", ignoreCase = true)
          },
        )
      }
      updateAndDrawWeatherParticles(dt, forceSakura = true)
    }

    // 2. UI Viewport: bottom white banner, Trial/Version, Web button, and animated Normal<->Extra
    // menu tiles
    uiViewport.apply()
    batch.projectionMatrix = uiCamera.combined
    shapes.projectionMatrix = uiCamera.combined

    batch.use {
      if (backNode != null && evalTimeMs >= 15500f) {
        drawAbmpNode(
          node = backNode,
          timeMs = evalTimeMs,
          skipLayerPredicate = { ev ->
            !ev.layerName.equals("White_Under", ignoreCase = true) &&
              !ev.layerName.equals("White_Fill", ignoreCase = true)
          },
        )
      }

      if (inIntro) {
        hudFont.draw(batch, "Click or Tap to Skip", VIRTUAL_WIDTH - 210f, 36f)
        return@use
      }

      hudFont.color = Color(0.80f, 0.40f, 0.55f, 0.90f)
      hudFont.draw(batch, "Product ver1.0", 16f, VIRTUAL_HEIGHT - 16f)
      hudFont.color = Color.WHITE

      val webHovered = Rectangle(780f, 20f, 150f, 36f).contains(mouseUi.x, mouseUi.y)
      val webTex =
        textureLoader.loadAbmpLayerTexture("スクリプト\\title\\button_web.b", if (webHovered) 1 else 0)
      if (webTex != null) {
        batch.draw(webTex, 780f, 20f, webTex.width.toFloat(), webTex.height.toFloat())
      }

      val menuAlpha =
        if (titleIntroSkipped) 1f else ((titleIntroTimerSec - 16.0f) / 0.25f).coerceIn(0f, 1f)
      val pageProg = (titlePageTransitionTimerSec / 0.25f).coerceIn(0f, 1f)
      if (pageProg < 1f && titlePrevPage != titlePage) {
        val outgoing = if (titlePrevPage == 1) titleExtraButtons else titleNormalButtons
        for (btn in outgoing) {
          drawTitleButtonAnimated(btn, dt, menuAlpha, pageProg, isOutgoing = true)
        }
      }
      for (btn in activeTitleButtons()) {
        drawTitleButtonAnimated(btn, dt, menuAlpha, pageProg, isOutgoing = false)
      }
      batch.color = Color.WHITE
    }
  }

  /**
   * Draws one scene layer — standing sprites plus the cut-in overlay — at the given opacity, so the
   * outgoing and incoming scene can crossfade together with the background wipe instead of the live
   * sprites sitting at full opacity on top of it. Must be called inside an active `batch.use`.
   */
  private fun drawSpriteLayer(
    sprites: List<RenderedSprite>,
    overlay: Texture?,
    alpha: Float,
    shakeX: Float,
    shakeY: Float,
  ) {
    if (alpha <= 0f || (sprites.isEmpty() && overlay == null)) return
    if (walkure.EngineTrace.enabled && alpha < 1f) {
      walkure.EngineTrace.log(
        "RENDER spriteLayer alpha=${alpha.toString().take(4)} " +
          "chars=[${sprites.joinToString(",") { it.charKey }}]",
      )
    }
    batch.setColor(1f, 1f, 1f, alpha)
    for (sprite in sprites) {
      if (sprite.centerX > VIRTUAL_WIDTH) continue
      val tex = sprite.texture
      val isActing = actionTimerSec > 0f && sprite.charKey == activeActionCharKey
      val actOffsetX =
        if (isActing && activeActionName?.contains("揺") == true) {
          sin(actionTimerSec * 45f) * 10f
        } else 0f
      val actOffsetY =
        if (isActing && activeActionName?.contains("揺") != true) {
          kotlin.math.abs(sin(actionTimerSec * 14f)) * 18f
        } else 0f
      val drawX = sprite.centerX - tex.width / 2f + shakeX + actOffsetX
      batch.draw(tex, drawX, shakeY + actOffsetY)
    }

    // Cut-in or foreground overlay (centered at native size if sub-screen cut-in, or full-screen
    // if >= 1024x768)
    if (overlay != null) {
      if (overlay.width < 1000 || overlay.height < 720) {
        val ox = (VIRTUAL_WIDTH - overlay.width) / 2f + shakeX
        val oy = (VIRTUAL_HEIGHT - overlay.height) / 2f + 36f + shakeY
        batch.draw(overlay, ox, oy)
      } else {
        batch.draw(overlay, shakeX, shakeY, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
      }
    }
    batch.setColor(1f, 1f, 1f, 1f)
  }

  private fun renderGameplayScreen(dt: Float) {
    if (bgFadeAlpha < 1f) {
      bgFadeAlpha = (bgFadeAlpha + dt * bgFadeSpeed).coerceAtMost(1f)
    }
    if (dayEyeCatchActive) {
      dayEyeCatchTimer = (dayEyeCatchTimer - dt).coerceAtLeast(0f)
      if (dayEyeCatchTimer <= 0f) {
        dayEyeCatchActive = false
      }
    }
    if (eyeCatchTimer > 0f) {
      eyeCatchTimer = (eyeCatchTimer - dt).coerceAtLeast(0f)
    }
    if (actionTimerSec > 0f) {
      actionTimerSec = (actionTimerSec - dt).coerceAtLeast(0f)
    }
    if (screenShakeTimerSec > 0f) {
      screenShakeTimerSec = (screenShakeTimerSec - dt).coerceAtLeast(0f)
    }
    if (flashTimerSec > 0f) {
      flashTimerSec = (flashTimerSec - dt).coerceAtLeast(0f)
    }

    val shakeX = if (screenShakeTimerSec > 0f) sin(screenShakeTimerSec * 65f) * 8f else 0f
    val shakeY = if (screenShakeTimerSec > 0f) cos(screenShakeTimerSec * 50f) * 6f else 0f

    // Capture the outgoing scene (previous bg + sprites + overlay) into prevSceneFbo so the ABMP
    // eyecatch can substitute it for the Dammy1024 "Capture" placeholder layer instead of skipping
    // it. Runs once on the first frame after a fade arms.
    if (pendingSceneCapture) {
      pendingSceneCapture = false
      prevSceneFbo?.let { fbo ->
        fbo.begin()
        batch.use {
          val prevBg = prevBackgroundTex
          if (prevBg != null) {
            batch.setColor(1f, 1f, 1f, 1f)
            batch.draw(prevBg, 0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
          } else {
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
          }
          drawSpriteLayer(prevSpriteTextures, prevOverlayTex, 1f, 0f, 0f)
        }
        fbo.end()
      }
    }

    if (!showBacklog && vm.pendingChoices().isEmpty() && !vm.isFinished()) {
      if (isSkipMode) {
        val canSkip =
          saveManager.skipAllUnread ||
            saveManager.isLineRead(vm.currentScript.path, vm.currentLineNumber)
        if (canSkip) {
          skipTimerSec += dt
          if (skipTimerSec >= 0.05f) {
            skipTimerSec = 0f
            advanceStep()
          }
        } else {
          isSkipMode = false
        }
      } else if (isAutoMode && !audioManager.isVoicePlaying()) {
        autoTimerSec += dt
        if (autoTimerSec >= saveManager.autoSpeedSec) {
          autoTimerSec = 0f
          advanceStep()
        }
      }
    }

    if (currentBackgroundTex == null && solidBgColor != null) {
      shapes.begin(ShapeRenderer.ShapeType.Filled)
      shapes.color = solidBgColor
      shapes.rect(0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
      shapes.end()
    }

    val bg = currentBackgroundTex ?: solidBlackTex
    val prevBg = prevBackgroundTex ?: bg
    val mask = ruleMaskTex
    val ruleShader = transitionShader
    val mShader = mosaicShader
    val transAbmp = activeTransitionAbmp

    transitionProgressSec += dt
    val progress = (transitionProgressSec / transitionDurationSec).coerceIn(0f, 1f)
    if (walkure.EngineTrace.enabled && isTransitionActive) {
      traceTransitionFrames++
      if (progress >= 1f) {
        walkure.EngineTrace.log(
          "RENDER transition END dur=${transitionDurationSec}s " +
            "actualActiveMs=${walkure.EngineTrace.msSince(traceTransitionStartNanos)} " +
            "frames=$traceTransitionFrames",
        )
        traceTransitionFrames = 0
      }
    }
    if (progress >= 1f) {
      isTransitionActive = false
      pendingSceneCapture = false
    }

    if (
      isTransitionActive &&
        activeTransitionMode == 4 &&
        bg != null &&
        prevBg != null &&
        mShader != null
    ) {
      batch.shader = mShader
      batch.use {
        prevBg.bind(1)
        bg.bind(0)
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)
        mShader.setUniformi("u_texture", 0)
        mShader.setUniformi("u_prevTexture", 1)
        mShader.setUniformf("u_progress", progress)
        mShader.setUniformf("u_resolution", VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
        batch.draw(bg, shakeX, shakeY, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
      }
      batch.shader = null
    } else if (
      isTransitionActive && mask != null && prevBg != null && bg != null && ruleShader != null
    ) {
      batch.shader = ruleShader
      batch.use {
        mask.bind(2)
        prevBg.bind(1)
        bg.bind(0)
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0)

        ruleShader.setUniformi("u_texture", 0)
        ruleShader.setUniformi("u_prevTexture", 1)
        ruleShader.setUniformi("u_maskTexture", 2)
        ruleShader.setUniformf("u_progress", progress)
        ruleShader.setUniformf("u_feather", activeTransitionFeather)

        batch.draw(bg, shakeX, shakeY, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
      }
      batch.shader = null
    } else if (isTransitionActive && activeTransitionMode == 2 && bg != null && prevBg != null) {
      // Color fade through black/white midpoint at progress = 0.5
      val midColorTex =
        if (solidBgColor != null && solidBgColor!!.r > 0.5f) solidWhiteTex else solidBlackTex
      batch.use {
        if (progress < 0.5f) {
          val outAlpha = (1f - progress * 2f).coerceIn(0f, 1f)
          if (midColorTex != null) {
            batch.setColor(1f, 1f, 1f, 1f)
            batch.draw(midColorTex, shakeX, shakeY, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
          }
          batch.setColor(1f, 1f, 1f, outAlpha)
          batch.draw(prevBg, shakeX, shakeY, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
        } else {
          val inAlpha = ((progress - 0.5f) * 2f).coerceIn(0f, 1f)
          if (midColorTex != null) {
            batch.setColor(1f, 1f, 1f, 1f)
            batch.draw(midColorTex, shakeX, shakeY, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
          }
          batch.setColor(1f, 1f, 1f, inAlpha)
          batch.draw(bg, shakeX, shakeY, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
        }
        batch.setColor(1f, 1f, 1f, 1f)
      }
    } else {
      batch.use {
        if (bg != null) {
          if (prevBg != null && bgFadeAlpha < 1f) {
            batch.setColor(1f, 1f, 1f, 1f)
            batch.draw(prevBg, shakeX, shakeY, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
          }
          batch.setColor(1f, 1f, 1f, bgFadeAlpha)
          batch.draw(bg, shakeX, shakeY, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
          batch.setColor(1f, 1f, 1f, 1f)
        }
      }
    }

    if (isTransitionActive && transAbmp != null) {
      batch.use {
        val durMs = (transAbmp.document?.durationMs ?: 1000).toFloat()
        // `Dammy1024` / `Dummy*` layers are the engine's stand-ins for the captured game screen
        // (`avgsystem_fade.s` binds `$$$GameThreadImageZ,1001.bmp` and `999.bmp` into them). Their
        // embedded bitmap is a grey test pattern, so painting it would cover the scene.
        drawAbmpNode(
          node = transAbmp,
          timeMs = (progress * durMs).coerceIn(0f, durMs),
          // `wfade01a/b/c` lead with `lscroll`: an 800x600 8-bit greyscale ramp, i.e. a rule-wipe
          // mask of the same family as `フェード\wind.bmp`, not artwork. Painting it is what put a
          // black-and-white gradient over half the screen.
          skipLayerPredicate = { ev ->
            val slot = transAbmp.slots.getOrNull(ev.imageIndex)
            slot?.layer?.name?.endsWith("scroll", ignoreCase = true) == true
          },
          // Substitute the captured outgoing scene for the Dammy1024 "Capture" placeholder layer
          // instead of skipping it, so the eyecatch plays over the real previous screen.
          // FBO textures are bottom-up (LibGDX) so they need vertical flip to match QLiE's
          // top-down layer coordinates.
          textureOverride = { ev ->
            val fbo = prevSceneFbo
            if (isScreenPlaceholderImage(transAbmp, ev.imageIndex)) fbo?.colorBufferTexture
            else null
          },
          textureFlipY = true,
          linkedTexture = activeTransitionLinkedTex,
        )
      }
    }

    // A full-screen eyecatch/motion banner replaces the scene for the whole fade. Painting the live
    // sprites over it is what showed two characters at the same time.
    val bannerOwnsScreen = isTransitionActive && transAbmp != null
    batch.use {
      if (!bannerOwnsScreen) {
        val outgoingAlpha = if (isTransitionActive) (1f - progress).coerceIn(0f, 1f) else 0f
        val incomingAlpha = if (isTransitionActive) progress.coerceIn(0f, 1f) else 1f
        drawSpriteLayer(prevSpriteTextures, prevOverlayTex, outgoingAlpha, shakeX, shakeY)
        drawSpriteLayer(activeSpriteTextures, overlayBackgroundTex, incomingAlpha, shakeX, shakeY)
      }

      // Drifting sakura petals or rain streaks
      updateAndDrawWeatherParticles(dt, forceSakura = false)
    }

    if (flashTimerSec > 0f) {
      Gdx.gl.glEnable(GL20.GL_BLEND)
      Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
      shapes.begin(ShapeRenderer.ShapeType.Filled)
      shapes.setColor(1f, 1f, 1f, (flashTimerSec / 0.32f).coerceIn(0f, 0.85f))
      shapes.rect(0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
      shapes.end()
      Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    // --- SCREEN UI ELEMENTS (Anchored to screen via uiCamera) ---
    uiViewport.apply()
    batch.projectionMatrix = uiCamera.combined
    shapes.projectionMatrix = uiCamera.combined

    // Top-right Debug Bar (Prev Scene / Next Scene / Title + status line)
    if (showDebugHud) {
      renderDebugSceneBar()
    }

    if (hideMessageWindow && !showBacklog) {
      // The calendar cut-in and wfade banner are part of the scene, not the message window, and
      // the day eyecatch is triggered through `abg` which hides the window. Draw them here and
      // return, otherwise they never appear.
      batch.use { renderEyeCatchOverlays() }
      return
    }

    if (showBacklog) {
      Gdx.gl.glEnable(GL20.GL_BLEND)
      Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
      shapes.begin(ShapeRenderer.ShapeType.Filled)
      shapes.setColor(0.04f, 0.05f, 0.10f, 0.94f)
      shapes.rect(40f, 24f, VIRTUAL_WIDTH - 80f, VIRTUAL_HEIGHT - 76f)
      shapes.end()
      shapes.begin(ShapeRenderer.ShapeType.Line)
      shapes.setColor(0.82f, 0.68f, 0.42f, 1f)
      shapes.rect(40f, 24f, VIRTUAL_WIDTH - 80f, VIRTUAL_HEIGHT - 76f)
      shapes.end()
      Gdx.gl.glDisable(GL20.GL_BLEND)

      batch.use {
        renderBacklogText()
      }
      return
    }

    // Authentic QLiE Message Window & System HUD from ライブラリ\avgシステム\window\layout_window01.s
    val msgWinAbmp = "ライブラリ\\avgシステム\\window\\messagewindow.b"
    val mesAlphaTex =
      textureLoader.loadAbmpLayerByName(msgWinAbmp, "*MesAlpha")
        ?: textureLoader.loadAbmpLayerTexture(msgWinAbmp, 0)
    val buttonBackTex =
      textureLoader.loadAbmpLayerByName(msgWinAbmp, "ButtonBack")
        ?: textureLoader.loadAbmpLayerTexture(msgWinAbmp, 1)
    val menuBackWheelTex =
      textureLoader.loadAbmpLayerByName(msgWinAbmp, "MenuBack_Back")
        ?: textureLoader.loadAbmpLayerTexture(msgWinAbmp, 2)
    val menuBackBaseTex =
      textureLoader.loadAbmpLayerByName(msgWinAbmp, "MenuBack_Base")
        ?: textureLoader.loadAbmpLayerTexture(msgWinAbmp, 3)
    val faceFrameTex =
      textureLoader.loadAbmpLayerByName("ライブラリ\\avgシステム\\window\\faceimage.b", "FaceWindow")
        ?: textureLoader.loadAbmpLayerTexture("ライブラリ\\avgシステム\\window\\faceimage.b", 1)
    val cursorStripTex =
      textureLoader.loadAbmpLayerTexture("ライブラリ\\avgシステム\\window\\cursorimage.b", 0)

    batch.use {
      // 1. Main MessageWindow frame (1024x193 at y=8) + bottom ButtonBack bar (882x35 at y=0) +
      // right radial wheel
      if (mesAlphaTex != null) {
        batch.draw(mesAlphaTex, 0f, 8f, VIRTUAL_WIDTH, 193f)
      }
      if (buttonBackTex != null) {
        batch.draw(buttonBackTex, (VIRTUAL_WIDTH - 882f) / 2f, 0f, 882f, 35f)
      }
      if (menuBackWheelTex != null) {
        batch.draw(menuBackWheelTex, 906f, 44f, 114f, 114f)
      }
      if (menuBackBaseTex != null) {
        batch.draw(menuBackBaseTex, 965f, 38f, 59f, 125f)
      }

      // 2. Left FaceWindow frame + Speaker Face Portrait (フェイス\*.argb)
      val faceTex = currentFaceTex
      if (faceTex != null) {
        if (faceFrameTex != null) {
          batch.draw(faceFrameTex, 18f, 10f, 202f, 186f)
        }
        val fx = -42f + currentFaceOffsetX * 0.45f
        batch.draw(faceTex, fx, 0f, 312f, 254f)
      }

      // 3. Speaker Name & Dialogue Text (layout_window01.s: MessageArea \p,237,611)
      val textLeftX = if (faceTex != null) 232f else 148f
      val textWidth = 895f - textLeftX
      val speaker = vm.currentSpeaker
      if (!speaker.isNullOrBlank()) {
        speakerFont.draw(batch, speaker, textLeftX, 186f)
      }

      dialogueFont.draw(
        batch,
        vm.currentText,
        textLeftX,
        148f,
        textWidth,
        Align.left,
        true,
      )

      // 4. Animated Click-Wait Cursor (CursorImage.b, 22 frames of 141x141)
      if (cursorStripTex != null && vm.pendingChoices().isEmpty()) {
        drawVerticalStripFrame(cursorStripTex, 855f, 22f, 78f, 78f, uiAnimTimeSec, 22f)
      }

      // 5. Animated Bottom System Buttons (WindowButton_Save.b, Load.b, Config.b, QSave.b, QLoad.b)
      for (btn in windowSystemButtons) {
        val hovered = btn.bounds.contains(mouseUi.x, mouseUi.y)
        val path = btn.abmpPath ?: continue
        val iconStrip = textureLoader.loadAbmpLayerTexture(path, 0)
        val normalText = textureLoader.loadAbmpLayerTexture(path, 1)
        val hoverGlow = textureLoader.loadAbmpLayerTexture(path, 2)
        val hoverText = textureLoader.loadAbmpLayerTexture(path, 3)

        if (hovered && hoverGlow != null) {
          batch.draw(
            hoverGlow,
            btn.bounds.x - 14f,
            btn.bounds.y - 10f,
            btn.bounds.width + 28f,
            btn.bounds.height + 20f,
          )
        }
        if (iconStrip != null) {
          // Animate strictly on mouse hover, clipped to button frame
          drawVerticalStripFrame(
            tex = iconStrip,
            x = btn.bounds.x + (btn.bounds.width - 32f) / 2f,
            y = btn.bounds.y + 14f,
            drawW = 32f,
            drawH = 32f,
            animTime = uiAnimTimeSec,
            fps = 24f,
            isPlaying = hovered,
          )
        }
        val labelLayer = if (hovered) (hoverText ?: normalText) else normalText
        if (labelLayer != null) {
          val lw = labelLayer.width.toFloat()
          val lh = labelLayer.height.toFloat()
          val lx = btn.bounds.x + (btn.bounds.width - lw) / 2f
          batch.draw(labelLayer, lx, btn.bounds.y + 2f, lw, lh)
        }
      }

      // 6. Right Radial Cluster Buttons (Close, Skip, Auto, BackLog, Voice)
      for (btn in windowRadialButtons) {
        val hovered = btn.bounds.contains(mouseUi.x, mouseUi.y)
        val active = btn.isActiveToggle()
        val path = btn.abmpPath ?: continue
        val layerIdx =
          when {
            active -> 2
            hovered -> 1
            else -> 0
          }
        val tex =
          textureLoader.loadAbmpLayerTexture(path, layerIdx)
            ?: textureLoader.loadAbmpLayerTexture(path, 0)
        if (tex != null) {
          batch.draw(tex, btn.bounds.x, btn.bounds.y)
        }
      }

      // 7. Authentic Choice Buttons (ライブラリ\avgシステム\select\selectback.b & selectbutton01.b)
      val choices = vm.pendingChoices()
      if (choices.isNotEmpty()) {
        val selectBackTex =
          textureLoader.loadAbmpLayerTexture("ライブラリ\\avgシステム\\select\\selectback.b", 0)
        val boxW = 660f
        val boxH = 42f
        val startX = (VIRTUAL_WIDTH - boxW) / 2f
        val startY = 480f
        choices.forEachIndexed { i, choice ->
          val by = startY - i * (boxH + 18f)
          val hovered = mouseUi.x in startX..(startX + boxW) && mouseUi.y in by..(by + boxH)
          if (selectBackTex != null) {
            batch.draw(selectBackTex, 0f, by - 4f, VIRTUAL_WIDTH, boxH + 8f)
          }
          val btnTex =
            textureLoader.loadAbmpLayerTexture(
              "ライブラリ\\avgシステム\\select\\selectbutton01.b",
              if (hovered) 1 else 0,
            )
          if (btnTex != null) {
            batch.draw(btnTex, startX, by, boxW, boxH)
          }
          dialogueFont.draw(
            batch,
            choice.text,
            startX + 20f,
            by + boxH - 11f,
            boxW - 40f,
            Align.center,
            false,
          )
        }
      }

      // 8. Day transition & calendar eyecatch
      // 9. Character eyecatch banner (wfade)
      renderEyeCatchOverlays()
    }
  }

  /**
   * Draws the calendar cut-in and the wfade character banner.
   *
   * Both must render even while the message window is hidden: `@@!EyeCatchDay` invokes
   * `AVG_BgCommand,abg,...`, and `Avg_mbgExcute` forces `Window=2`, which sets `hideMessageWindow`.
   * Drawing them after the message-window block meant the calendar was never visible.
   */
  private fun renderEyeCatchOverlays() {
    if (dayEyeCatchActive && dayEyeCatchTimer > 0f) {
      val elapsed = dayEyeCatchTotalSec - dayEyeCatchTimer
      val abmp = dayEyeCatchAbmp
      if (abmp?.document != null) {
        // Play the authentic 7.5 s timeline. The container is authored with April/Tuesday
        // placeholders; `@@!EyeCatchDay` passes the real art to `abg` as image files, so the
        // slot mapping below is what the engine binds at runtime:
        //   img0 = 日付背景m_N (mini card bg)   img1 = 日付背景b_N (blurred bg)
        //   img2 = month   img3 = day tens   img4 = day ones   img5 = weekday
        drawAbmpNode(
          node = abmp,
          timeMs = (elapsed * 1000f).coerceIn(0f, abmp.document.durationMs.toFloat()),
          textureOverride = { ev ->
            when (ev.imageIndex) {
              0 -> dayMiniBgTex
              1 -> dayBlurBgTex
              2 -> dayMonthTex
              3 -> dayDigit1Tex
              4 -> dayDigit2Tex
              5 -> dayWeekdayTex
              else -> null
            }
          },
        )
      }
    }

    if (eyeCatchTimer > 0f) {
      val alpha = eyeCatchTimer.coerceAtMost(1f)
      val banner = eyeCatchBannerTex
      if (banner != null) {
        batch.setColor(1f, 1f, 1f, alpha)
        val bx = (VIRTUAL_WIDTH - banner.width) / 2f
        val by = (VIRTUAL_HEIGHT - banner.height) / 2f
        batch.draw(banner, bx, by)
        batch.setColor(1f, 1f, 1f, 1f)
      }
    }
  }

  private fun renderDebugSceneBar() {
    Gdx.gl.glEnable(GL20.GL_BLEND)
    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    shapes.begin(ShapeRenderer.ShapeType.Filled)
    shapes.setColor(0.04f, 0.06f, 0.12f, 0.68f)
    shapes.rect(0f, VIRTUAL_HEIGHT - 36f, VIRTUAL_WIDTH, 36f)
    for (btn in debugHudButtons) {
      val hovered = btn.bounds.contains(mouseUi.x, mouseUi.y)
      if (hovered) shapes.setColor(0.24f, 0.32f, 0.52f, 0.92f)
      else shapes.setColor(0.12f, 0.16f, 0.28f, 0.85f)
      shapes.rect(btn.bounds.x, btn.bounds.y, btn.bounds.width, btn.bounds.height)
    }
    shapes.end()
    Gdx.gl.glDisable(GL20.GL_BLEND)

    batch.use {
      hudFont.draw(batch, statusMessage, 12f, VIRTUAL_HEIGHT - 12f)
      for (btn in debugHudButtons) {
        hudFont.draw(
          batch,
          btn.label(),
          btn.bounds.x + 2f,
          btn.bounds.y + btn.bounds.height - 6f,
          btn.bounds.width - 4f,
          Align.center,
          false,
        )
      }
    }
  }

  private fun switchSavePage(newPage: Int) {
    val clamped = newPage.coerceIn(0, 3)
    if (clamped == currentSavePage) return
    currentSavePage = clamped
    saveScrollOffset = 0f
    savePageExitTimerSec = 0f
    savePageEnterTimerSec = 0f
    clockNeedleTargetDeg = 36f + clamped * 72f
    refreshSlotThumbnails()
    audioManager.playSe("misc\\Click01.wav")
  }

  private fun saveSlotCardBounds(visIdx: Int, pageSlideX: Float): Rectangle {
    // Curved Save/Load slot arrangement (SetDataPosition): bows around right-side clock dial
    val startSlot = saveScrollOffset.toInt().coerceIn(0, 18)
    val frac = (saveScrollOffset - startSlot).coerceIn(0f, 1f)
    val rel = visIdx.toFloat() - frac
    val centerNorm = ((rel / 5.0f) - 0.5f) * 2.0f
    val cardW = 666f
    val cardH = 88f
    val cardRight = 786f + 72f * (centerNorm * centerNorm)
    val sx = (cardRight - cardW + pageSlideX).coerceAtLeast(-820f)
    val sy = 592f - rel * 86f
    return Rectangle(sx, sy, cardW, cardH)
  }

  private fun renderSaveLoadScreen(dt: Float) {
    uiViewport.apply()
    batch.projectionMatrix = uiCamera.combined
    shapes.projectionMatrix = uiCamera.combined

    savePageExitTimerSec = (savePageExitTimerSec + dt).coerceAtMost(0.25f)
    savePageEnterTimerSec = (savePageEnterTimerSec + dt).coerceAtMost(0.25f)
    // Damped spring clock needle matching QLiE `_cp1 = (_cp1*3 + _cp2) / 4`
    val damp = 1f - kotlin.math.exp(-15f * dt)
    clockNeedleAngleDeg += (clockNeedleTargetDeg - clockNeedleAngleDeg) * damp

    val enterT = (savePageEnterTimerSec / 0.25f).coerceIn(0f, 1f)
    val easeOut = 1f - (1f - enterT) * (1f - enterT) * (1f - enterT)
    val pageSlideX = if (savePageEnterTimerSec < 0.25f) (1f - easeOut) * 1000f else 0f

    val folder = if (isSaveScreenMode) "savepage" else "loadpage"
    val pageBg = textureLoader.loadTexture("スクリプト\\albummode\\back.jpg")
    val clockTex = textureLoader.loadAbmpLayerTexture("スクリプト\\saveload\\$folder\\clock.b", 0)
    val clockCursorTex = textureLoader.loadAbmpLayerTexture("スクリプト\\saveload\\$folder\\clock.b", 1)
    val underFrame = textureLoader.loadAbmpLayerTexture("スクリプト\\saveload\\$folder\\underframe.b", 0)
    val pageSlots = saveManager.listPageSlots(currentSavePage, slotsPerPage = 25)
    val startSlot = saveScrollOffset.toInt().coerceIn(0, 18)

    batch.use {
      if (pageBg != null) batch.draw(pageBg, 0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)

      // Right-side Clock dial (`\p,1024,0,210`) with damped rotating needle
      if (clockTex != null) {
        val cw = clockTex.width.toFloat().coerceAtMost(240f)
        batch.draw(clockTex, VIRTUAL_WIDTH - cw, 0f, cw, VIRTUAL_HEIGHT)
      }
      if (clockCursorTex != null) {
        val nw = clockCursorTex.width.toFloat()
        val nh = clockCursorTex.height.toFloat()
        val pivotX = nw / 2f
        val pivotY = nh / 2f
        batch.draw(
          clockCursorTex,
          VIRTUAL_WIDTH - 104f - pivotX,
          VIRTUAL_HEIGHT / 2f - pivotY,
          pivotX,
          pivotY,
          nw,
          nh,
          1f,
          1f,
          -clockNeedleAngleDeg,
          0,
          0,
          clockCursorTex.width,
          clockCursorTex.height,
          false,
          false,
        )
      }

      if (underFrame != null) batch.draw(underFrame, 0f, 0f, VIRTUAL_WIDTH, 80f)

      val title =
        if (isSaveScreenMode) {
          "SAVE DATA — Page ${currentSavePage + 1} (Slots ${startSlot + 1}..${(startSlot + 6).coerceAtMost(25)})"
        } else {
          "LOAD DATA — Page ${currentSavePage + 1} (Slots ${startSlot + 1}..${(startSlot + 6).coerceAtMost(25)})"
        }
      speakerFont.draw(batch, title, 140f, VIRTUAL_HEIGHT - 26f)

      // 6 visible curved slots from 25-slot page with scroll & ease-out entrance slide
      for (visIdx in 0 until 6) {
        val idx = startSlot + visIdx
        if (idx >= 25) break
        val slotNum = currentSavePage * 25 + idx + 1
        val meta = pageSlots.getOrNull(idx)
        val rect = saveSlotCardBounds(visIdx, pageSlideX)
        val sx = rect.x
        val sy = rect.y
        val cardHovered = rect.contains(mouseUi.x, mouseUi.y)

        val cardTex =
          textureLoader.loadAbmpLayerTexture(
            "スクリプト\\saveload\\$folder\\button_data.b",
            if (cardHovered) 1 else 0,
          )
        batch.setColor(1f, 1f, 1f, enterT.coerceAtLeast(0.2f))
        if (cardTex != null) {
          if (cardHovered) {
            batch.draw(cardTex, sx - 8f, sy - 3f, rect.width + 16f, rect.height + 6f)
          } else {
            batch.draw(cardTex, sx, sy, rect.width, rect.height)
          }
        }

        // Left Thumbnail Area (103x74)
        val thumb = slotThumbnailTextures[slotNum]
        if (thumb != null) {
          batch.draw(thumb, sx + 14f, sy + 7f, 103f, 74f)
        } else {
          val noDataTex =
            textureLoader.loadAbmpLayerTexture("スクリプト\\saveload\\$folder\\button_data.b", 8)
              ?: textureLoader.loadTexture("スクリプト\\saveload\\nodata.png")
          if (noDataTex != null) {
            batch.draw(noDataTex, sx + 14f, sy + 7f, 103f, 74f)
          }
        }

        if (meta != null) {
          val routeBadge =
            textureLoader.loadTexture("スクリプト\\saveload\\$folder\\no_${meta.routeName}.png")
              ?: textureLoader.loadTexture("スクリプト\\saveload\\$folder\\no_通常.png")
          if (routeBadge != null) {
            batch.draw(routeBadge, sx + 128f, sy + 8f, 70f, 70f)
          }
          speakerFont.draw(batch, String.format("#%03d", slotNum), sx + 210f, sy + 74f)
          hudFont.draw(batch, "[${meta.inGameDate}]  ${meta.realTimestamp}", sx + 278f, sy + 70f)
          dialogueFont.draw(
            batch,
            meta.dialoguePreview,
            sx + 210f,
            sy + 44f,
            440f,
            Align.left,
            false,
          )
        } else {
          val noRouteBadge = textureLoader.loadTexture("スクリプト\\saveload\\$folder\\no_none.png")
          if (noRouteBadge != null) {
            batch.draw(noRouteBadge, sx + 128f, sy + 8f, 70f, 70f)
          }
          speakerFont.draw(batch, String.format("#%03d", slotNum), sx + 210f, sy + 74f)
          hudFont.draw(batch, "--/--  ----/--/-- --:--", sx + 278f, sy + 70f)
          hudFont.draw(batch, "－－－ (No Save Data)", sx + 210f, sy + 44f)
        }
      }
      batch.setColor(1f, 1f, 1f, 1f)

      // Touch-friendly Up/Down scroll controls on far-left
      hudFont.draw(batch, "▲ Up", 20f, 580f, 64f, Align.center, false)
      hudFont.draw(batch, "${startSlot + 1}/25", 20f, 400f, 64f, Align.center, false)
      hudFont.draw(batch, "▼ Dn", 20f, 220f, 64f, Align.center, false)

      // Authentic Page Buttons (Page01_01.png..Page04_03.png)
      for (p in 0 until 4) {
        val px = 140f + p * 84f
        val py = 20f
        val pHover = Rectangle(px, py, 74f, 36f).contains(mouseUi.x, mouseUi.y)
        val pActive = currentSavePage == p
        val pagePng =
          String.format(
            "スクリプト\\saveload\\page%02d_%02d.png",
            p + 1,
            if (pActive) 3 else if (pHover) 2 else 1,
          )
        val pageTex = textureLoader.loadTexture(pagePng)
        if (pageTex != null) {
          batch.draw(pageTex, px, py, 74f, 32f)
        } else {
          hudFont.draw(batch, "Page ${p + 1}", px, py + 22f, 74f, Align.center, false)
        }
      }

      // Title and Back Buttons
      val titleHover =
        Rectangle(VIRTUAL_WIDTH - 260f, 16f, 100f, 40f).contains(mouseUi.x, mouseUi.y)
      val backHover = Rectangle(VIRTUAL_WIDTH - 150f, 16f, 100f, 40f).contains(mouseUi.x, mouseUi.y)
      renderDialogChoiceButton(
        "スクリプト\\config\\button_title.b",
        VIRTUAL_WIDTH - 260f,
        16f,
        titleHover,
        "Title",
      )
      renderDialogChoiceButton(
        "スクリプト\\config\\button_cancel.b",
        VIRTUAL_WIDTH - 150f,
        16f,
        backHover,
        "Back",
      )
    }
  }

  private fun handleSaveLoadTouch(ui: Vector2, button: Int): Boolean {
    if (Rectangle(16f, 550f, 72f, 48f).contains(ui.x, ui.y)) {
      saveScrollOffset = (saveScrollOffset - 4f).coerceIn(0f, 18f)
      return true
    }
    if (Rectangle(16f, 190f, 72f, 48f).contains(ui.x, ui.y)) {
      saveScrollOffset = (saveScrollOffset + 4f).coerceIn(0f, 18f)
      return true
    }
    val startSlot = saveScrollOffset.toInt().coerceIn(0, 18)
    for (visIdx in 0 until 6) {
      val idx = startSlot + visIdx
      if (idx >= 25) break
      if (saveSlotCardBounds(visIdx, 0f).contains(ui.x, ui.y)) {
        val slotNum = currentSavePage * 25 + idx + 1
        val existing = saveManager.loadSlot(slotNum)
        if (button == Input.Buttons.RIGHT) {
          if (existing != null) {
            showConfirmModal(
              sysMessages.deleteText,
              "スクリプト\\dialog\\english\\デ-タを消去します。よろしいですか？.png",
            ) {
              saveManager.deleteSlot(slotNum)
              refreshSlotThumbnails()
              statusMessage = "Deleted Slot #$slotNum"
            }
          } else {
            triggerUiOverlay("スクリプト\\saveload\\fadeout01.b")
            mode = previousMode
          }
          return true
        }
        if (isSaveScreenMode) {
          val performSave = {
            val thumbBytes = captureThumbnailPngBytes()
            saveManager.saveSlot(
              slotNumber = slotNum,
              snap = vm.snapshot(),
              inGameDate = vm.inGameDate,
              routeName = vm.routeName,
              routeFlags = campaign.routeFlags,
              thumbnailPngBytes = thumbBytes,
            )
            refreshSlotThumbnails()
            statusMessage = "Saved to Slot #$slotNum"
          }
          if (existing != null) {
            showConfirmModal(
              sysMessages.overSaveText,
              "スクリプト\\dialog\\english\\上書きします。よろしいですか？.png",
              performSave,
            )
          } else {
            performSave()
          }
        } else {
          val loaded = existing ?: return true
          restoreFromSaveSlot(loaded)
        }
        return true
      }
    }
    if (button == Input.Buttons.RIGHT) {
      triggerUiOverlay("スクリプト\\saveload\\fadeout01.b")
      mode = previousMode
      return true
    }
    for (p in 0 until 4) {
      val px = 140f + p * 84f
      val py = 20f
      if (Rectangle(px, py, 74f, 36f).contains(ui.x, ui.y)) {
        switchSavePage(p)
        return true
      }
    }
    if (Rectangle(VIRTUAL_WIDTH - 260f, 16f, 100f, 40f).contains(ui.x, ui.y)) {
      triggerUiOverlay("スクリプト\\saveload\\fadeout01.b")
      mode = EngineMode.TITLE
      return true
    }
    if (Rectangle(VIRTUAL_WIDTH - 150f, 16f, 100f, 40f).contains(ui.x, ui.y)) {
      triggerUiOverlay("スクリプト\\saveload\\fadeout01.b")
      mode = previousMode
      return true
    }
    return false
  }

  private fun switchConfigTab(newTab: Int) {
    if (newTab == configTab) return
    configPrevTab = configTab
    configTab = newTab
    configTabExitTimerSec = 0f
    configTabEnterTimerSec = 0f
    audioManager.playSe("misc\\Click01.wav")
  }

  private fun drawConfigTabContents(
    tab: Int,
    offsetX: Float,
    alpha: Float,
    sliderThumbTex: Texture?,
    onOffTex0: Texture?,
    onOffTex2: Texture?,
  ) {
    if (alpha <= 0.01f) return
    when (tab) {
      0 -> {
        val sliders =
          listOf(
            saveManager.bgmVolume,
            saveManager.voiceVolume,
            saveManager.seVolume,
            ((4.0f - saveManager.autoSpeedSec) / 3.5f).coerceIn(0f, 1f),
          )
        val labels =
          listOf(
            "BGM Volume (${(saveManager.bgmVolume * 100).toInt()}%)",
            "Voice Volume (${(saveManager.voiceVolume * 100).toInt()}%)",
            "SFX Volume (${(saveManager.seVolume * 100).toInt()}%)",
            String.format("Auto Speed (%.1fs delay)", saveManager.autoSpeedSec),
          )

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        sliders.forEachIndexed { i, ratio ->
          val bx = 340f + offsetX
          val by = VIRTUAL_HEIGHT - 170f - i * 54f
          shapes.setColor(0.14f, 0.18f, 0.28f, alpha)
          shapes.rect(bx, by, 320f, 14f)
          shapes.setColor(0.84f, 0.70f, 0.42f, alpha)
          shapes.rect(bx, by, 320f * ratio, 14f)
        }
        shapes.setColor(0.20f, 0.28f, 0.46f, 0.95f * alpha)
        shapes.rect(200f + offsetX, VIRTUAL_HEIGHT - 400f, 240f, 32f)
        shapes.rect(456f + offsetX, VIRTUAL_HEIGHT - 400f, 240f, 32f)
        shapes.rect(712f + offsetX, VIRTUAL_HEIGHT - 400f, 240f, 32f)
        shapes.rect(200f + offsetX, VIRTUAL_HEIGHT - 446f, 360f, 32f)
        shapes.rect(580f + offsetX, VIRTUAL_HEIGHT - 446f, 372f, 32f)
        shapes.end()
        Gdx.gl.glDisable(GL20.GL_BLEND)

        batch.use {
          speakerFont.color = Color(0.99f, 0.88f, 0.56f, alpha)
          speakerFont.draw(batch, "SOUND & SYSTEM SETTINGS", 116f + offsetX, VIRTUAL_HEIGHT - 86f)
          labels.forEachIndexed { i, text ->
            val by = VIRTUAL_HEIGHT - 170f - i * 54f
            hudFont.color = Color(0.92f, 0.94f, 0.99f, alpha)
            hudFont.draw(batch, text, 120f + offsetX, by + 16f)
            if (sliderThumbTex != null) {
              batch.setColor(1f, 1f, 1f, alpha)
              val thumbX = 340f + offsetX + 320f * sliders[i] - 10f
              batch.draw(sliderThumbTex, thumbX, by - 4f, 20f, 22f)
            }
          }

          val skipText = if (saveManager.skipAllUnread) "Skip: ALL TEXT" else "Skip: READ ONLY"
          val fsText = if (Gdx.graphics.isFullscreen) "Display: FULLSCREEN" else "Display: WINDOWED"
          val honText = "Honorifics: ${saveManager.honorificsMode}"
          val aspectText = "Aspect Ratio: ${saveManager.aspectRatioMode.displayName}"
          val panResetText =
            if (panOffsetX != 0f || panOffsetY != 0f) {
              "Pan: (${panOffsetX.toInt()}, ${panOffsetY.toInt()}) [Reset to Center]"
            } else {
              "Pan: Center (0, 0) [Drag to Move]"
            }
          hudFont.color = Color(0.92f, 0.94f, 0.99f, alpha)
          hudFont.draw(
            batch,
            skipText,
            200f + offsetX,
            VIRTUAL_HEIGHT - 378f,
            240f,
            Align.center,
            false,
          )
          hudFont.draw(
            batch,
            fsText,
            456f + offsetX,
            VIRTUAL_HEIGHT - 378f,
            240f,
            Align.center,
            false,
          )
          hudFont.draw(
            batch,
            honText,
            712f + offsetX,
            VIRTUAL_HEIGHT - 378f,
            240f,
            Align.center,
            false,
          )
          hudFont.draw(
            batch,
            aspectText,
            200f + offsetX,
            VIRTUAL_HEIGHT - 424f,
            360f,
            Align.center,
            false,
          )
          hudFont.draw(
            batch,
            panResetText,
            580f + offsetX,
            VIRTUAL_HEIGHT - 424f,
            372f,
            Align.center,
            false,
          )
          hudFont.color = Color.WHITE
          speakerFont.color = Color.WHITE
          batch.setColor(1f, 1f, 1f, 1f)
        }
      }

      1 -> {
        batch.use {
          speakerFont.color = Color(0.99f, 0.88f, 0.56f, alpha)
          speakerFont.draw(
            batch,
            "CHARACTER VOICE CHANNELS (voicedata.dat)",
            116f + offsetX,
            VIRTUAL_HEIGHT - 86f,
          )
          hudFont.color = Color(0.92f, 0.94f, 0.99f, alpha)
          voiceChannels.forEachIndexed { idx, ch ->
            val col = idx % 3
            val row = idx / 3
            val cx = 110f + col * 280f + offsetX
            val cy = VIRTUAL_HEIGHT - 180f - row * 52f
            val muted = saveManager.mutedVoiceActors.contains(ch.index)
            val badgeTex = if (muted) onOffTex0 else (onOffTex2 ?: onOffTex0)
            if (badgeTex != null) {
              batch.setColor(1f, 1f, 1f, alpha)
              batch.draw(badgeTex, cx, cy, 32f, 32f)
            }
            val stateTag = if (muted) "[MUTED]" else "[ACTIVE]"
            hudFont.draw(batch, "$stateTag ${ch.displayLabel}", cx + 40f, cy + 22f)
          }
          hudFont.color = Color.WHITE
          speakerFont.color = Color.WHITE
          batch.setColor(1f, 1f, 1f, 1f)
        }
      }

      2 -> {
        batch.use {
          speakerFont.color = Color(0.99f, 0.88f, 0.56f, alpha)
          speakerFont.draw(
            batch,
            "TEXT & MESSAGE WINDOW SETTINGS",
            116f + offsetX,
            VIRTUAL_HEIGHT - 86f,
          )
          hudFont.color = Color(0.92f, 0.94f, 0.99f, alpha)
          hudFont.draw(
            batch,
            String.format(
              "Auto-Advance Wait: %.1fs (adjust on System tab)",
              saveManager.autoSpeedSec,
            ),
            120f + offsetX,
            VIRTUAL_HEIGHT - 180f,
          )
          hudFont.draw(
            batch,
            "Unread Skip Mode: ${if (saveManager.skipAllUnread) "All Text" else "Read Only"}",
            120f + offsetX,
            VIRTUAL_HEIGHT - 220f,
          )
          hudFont.draw(
            batch,
            "Honorifics Mode: ${saveManager.honorificsMode}",
            120f + offsetX,
            VIRTUAL_HEIGHT - 260f,
          )
          hudFont.draw(
            batch,
            "Click dialogue toggles on System tab to customize.",
            120f + offsetX,
            VIRTUAL_HEIGHT - 300f,
          )
          hudFont.color = Color.WHITE
          speakerFont.color = Color.WHITE
        }
      }

      else -> {
        batch.use {
          speakerFont.color = Color(0.99f, 0.88f, 0.56f, alpha)
          speakerFont.draw(
            batch,
            "GAME DISPLAY & ENGINE INFO",
            116f + offsetX,
            VIRTUAL_HEIGHT - 86f,
          )
          hudFont.color = Color(0.92f, 0.94f, 0.99f, alpha)
          hudFont.draw(
            batch,
            "Aspect Ratio: ${saveManager.aspectRatioMode.displayName}",
            120f + offsetX,
            VIRTUAL_HEIGHT - 180f,
          )
          hudFont.draw(
            batch,
            "Renderer: OpenGL Shader Rules & Mosaic Transitions (フェード)",
            120f + offsetX,
            VIRTUAL_HEIGHT - 220f,
          )
          hudFont.draw(
            batch,
            "Audio: 35-Channel Decrypted Ogg Vorbis Stream",
            120f + offsetX,
            VIRTUAL_HEIGHT - 260f,
          )
          hudFont.draw(
            batch,
            "Save Format: Native Cross-Play SystemData.dat + SaveData*.svd",
            120f + offsetX,
            VIRTUAL_HEIGHT - 300f,
          )
          hudFont.color = Color.WHITE
          speakerFont.color = Color.WHITE
        }
      }
    }
  }

  private fun renderConfigScreen(dt: Float) {
    uiViewport.apply()
    batch.projectionMatrix = uiCamera.combined
    shapes.projectionMatrix = uiCamera.combined

    configTabExitTimerSec = (configTabExitTimerSec + dt).coerceAtMost(0.25f)
    configTabEnterTimerSec = (configTabEnterTimerSec + dt).coerceAtMost(0.30f)

    val exitT = (configTabExitTimerSec / 0.25f).coerceIn(0f, 1f)
    val enterT = (configTabEnterTimerSec / 0.30f).coerceIn(0f, 1f)
    val enterEase = 1f - (1f - enterT) * (1f - enterT) * (1f - enterT)
    val exitEase = exitT * exitT

    val exitOffsetX = -1000f * exitEase
    val enterOffsetX = 1000f * (1f - enterEase)

    val cfgFrame =
      textureLoader.loadTexture("スクリプト\\config\\back_frame.png")
        ?: textureLoader.loadTexture("スクリプト\\albummode\\back.jpg")
    val frameBasic =
      textureLoader.loadTexture("スクリプト\\config\\basic\\frame_basic.png")
        ?: textureLoader.loadTexture("スクリプト\\config\\frame_basic.png")
    val frameVoice =
      textureLoader.loadTexture("スクリプト\\config\\voice\\frame_voice.png")
        ?: textureLoader.loadTexture("スクリプト\\config\\frame_voice.png")
    val frameText = textureLoader.loadTexture("スクリプト\\config\\text\\frame_text.png")
    val iconConfigNode = textureLoader.loadAbmpRuntime("スクリプト\\config\\icon_config.b")
    val sliderThumbTex =
      textureLoader.loadAbmpLayerTexture("スクリプト\\config\\basic\\scroll_bar01.b", 0)
        ?: textureLoader.loadAbmpLayerTexture("スクリプト\\config\\scroll_bar01.b", 0)
    val onOffTex0 =
      textureLoader.loadAbmpLayerTexture("スクリプト\\config\\basic\\button_onoff.b", 0)
        ?: textureLoader.loadAbmpLayerTexture("スクリプト\\config\\button_onoff.b", 0)
    val onOffTex2 =
      textureLoader.loadAbmpLayerTexture("スクリプト\\config\\basic\\button_onoff.b", 2)
        ?: textureLoader.loadAbmpLayerTexture("スクリプト\\config\\button_onoff.b", 2)

    batch.use {
      if (cfgFrame != null) batch.draw(cfgFrame, 0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)

      // Outgoing tab background (`250ms` exit `-1000`)
      if (exitT < 1f && configPrevTab != configTab) {
        val outFrame =
          when (configPrevTab) {
            1 -> frameVoice ?: frameBasic
            2 -> frameText ?: frameBasic
            else -> frameBasic
          }
        if (outFrame != null) {
          batch.setColor(1f, 1f, 1f, 1f - exitEase)
          batch.draw(outFrame, exitOffsetX, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
        }
      }

      // Incoming tab background (`300ms` entrance `+1000 -> 0`)
      val activeTabFrame =
        when (configTab) {
          1 -> frameVoice ?: frameBasic
          2 -> frameText ?: frameBasic
          else -> frameBasic
        }
      if (activeTabFrame != null) {
        batch.setColor(1f, 1f, 1f, enterEase)
        batch.draw(activeTabFrame, enterOffsetX, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
      }
      batch.setColor(1f, 1f, 1f, 1f)

      if (iconConfigNode != null) {
        val durMs = (iconConfigNode.document?.durationMs ?: 1200).coerceAtLeast(400)
        drawAbmpNode(iconConfigNode, (uiAnimTimeSec * 1000f) % durMs)
      }
    }

    // Draw 4 tab buttons: System, Voice, Text, Display (button_page.b)
    val tabNames = listOf("System", "Voice", "Text", "Display")
    for (t in 0 until 4) {
      val tx = 110f + t * 136f
      val ty = VIRTUAL_HEIGHT - 64f
      val hovered = Rectangle(tx, ty, 128f, 32f).contains(mouseUi.x, mouseUi.y)
      val active = configTab == t
      val pageBtnTex =
        textureLoader.loadAbmpLayerTexture(
          "スクリプト\\config\\button_page.b",
          if (active) 2 else if (hovered) 1 else 0,
        )
      batch.use {
        if (pageBtnTex != null) {
          batch.draw(pageBtnTex, tx, ty, 128f, 32f)
        }
        hudFont.draw(batch, tabNames[t], tx, ty + 22f, 128f, Align.center, false)
      }
    }

    if (exitT < 1f && configPrevTab != configTab) {
      drawConfigTabContents(
        configPrevTab,
        exitOffsetX,
        1f - exitEase,
        sliderThumbTex,
        onOffTex0,
        onOffTex2,
      )
    }
    drawConfigTabContents(configTab, enterOffsetX, enterEase, sliderThumbTex, onOffTex0, onOffTex2)

    // Top-right buttons: Reset & Back
    val initHover =
      Rectangle(VIRTUAL_WIDTH - 260f, VIRTUAL_HEIGHT - 72f, 100f, 40f)
        .contains(mouseUi.x, mouseUi.y)
    val backHover =
      Rectangle(VIRTUAL_WIDTH - 150f, VIRTUAL_HEIGHT - 72f, 100f, 40f)
        .contains(mouseUi.x, mouseUi.y)
    batch.use {
      renderDialogChoiceButton(
        "スクリプト\\config\\button_reset.b",
        VIRTUAL_WIDTH - 260f,
        VIRTUAL_HEIGHT - 72f,
        initHover,
        "Reset",
      )
      renderDialogChoiceButton(
        "スクリプト\\config\\button_cancel.b",
        VIRTUAL_WIDTH - 150f,
        VIRTUAL_HEIGHT - 72f,
        backHover,
        "Back",
      )
    }
  }

  private fun handleConfigTouch(ui: Vector2, button: Int): Boolean {
    if (button == Input.Buttons.RIGHT) {
      saveManager.saveSystemData()
      triggerUiOverlay("スクリプト\\config\\fadeout01.b")
      mode = previousMode
      return true
    }
    // Tab switching (System, Voice, Text, Display)
    for (t in 0 until 4) {
      val tx = 110f + t * 136f
      val ty = VIRTUAL_HEIGHT - 64f
      if (Rectangle(tx, ty, 128f, 32f).contains(ui.x, ui.y)) {
        switchConfigTab(t)
        return true
      }
    }
    if (Rectangle(VIRTUAL_WIDTH - 260f, VIRTUAL_HEIGHT - 72f, 100f, 40f).contains(ui.x, ui.y)) {
      showConfirmModal(
        sysMessages.configResetText,
        "スクリプト\\dialog\\english\\初期設定に戻します。よろしいですか？.png",
      ) {
        saveManager.resetConfigToDefaults()
        audioManager.bgmVolume = saveManager.bgmVolume
        audioManager.voiceVolume = saveManager.voiceVolume
        audioManager.seVolume = saveManager.seVolume
      }
      return true
    }
    if (Rectangle(VIRTUAL_WIDTH - 150f, VIRTUAL_HEIGHT - 72f, 100f, 40f).contains(ui.x, ui.y)) {
      saveManager.saveSystemData()
      triggerUiOverlay("スクリプト\\config\\fadeout01.b")
      mode = previousMode
      return true
    }

    if (configTab == 0) {
      // Sliders
      for (i in 0 until 4) {
        val bx = 340f
        val by = VIRTUAL_HEIGHT - 170f - i * 54f
        if (Rectangle(bx - 10f, by - 10f, 340f, 34f).contains(ui.x, ui.y)) {
          val ratio = ((ui.x - bx) / 320f).coerceIn(0f, 1f)
          when (i) {
            0 -> {
              saveManager.bgmVolume = ratio
              audioManager.bgmVolume = ratio
            }
            1 -> {
              saveManager.voiceVolume = ratio
              audioManager.voiceVolume = ratio
            }
            2 -> {
              saveManager.seVolume = ratio
              audioManager.seVolume = ratio
            }
            3 -> {
              saveManager.autoSpeedSec = (4.0f - ratio * 3.5f).coerceIn(0.4f, 4.0f)
            }
          }
          saveManager.saveSystemData()
          return true
        }
      }

      if (Rectangle(200f, VIRTUAL_HEIGHT - 400f, 240f, 32f).contains(ui.x, ui.y)) {
        saveManager.skipAllUnread = !saveManager.skipAllUnread
        saveManager.saveSystemData()
        return true
      }
      if (Rectangle(456f, VIRTUAL_HEIGHT - 400f, 240f, 32f).contains(ui.x, ui.y)) {
        if (Gdx.graphics.isFullscreen) {
          Gdx.graphics.setWindowedMode(1024, 768)
          saveManager.fullscreen = false
        } else {
          Gdx.graphics.setFullscreenMode(Gdx.graphics.displayMode)
          saveManager.fullscreen = true
        }
        saveManager.saveSystemData()
        return true
      }
      if (Rectangle(712f, VIRTUAL_HEIGHT - 400f, 240f, 32f).contains(ui.x, ui.y)) {
        saveManager.honorificsMode =
          if (saveManager.honorificsMode == walkure.pack.HonorificsMode.HON) {
            walkure.pack.HonorificsMode.NOHON
          } else {
            walkure.pack.HonorificsMode.HON
          }
        saveManager.saveSystemData()
        return true
      }
      if (Rectangle(200f, VIRTUAL_HEIGHT - 446f, 360f, 32f).contains(ui.x, ui.y)) {
        setAspectRatioMode(saveManager.aspectRatioMode.next())
        audioManager.playSe("misc\\Click01.wav")
        return true
      }
      if (Rectangle(580f, VIRTUAL_HEIGHT - 446f, 372f, 32f).contains(ui.x, ui.y)) {
        resetPan()
        audioManager.playSe("misc\\Click01.wav")
        return true
      }
    } else if (configTab == 1) {
      voiceChannels.forEachIndexed { idx, ch ->
        val col = idx % 3
        val row = idx / 3
        val cx = 110f + col * 280f
        val cy = VIRTUAL_HEIGHT - 180f - row * 52f
        if (Rectangle(cx, cy, 260f, 36f).contains(ui.x, ui.y)) {
          if (!saveManager.mutedVoiceActors.remove(ch.index)) {
            saveManager.mutedVoiceActors.add(ch.index)
          }
          saveManager.saveSystemData()
          return true
        }
      }
    }

    return false
  }

  private fun renderAlbumMusicScreen() {
    uiViewport.apply()
    batch.projectionMatrix = uiCamera.combined
    shapes.projectionMatrix = uiCamera.combined

    val fullCg = fullScreenCgPath
    if (fullCg != null) {
      val tex = textureLoader.loadTexture(fullCg)
      batch.use {
        if (tex != null) batch.draw(tex, 0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
      }
      return
    }

    val bg = textureLoader.loadTexture("スクリプト\\albummode\\back.jpg")
    batch.use {
      if (bg != null) batch.draw(bg, 0f, 0f, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)
    }

    val tabs = listOf("Celia", "Mio", "Noel", "Lisa", "Etc", "Music Room", "Memory")
    Gdx.gl.glEnable(GL20.GL_BLEND)
    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    shapes.begin(ShapeRenderer.ShapeType.Filled)
    shapes.setColor(0.05f, 0.07f, 0.14f, 0.82f)
    shapes.rect(24f, 24f, VIRTUAL_WIDTH - 48f, VIRTUAL_HEIGHT - 48f)

    tabs.forEachIndexed { i, tab ->
      if (tab == currentAlbumTab) shapes.setColor(0.32f, 0.42f, 0.68f, 0.95f)
      else shapes.setColor(0.14f, 0.18f, 0.30f, 0.90f)
      shapes.rect(36f + i * 116f, VIRTUAL_HEIGHT - 82f, 110f, 34f)
    }
    shapes.setColor(0.45f, 0.20f, 0.24f, 0.95f)
    shapes.rect(VIRTUAL_WIDTH - 140f, VIRTUAL_HEIGHT - 82f, 100f, 34f)

    if (currentAlbumTab == "Music Room") {
      bgmTracks.take(36).forEachIndexed { idx, entry ->
        val col = idx % 3
        val row = idx / 3
        val bx = 48f + col * 310f
        val by = VIRTUAL_HEIGHT - 144f - row * 48f
        val unlocked = saveManager.isBgmUnlocked(entry.path)
        val active =
          unlocked && entry.path.endsWith(audioManager.activeBgmName().orEmpty(), ignoreCase = true)
        when {
          active -> shapes.setColor(0.28f, 0.46f, 0.36f, 0.95f)
          unlocked -> shapes.setColor(0.12f, 0.16f, 0.28f, 0.92f)
          else -> shapes.setColor(0.08f, 0.09f, 0.14f, 0.85f)
        }
        shapes.rect(bx, by, 296f, 38f)
      }
    } else if (currentAlbumTab == "Memory") {
      val heroines = listOf("Celia", "Mio", "Noel", "Lisa", "Bertille")
      heroines.forEachIndexed { idx, h ->
        val bx = 80f + (idx % 3) * 290f
        val by = VIRTUAL_HEIGHT - 200f - (idx / 3) * 110f
        val cleared = saveManager.clearedRoutes.any { it.equals(h, ignoreCase = true) }
        if (cleared) shapes.setColor(0.22f, 0.34f, 0.56f, 0.95f)
        else shapes.setColor(0.10f, 0.12f, 0.18f, 0.88f)
        shapes.rect(bx, by, 260f, 80f)
      }
    }
    shapes.end()
    Gdx.gl.glDisable(GL20.GL_BLEND)

    batch.use {
      tabs.forEachIndexed { i, tab ->
        hudFont.draw(batch, tab, 36f + i * 116f, VIRTUAL_HEIGHT - 59f, 110f, Align.center, false)
      }
      hudFont.draw(
        batch,
        "Back [Esc]",
        VIRTUAL_WIDTH - 140f,
        VIRTUAL_HEIGHT - 59f,
        100f,
        Align.center,
        false,
      )

      if (currentAlbumTab == "Music Room") {
        bgmTracks.take(36).forEachIndexed { idx, entry ->
          val col = idx % 3
          val row = idx / 3
          val bx = 48f + col * 310f
          val by = VIRTUAL_HEIGHT - 144f - row * 48f
          val unlocked = saveManager.isBgmUnlocked(entry.path)
          val name = if (unlocked) "♪ ${entry.path.substringAfterLast('\\')}" else "🔒 Locked Track"
          hudFont.color = if (unlocked) Color.WHITE else Color(0.55f, 0.58f, 0.65f, 0.85f)
          hudFont.draw(batch, name, bx + 12f, by + 25f)
        }
        hudFont.color = Color.WHITE
      } else if (currentAlbumTab == "Memory") {
        val heroines = listOf("Celia", "Mio", "Noel", "Lisa", "Bertille")
        heroines.forEachIndexed { idx, h ->
          val bx = 80f + (idx % 3) * 290f
          val by = VIRTUAL_HEIGHT - 200f - (idx / 3) * 110f
          val cleared = saveManager.clearedRoutes.any { it.equals(h, ignoreCase = true) }
          val label = if (cleared) "$h Route [Replay]" else "$h Route [Locked]"
          hudFont.color = if (cleared) Color.WHITE else Color(0.55f, 0.58f, 0.65f, 0.85f)
          hudFont.draw(batch, label, bx, by + 48f, 260f, Align.center, false)
        }
        hudFont.color = Color.WHITE
      } else {
        val groups = albumLists[currentAlbumTab].orEmpty()
        val pageItems = groups.drop(currentAlbumPage * 12).take(12)
        pageItems.forEachIndexed { idx, group ->
          val col = idx % 4
          val row = idx / 4
          val gx = 52f + col * 234f
          val gy = VIRTUAL_HEIGHT - 270f - row * 190f
          val unlocked =
            saveManager.isCgUnlocked(group.thumbnailPath) ||
              group.cgVariants.any { saveManager.isCgUnlocked(it) }
          if (unlocked) {
            val cgPath =
              group.cgVariants.firstOrNull { saveManager.isCgUnlocked(it) } ?: group.thumbnailPath
            val tex =
              aliasResolver.resolveBackgroundPath(cgPath)?.let { textureLoader.loadTexture(it) }
            if (tex != null) {
              batch.draw(tex, gx, gy, 214f, 160f)
            }
            hudFont.color = Color.WHITE
            hudFont.draw(batch, group.thumbnailPath, gx, gy - 6f, 214f, Align.center, false)
          } else {
            val lockedTex = solidBlackTex
            if (lockedTex != null) {
              batch.setColor(0.18f, 0.20f, 0.28f, 0.9f)
              batch.draw(lockedTex, gx, gy, 214f, 160f)
              batch.setColor(1f, 1f, 1f, 1f)
            }
            hudFont.color = Color(0.60f, 0.64f, 0.72f, 0.9f)
            hudFont.draw(batch, "🔒 Unseen CG", gx, gy + 86f, 214f, Align.center, false)
            hudFont.draw(batch, "???", gx, gy - 6f, 214f, Align.center, false)
          }
        }
        hudFont.color = Color.WHITE
      }
    }
  }

  private fun handleAlbumTouch(ui: Vector2, button: Int): Boolean {
    if (fullScreenCgPath != null) {
      fullScreenCgPath = null
      return true
    }
    if (button == Input.Buttons.RIGHT) {
      mode = previousMode
      return true
    }
    val tabs = listOf("Celia", "Mio", "Noel", "Lisa", "Etc", "Music Room", "Memory")
    tabs.forEachIndexed { i, tab ->
      if (Rectangle(36f + i * 116f, VIRTUAL_HEIGHT - 82f, 110f, 34f).contains(ui.x, ui.y)) {
        currentAlbumTab = tab
        currentAlbumPage = 0
        audioManager.playSe("misc\\Click01.wav")
        return true
      }
    }
    if (Rectangle(VIRTUAL_WIDTH - 140f, VIRTUAL_HEIGHT - 82f, 100f, 34f).contains(ui.x, ui.y)) {
      mode = previousMode
      return true
    }

    if (currentAlbumTab == "Music Room") {
      bgmTracks.take(36).forEachIndexed { idx, entry ->
        val col = idx % 3
        val row = idx / 3
        val bx = 48f + col * 310f
        val by = VIRTUAL_HEIGHT - 144f - row * 48f
        if (Rectangle(bx, by, 296f, 38f).contains(ui.x, ui.y)) {
          if (saveManager.isBgmUnlocked(entry.path)) {
            audioManager.playBgm(entry.path)
          } else {
            audioManager.playSe("misc\\Cancel01.WAV")
          }
          return true
        }
      }
    } else if (currentAlbumTab == "Memory") {
      val heroines = listOf("Celia", "Mio", "Noel", "Lisa", "Bertille")
      heroines.forEachIndexed { idx, h ->
        val bx = 80f + (idx % 3) * 290f
        val by = VIRTUAL_HEIGHT - 200f - (idx / 3) * 110f
        if (Rectangle(bx, by, 260f, 80f).contains(ui.x, ui.y)) {
          val cleared = saveManager.clearedRoutes.any { it.equals(h, ignoreCase = true) }
          val firstStage = campaign.heroineRoutes[h.lowercase()]?.firstOrNull()
          if (cleared && firstStage != null) {
            audioManager.playSe("misc\\Click01.wav")
            transitionToCampaignStage(firstStage)
          } else {
            audioManager.playSe("misc\\Cancel01.WAV")
          }
          return true
        }
      }
    } else {
      val groups = albumLists[currentAlbumTab].orEmpty()
      val pageItems = groups.drop(currentAlbumPage * 12).take(12)
      pageItems.forEachIndexed { idx, group ->
        val col = idx % 4
        val row = idx / 4
        val gx = 52f + col * 234f
        val gy = VIRTUAL_HEIGHT - 270f - row * 190f
        if (Rectangle(gx, gy, 214f, 160f).contains(ui.x, ui.y)) {
          val cgSymbol =
            group.cgVariants.firstOrNull { saveManager.isCgUnlocked(it) }
              ?: group.thumbnailPath.takeIf { saveManager.isCgUnlocked(it) }
          if (cgSymbol != null) {
            fullScreenCgPath = aliasResolver.resolveBackgroundPath(cgSymbol)
          } else {
            audioManager.playSe("misc\\Cancel01.WAV")
          }
          return true
        }
      }
    }
    return false
  }

  private fun renderVideoScreen() {
    if (videoPlayer.isExternalOverlay) {
      clearScreen(red = 0f, green = 0f, blue = 0f, alpha = 1f)
      return
    }
    if (!videoPlayer.isPlaying()) {
      videoPlayer.stop()
      val cb = onVideoFinished
      onVideoFinished = null
      cb?.invoke()
      return
    }
    val tex = videoPlayer.updateTexture()
    batch.use {
      if (tex != null) {
        batch.draw(tex, 0f, 96f, VIRTUAL_WIDTH, 576f)
      }
      hudFont.draw(
        batch,
        "Playing ${videoPlayer.activeMovieName ?: "Movie"} — Click / Space / Esc to Skip",
        0f,
        36f,
        VIRTUAL_WIDTH,
        Align.center,
        false,
      )
    }
  }

  private fun renderBacklogText() {
    speakerFont.draw(
      batch,
      "Dialogue Backlog (Up/Down/Wheel to scroll, L or Esc to close)",
      64f,
      VIRTUAL_HEIGHT - 64f,
    )
    val entries = vm.backlog
    val visibleCount = 7
    val endExclusive = (entries.size - backlogScrollOffset).coerceIn(0, entries.size)
    val startInclusive = (endExclusive - visibleCount).coerceAtLeast(0)

    var curY = VIRTUAL_HEIGHT - 104f
    for (idx in startInclusive until endExclusive) {
      val item = entries[idx]
      val header = buildString {
        append("#${idx + 1} ")
        if (!item.speaker.isNullOrBlank()) append("【${item.speaker}】 ")
        if (!item.voiceId.isNullOrBlank()) append("[Voice: ${item.voiceId}]")
      }
      hudFont.draw(batch, header, 68f, curY)
      curY -= 22f
      glyphLayout.setText(
        dialogueFont,
        item.text,
        Color.WHITE,
        VIRTUAL_WIDTH - 140f,
        Align.left,
        true,
      )
      dialogueFont.draw(batch, glyphLayout, 68f, curY)
      curY -= (glyphLayout.height + 24f)
    }
  }

  private fun findClickedChoice(wx: Float, wy: Float, count: Int): Int {
    val boxW = 660f
    val boxH = 42f
    val startX = (VIRTUAL_WIDTH - boxW) / 2f
    val startY = 480f
    for (i in 0 until count) {
      val by = startY - i * (boxH + 18f)
      if (wx in startX..(startX + boxW) && wy in by..(by + boxH)) {
        return i
      }
    }
    return -1
  }

  private fun captureThumbnailPngBytes(): ByteArray? {
    return runCatching {
      val fullPixmap =
        Pixmap.createFromFrameBuffer(
          0,
          0,
          Gdx.graphics.backBufferWidth,
          Gdx.graphics.backBufferHeight,
        )
      val thumbW = gameDat.thumWidth
      val thumbH = gameDat.thumHeight
      val thumb = Pixmap(thumbW, thumbH, Pixmap.Format.RGBA8888)
      for (y in 0 until thumbH) {
        val srcY = fullPixmap.height - 1 - (y * fullPixmap.height / thumbH)
        for (x in 0 until thumbW) {
          val srcX = x * fullPixmap.width / thumbW
          thumb.drawPixel(x, y, fullPixmap.getPixel(srcX, srcY))
        }
      }
      val tmpFile = File.createTempFile("walkure_thumb", ".png")
      PixmapIO.writePNG(FileHandle(tmpFile), thumb)
      val bytes = tmpFile.readBytes()
      tmpFile.delete()
      thumb.dispose()
      fullPixmap.dispose()
      bytes
    }
      .getOrNull()
  }

  private fun quickSave() {
    val thumb = captureThumbnailPngBytes()
    val meta =
      saveManager.saveSlot(
        slotNumber = 0,
        snap = vm.snapshot(),
        inGameDate = vm.inGameDate,
        routeName = vm.routeName,
        routeFlags = campaign.routeFlags,
        thumbnailPngBytes = thumb,
      )
    statusMessage =
      "QuickSaved [${meta.inGameDate}] ${meta.scriptPath.substringAfterLast('\\')} #${meta.instructionPointer}"
  }

  private fun quickLoad() {
    val loaded = saveManager.loadSlot(0)
    if (loaded == null) {
      statusMessage = "No QuickSave found"
      return
    }
    showConfirmModal(sysMessages.quickLoadText, "スクリプト\\dialog\\english\\クイックロ-ドします。よろしいですか？.png") {
      restoreFromSaveSlot(loaded)
    }
  }

  private fun restoreFromSaveSlot(slot: SaveSlotMetadata) {
    audioManager.stopAllVoiceAndSe()
    val parsed = ScenarioParser.parse(slot.scriptPath, vfs.readTextShiftJis(slot.scriptPath))
    campaign.routeFlags.putAll(slot.routeFlags)
    vm.loadSnapshot(
      script = parsed,
      ip = (slot.instructionPointer - 1).coerceAtLeast(0),
      bgSymbol = slot.backgroundSymbol,
      bgPath = slot.backgroundVfsPath,
      sprites = slot.parseSprites(),
      bgmSymbol = slot.bgmSymbol,
      bgmPath = slot.bgmVfsPath,
      dateStr = slot.inGameDate,
      routeStr = slot.routeName,
    )
    mode = EngineMode.GAMEPLAY
    advanceStep()
  }

  override fun resize(width: Int, height: Int) {
    recreateViewport(width, height)
  }

  override fun dispose() {
    runCatching { videoPlayer.dispose() }
    runCatching { audioManager.dispose() }
    runCatching { textureLoader.dispose() }
    runCatching { transitionShader?.dispose() }
    runCatching { mosaicShader?.dispose() }
    runCatching { solidBlackTex?.dispose() }
    runCatching { solidWhiteTex?.dispose() }
    runCatching { prevSceneFbo?.dispose() }
    slotThumbnailTextures.values.forEach { runCatching { it.dispose() } }
    runCatching { dialogueFont.dispose() }
    runCatching { speakerFont.dispose() }
    runCatching { hudFont.dispose() }
    runCatching { fontGenerator?.dispose() }
    runCatching { batch.dispose() }
    runCatching { shapes.dispose() }
    runCatching { vfs.close() }
  }
}

/**
 * Mobile-safe Pan & Scan Viewport for wide displays (smartphones 16:9, 18:9, 19.5:9, 20:9, etc.).
 *
 * Keeps OpenGL screen bounds strictly clamped to [0, 0, screenWidth, screenHeight] to completely
 * avoid passing negative offsets to [HdpiUtils.glViewport], which triggers black screens and driver
 * clipping faults on mobile GPUs (e.g. Mali, PowerVR, Adreno).
 */
class PanScanViewport(
  private val targetWorldWidth: Float,
  private val targetWorldHeight: Float,
  camera: OrthographicCamera,
) : Viewport() {
  init {
    this.camera = camera
  }

  override fun update(screenWidth: Int, screenHeight: Int, centerCamera: Boolean) {
    setScreenBounds(0, 0, screenWidth, screenHeight)
    val sw = screenWidth.toFloat().coerceAtLeast(1f)
    val sh = screenHeight.toFloat().coerceAtLeast(1f)
    val screenAspect = sw / sh
    val targetAspect = targetWorldWidth / targetWorldHeight

    if (screenAspect >= targetAspect) {
      worldWidth = targetWorldWidth
      worldHeight = targetWorldWidth / screenAspect
    } else {
      worldHeight = targetWorldHeight
      worldWidth = targetWorldHeight * screenAspect
    }
    apply(centerCamera)
  }
}
