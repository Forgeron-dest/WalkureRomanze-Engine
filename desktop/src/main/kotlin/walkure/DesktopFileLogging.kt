package walkure

import java.io.BufferedOutputStream
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
    // The runtime tracer can emit hundreds of lines per second while skipping dialogue, and each
    // println used to force a synchronous file flush inside the very windows it was measuring. The
    // file side is now buffered; only the console is flushed per line.
    val bufferedLog = BufferedOutputStream(logOutput, 256 * 1024)
    bufferedLog.write(
      "=== Walkure Romanze runtime log started ${Instant.now()} ===\n"
        .toByteArray(Charset.defaultCharset()),
    )
    bufferedLog.flush()

    val lock = Any()
    val consoleCharset = Charset.defaultCharset()
    System.setOut(
      PrintStream(TeeOutputStream(System.out, bufferedLog, lock), true, consoleCharset),
    )
    System.setErr(
      PrintStream(TeeOutputStream(System.err, bufferedLog, lock), true, consoleCharset),
    )
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { bufferedLog.flush() } })
  }
}

private class TeeOutputStream(
  private val console: OutputStream,
  private val log: OutputStream,
  private val lock: Any,
) : OutputStream() {
  private var lastFileFlushMs = System.currentTimeMillis()

  override fun write(value: Int) {
    synchronized(lock) {
      console.write(value)
      log.write(value)
      maybeFlushFile()
    }
  }

  override fun write(bytes: ByteArray, offset: Int, length: Int) {
    synchronized(lock) {
      console.write(bytes, offset, length)
      log.write(bytes, offset, length)
      maybeFlushFile()
    }
  }

  /** Drains the file side at most a few times a second, so tracing stays affordable and live. */
  private fun maybeFlushFile() {
    val now = System.currentTimeMillis()
    if (now - lastFileFlushMs >= FLUSH_INTERVAL_MS) {
      lastFileFlushMs = now
      log.flush()
    }
  }

  override fun flush() {
    synchronized(lock) {
      // Flushing the console keeps output live; the buffered file side is drained periodically.
      console.flush()
    }
  }

  private companion object {
    const val FLUSH_INTERVAL_MS = 250L
  }
}
