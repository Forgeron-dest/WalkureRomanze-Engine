package walkure.image

import kotlin.math.pow
import kotlin.math.roundToInt
import walkure.crypto.QlieCipher
import walkure.crypto.readIntLE
import walkure.crypto.readUInt16LE

/**
 * Pure-Kotlin, Android-safe parser and keyframe evaluator for QLiE `abdata10..13` (`abmotion10` /
 * `ablayer18`) timelines inside `.b` UI, title, and transition containers.
 *
 * Binary layout and easing math reverse-engineered from `WALKURE_ROMANZE.exe`:
 * - `0x4e4084`: `ablayer18` stream reader (`162`-byte / `0xa2` keyframe stride)
 * - `0x4e3c58`: `ablayer` keyframe unpacking & pivot/blend/ease fields
 * - `0x4df774`, `0x4cc7dc`, `0x4cc828`, `0x4cc8b0`: linear / ease-out / ease-in interpolation
 */
object AbmotionParser {

  const val KEYFRAME_STRIDE = 0xa2 // 162 bytes per ablayer18 keyframe

  data class AbLayerKeyframe(
    val timeMs: Int,
    val kfType: Int,
    val imageIndex: Int,
    val maskIndex: Int,
    val childStartMs: Int,
    val childEndMs: Int,
    val x: Float,
    val y: Float,
    val scaleX: Float,
    val scaleY: Float,
    val zPriority: Int,
    val angleDeg: Float,
    val tintR: Float,
    val tintG: Float,
    val tintB: Float,
    val easeType: Int,
    val easeParam: Int,
    val blendMode: Int,
    val alpha: Float,
    val pivotX: Float,
    val pivotY: Float,
    val isExplicitKey: Boolean,
  )

  data class AbmotionLayer(
    val name: String,
    val enabled: Boolean,
    val groupRole: Int, // 0 = normal, 1 = parent modifier, 2 = child of preceding role=1
    val keyframes: List<AbLayerKeyframe>,
  )

  data class AbmotionTrack(
    val name: String,
    val layers: List<AbmotionLayer>,
  )

  data class AbdataDocument(
    val name: String,
    val width: Int,
    val height: Int,
    val durationMs: Int,
    val paramText: String,
    val timelineStartMs: Int,
    val timelineEndMs: Int,
    val tracks: List<AbmotionTrack>,
  ) {
    fun findTrack(name: String): AbmotionTrack? = tracks.firstOrNull {
      it.name.equals(name, ignoreCase = true)
    }

    fun defaultTrack(): AbmotionTrack? =
      findTrack("NoName") ?: findTrack("通常") ?: tracks.firstOrNull()
  }

  data class EvaluatedLayerState(
    val layerName: String,
    val visible: Boolean,
    val kfType: Int,
    val imageIndex: Int,
    val maskIndex: Int,
    val childTimeMs: Float,
    val x: Float,
    val y: Float,
    val scaleXPercent: Float,
    val scaleYPercent: Float,
    val zPriority: Int,
    val angleDeg: Float,
    val tintR: Float,
    val tintG: Float,
    val tintB: Float,
    val alpha: Float,
    val blendMode: Int,
    val pivotX: Float,
    val pivotY: Float,
  )

  data class ButtonLayerIndices(
    val normalTileIndex: Int,
    val hoverTileIndex: Int,
    val downTileIndex: Int,
    val iconStripIndex: Int?,
    val labelNormalIndex: Int?,
    val labelHoverIndex: Int?,
    val maskIndex: Int?,
  )

  /** One sprite of a `kfType == 20` particle field: local position, variant, loop offset. */
  data class ParticlePlacement(
    val x: Float,
    val y: Float,
    val trackIndex: Int,
    val timeOffsetMs: Float,
  )

  /**
   * Deterministic layout for `kfType == 20` particle emitters (sakura petals, grass tufts,
   * sparkles). The emitter's `scaleX/scaleY` give the emission-rect size in pixels and its
   * position/pivot the rect center; variant tracks and loop offsets cycle per particle so petals
   * differ in shape and phase. Pure function of its inputs (seeded only by [seedKey]), so the field
   * is stable across frames while each petal animates through its own nested timeline.
   */
  fun particleFieldLayout(
    ev: EvaluatedLayerState,
    seedKey: String,
    trackCount: Int,
    childDurMs: Int,
  ): List<ParticlePlacement> {
    val w = ev.scaleXPercent
    val h = ev.scaleYPercent
    if (w <= 0f || h <= 0f || trackCount <= 0 || childDurMs <= 0) {
      return listOf(ParticlePlacement(ev.x, ev.y, 0, 0f))
    }
    val count = ((w * h / 60000f).roundToInt()).coerceIn(4, 20)
    val left = ev.x - w * ev.pivotX
    val top = ev.y - h * ev.pivotY
    val rng = kotlin.random.Random(seedKey.hashCode())
    return List(count) { i ->
      ParticlePlacement(
        x = left + rng.nextFloat() * w,
        y = top + rng.nextFloat() * h,
        trackIndex = i % trackCount,
        timeOffsetMs = i * childDurMs.toFloat() / count,
      )
    }
  }

  fun parseAbdata(bytes: ByteArray): AbdataDocument? {
    if (bytes.size < 32) return null
    return runCatching {
      var pos = 0
      val nameLen = readUInt16LE(bytes, pos)
      pos += 2
      val name = String(bytes, pos, nameLen, QlieCipher.SHIFT_JIS)
      pos += nameLen

      /* val u1 = */ readIntLE(bytes, pos)
      val width = readIntLE(bytes, pos + 4)
      val height = readIntLE(bytes, pos + 8)
      val durationMs = readIntLE(bytes, pos + 12)
      /* val u2 = readIntLE(bytes, pos + 16) */
      /* val u3 = readIntLE(bytes, pos + 20) */
      pos += 24

      val pLen = readUInt16LE(bytes, pos)
      pos += 2
      val paramText = String(bytes, pos, pLen, QlieCipher.SHIFT_JIS)
      pos += pLen

      val (tlStart, tlEnd) = parseTimelineRange(paramText)

      val trackCount = bytes[pos].toInt() and 0xFF
      pos += 1
      val tracks = ArrayList<AbmotionTrack>(trackCount)

      for (mi in 0 until trackCount) {
        if (pos + 16 > bytes.size) break
        val mTag = String(bytes, pos, 16, Charsets.US_ASCII).trimEnd('\u0000')
        pos += 16
        if (!mTag.startsWith("abmotion")) break

        val mNameLen = readUInt16LE(bytes, pos)
        pos += 2
        val mName = String(bytes, pos, mNameLen, QlieCipher.SHIFT_JIS)
        pos += mNameLen
        val layerCount = bytes[pos].toInt() and 0xFF
        pos += 1

        val layers = ArrayList<AbmotionLayer>(layerCount)
        for (li in 0 until layerCount) {
          if (pos + 16 > bytes.size) break
          val lTag = String(bytes, pos, 16, Charsets.US_ASCII).trimEnd('\u0000')
          pos += 16
          val ver = lTag.removePrefix("ablayer").toIntOrNull() ?: 18

          val lNameLen = readUInt16LE(bytes, pos)
          pos += 2
          val lName = String(bytes, pos, lNameLen, QlieCipher.SHIFT_JIS)
          pos += lNameLen

          val enabledByte = bytes[pos].toInt() and 0xFF
          pos += 1
          val kfCount = bytes[pos].toInt() and 0xFF
          pos += 1
          val groupRole = if (ver >= 18) (bytes[pos].toInt() and 0xFF).also { pos += 1 } else 0

          val rawStride =
            when {
              ver >= 18 -> KEYFRAME_STRIDE
              ver >= 16 -> 0x91
              ver >= 14 -> 0x5c
              else -> 0x39
            }

          val kfs = ArrayList<AbLayerKeyframe>(kfCount)
          for (ki in 0 until kfCount) {
            if (pos + rawStride > bytes.size) break
            if (ver >= 18) {
              kfs.add(readKeyframe18(bytes, pos))
            }
            pos += rawStride
          }

          layers.add(
            AbmotionLayer(
              name = lName,
              enabled = enabledByte != 0,
              groupRole = groupRole,
              keyframes = kfs,
            ),
          )
        }
        tracks.add(AbmotionTrack(mName, layers))
      }

      AbdataDocument(
        name = name,
        width = width,
        height = height,
        durationMs = if (durationMs > 0) durationMs else (tlEnd - tlStart).coerceAtLeast(0),
        paramText = paramText,
        timelineStartMs = tlStart,
        timelineEndMs = tlEnd,
        tracks = tracks,
      )
    }
      .getOrNull()
  }

  private fun readKeyframe18(buf: ByteArray, base: Int): AbLayerKeyframe {
    val timeMs = readIntLE(buf, base)
    val kfType = buf[base + 4].toInt() and 0xFF
    val imageIndex = buf[base + 6].toInt() and 0xFF
    val maskIndex = buf[base + 7].toInt() and 0xFF
    val childStartMs = readIntLE(buf, base + 0x10)
    val childEndMs = readIntLE(buf, base + 0x14)
    val x = readInt16LE(buf, base + 0x18).toFloat()
    val y = readInt16LE(buf, base + 0x1a).toFloat()
    val scaleX = readInt16LE(buf, base + 0x1c).toFloat()
    val scaleY = readInt16LE(buf, base + 0x1e).toFloat()
    val zPriority = readInt16LE(buf, base + 0x20)
    val angleDeg = readInt16LE(buf, base + 0x22).toFloat()
    val tintR = (buf[base + 0x28].toInt() and 0xFF) / 255f
    val tintG = (buf[base + 0x29].toInt() and 0xFF) / 255f
    val tintB = (buf[base + 0x2a].toInt() and 0xFF) / 255f
    val easeType = buf[base + 0x30].toInt() and 0xFF
    val easeParam = buf[base + 0x31].toInt() and 0xFF
    val blendMode = buf[base + 0x34].toInt() and 0xFF
    val alpha = (buf[base + 0x35].toInt() and 0xFF) / 255f
    val pivotX = (buf[base + 0x85].toInt() and 0xFF) / 100f
    val pivotY = (buf[base + 0x87].toInt() and 0xFF) / 100f

    return AbLayerKeyframe(
      timeMs = timeMs,
      kfType = kfType,
      imageIndex = imageIndex,
      maskIndex = maskIndex,
      childStartMs = childStartMs,
      childEndMs = childEndMs,
      x = x,
      y = y,
      scaleX = scaleX,
      scaleY = scaleY,
      zPriority = zPriority,
      angleDeg = angleDeg,
      tintR = tintR,
      tintG = tintG,
      tintB = tintB,
      easeType = easeType,
      easeParam = easeParam,
      blendMode = blendMode,
      alpha = alpha,
      pivotX = pivotX,
      pivotY = pivotY,
      isExplicitKey = kfType != 0,
    )
  }

  /**
   * Evaluates a track at [timeMs] into a Z-ordered list of active [EvaluatedLayerState]s. Supports
   * parent camera/modifier layers (`groupRole == 1`) affecting subsequent child layers (`groupRole
   * == 2`), plus lone art layers heading an `[art, live-child]` pair (calendar shadow+card, day
   * tens+ones), which share the pending modifier with their child.
   */
  fun evaluateTrack(track: AbmotionTrack, timeMs: Float): List<EvaluatedLayerState> {
    val result = ArrayList<EvaluatedLayerState>(track.layers.size)
    var currentModifier: EvaluatedLayerState? = null
    val layers = track.layers

    for ((index, layer) in layers.withIndex()) {
      if (!layer.enabled || layer.keyframes.isEmpty()) continue
      val eval = evaluateLayer(layer, timeMs)
      if (layer.groupRole == 1) {
        currentModifier = eval
        continue
      }

      // A lone art layer heading an [art, live-child] pair (calendar shadow+card, day tens+ones)
      // shares the pending modifier with its child so the pair renders as a unit; standalone
      // art layers ignore it.
      val sharesModifier =
        layer.groupRole == 2 ||
          (layer.groupRole == 0 &&
            layers.getOrNull(index + 1)?.let { next ->
              next.groupRole == 2 && next.keyframes.any { kf -> kf.kfType != 0 }
            } == true)
      val finalEval =
        if (currentModifier != null && eval.visible && sharesModifier) {
          val mod = currentModifier
          eval.copy(
            x = eval.x + mod.x,
            y = eval.y + mod.y,
            scaleXPercent = eval.scaleXPercent * (mod.scaleXPercent / 100f),
            scaleYPercent = eval.scaleYPercent * (mod.scaleYPercent / 100f),
            alpha = (eval.alpha * mod.alpha).coerceIn(0f, 1f),
            childTimeMs = eval.childTimeMs + mod.childTimeMs,
          )
        } else {
          eval
        }

      if (finalEval.visible) {
        result.add(finalEval)
      }
    }
    return result.sortedBy { it.zPriority }
  }

  /**
   * Evaluates one layer at [timeMs].
   *
   * Keyframe roles, reverse-engineered from `スクリプト\title\back.b` and `モーション\日付アイキャッチ.b`:
   * - A layer is live from its first keyframe through its last one. `kfType == 0` filler keys
   *   extend the hold of the nearest preceding real (or interpolation-destination) key; past the
   *   last key the layer hides.
   * - A `kfType == 0` key right after an eased (`easeType != 0`) key is that segment's
   *   interpolation destination: its values hold (with the eased source's image slot).
   * - The nested child timeline follows the `(timeMs, childStartMs)` ramp sampled by the keys, so a
   *   held container keeps playing its content instead of freezing on one offset.
   * - Layers without any `kfType != 0` key carry no artwork and stay invisible, while parent
   *   modifiers (`groupRole == 1`) still evaluate their value curve.
   */
  fun evaluateLayer(layer: AbmotionLayer, timeMs: Float): EvaluatedLayerState {
    val kfs = layer.keyframes
    if (kfs.isEmpty()) {
      return EvaluatedLayerState(
        layerName = layer.name,
        visible = false,
        kfType = 0,
        imageIndex = 0,
        maskIndex = 0,
        childTimeMs = 0f,
        x = 0f,
        y = 0f,
        scaleXPercent = 100f,
        scaleYPercent = 100f,
        zPriority = 0,
        angleDeg = 0f,
        tintR = 1f,
        tintG = 1f,
        tintB = 1f,
        alpha = 0f,
        blendMode = 0,
        pivotX = 0f,
        pivotY = 0f,
      )
    }
    if (layer.groupRole == 1) {
      return evaluateModifierCurve(layer, kfs, timeMs)
    }
    val firstReal = kfs.firstOrNull { it.kfType != 0 } ?: return hiddenState(layer, kfs.first())
    if (timeMs < firstReal.timeMs || timeMs > kfs.last().timeMs) {
      return hiddenState(layer, firstReal)
    }

    // Current segment over the full key list (filler keys included).
    var idx = 0
    while (idx + 1 < kfs.size && timeMs >= kfs[idx + 1].timeMs) {
      idx++
    }
    val childTimeMs = childTimeOnRamp(kfs, timeMs)
    val k0 = kfs[idx]

    // Eased segment: interpolate into the next key, which may itself be kfType == 0. The source
    // may even be kfType == 0 (Scene02 exit keys carry easeType != 0 with image and values).
    if (k0.easeType != 0 && idx + 1 < kfs.size) {
      return interpolateState(layer, k0, kfs[idx + 1], timeMs, childTimeMs)
    }
    // Held real key: values freeze while the nested child timeline keeps playing.
    if (k0.kfType != 0) {
      return holdState(layer, k0, childTimeMs)
    }
    // Interpolation destination after an eased key: hold its values with the eased source's
    // image slot (destinations usually carry imageIndex 0).
    if (idx > 0 && kfs[idx - 1].easeType != 0) {
      val src = kfs[idx - 1]
      return holdState(
        layer,
        k0,
        childTimeMs,
        imageIndex = src.imageIndex,
        maskIndex = src.maskIndex,
        kfType = src.kfType,
      )
    }
    // Filler key: extend the hold of the nearest preceding real (or destination) key.
    var holdIdx = idx - 1
    while (holdIdx > 0 && kfs[holdIdx].kfType == 0 && kfs[holdIdx - 1].easeType == 0) {
      holdIdx--
    }
    val held = kfs[holdIdx]
    if (held.kfType == 0 && (holdIdx == 0 || kfs[holdIdx - 1].easeType == 0)) {
      return hiddenState(layer, firstReal)
    }
    if (held.kfType != 0) {
      return holdState(layer, held, childTimeMs)
    }
    val src = kfs[holdIdx - 1]
    return holdState(
      layer,
      held,
      childTimeMs,
      imageIndex = src.imageIndex,
      maskIndex = src.maskIndex,
      kfType = src.kfType,
    )
  }

  /**
   * Evaluates a parent modifier (`groupRole == 1`) as a pure value curve over the full key list.
   * Modifiers carry no artwork of their own (their keys are usually all `kfType == 0`), so the
   * result is never visible; [evaluateTrack] still consumes its position, scale, alpha, and child
   * time for the following `groupRole == 2` layer. Unlike artwork (which holds its pose on
   * `easeType == 0`), a value curve always interpolates — like [childTimeOnRamp] — so the calendar
   * photo card fades in instead of popping.
   */
  private fun evaluateModifierCurve(
    layer: AbmotionLayer,
    kfs: List<AbLayerKeyframe>,
    timeMs: Float,
  ): EvaluatedLayerState {
    val first = kfs.first()
    val childTimeMs = childTimeOnRamp(kfs, timeMs)
    if (timeMs < first.timeMs) {
      return holdState(layer, first, childTimeMs).copy(visible = false)
    }
    var idx = 0
    while (idx + 1 < kfs.size && timeMs >= kfs[idx + 1].timeMs) {
      idx++
    }
    val k0 = kfs[idx]
    if (idx + 1 < kfs.size) {
      return interpolateState(
          layer,
          k0,
          kfs[idx + 1],
          timeMs,
          childTimeMs,
          forceLinear = k0.easeType == 0,
        )
        .copy(visible = false)
    }
    return holdState(layer, k0, childTimeMs).copy(visible = false)
  }

  /**
   * Nested child timeline position: the piecewise-linear `(timeMs, childStartMs)` ramp sampled by
   * the real keys and the `childStartMs`-carrying filler keys. Held constant past the last sample
   * (a filler pad with `childStartMs == 0` is not a sample, so trailing pads never rewind the
   * child). All-`childStartMs == 0` layers (particles, plain art) map to a constant `0`.
   */
  private fun childTimeOnRamp(kfs: List<AbLayerKeyframe>, timeMs: Float): Float {
    var prevT = 0
    var prevCs = 0
    var havePrev = false
    for (kf in kfs) {
      if (kf.kfType == 0 && kf.childStartMs == 0) continue
      if (timeMs < kf.timeMs) {
        if (!havePrev) return kf.childStartMs.toFloat()
        val span = (kf.timeMs - prevT).toFloat()
        if (span <= 0.001f) return kf.childStartMs.toFloat()
        return prevCs + (kf.childStartMs - prevCs) * ((timeMs - prevT) / span)
      }
      prevT = kf.timeMs
      prevCs = kf.childStartMs
      havePrev = true
    }
    return if (havePrev) prevCs.toFloat() else 0f
  }

  private fun hiddenState(layer: AbmotionLayer, kf: AbLayerKeyframe): EvaluatedLayerState =
    EvaluatedLayerState(
      layerName = layer.name,
      visible = false,
      kfType = kf.kfType,
      imageIndex = kf.imageIndex,
      maskIndex = kf.maskIndex,
      childTimeMs = kf.childStartMs.toFloat(),
      x = kf.x,
      y = kf.y,
      scaleXPercent = kf.scaleX,
      scaleYPercent = kf.scaleY,
      zPriority = kf.zPriority,
      angleDeg = kf.angleDeg,
      tintR = kf.tintR,
      tintG = kf.tintG,
      tintB = kf.tintB,
      alpha = 0f,
      blendMode = kf.blendMode,
      pivotX = kf.pivotX,
      pivotY = kf.pivotY,
    )

  private fun holdState(
    layer: AbmotionLayer,
    kf: AbLayerKeyframe,
    childTimeMs: Float,
    imageIndex: Int = kf.imageIndex,
    maskIndex: Int = kf.maskIndex,
    kfType: Int = kf.kfType,
  ): EvaluatedLayerState =
    EvaluatedLayerState(
      layerName = layer.name,
      visible = kf.alpha > 0.001f,
      kfType = kfType,
      imageIndex = imageIndex,
      maskIndex = maskIndex,
      childTimeMs = childTimeMs,
      x = kf.x,
      y = kf.y,
      scaleXPercent = kf.scaleX,
      scaleYPercent = kf.scaleY,
      zPriority = kf.zPriority,
      angleDeg = kf.angleDeg,
      tintR = kf.tintR,
      tintG = kf.tintG,
      tintB = kf.tintB,
      alpha = kf.alpha,
      blendMode = kf.blendMode,
      pivotX = kf.pivotX,
      pivotY = kf.pivotY,
    )

  private fun interpolateState(
    layer: AbmotionLayer,
    k0: AbLayerKeyframe,
    k1: AbLayerKeyframe,
    timeMs: Float,
    childTimeMs: Float,
    forceLinear: Boolean = false,
  ): EvaluatedLayerState {
    val span = (k1.timeMs - k0.timeMs).toFloat()
    val rawT = if (span <= 0.001f) 1f else ((timeMs - k0.timeMs) / span).coerceIn(0f, 1f)
    val easedT = if (forceLinear) rawT else applyEasing(rawT, k0.easeType, k0.easeParam)

    val alpha = lerp(k0.alpha, k1.alpha, easedT)
    return EvaluatedLayerState(
      layerName = layer.name,
      visible = alpha > 0.001f,
      kfType = if (k0.kfType != 0) k0.kfType else k1.kfType,
      imageIndex = k0.imageIndex,
      maskIndex = k0.maskIndex,
      childTimeMs = childTimeMs,
      x = lerp(k0.x, k1.x, easedT),
      y = lerp(k0.y, k1.y, easedT),
      scaleXPercent = lerp(k0.scaleX, k1.scaleX, easedT),
      scaleYPercent = lerp(k0.scaleY, k1.scaleY, easedT),
      zPriority = k0.zPriority,
      angleDeg = lerp(k0.angleDeg, k1.angleDeg, easedT),
      tintR = lerp(k0.tintR, k1.tintR, easedT),
      tintG = lerp(k0.tintG, k1.tintG, easedT),
      tintB = lerp(k0.tintB, k1.tintB, easedT),
      alpha = alpha,
      blendMode = k0.blendMode,
      pivotX = lerp(k0.pivotX, k1.pivotX, easedT),
      pivotY = lerp(k0.pivotY, k1.pivotY, easedT),
    )
  }

  /**
   * Exact QLiE easing modes from `0x4df774`:
   * - `0`: hold / step
   * - `1`: linear (`0x4cc7dc`)
   * - `2`: spline / smoothstep (`0x4ccdc8`)
   * - `3`: ease-out `1 - (1 - t)^p` (`0x4cc828`)
   * - `4`: ease-in `t^p` (`0x4cc8b0`)
   * - `>=5`: smoothstep in-out
   */
  fun applyEasing(t: Float, easeType: Int, easeParam: Int): Float {
    val clamped = t.coerceIn(0f, 1f)
    val power = 1f + (easeParam.coerceAtLeast(1) / 100f)
    return when (easeType) {
      0 -> 0f
      1 -> clamped
      2 -> clamped * clamped * (3f - 2f * clamped)
      3 -> 1f - (1f - clamped).pow(power)
      4 -> clamped.pow(power)
      else ->
        if (clamped < 0.5f) {
          0.5f * (2f * clamped).pow(power)
        } else {
          1f - 0.5f * (2f * (1f - clamped)).pow(power)
        }
    }
  }

  /**
   * Semantic layer resolution for QLiE button `.b` archives (fixes `button_music.b` where a nested
   * `Icon_sound_b/mask` shifts flattened indices of `t01`/`t02` and `Mask`).
   */
  fun resolveButtonLayerIndices(container: AbmpDecoder.AbmpContainer): ButtonLayerIndices {
    val flattened = container.allFlattenedImages()
    val normalTileIdx =
      flattened
        .indexOfFirst {
          it.name.endsWith("01") &&
            !it.name.startsWith("t", ignoreCase = true) &&
            !it.name.contains("mask", ignoreCase = true)
        }
        .takeIf { it >= 0 } ?: 0
    val hoverTileIdx =
      flattened
        .indexOfFirst {
          it.name.endsWith("02") &&
            !it.name.startsWith("t", ignoreCase = true) &&
            !it.name.contains("mask", ignoreCase = true)
        }
        .takeIf { it >= 0 } ?: normalTileIdx
    val downTileIdx =
      flattened
        .indexOfFirst {
          it.name.endsWith("03") &&
            !it.name.startsWith("t", ignoreCase = true) &&
            !it.name.contains("mask", ignoreCase = true)
        }
        .takeIf { it >= 0 } ?: hoverTileIdx

    val iconStripIdx =
      flattened
        .indexOfFirst {
          it.name.contains("comb", ignoreCase = true) ||
            (it.name.contains("icon", ignoreCase = true) &&
              !it.name.contains("mask", ignoreCase = true))
        }
        .takeIf { it >= 0 }

    val labelNormalIdx =
      flattened
        .indexOfFirst {
          it.name.equals("t01", ignoreCase = true) ||
            it.name.endsWith("text01", ignoreCase = true) ||
            it.name.endsWith("Text01")
        }
        .takeIf { it >= 0 }

    val labelHoverIdx =
      flattened
        .indexOfFirst {
          it.name.equals("t02", ignoreCase = true) ||
            it.name.endsWith("text02", ignoreCase = true) ||
            it.name.endsWith("Text02")
        }
        .takeIf { it >= 0 } ?: labelNormalIdx

    val maskIdx =
      flattened
        .indexOfFirst {
          it.name.equals("Mask", ignoreCase = false)
        }
        .takeIf { it >= 0 }
        ?: flattened.indexOfLast { it.name.equals("mask", ignoreCase = true) }.takeIf { it >= 0 }

    return ButtonLayerIndices(
      normalTileIndex = normalTileIdx,
      hoverTileIndex = hoverTileIdx,
      downTileIndex = downTileIdx,
      iconStripIndex = iconStripIdx,
      labelNormalIndex = labelNormalIdx,
      labelHoverIndex = labelHoverIdx,
      maskIndex = maskIdx,
    )
  }

  private fun parseTimelineRange(paramText: String): Pair<Int, Int> {
    val match = Regex("""\*GUITimeLineParam\s*=\s*([^\r\n]+)""").find(paramText) ?: return 0 to 1000
    val parts = match.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }
    // Format: enable?, loop?, autoplay?, startViewMs, endMs, stepMs, zoom?
    val endMs = parts.getOrNull(4)?.coerceAtLeast(0) ?: 1000
    return 0 to endMs
  }

  private fun readInt16LE(data: ByteArray, offset: Int): Int {
    val raw = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    return raw.toShort().toInt()
  }

  private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
