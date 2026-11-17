package walkure.gdx

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.utils.Disposable
import walkure.pack.QlieVirtualFileSystem
import java.io.File

/**
 * OpenAL Ogg Vorbis & WAV Audio Subsystem for *Walkure Romanze* via LibGDX.
 *
 * Manages three independent audio channels streamed directly from `GameData/data*.pack`:
 * 1. **BGM Channel (`音楽\bgm*.ogg` in `data1.pack`)**: Seamless looping background music.
 * 2. **Character Voice Channel (`ボイス\*.ogg` in `data1.pack`)**: Spoken dialogue voice acting.
 * 3. **Sound Effect Channel (`効果音\*.ogg` in `data3.pack` / `data4.pack`)**: One-shot or looping SFX (`loop=1`).
 */
class QlieAudioManager(private val vfs: QlieVirtualFileSystem) : Disposable {
    private var currentBgmPath: String? = null
    private var bgmMusic: Music? = null
    private var voiceMusic: Music? = null
    private var seMusic: Music? = null
    private var lastVoicePath: String? = null

    var isMuted: Boolean = false
        set(value) {
            field = value
            applyVolumes()
        }

    var bgmVolume: Float = 0.50f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolumes()
        }

    var voiceVolume: Float = 0.90f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolumes()
        }

    var seVolume: Float = 0.75f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolumes()
        }

    fun activeBgmName(): String? = currentBgmPath?.substringAfterLast('\\')?.substringAfterLast('/')

    fun isVoicePlaying(): Boolean = voiceMusic?.isPlaying == true

    private fun resolveAudioFileHandle(resolvedPath: String): FileHandle {
        if (Gdx.app?.type == Application.ApplicationType.Android) {
            val safeName = resolvedPath.replace('\\', '_').replace('/', '_')
            val cacheDir = File(vfs.gameDir, ".cache_audio").apply { mkdirs() }
            val cacheFile = File(cacheDir, safeName)
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                runCatching {
                    cacheFile.writeBytes(vfs.readBytes(resolvedPath))
                }
            }
            return Gdx.files.absolute(cacheFile.absolutePath)
        }
        return QlieVfsFileHandle(vfs, resolvedPath)
    }

    /**
     * Starts or switches the looping BGM track. If [vfsPath] is already playing, does nothing.
     */
    fun playBgm(vfsPath: String?) {
        if (vfsPath == null) {
            stopBgm()
            return
        }
        val resolved = vfs.resolveEntry(vfsPath)?.second?.path ?: return
        if (resolved.equals(currentBgmPath, ignoreCase = true) && bgmMusic?.isPlaying == true) {
            return
        }

        stopBgm()
        runCatching {
            val handle = resolveAudioFileHandle(resolved)
            val music = Gdx.audio.newMusic(handle)
            music.isLooping = true
            music.volume = if (isMuted) 0f else bgmVolume
            music.play()
            bgmMusic = music
            currentBgmPath = resolved
        }.onFailure { err ->
            Gdx.app?.error("QlieAudio", "Failed to play BGM $resolved: ${err.message}")
        }
    }

    /**
     * Plays an external `.ogg` audio file on the BGM channel (used for demuxed `.ogv` movie audio streams).
     */
    fun playExternalOggFile(file: File, loop: Boolean = false) {
        if (!file.exists()) return
        stopBgm()
        runCatching {
            val music = Gdx.audio.newMusic(FileHandle(file))
            music.isLooping = loop
            music.volume = if (isMuted) 0f else bgmVolume
            music.play()
            bgmMusic = music
            currentBgmPath = file.absolutePath
        }.onFailure { err ->
            Gdx.app?.error("QlieAudio", "Failed to play external OGG ${file.absolutePath}: ${err.message}")
        }
    }

    fun stopBgm() {
        runCatching { bgmMusic?.stop() }
        runCatching { bgmMusic?.dispose() }
        bgmMusic = null
        currentBgmPath = null
    }

    /**
     * Stops any currently playing voice line and plays the new `.ogg` character voice clip.
     */
    fun playVoice(vfsPath: String?) {
        stopVoice()
        if (vfsPath == null) return
        val resolved = vfs.resolveEntry(vfsPath)?.second?.path ?: return
        lastVoicePath = resolved

        runCatching {
            val handle = resolveAudioFileHandle(resolved)
            val music = Gdx.audio.newMusic(handle)
            music.isLooping = false
            music.volume = if (isMuted) 0f else voiceVolume
            music.play()
            voiceMusic = music
        }.onFailure { err ->
            Gdx.app?.error("QlieAudio", "Failed to play Voice $resolved: ${err.message}")
        }
    }

    fun replayLastVoice() {
        val path = lastVoicePath ?: return
        playVoice(path)
    }

    fun stopVoice() {
        runCatching { voiceMusic?.stop() }
        runCatching { voiceMusic?.dispose() }
        voiceMusic = null
    }

    /**
     * Plays a sound effect (`^se0` / `^se`), optionally looping when `loop=1` is specified in the script.
     */
    fun playSe(vfsPath: String?, loop: Boolean = false) {
        if (vfsPath == null) {
            stopSe()
            return
        }
        val resolved = vfs.resolveEntry(vfsPath)?.second?.path ?: return
        stopSe()

        runCatching {
            val handle = resolveAudioFileHandle(resolved)
            val music = Gdx.audio.newMusic(handle)
            music.isLooping = loop
            music.volume = if (isMuted) 0f else seVolume
            music.play()
            seMusic = music
        }.onFailure { err ->
            Gdx.app?.error("QlieAudio", "Failed to play SE $resolved: ${err.message}")
        }
    }

    fun stopSe() {
        runCatching { seMusic?.stop() }
        runCatching { seMusic?.dispose() }
        seMusic = null
    }

    /**
     * Stops all active voice and sound effect streams (e.g. when returning to the Title Screen or switching scenes).
     */
    fun stopAllVoiceAndSe() {
        stopVoice()
        stopSe()
    }

    private fun applyVolumes() {
        bgmMusic?.volume = if (isMuted) 0f else bgmVolume
        voiceMusic?.volume = if (isMuted) 0f else voiceVolume
        seMusic?.volume = if (isMuted) 0f else seVolume
    }

    override fun dispose() {
        stopBgm()
        stopVoice()
        stopSe()
    }
}
