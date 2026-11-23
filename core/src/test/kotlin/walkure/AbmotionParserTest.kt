package walkure

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import walkure.image.AbmotionParser
import walkure.image.AbmpDecoder
import walkure.pack.HonorificsMode
import walkure.pack.QlieVirtualFileSystem

/**
 * Regression tests for QLiE `ablayer18` keyframe evaluation ([AbmotionParser.evaluateLayer]).
 *
 * Keyframe roles were reverse-engineered from `スクリプト\title\back.b` and `モーション\日付アイキャッチ.b`:
 * - A layer is live from its first keyframe through its last one. `kfType == 0` filler keys extend
 *   the hold of the nearest preceding real (or interpolation-destination) key; past the last key
 *   the layer hides (title scenes `Scene01a/01b/02`, calendar layers).
 * - A `kfType == 0` key right after an eased (`easeType != 0`) key is that segment's interpolation
 *   destination: its values hold (with the eased source's image slot) until the next key.
 * - The nested child timeline follows the `(timeMs, childStartMs)` ramp sampled by the keys, so a
 *   held container keeps playing its content in wall-clock time (title captions, slideshows).
 * - Layers without any `kfType != 0` key carry no artwork (`BGM`, calendar `NoName` dummies) and
 *   stay invisible, while parent modifiers (`groupRole == 1`) still evaluate their value curve.
 */
class AbmotionParserTest {
  private fun kf(
    timeMs: Int,
    kfType: Int = 4,
    imageIndex: Int = 0,
    x: Float = 0f,
    y: Float = 0f,
    scaleX: Float = 100f,
    scaleY: Float = 100f,
    zPriority: Int = 0,
    angleDeg: Float = 0f,
    easeType: Int = 0,
    easeParam: Int = 100,
    blendMode: Int = 0,
    alpha: Float = 1f,
    pivotX: Float = 0f,
    pivotY: Float = 0f,
    childStartMs: Int = 0,
    childEndMs: Int = 0,
  ) =
    AbmotionParser.AbLayerKeyframe(
      timeMs = timeMs,
      kfType = kfType,
      imageIndex = imageIndex,
      maskIndex = 0,
      childStartMs = childStartMs,
      childEndMs = childEndMs,
      x = x,
      y = y,
      scaleX = scaleX,
      scaleY = scaleY,
      zPriority = zPriority,
      angleDeg = angleDeg,
      tintR = 1f,
      tintG = 1f,
      tintB = 1f,
      easeType = easeType,
      easeParam = easeParam,
      blendMode = blendMode,
      alpha = alpha,
      pivotX = pivotX,
      pivotY = pivotY,
    )

  private fun layer(
    name: String = "Test",
    groupRole: Int = 0,
    keyframes: List<AbmotionParser.AbLayerKeyframe>,
  ) =
    AbmotionParser.AbmotionLayer(
      name,
      enabled = true,
      groupRole = groupRole,
      keyframes = keyframes,
    )

  @Test
  fun testPadExtendsHoldAndHidesPastLastKey() {
    // Shape of back.b Scene01a / Scene01b / Scene02 top-level layers.
    val l =
      layer(
        keyframes =
          listOf(kf(0, x = 0f), kf(1500, x = 10f), kf(3000, kfType = 0), kf(4000, kfType = 0)),
      )
    assertTrue(layerVisibleAt(l, 0f))
    assertTrue(layerVisibleAt(l, 1500f))
    assertEquals(10f, AbmotionParser.evaluateLayer(l, 1500f).x, 0.01f)
    assertEquals(10f, AbmotionParser.evaluateLayer(l, 3500f).x, 0.01f)
    assertTrue(layerVisibleAt(l, 4000f))
    assertFalse(layerVisibleAt(l, 4001f), "Scene must hide once the playhead passes its last key")
    assertFalse(layerVisibleAt(l, 16000f), "Scene must stay hidden at the title rest pose")
  }

  @Test
  fun testLayerHiddenBeforeFirstRealKey() {
    // Shape of back.b Scene01b (starts at t=3000).
    val l = layer(keyframes = listOf(kf(3000), kf(5500, kfType = 0)))
    assertFalse(layerVisibleAt(l, 0f))
    assertFalse(layerVisibleAt(l, 2999f))
    assertTrue(layerVisibleAt(l, 3000f))
    assertTrue(layerVisibleAt(l, 4000f))
    assertTrue(layerVisibleAt(l, 5500f))
    assertFalse(layerVisibleAt(l, 5501f))
  }

  @Test
  fun testEasedDestinationHoldsThenHides() {
    // Shape of Scene01a BG01: kf0 --ease--> kf1(destination) ... kf2(end marker).
    val l =
      layer(
        keyframes =
          listOf(
            kf(0, x = 560f, easeType = 1),
            kf(1000, kfType = 0, x = 470f),
            kf(2000, kfType = 0, x = 0f),
          ),
      )
    val mid = AbmotionParser.evaluateLayer(l, 500f)
    assertTrue(mid.visible)
    assertEquals(515f, mid.x, 0.01f)
    val held = AbmotionParser.evaluateLayer(l, 1500f)
    assertTrue(held.visible)
    assertEquals(470f, held.x, 0.01f)
    assertEquals(470f, AbmotionParser.evaluateLayer(l, 2000f).x, 0.01f)
    assertFalse(layerVisibleAt(l, 2001f))
  }

  @Test
  fun testDestinationHoldKeepsEasedSourceImage() {
    // Shape of Scene01a glow streak: kf0(img=2) --ease--> kf1(img=0); the streak keeps image 2.
    val l =
      layer(
        keyframes =
          listOf(
            kf(0, imageIndex = 2, easeType = 1),
            kf(1000, kfType = 0, imageIndex = 0),
            kf(2000, kfType = 0, imageIndex = 0),
          ),
      )
    val held = AbmotionParser.evaluateLayer(l, 1500f)
    assertTrue(held.visible)
    assertEquals(2, held.imageIndex)
    assertFalse(layerVisibleAt(l, 2001f))
  }

  @Test
  fun testHeldChildTimeMapsWallClock() {
    // Shape of back.b Scene01a top-level keys: the nested timeline follows the (timeMs,
    // childStartMs) ramp instead of freezing on each key's offset.
    val l =
      layer(
        keyframes =
          listOf(
            kf(0, childStartMs = 0),
            kf(1500, childStartMs = 1000),
            kf(3000, kfType = 0, childStartMs = 2000),
          ),
      )
    assertEquals(500f, AbmotionParser.evaluateLayer(l, 750f).childTimeMs, 0.01f)
    assertEquals(1000f, AbmotionParser.evaluateLayer(l, 1500f).childTimeMs, 0.01f)
    assertEquals(1333.33f, AbmotionParser.evaluateLayer(l, 2000f).childTimeMs, 0.01f)
  }

  @Test
  fun testEasedSegmentLerpsChildTime() {
    // Shape of Logo Particle: child time maps linearly across the eased segment.
    val l =
      layer(
        keyframes =
          listOf(
            kf(750, childStartMs = 0, easeType = 1),
            kf(2000, kfType = 0, childStartMs = 2000),
          ),
      )
    assertEquals(0f, AbmotionParser.evaluateLayer(l, 750f).childTimeMs, 0.01f)
    assertEquals(1000f, AbmotionParser.evaluateLayer(l, 1375f).childTimeMs, 0.01f)
  }

  @Test
  fun testLayerWithoutRealKeysStaysInvisible() {
    // Shape of back.b BGM and the calendar all-zero NoName dummy layers.
    val l =
      layer(
        keyframes =
          listOf(
            kf(0, kfType = 0),
            kf(1000, kfType = 0),
            kf(2000, kfType = 0),
          ),
      )
    assertFalse(layerVisibleAt(l, 0f))
    assertFalse(layerVisibleAt(l, 500f))
    assertFalse(layerVisibleAt(l, 5000f))
  }

  @Test
  fun testModifierEvaluatesValueCurveWithoutRealKeys() {
    // Shape of Scene01b Time: pure-curve parent drift applied to the following role=2 child.
    val mod =
      layer(
        name = "Time",
        groupRole = 1,
        keyframes =
          listOf(
            kf(0, kfType = 0, x = 130f, y = 30f, easeType = 1),
            kf(2000, kfType = 0, x = -140f, y = -40f),
          ),
      )
    val atMid = AbmotionParser.evaluateLayer(mod, 1000f)
    assertEquals(-5f, atMid.x, 0.01f)
    assertEquals(-5f, atMid.y, 0.01f)

    val child =
      layer(
        name = "Paticle",
        groupRole = 2,
        keyframes = listOf(kf(0, x = 500f, y = 300f), kf(3000, kfType = 0)),
      )
    val track = AbmotionParser.AbmotionTrack("NoName", listOf(mod, child))
    val states = AbmotionParser.evaluateTrack(track, 1000f)
    assertEquals(1, states.size)
    assertEquals(495f, states[0].x, 0.01f)
    assertEquals(295f, states[0].y, 0.01f)
  }

  @Test
  fun testType0EaseSourceStillInterpolates() {
    // Shape of Scene02 fourth slide: a type-0 key with easeType != 0 opens an eased segment.
    val l =
      layer(
        keyframes =
          listOf(
            kf(3250, x = 490f),
            kf(4000, kfType = 0, imageIndex = 3, x = 490f, easeType = 4),
            kf(4250, kfType = 0, imageIndex = 0, x = 1500f),
            kf(5250, kfType = 0, imageIndex = 0, x = 0f),
          ),
      )
    val mid = AbmotionParser.evaluateLayer(l, 4125f)
    assertTrue(mid.visible)
    assertTrue(mid.x > 490f && mid.x < 1500f, "Slide-out must interpolate, got x=${mid.x}")
    // Past the exit destination the layer holds the off-screen pose through its last key.
    val held = AbmotionParser.evaluateLayer(l, 4500f)
    assertTrue(held.visible)
    assertEquals(1500f, held.x, 0.01f)
    assertEquals(1500f, AbmotionParser.evaluateLayer(l, 5250f).x, 0.01f)
    assertFalse(layerVisibleAt(l, 5251f))
  }

  private fun layerVisibleAt(layer: AbmotionParser.AbmotionLayer, timeMs: Float): Boolean =
    AbmotionParser.evaluateLayer(layer, timeMs).visible

  private fun evState(
    x: Float = 500f,
    y: Float = 400f,
    scaleXPercent: Float = 1206f,
    scaleYPercent: Float = 1106f,
    pivotX: Float = 0.5f,
    pivotY: Float = 0.5f,
  ) =
    AbmotionParser.EvaluatedLayerState(
      layerName = "hana_pink",
      visible = true,
      kfType = 20,
      imageIndex = 0,
      maskIndex = 0,
      childTimeMs = 0f,
      x = x,
      y = y,
      scaleXPercent = scaleXPercent,
      scaleYPercent = scaleYPercent,
      zPriority = 10,
      angleDeg = 0f,
      tintR = 1f,
      tintG = 1f,
      tintB = 1f,
      alpha = 1f,
      blendMode = 0,
      pivotX = pivotX,
      pivotY = pivotY,
    )

  @Test
  fun testModifierRampsAcrossStepKeys() {
    // A parent modifier is a pure value curve: unlike artwork it interpolates every segment, so
    // the calendar photo card (L11 alpha 0@1290 -> 255@3270) fades in instead of popping.
    val mod =
      layer(
        name = "Fade",
        groupRole = 1,
        keyframes =
          listOf(
            kf(1290, kfType = 0, easeType = 0, alpha = 0f),
            kf(3270, kfType = 0, easeType = 0, alpha = 1f),
          ),
      )
    assertEquals(0f, AbmotionParser.evaluateLayer(mod, 1290f).alpha, 0.01f)
    assertEquals(0.5f, AbmotionParser.evaluateLayer(mod, 2280f).alpha, 0.01f)
    assertEquals(1f, AbmotionParser.evaluateLayer(mod, 3270f).alpha, 0.01f)
    assertEquals(1f, AbmotionParser.evaluateLayer(mod, 5000f).alpha, 0.01f)
  }

  @Test
  fun testPairedArtSharesModifierWithChild() {
    // A lone art layer heading an [art, live-child] pair shares the pending modifier; a
    // standalone art layer followed by anything else ignores it.
    val mod =
      layer(
        name = "Fade",
        groupRole = 1,
        keyframes = listOf(kf(0, kfType = 0, alpha = 0f), kf(1000, kfType = 0, alpha = 1f)),
      )
    val art = layer(name = "Art", keyframes = listOf(kf(0), kf(2000, kfType = 0)))
    val child =
      layer(name = "Child", groupRole = 2, keyframes = listOf(kf(0), kf(2000, kfType = 0)))
    val lone = layer(name = "Lone", keyframes = listOf(kf(0), kf(2000, kfType = 0)))
    val pairedTrack = AbmotionParser.AbmotionTrack("T", listOf(mod, art, child))
    val paired = AbmotionParser.evaluateTrack(pairedTrack, 500f)
    assertEquals(0.5f, paired.first { it.layerName == "Art" }.alpha, 0.01f)
    assertEquals(0.5f, paired.first { it.layerName == "Child" }.alpha, 0.01f)
    val soloTrack = AbmotionParser.AbmotionTrack("T", listOf(mod, lone))
    val solo = AbmotionParser.evaluateTrack(soloTrack, 500f)
    assertEquals(1f, solo.first { it.layerName == "Lone" }.alpha, 0.01f)
  }

  @Test
  fun testParticleFieldLayout() {
    val ev = evState()
    val a = AbmotionParser.particleFieldLayout(ev, "hana_pink#0", 3, 1000)
    val b = AbmotionParser.particleFieldLayout(ev, "hana_pink#0", 3, 1000)
    assertEquals(a, b, "Field layout must be deterministic across frames")
    // 1206x1106 px / 60000 = 22 -> clamped to 20 petals.
    assertEquals(20, a.size)
    for (p in a) {
      assertTrue(p.x in -103f..1103f, "Petal x=${p.x} escapes the emission rect")
      assertTrue(p.y in -153f..953f, "Petal y=${p.y} escapes the emission rect")
    }
    assertEquals(listOf(0, 1, 2, 0, 1, 2), a.take(6).map { it.trackIndex })
    assertEquals(0f, a[0].timeOffsetMs)
    assertEquals(50f, a[1].timeOffsetMs, 0.01f)
    // Degenerate rect collapses to one centered placement.
    val flat = AbmotionParser.particleFieldLayout(ev.copy(scaleXPercent = 0f), "x", 3, 1000)
    assertEquals(1, flat.size)
    assertEquals(500f, flat[0].x)
    assertEquals(400f, flat[0].y)
    // Small fields clamp to at least 4 petals.
    val smallEv = ev.copy(scaleXPercent = 400f, scaleYPercent = 150f)
    val small = AbmotionParser.particleFieldLayout(smallEv, "x", 1, 1310)
    assertEquals(4, small.size)
  }

  private val gameDir: File? = run {
    val cwd = File(".").canonicalFile
    generateSequence(cwd) { it.parentFile }.firstOrNull { File(it, "GameData").isDirectory }
  }

  @Test
  fun testRealTitleTimelineSceneVisibility() {
    val dir = gameDir ?: error("GameData was not found in the current directory or its parents.")
    QlieVirtualFileSystem(dir, HonorificsMode.HON).use { vfs ->
      val container = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\back.b"))
      val doc = assertNotNull(AbmotionParser.parseAbdata(container.decompressedAbdataBytes))
      val track = assertNotNull(doc.defaultTrack())
      fun statesAt(ms: Float): List<String> =
        AbmotionParser.evaluateTrack(track, ms).map { it.layerName }

      // Intro scenes start on cue; an outgoing scene holds beneath its (opaque, later-drawn)
      // successor until its own last key.
      assertTrue("Scene01a" in statesAt(1000f))
      assertFalse("Scene01b" in statesAt(1000f))
      val at4000 = statesAt(4000f)
      assertTrue("Scene01b" in at4000)
      assertTrue("Scene01a" in at4000)
      assertTrue(at4000.indexOf("Scene01a") < at4000.indexOf("Scene01b"))
      assertTrue("Scene02" in statesAt(8000f))
      assertFalse("Scene01b" in statesAt(8000f))
      // Title rest pose: only the final scene, logo, and white banner remain.
      val rest = statesAt(16000f)
      assertTrue("Scene03" in rest)
      assertTrue("Logo" in rest)
      assertTrue("White_Fill" in rest)
      assertFalse("Scene01a" in rest)
      assertFalse("Scene01b" in rest)
      assertFalse("Scene02" in rest)
      assertFalse("Frame" in rest)
      assertFalse("BGM" in rest)
      // Nested scene timelines play instead of freezing on their first child offset: Scene01b
      // maps wall [3000..5500] onto child [0..2000].
      val scene01b = track.layers.first { it.name == "Scene01b" }
      assertEquals(800f, AbmotionParser.evaluateLayer(scene01b, 4000f).childTimeMs, 0.01f)
    }
  }

  @Test
  fun testRealCalendarDummyLayersStayInvisible() {
    val dir = gameDir ?: error("GameData was not found in the current directory or its parents.")
    QlieVirtualFileSystem(dir, HonorificsMode.HON).use { vfs ->
      val container = AbmpDecoder.parse(vfs.readBytes("モーション\\日付アイキャッチ.b"))
      val doc = assertNotNull(AbmotionParser.parseAbdata(container.decompressedAbdataBytes))
      val track = assertNotNull(doc.defaultTrack())
      // The all-zero role=2 NoName layers must not paint extra mini-card copies at (0,0).
      val dummies =
        track.layers.filter { it.name == "NoName" && it.keyframes.all { kf -> kf.kfType == 0 } }
      for (layer in dummies) {
        assertFalse(
          AbmotionParser.evaluateLayer(layer, 4000f).visible,
          "Dummy layer must stay invisible",
        )
      }
      // Date layers show mid-animation and hide once the cut-in ends.
      val statesMid = AbmotionParser.evaluateTrack(track, 4000f).map { it.layerName }.toSet()
      assertTrue("日付/月/April" in statesMid)
      val statesEnd = AbmotionParser.evaluateTrack(track, 7000f).map { it.layerName }.toSet()
      assertTrue("日付/月/April" in statesEnd)
      val statesPast = AbmotionParser.evaluateTrack(track, 7001f).map { it.layerName }.toSet()
      assertFalse("日付/月/April" in statesPast)
      // The mini photo card fades in through its L11 modifier (alpha 0@1290 -> 255@3270).
      val midFade = AbmotionParser.evaluateTrack(track, 2280f).first { it.layerName == "背景/町" }
      assertEquals(0.5f, midFade.alpha, 0.05f)
      // Its drop shadow fades on the same curve, and the day digits move as one number.
      val shadow = AbmotionParser.evaluateTrack(track, 2280f).first { it.layerName == "背景/陰" }
      assertEquals(0.5f, shadow.alpha, 0.05f)
      val at1000 = AbmotionParser.evaluateTrack(track, 1000f)
      val tens = at1000.first { it.layerName == "NoName" && it.imageIndex == 3 }
      val ones = at1000.first { it.layerName == "日付/数字/26" }
      assertEquals(14f, ones.x - tens.x, 0.01f)
    }
  }

  @Test
  fun testBlackIconMaskWhitening() {
    val dir = gameDir ?: error("GameData was not found in the current directory or its parents.")
    com.badlogic.gdx.utils.GdxNativesLoader.load()
    QlieVirtualFileSystem(dir, HonorificsMode.HON).use { vfs ->
      // button_quit.b ships a shape-only black icon strip: it must normalize to a white alpha
      // mask so the button tint colorizes it instead of multiplying down to black.
      val quit = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\button_quit.b"))
      val quitStrip = quit.allFlattenedImages().first { it.name.contains("comb") }
      val qpm = assertNotNull(quitStrip.decodePixmap())
      assertTrue(walkure.gdx.whitenBlackMaskInPlace(qpm))
      assertEquals(0xFFFFFFFF.toInt(), qpm.getPixel(40, 100))
      assertEquals(0, qpm.getPixel(10, 10) and 0xFF, "Transparent pixels must stay transparent")
      qpm.dispose()
      // A colored strip is returned untouched.
      val start = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\button_start.b"))
      val startStrip = start.allFlattenedImages().first { it.name.contains("comb") }
      val spm = assertNotNull(startStrip.decodePixmap())
      val before = spm.getPixel(40, 40)
      assertFalse(walkure.gdx.whitenBlackMaskInPlace(spm))
      assertEquals(before, spm.getPixel(40, 40))
      spm.dispose()
      // Across all menu buttons, only the shape-only black strips normalize.
      for (button in titleButtons) {
        val c = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\$button"))
        val strip = c.allFlattenedImages().first { it.name.contains("comb") }
        val pm = assertNotNull(strip.decodePixmap(), button)
        val whitened = walkure.gdx.whitenBlackMaskInPlace(pm)
        pm.dispose()
        assertEquals(button == "button_quit.b" || button == "button_cancel.b", whitened, button)
      }
    }
  }

  @Test
  fun testButtonIconStripFrameGeometry() {
    // The renderer plays icon strips as 80x80 frames sized by the nested icon document; every
    // menu button must satisfy that shape or frames would crop/misalign.
    val dir = gameDir ?: error("GameData was not found in the current directory or its parents.")
    com.badlogic.gdx.utils.GdxNativesLoader.load()
    QlieVirtualFileSystem(dir, HonorificsMode.HON).use { vfs ->
      for (button in titleButtons) {
        val c = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\$button"))
        val idx = AbmotionParser.resolveButtonLayerIndices(c)
        val strip = c.allFlattenedImages().getOrNull(idx.iconStripIndex ?: -1)
        val pm = assertNotNull(strip?.decodePixmap(), "$button icon strip")
        assertEquals(80, pm.width, "$button strip width")
        assertEquals(0, pm.height % 80, "$button strip height ${pm.height}")
        assertTrue(pm.height / 80 >= 10, "$button strip has too few frames")
        pm.dispose()
        val iconChild =
          c.images.firstOrNull { it.nestedAbmp != null && isIconLayer(it.name) }?.nestedAbmp
        val abdata = iconChild?.decompressedAbdataBytes
        val doc = assertNotNull(abdata?.let { AbmotionParser.parseAbdata(it) }, button)
        assertEquals(80, doc.width, "$button icon doc width")
        assertEquals(80, doc.height, "$button icon doc height")
      }
    }
  }

  @Test
  fun testRealTitleChildMapping() {
    val dir = gameDir ?: error("GameData was not found in the current directory or its parents.")
    QlieVirtualFileSystem(dir, HonorificsMode.HON).use { vfs ->
      val container = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\back.b"))
      val doc = assertNotNull(AbmotionParser.parseAbdata(container.decompressedAbdataBytes))
      val track = assertNotNull(doc.defaultTrack())
      // Held past their last real key, Scene03 and Logo freeze their child offsets at the title
      // rest pose (Mio and the logo assembly stay up).
      val scene03 = track.layers.first { it.name == "Scene03" }
      assertEquals(3000f, AbmotionParser.evaluateLayer(scene03, 16000f).childTimeMs, 0.01f)
      val logo = track.layers.first { it.name == "Logo" }
      assertEquals(2000f, AbmotionParser.evaluateLayer(logo, 16000f).childTimeMs, 0.01f)
      // No child-time jumps at segment joints.
      val scene01a = track.layers.first { it.name == "Scene01a" }
      val before = AbmotionParser.evaluateLayer(scene01a, 1499f).childTimeMs
      val at = AbmotionParser.evaluateLayer(scene01a, 1500f).childTimeMs
      assertTrue(abs(at - before) < 2f, "child jump $before -> $at")
      // Rest-pose layer kinds the renderer branches on.
      val rest = AbmotionParser.evaluateTrack(track, 16000f)
      assertEquals(1, rest.first { it.layerName == "White_Fill" }.kfType)
      assertEquals(20, rest.first { it.layerName == "Particle" }.kfType)
    }
  }

  @Test
  fun testTransitionTimelinesEvaluate() {
    val dir = gameDir ?: error("GameData was not found in the current directory or its parents.")
    com.badlogic.gdx.utils.GdxNativesLoader.load()
    QlieVirtualFileSystem(dir, HonorificsMode.HON).use { vfs ->
      val fades = vfs.listEntries("フェード\\").filter { it.normalizedPath.endsWith(".b") }
      assertTrue(fades.size >= 40, "expected the wfade set, got ${fades.size}")
      for (entry in fades) {
        val c = AbmpDecoder.parse(vfs.readBytes(entry.path))
        val doc = assertNotNull(AbmotionParser.parseAbdata(c.decompressedAbdataBytes), entry.path)
        val track = assertNotNull(doc.defaultTrack(), entry.path)
        val dur = doc.durationMs.coerceAtLeast(1).toFloat()
        AbmotionParser.evaluateTrack(track, 0f)
        val mid = AbmotionParser.evaluateTrack(track, dur / 2f)
        assertTrue(mid.isNotEmpty(), "${entry.path} shows nothing mid-transition")
        AbmotionParser.evaluateTrack(track, dur)
      }
      // wfade01 rule masks: an `lscroll` layer the renderer skips (wipe mask, not art).
      for (p in listOf("フェード\\wfade01a.b", "フェード\\wfade01b.b", "フェード\\wfade01c.b")) {
        val c = AbmpDecoder.parse(vfs.readBytes(p))
        assertTrue(c.images.any { it.name.endsWith("scroll") }, "$p must carry an lscroll mask")
      }
      // wfade02/wfade03 carry the Dammy1024 captured-screen placeholder the renderer substitutes.
      val wfade23 = fades.filter {
        it.normalizedPath.contains("wfade02") || it.normalizedPath.contains("wfade03")
      }
      assertTrue(wfade23.isNotEmpty())
      for (entry in wfade23) {
        val c = AbmpDecoder.parse(vfs.readBytes(entry.path))
        val hasDammy = c.images.any { it.name.startsWith("Dammy") }
        assertTrue(hasDammy, "${entry.path} must carry Dammy1024")
      }
      // wfade03 layout the eyecatch path relies on, with one particle emitter and an invisible
      // trailing dummy.
      val w3 = AbmpDecoder.parse(vfs.readBytes("フェード\\wfade03美桜.b"))
      val w3doc = assertNotNull(AbmotionParser.parseAbdata(w3.decompressedAbdataBytes))
      val w3track = assertNotNull(w3doc.defaultTrack())
      val expectedRoles =
        listOf(
          "Capture:0",
          "Back:0",
          "Time:1",
          "Chara(5):2",
          "Rotate:1",
          "Circle(3):2",
          "Particle_Box(2):0",
          "NoName:0",
        )
      assertEquals(expectedRoles, w3track.layers.map { "${it.name}:${it.groupRole}" })
      assertEquals(1, w3track.layers.count { l -> l.keyframes.any { it.kfType == 20 } })
      val w3mid = AbmotionParser.evaluateTrack(w3track, 1500f)
      val strayDummies = w3mid.filter { it.layerName == "NoName" && it.imageIndex == 0 }
      assertTrue(strayDummies.none { it.x == 0f && it.y == 0f })
      // Rule-mask bitmaps decode (same decision as the loader, exercised without GL).
      val masks = vfs.listEntries("フェード\\").filter { it.normalizedPath.endsWith(".bmp") }
      assertTrue(masks.isNotEmpty(), "expected rule-mask bmps")
      for (entry in masks) {
        val bytes = vfs.readBytes(entry.path)
        val pm =
          if (AbmpDecoder.isDpngData(bytes)) walkure.image.DpngDecoder.decodePixmap(bytes)
          else com.badlogic.gdx.graphics.Pixmap(bytes, 0, bytes.size)
        assertNotNull(pm, entry.path)
        assertTrue(pm.width > 0 && pm.height > 0, entry.path)
        pm.dispose()
      }
    }
  }

  private fun isIconLayer(name: String): Boolean =
    name.contains("icon", ignoreCase = true) || name.contains("comb", ignoreCase = true)

  private val titleButtons =
    listOf(
      "button_start.b",
      "button_load.b",
      "button_config.b",
      "button_special.b",
      "button_quit.b",
      "button_memory.b",
      "button_album.b",
      "button_music.b",
      "button_cancel.b",
    )

  @Test
  fun testButtonLayerIndicesResolveToDecodableArt() {
    val dir = gameDir ?: error("GameData was not found in the current directory or its parents.")
    com.badlogic.gdx.utils.GdxNativesLoader.load()
    QlieVirtualFileSystem(dir, HonorificsMode.HON).use { vfs ->
      for (button in titleButtons) {
        val container = AbmpDecoder.parse(vfs.readBytes("スクリプト\\title\\$button"))
        val flattened = container.allFlattenedImages()
        val idx = AbmotionParser.resolveButtonLayerIndices(container)
        for (i in
          listOfNotNull(
            idx.normalTileIndex,
            idx.hoverTileIndex,
            idx.downTileIndex,
            idx.iconStripIndex,
            idx.labelNormalIndex,
            idx.labelHoverIndex,
          )) {
          val pixmap = assertNotNull(flattened.getOrNull(i)?.decodePixmap(), "$button flat[$i]")
          assertTrue(pixmap.width > 0 && pixmap.height > 0)
          // The tile art must be opaque somewhere (guards against blank/black decodes).
          if (i == idx.normalTileIndex) {
            var opaque = 0
            for (y in 0 until pixmap.height step 4) {
              for (x in 0 until pixmap.width step 4) {
                if ((pixmap.getPixel(x, y) and 0xFF) > 128) opaque++
              }
            }
            assertTrue(opaque > 10, "$button normal tile must contain opaque pixels")
          }
          pixmap.dispose()
        }
      }
    }
  }
}
