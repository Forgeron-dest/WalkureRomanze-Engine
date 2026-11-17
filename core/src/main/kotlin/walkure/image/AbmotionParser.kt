package walkure.image

import kotlin.math.pow
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
   * == 2`).
   */
  fun evaluateTrack(track: AbmotionTrack, timeMs: Float): List<EvaluatedLayerState> {
    val result = ArrayList<EvaluatedLayerState>(track.layers.size)
    var currentModifier: EvaluatedLayerState? = null

    for (layer in track.layers) {
      if (!layer.enabled || layer.keyframes.isEmpty()) continue
      val eval = evaluateLayer(layer, timeMs)
      if (layer.groupRole == 1) {
        currentModifier = eval
        continue
      }

      val finalEval =
        if (layer.groupRole == 2 && currentModifier != null && eval.visible) {
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

  fun evaluateLayer(layer: AbmotionLayer, timeMs: Float): EvaluatedLayerState {
    val kfs = layer.keyframes
    // Trim trailing empty pad keyframes that exist after easeType==0 hold keyframes
    var lastRealIdx = kfs.indexOfLast { it.kfType != 0 || it.easeType != 0 }
    if (lastRealIdx < 0) lastRealIdx = 0
    // Include one immediate destination keyframe after an easeType > 0 keyframe (even if its
    // kfType==0)
    if (lastRealIdx + 1 < kfs.size && kfs[lastRealIdx].easeType != 0) {
      lastRealIdx += 1
    }
    val activeKfs = kfs.subList(0, (lastRealIdx + 1).coerceAtMost(kfs.size))

    val first = activeKfs.first()
    if (timeMs < first.timeMs) {
      return EvaluatedLayerState(
        layerName = layer.name,
        visible = false,
        kfType = first.kfType,
        imageIndex = first.imageIndex,
        maskIndex = first.maskIndex,
        childTimeMs = first.childStartMs.toFloat(),
        x = first.x,
        y = first.y,
        scaleXPercent = first.scaleX,
        scaleYPercent = first.scaleY,
        zPriority = first.zPriority,
        angleDeg = first.angleDeg,
        tintR = first.tintR,
        tintG = first.tintG,
        tintB = first.tintB,
        alpha = 0f,
        blendMode = first.blendMode,
        pivotX = first.pivotX,
        pivotY = first.pivotY,
      )
    }

    // Find current keyframe segment
    var idx = 0
    while (idx + 1 < activeKfs.size && timeMs >= activeKfs[idx + 1].timeMs) {
      idx++
    }

    val k0 = activeKfs[idx]
    if (idx + 1 >= activeKfs.size || k0.easeType == 0) {
      // Hold at k0 (unless k0 itself is a pure terminator with kfType==0 and preceding key had
      // ease==0)
      return EvaluatedLayerState(
        layerName = layer.name,
        visible = k0.alpha > 0.001f,
        kfType = k0.kfType,
        imageIndex = k0.imageIndex,
        maskIndex = k0.maskIndex,
        childTimeMs = k0.childStartMs.toFloat(),
        x = k0.x,
        y = k0.y,
        scaleXPercent = k0.scaleX,
        scaleYPercent = k0.scaleY,
        zPriority = k0.zPriority,
        angleDeg = k0.angleDeg,
        tintR = k0.tintR,
        tintG = k0.tintG,
        tintB = k0.tintB,
        alpha = k0.alpha,
        blendMode = k0.blendMode,
        pivotX = k0.pivotX,
        pivotY = k0.pivotY,
      )
    }

    val k1 = activeKfs[idx + 1]
    val span = (k1.timeMs - k0.timeMs).toFloat()
    val rawT = if (span <= 0.001f) 1f else ((timeMs - k0.timeMs) / span).coerceIn(0f, 1f)
    val easedT = applyEasing(rawT, k0.easeType, k0.easeParam)

    val alpha = lerp(k0.alpha, k1.alpha, easedT)
    return EvaluatedLayerState(
      layerName = layer.name,
      visible = alpha > 0.001f,
      kfType = if (k0.kfType != 0) k0.kfType else k1.kfType,
      imageIndex = k0.imageIndex,
      maskIndex = k0.maskIndex,
      childTimeMs = lerp(k0.childStartMs.toFloat(), k1.childStartMs.toFloat(), rawT),
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
