package walkure.gdx

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.utils.Disposable
import walkure.EngineTrace
import walkure.image.AbmotionParser
import walkure.image.AbmpDecoder
import walkure.image.ArgbDecoder
import walkure.image.DpngDecoder
import walkure.pack.QlieVirtualFileSystem

/**
 * Normalizes a shape-only (near-black) icon strip into a white alpha mask, in place. Some button
 * icon strips (`button_quit.b`, `button_cancel.b`) carry black art, which no multiply tint can ever
 * colorize, so the buttons using them would animate in black. Colored strips are returned
 * untouched. Returns whether the pixmap was whitened.
 */
fun whitenBlackMaskInPlace(pixmap: Pixmap): Boolean {
  var hasOpaque = false
  var allBlack = true
  for (y in 0 until pixmap.height) {
    for (x in 0 until pixmap.width) {
      val p = pixmap.getPixel(x, y)
      if ((p and 0xFF) > 128) {
        hasOpaque = true
        val maxChannel = maxOf((p ushr 24) and 0xFF, (p ushr 16) and 0xFF, (p ushr 8) and 0xFF)
        if (maxChannel > 16) {
          allBlack = false
        }
      }
    }
  }
  if (!hasOpaque || !allBlack) return false
  val prevBlending = pixmap.blending
  pixmap.blending = Pixmap.Blending.None
  for (y in 0 until pixmap.height) {
    for (x in 0 until pixmap.width) {
      val alpha = pixmap.getPixel(x, y) and 0xFF
      pixmap.drawPixel(x, y, (0xFFFFFF shl 8) or alpha)
    }
  }
  pixmap.blending = prevBlending
  return true
}

/**
 * Decodes QLiE `.argb` (`ARGBSaveData1`), `.b` (`abmp10`/`11`/`12`), `.jpg`, and `.png` assets from
 * [QlieVirtualFileSystem] directly into hardware-accelerated OpenGL [Texture]s via LibGDX [Pixmap]
 * (with zero `java.awt` dependency so it works identically on Desktop and Android).
 */
class QlieGdxTextureLoader(
  private val vfs: QlieVirtualFileSystem,
  private val maxCacheEntries: Int = 512,
) : Disposable {

  private val cache =
    object : LinkedHashMap<String, Texture>(maxCacheEntries, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Texture>?): Boolean {
        if (size > maxCacheEntries && eldest != null) {
          if (EngineTrace.enabled) {
            EngineTrace.log("TEXTURE evict+dispose key=${eldest.key} (cache was $size entries)")
          }
          runCatching { eldest.value.dispose() }
          return true
        }
        return false
      }
    }

  /**
   * Textures whose holders keep long-lived references (title button tiles, icon strips, and
   * labels). Evicting and disposing one of those would leave its holder with a dead GL handle that
   * renders black, so they bypass the evicting LRU and live until [dispose].
   */
  private val pinnedCache = LinkedHashMap<String, Texture>()

  fun loadTexture(pathOrQuery: String): Texture? {
    val resolvedEntry = vfs.resolveEntry(pathOrQuery)?.second ?: return null
    if (resolvedEntry.normalizedPath.endsWith(".b")) {
      return loadCompositedAbmpTexture(resolvedEntry.path)
    }
    val key = resolvedEntry.normalizedPath
    cache[key]?.let {
      return it
    }

    val rawBytes = runCatching { vfs.readBytes(resolvedEntry.path) }.getOrNull() ?: return null

    val texture = runCatching {
      when {
        ArgbDecoder.isArgbData(rawBytes) -> {
          val pixmap = ArgbDecoder.decodePixmap(rawBytes)
          Texture(pixmap).also { tex ->
            tex.setFilter(TextureFilter.Linear, TextureFilter.Linear)
            pixmap.dispose()
          }
        }

        AbmpDecoder.isAbmpData(rawBytes) -> {
          val container = AbmpDecoder.parse(rawBytes)
          val pixmap =
            container.allFlattenedImages().firstNotNullOfOrNull { it.decodePixmap() }
              ?: return@runCatching null
          Texture(pixmap).also { tex ->
            tex.setFilter(TextureFilter.Linear, TextureFilter.Linear)
            pixmap.dispose()
          }
        }

        AbmpDecoder.isDpngData(rawBytes) -> {
          val pixmap = DpngDecoder.decodePixmap(rawBytes) ?: return@runCatching null
          Texture(pixmap).also { tex ->
            tex.setFilter(TextureFilter.Linear, TextureFilter.Linear)
            pixmap.dispose()
          }
        }

        else -> {
          val pixmap = Pixmap(rawBytes, 0, rawBytes.size)
          Texture(pixmap).also { tex ->
            tex.setFilter(TextureFilter.Linear, TextureFilter.Linear)
            pixmap.dispose()
          }
        }
      }
    }
      .getOrNull()

    if (texture != null) {
      cache[key] = texture
    }
    return texture
  }

  /**
   * Loads a specific layer index (e.g. `0` = normal, `1` = hover, `2` = pressed) from an `abmp`
   * `.b` file.
   */
  fun loadAbmpLayerTexture(pathOrQuery: String, layerIndex: Int): Texture? {
    val resolvedEntry = vfs.resolveEntry(pathOrQuery)?.second ?: return null
    val key = "${resolvedEntry.normalizedPath}#layer$layerIndex"
    cache[key]?.let {
      return it
    }

    val rawBytes = runCatching { vfs.readBytes(resolvedEntry.path) }.getOrNull() ?: return null
    if (!AbmpDecoder.isAbmpData(rawBytes)) return loadTexture(pathOrQuery)

    val texture = runCatching {
      val container = AbmpDecoder.parse(rawBytes)
      val layers = container.allFlattenedImages()
      val layer = layers.getOrNull(layerIndex) ?: return@runCatching null
      val pixmap = layer.decodePixmap() ?: return@runCatching null
      Texture(pixmap).also { tex ->
        tex.setFilter(TextureFilter.Linear, TextureFilter.Linear)
        pixmap.dispose()
      }
    }
      .getOrNull()

    if (texture != null) {
      cache[key] = texture
    }
    return texture
  }

  /**
   * Loads a named layer (case-insensitive) from an `abmp` `.b` container (such as
   * `スクリプト\title\back.b` or `MessageWindow.b`).
   */
  fun loadAbmpLayerByName(pathOrQuery: String, layerName: String): Texture? {
    val resolvedEntry = vfs.resolveEntry(pathOrQuery)?.second ?: return null
    val key = "${resolvedEntry.normalizedPath}#name:${layerName.lowercase()}"
    cache[key]?.let {
      return it
    }

    val rawBytes = runCatching { vfs.readBytes(resolvedEntry.path) }.getOrNull() ?: return null
    if (!AbmpDecoder.isAbmpData(rawBytes)) return null

    val texture = runCatching {
      val container = AbmpDecoder.parse(rawBytes)
      val layers = container.allFlattenedImages()
      val layer =
        layers.firstOrNull { it.name.equals(layerName, ignoreCase = true) }
          ?: layers.firstOrNull { it.name.contains(layerName, ignoreCase = true) }
          ?: return@runCatching null
      val pixmap = layer.decodePixmap() ?: return@runCatching null
      Texture(pixmap).also { tex ->
        tex.setFilter(TextureFilter.Linear, TextureFilter.Linear)
        pixmap.dispose()
      }
    }
      .getOrNull()

    if (texture != null) {
      cache[key] = texture
    }
    return texture
  }

  /**
   * Composites a multi-layer cut-in `.b` file (`mio401_1.b`, `sd_美桜お茶会a.b`, `ササミカットイン_a.b`, `黒帯.b`)
   * by drawing background/underlay layers (`*下`, `*u`, `*背景`) first, then main illustration layers,
   * and finally frame layers (`*枠`) centered onto a single [Texture].
   */
  fun loadCompositedAbmpTexture(pathOrQuery: String): Texture? {
    val resolvedEntry = vfs.resolveEntry(pathOrQuery)?.second ?: return null
    val key = "${resolvedEntry.normalizedPath}#composited"
    cache[key]?.let {
      return it
    }

    val rawBytes = runCatching { vfs.readBytes(resolvedEntry.path) }.getOrNull() ?: return null
    if (!AbmpDecoder.isAbmpData(rawBytes)) return loadTexture(pathOrQuery)

    val texture = runCatching {
      val container = AbmpDecoder.parse(rawBytes)
      val decoded =
        container.allFlattenedImages().mapNotNull { layer ->
          val pm = layer.decodePixmap() ?: return@mapNotNull null
          layer.name to pm
        }
      if (decoded.isEmpty()) return@runCatching null
      if (decoded.size == 1) {
        val single = decoded.first().second
        return@runCatching Texture(single).also { tex ->
          tex.setFilter(TextureFilter.Linear, TextureFilter.Linear)
          single.dispose()
        }
      }

      // Order layers from back to front:
      // 0 = underlay/border base (*下, *u, *背景)
      // 1 = main illustration
      // 2 = foreground frame (*枠)
      val ordered = decoded.sortedBy { (name, _) ->
        val lower = name.lowercase()
        when {
          lower.endsWith("下") || lower.endsWith("u") || lower.contains("背景") -> 0
          lower.contains("枠") -> 2
          else -> 1
        }
      }

      val maxW = ordered.maxOf { it.second.width }
      val maxH = ordered.maxOf { it.second.height }
      val canvas = Pixmap(maxW, maxH, Pixmap.Format.RGBA8888)
      canvas.blending = Pixmap.Blending.SourceOver

      for ((_, pm) in ordered) {
        val dx = (maxW - pm.width) / 2
        val dy = (maxH - pm.height) / 2
        canvas.drawPixmap(pm, dx, dy)
        pm.dispose()
      }

      Texture(canvas).also { tex ->
        tex.setFilter(TextureFilter.Linear, TextureFilter.Linear)
        canvas.dispose()
      }
    }
      .getOrNull()

    if (texture != null) {
      cache[key] = texture
    }
    return texture
  }

  /**
   * Loads rule transition mask texture from `フェード\` (`.bmp` or `.b`), normalizing single-channel
   * rule masks.
   */
  fun loadTransitionMaskTexture(maskNameOrSymbol: String): Texture? {
    val clean = maskNameOrSymbol.trim().trim('"')
    if (clean.startsWith("wfade", ignoreCase = true)) {
      // wfade is an eyecatch animation container, not a rule-wipe grayscale mask
      return null
    }
    val directPath =
      if (clean.endsWith(".bmp") || clean.endsWith(".b")) "フェード\\$clean" else "フェード\\$clean.b"
    val resolved =
      vfs.resolveEntry(directPath)?.second
        ?: vfs.resolveEntry("フェード\\$clean.bmp")?.second
        ?: vfs.listEntries("フェード\\").firstOrNull { it.normalizedPath.contains(clean.lowercase()) }
        ?: return null

    val key = "${resolved.normalizedPath}#mask"
    cache[key]?.let {
      return it
    }

    val rawBytes = runCatching { vfs.readBytes(resolved.path) }.getOrNull() ?: return null
    val texture = runCatching {
      if (AbmpDecoder.isAbmpData(rawBytes)) {
        val container = AbmpDecoder.parse(rawBytes)
        val largest =
          container.allFlattenedImages().maxByOrNull { it.payload.size } ?: return@runCatching null
        val pm = largest.decodePixmap() ?: return@runCatching null
        Texture(pm).also {
          it.setFilter(TextureFilter.Linear, TextureFilter.Linear)
          pm.dispose()
        }
      } else {
        val pm =
          if (AbmpDecoder.isDpngData(rawBytes)) DpngDecoder.decodePixmap(rawBytes)
          else Pixmap(rawBytes, 0, rawBytes.size)
        Texture(pm ?: return@runCatching null).also {
          it.setFilter(TextureFilter.Linear, TextureFilter.Linear)
          pm.dispose()
        }
      }
    }
      .getOrNull()

    if (texture != null) {
      cache[key] = texture
    }
    return texture
  }

  /**
   * Loads the largest layer from an `abmp` `.b` file (used for single-layer rule masks
   * `フェード\wfade*.b`).
   */
  fun loadLargestAbmpLayerTexture(pathOrQuery: String): Texture? {
    val resolvedEntry = vfs.resolveEntry(pathOrQuery)?.second ?: return null
    val key = "${resolvedEntry.normalizedPath}#largest"
    cache[key]?.let {
      return it
    }

    val rawBytes = runCatching { vfs.readBytes(resolvedEntry.path) }.getOrNull() ?: return null
    if (!AbmpDecoder.isAbmpData(rawBytes)) return loadTexture(pathOrQuery)

    val texture = runCatching {
      val container = AbmpDecoder.parse(rawBytes)
      val largestLayer =
        container.allFlattenedImages().maxByOrNull { it.payload.size } ?: return@runCatching null
      val pixmap = largestLayer.decodePixmap() ?: return@runCatching null
      Texture(pixmap).also { tex ->
        tex.setFilter(TextureFilter.Linear, TextureFilter.Linear)
        pixmap.dispose()
      }
    }
      .getOrNull()

    if (texture != null) {
      cache[key] = texture
    }
    return texture
  }

  class AbmpSlot(
    val layer: AbmpDecoder.AbmpImageLayer,
    val texture: Texture?,
    val childNode: AbmpRuntimeNode?,
  )

  class AbmpRuntimeNode(
    val container: AbmpDecoder.AbmpContainer,
    val document: AbmotionParser.AbdataDocument?,
    val slots: List<AbmpSlot>,
  )

  data class LoadedButtonAssets(
    val runtime: AbmpRuntimeNode,
    val normalTile: Texture?,
    val hoverTile: Texture?,
    val downTile: Texture?,
    val iconStrip: Texture?,
    val labelNormal: Texture?,
    val labelHover: Texture?,
    val mask: Texture?,
    val iconChildNode: AbmpRuntimeNode?,
  )

  private val runtimeCache = LinkedHashMap<String, AbmpRuntimeNode>(64, 0.75f, true)
  private val runtimeOwnedTextures = ArrayList<Texture>()

  fun loadAbmpRuntime(pathOrQuery: String): AbmpRuntimeNode? {
    val resolvedEntry = vfs.resolveEntry(pathOrQuery)?.second ?: return null
    runtimeCache[resolvedEntry.normalizedPath]?.let {
      return it
    }

    val rawBytes = runCatching { vfs.readBytes(resolvedEntry.path) }.getOrNull() ?: return null
    if (!AbmpDecoder.isAbmpData(rawBytes)) return null
    val container = runCatching { AbmpDecoder.parse(rawBytes) }.getOrNull() ?: return null

    fun buildNode(c: AbmpDecoder.AbmpContainer): AbmpRuntimeNode {
      val doc = AbmotionParser.parseAbdata(c.decompressedAbdataBytes)
      val builtSlots =
        c.images.map { img ->
          if (img.nestedAbmp != null) {
            AbmpSlot(img, null, buildNode(img.nestedAbmp))
          } else {
            val tex = runCatching {
              img.decodePixmap()?.let { px ->
                Texture(px).also { t ->
                  t.setFilter(TextureFilter.Linear, TextureFilter.Linear)
                  px.dispose()
                  runtimeOwnedTextures.add(t)
                }
              }
            }
              .getOrNull()
            AbmpSlot(img, tex, null)
          }
        }
      return AbmpRuntimeNode(c, doc, builtSlots)
    }

    val node = buildNode(container)
    runtimeCache[resolvedEntry.normalizedPath] = node
    return node
  }

  /**
   * Loads a specific layer index from an `abmp` `.b` file into the [pinnedCache] (never evicted).
   * Used for assets whose holders outlive a single frame. When [whitenBlackMask] is set, a
   * shape-only black layer is normalized to a white alpha mask so the caller's tint colorizes it
   * instead of multiplying down to black.
   */
  fun loadPinnedAbmpLayerTexture(
    pathOrQuery: String,
    layerIndex: Int,
    whitenBlackMask: Boolean = false,
  ): Texture? {
    val resolvedEntry = vfs.resolveEntry(pathOrQuery)?.second ?: return null
    val key = "${resolvedEntry.normalizedPath}#pinned-layer$layerIndex"
    pinnedCache[key]?.let {
      return it
    }

    val rawBytes = runCatching { vfs.readBytes(resolvedEntry.path) }.getOrNull() ?: return null
    if (!AbmpDecoder.isAbmpData(rawBytes)) return null

    val texture = runCatching {
      val container = AbmpDecoder.parse(rawBytes)
      val layers = container.allFlattenedImages()
      val layer = layers.getOrNull(layerIndex) ?: return@runCatching null
      val pixmap = layer.decodePixmap() ?: return@runCatching null
      if (whitenBlackMask) {
        whitenBlackMaskInPlace(pixmap)
      }
      Texture(pixmap).also { tex ->
        tex.setFilter(TextureFilter.Linear, TextureFilter.Linear)
        pixmap.dispose()
      }
    }
      .getOrNull()

    if (texture != null) {
      pinnedCache[key] = texture
    } else if (EngineTrace.enabled) {
      EngineTrace.log("TEXTURE pinned layer missing: ${resolvedEntry.path} index=$layerIndex")
    }
    return texture
  }

  /**
   * Resolves semantic button assets (`01/02/03` tiles, icon strip / child clip, `t01/t02` labels,
   * `Mask`) along with the parsed `abdata13` animation document. Layer textures are pinned: the
   * title screen holds them across frames, so LRU eviction must never dispose them.
   */
  fun loadButtonAssets(pathOrQuery: String): LoadedButtonAssets? {
    val resolvedEntry = vfs.resolveEntry(pathOrQuery)?.second ?: return null
    val runtime = loadAbmpRuntime(resolvedEntry.path) ?: return null
    val idx = AbmotionParser.resolveButtonLayerIndices(runtime.container)
    val iconChild =
      runtime.slots
        .firstOrNull {
          it.childNode != null &&
            (it.layer.name.contains("icon", ignoreCase = true) ||
              it.layer.name.contains("comb", ignoreCase = true))
        }
        ?.childNode

    return LoadedButtonAssets(
      runtime = runtime,
      normalTile = loadPinnedAbmpLayerTexture(resolvedEntry.path, idx.normalTileIndex),
      hoverTile = loadPinnedAbmpLayerTexture(resolvedEntry.path, idx.hoverTileIndex),
      downTile = loadPinnedAbmpLayerTexture(resolvedEntry.path, idx.downTileIndex),
      iconStrip =
        idx.iconStripIndex?.let {
          loadPinnedAbmpLayerTexture(resolvedEntry.path, it, whitenBlackMask = true)
        },
      labelNormal =
        idx.labelNormalIndex?.let { loadPinnedAbmpLayerTexture(resolvedEntry.path, it) },
      labelHover = idx.labelHoverIndex?.let { loadPinnedAbmpLayerTexture(resolvedEntry.path, it) },
      mask = idx.maskIndex?.let { loadPinnedAbmpLayerTexture(resolvedEntry.path, it) },
      iconChildNode = iconChild,
    )
  }

  override fun dispose() {
    cache.values.forEach { runCatching { it.dispose() } }
    cache.clear()
    pinnedCache.values.forEach { runCatching { it.dispose() } }
    pinnedCache.clear()
    runtimeOwnedTextures.forEach { runCatching { it.dispose() } }
    runtimeOwnedTextures.clear()
    runtimeCache.clear()
  }
}
