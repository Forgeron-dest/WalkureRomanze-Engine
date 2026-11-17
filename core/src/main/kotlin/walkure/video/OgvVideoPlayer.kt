package walkure.video

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.utils.Disposable
import walkure.gdx.QlieAudioManager
import walkure.pack.QlieVirtualFileSystem
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-Time Ogg Theora + Vorbis (`.ogv`) Video Streamer for LibGDX OpenGL.
 *
 * Plays:
 * - `スクリプト\logo\ricotta_logo.ogv` (`data7.pack`)
 * - `GameData/movie/VR_OP.ogv` (`シナリオ\op_movie.s`)
 * - `GameData/movie/VR_ED_MIO.ogv`, `VR_ED_Noel.ogv`, `VR_ED_Risa.ogv`, `VR_ED_Sui.ogv` (`シナリオ\ed_movie.s`)
 *
 * Demuxes the embedded Vorbis audio stream (`-vn -c:a copy`) into a cached `.ogg` file played via
 * [QlieAudioManager.playExternalOggFile] while streaming 30fps RGBA video frames into an OpenGL [Texture].
 */
class OgvVideoPlayer(
    private val vfs: QlieVirtualFileSystem,
    private val audioManager: QlieAudioManager
) : VideoPlayerPlatform {

    companion object {
        const val STREAM_WIDTH = 640
        const val STREAM_HEIGHT = 480
        const val FRAME_BYTES = STREAM_WIDTH * STREAM_HEIGHT * 4
    }

    override val isExternalOverlay: Boolean get() = false

    private var videoProcess: Process? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var latestFrameBytes: ByteArray? = null

    @Volatile
    private var frameDirty = false

    private var framePixmap: Pixmap? = null
    var videoTexture: Texture? = null
        private set

    override var activeMovieName: String? = null
        private set

    override fun isPlaying(): Boolean = running.get()

    override fun startMovie(movieNameOrPath: String, onComplete: () -> Unit): Boolean {
        stop()
        val ogvFile = VideoPlayerPlatform.resolveMovieFile(vfs, movieNameOrPath) ?: return false
        activeMovieName = ogvFile.name

        // Extract and start the embedded Vorbis audio stream from the .ogv container
        startAccompanyingAudio(ogvFile)

        return runCatching {
            val pb = ProcessBuilder(
                "ffmpeg",
                "-loglevel", "quiet",
                "-re",
                "-i", ogvFile.absolutePath,
                "-map", "0:v:0",
                "-f", "rawvideo",
                "-pix_fmt", "rgba",
                "-s", "${STREAM_WIDTH}x${STREAM_HEIGHT}",
                "-r", "30",
                "pipe:1"
            )
            val proc = pb.start()
            videoProcess = proc
            running.set(true)

            val pixmap = Pixmap(STREAM_WIDTH, STREAM_HEIGHT, Pixmap.Format.RGBA8888)
            framePixmap = pixmap
            videoTexture = Texture(pixmap).apply {
                setFilter(TextureFilter.Linear, TextureFilter.Linear)
            }

            readerThread = Thread({
                readFramesLoop(proc.inputStream)
            }, "OgvFrameReader-${ogvFile.name}").apply {
                isDaemon = true
                start()
            }
            true
        }.getOrElse {
            running.set(false)
            false
        }
    }

    /**
     * Uploads the newest decoded RGBA frame to the OpenGL [videoTexture] on the main render thread.
     */
    override fun updateTexture(): Texture? {
        if (!running.get()) return null
        val frame = latestFrameBytes
        val pixmap = framePixmap
        val tex = videoTexture
        if (frameDirty && frame != null && pixmap != null && tex != null) {
            frameDirty = false
            val buf = pixmap.pixels
            buf.clear()
            buf.put(frame, 0, FRAME_BYTES)
            buf.flip()
            tex.draw(pixmap, 0, 0)
        }
        return videoTexture
    }

    override fun stop() {
        running.set(false)
        runCatching { videoProcess?.destroyForcibly() }
        videoProcess = null
        readerThread = null
        latestFrameBytes = null
        frameDirty = false
        activeMovieName = null
        audioManager.stopBgm()

        runCatching { videoTexture?.dispose() }
        videoTexture = null
        runCatching { framePixmap?.dispose() }
        framePixmap = null
    }

    private fun readFramesLoop(input: InputStream) {
        val buf = ByteArray(FRAME_BYTES)
        try {
            while (running.get()) {
                var off = 0
                while (off < FRAME_BYTES && running.get()) {
                    val r = input.read(buf, off, FRAME_BYTES - off)
                    if (r < 0) {
                        running.set(false)
                        return
                    }
                    off += r
                }
                if (off == FRAME_BYTES) {
                    latestFrameBytes = buf.copyOf()
                    frameDirty = true
                }
            }
        } catch (_: Exception) {
            running.set(false)
        }
    }

    /**
     * Remuxes the embedded Ogg Vorbis audio stream (`-vn -c:a copy`) from [ogvFile] into a temporary `.ogg`
     * file (~100ms copy, no re-encoding) so LibGDX OpenAL plays the exact vocal opening/ending song or logo chime.
     */
    private fun startAccompanyingAudio(ogvFile: File) {
        val cacheAudioFile = File(
            System.getProperty("java.io.tmpdir"),
            "walkure_movie_audio_${ogvFile.nameWithoutExtension}.ogg"
        )
        if (!cacheAudioFile.exists() || cacheAudioFile.length() < 1024L) {
            runCatching {
                val proc = ProcessBuilder(
                    "ffmpeg",
                    "-loglevel", "error",
                    "-y",
                    "-i", ogvFile.absolutePath,
                    "-vn",
                    "-map", "0:a:0?",
                    "-c:a", "copy",
                    cacheAudioFile.absolutePath
                ).start()
                proc.waitFor(5, TimeUnit.SECONDS)
            }
        }

        if (cacheAudioFile.exists() && cacheAudioFile.length() > 1024L) {
            audioManager.playExternalOggFile(cacheAudioFile, loop = false)
        } else {
            val nameLower = ogvFile.name.lowercase()
            if (nameLower.contains("vr_op") || nameLower.contains("vr_ed")) {
                audioManager.playBgm("音楽\\op_inst.ogg")
            }
        }
    }

    override fun dispose() {
        stop()
    }
}
