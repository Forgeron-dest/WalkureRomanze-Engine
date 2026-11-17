package walkure.gdx

import com.badlogic.gdx.Files.FileType
import com.badlogic.gdx.files.FileHandle
import walkure.pack.QlieVirtualFileSystem
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * In-memory LibGDX [FileHandle] backed directly by [QlieVirtualFileSystem].
 *
 * Allows LibGDX's OpenAL Ogg Vorbis / WAV audio decoders (`Gdx.audio.newMusic`, `Gdx.audio.newSound`)
 * and `Pixmap` / `Texture` loaders to stream decrypted & decompressed entries directly from
 * `GameData/data*.pack` with zero temporary files written to disk.
 */
class QlieVfsFileHandle(
    private val vfs: QlieVirtualFileSystem,
    val vfsPath: String
) : FileHandle(vfsPath.replace('\\', '/'), FileType.Internal) {

    private val cachedBytes: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        vfs.readBytes(vfsPath)
    }

    override fun read(): InputStream = ByteArrayInputStream(cachedBytes)

    override fun read(bufferSize: Int): BufferedInputStream =
        BufferedInputStream(read(), bufferSize)

    override fun readBytes(): ByteArray = cachedBytes.copyOf()

    override fun length(): Long = cachedBytes.size.toLong()

    override fun exists(): Boolean = vfs.exists(vfsPath)

    override fun name(): String = vfsPath.substringAfterLast('\\').substringAfterLast('/')

    override fun extension(): String = name().substringAfterLast('.', "").lowercase()

    override fun nameWithoutExtension(): String = name().substringBeforeLast('.')

    override fun path(): String = vfsPath.replace('\\', '/')

    override fun toString(): String = "qlie://$vfsPath"
}
