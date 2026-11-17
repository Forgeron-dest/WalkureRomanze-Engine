package walkure.android

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import com.badlogic.gdx.Gdx
import walkure.pack.QlieVirtualFileSystem
import walkure.video.VideoPlayerPlatform
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android Hardware-Accelerated Video Player.
 *
 * Overlays a full-screen [VideoView] on top of the LibGDX [AndroidGraphics] OpenGL surface
 * during cutscene playback (Logo, Opening, Heroine Endings).
 *
 * Features:
 * - Direct zero-CPU hardware decoding via Android MediaCodec / MediaPlayer (supports H.264/AAC .mp4 and VP8/VP9 .webm).
 * - Instant tap-to-skip gesture anywhere on the screen.
 * - Extracts bundled asset videos (e.g. `ricotta_logo.mp4`) to cache automatically.
 * - Seamlessly detaches and resumes LibGDX on completion or error with zero dropped frames.
 */
class AndroidVideoPlayer(
    private val activity: Activity,
    private val container: FrameLayout,
    private val vfs: QlieVirtualFileSystem
) : VideoPlayerPlatform {

    override val isExternalOverlay: Boolean get() = true

    private val isMoviePlaying = AtomicBoolean(false)
    private var currentVideoView: VideoView? = null
    private var currentCallback: (() -> Unit)? = null

    override var activeMovieName: String? = null
        private set

    override fun isPlaying(): Boolean = isMoviePlaying.get()

    override fun startMovie(movieNameOrPath: String, onComplete: () -> Unit): Boolean {
        val movieFile = resolveVideoFile(movieNameOrPath)
        if (movieFile == null || !movieFile.exists() || movieFile.length() == 0L) {
            Gdx.app.postRunnable { onComplete() }
            return false
        }

        activeMovieName = movieFile.name
        currentCallback = onComplete

        activity.runOnUiThread {
            try {
                // Ensure any previous video is cleared
                currentVideoView?.let { vv ->
                    runCatching {
                        vv.stopPlayback()
                        container.removeView(vv)
                    }
                }

                // Set black backdrop on parent container to pillarbox/letterbox video without obscuring SurfaceView
                container.setBackgroundColor(Color.BLACK)

                val vv = VideoView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                    // Note: Do NOT call setBackgroundColor on VideoView itself;
                    // as a SurfaceView subclass, setBackgroundColor paints an opaque canvas
                    // over the underlying hardware decoding Surface, causing the video to appear completely black!
                }
                currentVideoView = vv
                isMoviePlaying.set(true)

                fun finishAndDismiss() {
                    if (!isMoviePlaying.compareAndSet(true, false)) return
                    activeMovieName = null
                    val cb = currentCallback
                    currentCallback = null

                    activity.runOnUiThread {
                        runCatching {
                            vv.stopPlayback()
                            container.removeView(vv)
                            container.setBackgroundColor(Color.TRANSPARENT)
                        }
                        if (currentVideoView === vv) {
                            currentVideoView = null
                        }
                    }

                    Gdx.app.postRunnable { cb?.invoke() }
                }

                vv.setOnCompletionListener {
                    finishAndDismiss()
                }

                vv.setOnErrorListener { _, _, _ ->
                    finishAndDismiss()
                    true
                }

                vv.setOnClickListener {
                    finishAndDismiss()
                }

                // Tap to skip
                vv.setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        v.performClick()
                        finishAndDismiss()
                        true
                    } else {
                        true
                    }
                }

                container.addView(vv)
                vv.setVideoPath(movieFile.absolutePath)
                vv.start()
            } catch (_: Exception) {
                isMoviePlaying.set(false)
                val cb = currentCallback
                currentCallback = null
                Gdx.app.postRunnable { cb?.invoke() }
            }
        }

        return true
    }

    override fun stop() {
        if (!isMoviePlaying.compareAndSet(true, false)) return
        activeMovieName = null
        val cb = currentCallback
        currentCallback = null

        activity.runOnUiThread {
            currentVideoView?.let { vv ->
                runCatching {
                    vv.stopPlayback()
                    container.removeView(vv)
                    container.setBackgroundColor(Color.TRANSPARENT)
                }
            }
            currentVideoView = null
        }

        if (cb != null) {
            Gdx.app.postRunnable { cb.invoke() }
        }
    }

    override fun dispose() {
        stop()
    }

    private fun resolveVideoFile(movieNameOrPath: String): File? {
        val cleanName = movieNameOrPath.substringAfterLast('\\').substringAfterLast('/')
        val stem = cleanName.substringBeforeLast('.')

        // 1. Check if bundled in APK assets (e.g. ricotta_logo.mp4)
        if (cleanName.contains("logo", ignoreCase = true)) {
            val cachedAsset = extractAssetVideoIfPresent("$stem.mp4")
                ?: extractAssetVideoIfPresent("$stem.ogv")
            if (cachedAsset != null && cachedAsset.exists() && cachedAsset.length() > 0) {
                return cachedAsset
            }
        }

        // 2. Search game directory and external app directories
        val extraDirs = listOfNotNull(
            activity.getExternalFilesDir(null),
            File(activity.getExternalFilesDir(null) ?: activity.filesDir, "GameData/movie"),
            File(activity.getExternalFilesDir(null) ?: activity.filesDir, "movie"),
            activity.filesDir,
            activity.cacheDir
        )

        return VideoPlayerPlatform.resolveMovieFile(vfs, movieNameOrPath, extraDirs)
    }

    private fun extractAssetVideoIfPresent(assetName: String): File? {
        val target = File(activity.cacheDir, assetName)
        if (target.exists() && target.length() > 0) return target

        return runCatching {
            activity.assets.open(assetName).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target
        }.getOrNull()
    }
}
