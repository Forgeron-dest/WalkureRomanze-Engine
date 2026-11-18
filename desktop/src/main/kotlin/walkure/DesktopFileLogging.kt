package walkure

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.time.Instant

internal object DesktopFileLogging {
  fun install(logFile: File) {
    val parent = logFile.absoluteFile.parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw IOException("Could not create log directory: ${parent.absolutePath}")
    }

    val logOutput = FileOutputStream(logFile, false)
    logOutput.write(
      "=== Walkure Romanze runtime log started ${Instant.now()} ===\n"
        .toByteArray(Charset.defaultCharset()),
    )
    logOutput.flush()

    val lock = Any()
    System.setOut(
      PrintStream(TeeOutputStream(System.out, logOutput, lock), true, Charset.defaultCharset()),
    )
    System.setErr(
      PrintStream(TeeOutputStream(System.err, logOutput, lock), true, Charset.defaultCharset()),
    )
  }
}

private class TeeOutputStream(
  private val console: OutputStream,
  private val log: OutputStream,
  private val lock: Any,
) : OutputStream() {
  override fun write(value: Int) {
    synchronized(lock) {
      console.write(value)
      log.write(value)
    }
  }

  override fun write(bytes: ByteArray, offset: Int, length: Int) {
    synchronized(lock) {
      console.write(bytes, offset, length)
      log.write(bytes, offset, length)
    }
  }

  override fun flush() {
    synchronized(lock) {
      console.flush()
      log.flush()
    }
  }
}
