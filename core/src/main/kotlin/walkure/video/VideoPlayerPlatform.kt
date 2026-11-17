package walkure.video

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import walkure.pack.QlieVirtualFileSystem
import java.io.File

/**
 * Cross-platform video playback interface for Walkure Romanze cutscenes (Startup Logo, Opening, Endings).
 */
interface VideoPlayerPlatform : Disposable {

    /**
     * True if the platform plays video externally (e.g. Android native [android.widget.VideoView] overlay).
     * If true, LibGDX will not enter [walkure.gdx.WalkureGdxGame.EngineMode.VIDEO] and will wait for [onComplete].
     */
    val isExternalOverlay: Boolean get() = false

    /**
     * Starts playback of a movie file.
     * [movieNameOrPath] can be a relative name (e.g. "VR_OP.ogv", "VR_OP.mp4", "スクリプト\logo\ricotta_logo.ogv").
     * [onComplete] must be invoked when playback finishes or is skipped.
     * Returns true if video playback successfully started, or false otherwise.
     */
    fun startMovie(movieNameOrPath: String, onComplete: () -> Unit = {}): Boolean

    /**
     * Updates and returns the OpenGL [Texture] containing the decoded frame (used when [isExternalOverlay] is false).
     */
    fun updateTexture(): Texture? = null

    /**
     * Returns true if a movie is actively playing.
     */
    fun isPlaying(): Boolean

    /**
     * Stops video playback immediately.
     */
    fun stop()

    /**
     * Name of the currently playing movie, if any.
     */
    val activeMovieName: String? get() = null

    override fun dispose() {}

    companion object {
        /**
         * Resolves a video file by prioritizing hardware-friendly `.mp4` and `.webm`,
         * then falling back to `.ogv` and VFS archives.
         */
        fun resolveMovieFile(vfs: QlieVirtualFileSystem, movieNameOrPath: String, extraSearchDirs: List<File> = emptyList()): File? {
            val cleanName = movieNameOrPath.substringAfterLast('\\').substringAfterLast('/')
            val stem = cleanName.substringBeforeLast('.')
            val extensions = listOf(".mp4", ".webm", ".ogv")

            // 1. Check extra directories (e.g. Android app files/cache, assets directory)
            for (dir in extraSearchDirs) {
                if (!dir.isDirectory) continue
                for (ext in extensions) {
                    val candidate = File(dir, "$stem$ext")
                    if (candidate.exists() && candidate.length() > 0) return candidate
                    dir.listFiles()?.firstOrNull { it.name.equals("$stem$ext", ignoreCase = true) }?.let {
                        if (it.length() > 0) return it
                    }
                }
            }

            // 2. Check in GameData/movie and movie/ directories
            val searchDirs = listOf(
                File(vfs.gameDir, "GameData/movie"),
                File(vfs.gameDir, "movie"),
                vfs.gameDir
            )
            for (movieDir in searchDirs) {
                if (!movieDir.isDirectory) continue
                for (ext in extensions) {
                    val candidate = File(movieDir, "$stem$ext")
                    if (candidate.exists() && candidate.length() > 0) return candidate
                    movieDir.listFiles()?.firstOrNull { it.name.equals("$stem$ext", ignoreCase = true) }?.let {
                        if (it.length() > 0) return it
                    }
                }
            }

            // 3. Direct file in gameDir
            for (ext in extensions) {
                val direct = File(vfs.gameDir, "$stem$ext")
                if (direct.exists() && direct.length() > 0) return direct
            }

            // 4. Check inside VFS (e.g. スクリプト\logo\ricotta_logo.ogv in data7.pack)
            val vfsEntry = vfs.resolveEntry(movieNameOrPath) ?: vfs.listEntries(".ogv").firstOrNull {
                it.normalizedPath.endsWith(cleanName.lowercase())
            }?.let { vfs.resolveEntry(it.path) }

            if (vfsEntry != null) {
                val tempFile = File(
                    System.getProperty("java.io.tmpdir"),
                    "walkure_${vfsEntry.second.path.substringAfterLast('\\')}"
                )
                if (!tempFile.exists() || tempFile.length() != vfsEntry.second.storedSize.toLong()) {
                    tempFile.writeBytes(vfs.readBytes(vfsEntry.second.path))
                }
                return tempFile
            }

            return null
        }
    }
}
