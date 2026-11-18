package walkure.android

import android.content.Context
import com.badlogic.gdx.ApplicationLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AndroidRuntimeLogging
private constructor(
  private val logFile: File,
  private val logOutput: FileOutputStream,
  private val lock: Any,
  private val originalOut: PrintStream,
  private val originalErr: PrintStream,
) : AutoCloseable {
  private val redirectedOut =
    PrintStream(TeeOutputStream(originalOut, logOutput, lock), true, "UTF-8")
  private val redirectedErr =
    PrintStream(TeeOutputStream(originalErr, logOutput, lock), true, "UTF-8")
  private val originalUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
  private val loggingUncaughtExceptionHandler =
    Thread.UncaughtExceptionHandler { thread, throwable ->
      try {
        writeLog("FATAL", thread.name, "Uncaught exception", throwable)
      } catch (exception: IOException) {
        exception.printStackTrace(redirectedErr)
      }
      originalUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        ?: throwable.printStackTrace(redirectedErr)
    }

  private var originalApplicationLogger: ApplicationLogger? = null
  private var fileApplicationLogger: ApplicationLogger? = null

  init {
    val header = "=== Walkure Romanze Android runtime log: $logFile ===\n"
    synchronized(lock) {
      logOutput.write(header.toByteArray(StandardCharsets.UTF_8))
      logOutput.flush()
    }
    System.setOut(redirectedOut)
    System.setErr(redirectedErr)
    Thread.setDefaultUncaughtExceptionHandler(loggingUncaughtExceptionHandler)
  }

  fun wrapApplicationLogger(delegate: ApplicationLogger): ApplicationLogger {
    if (delegate === fileApplicationLogger) return delegate
    val fileLogger = FileApplicationLogger(delegate)
    originalApplicationLogger = delegate
    fileApplicationLogger = fileLogger
    return fileLogger
  }

  fun originalApplicationLogger(): ApplicationLogger? = originalApplicationLogger

  override fun close() {
    if (System.out === redirectedOut) System.setOut(originalOut)
    if (System.err === redirectedErr) System.setErr(originalErr)
    if (Thread.getDefaultUncaughtExceptionHandler() === loggingUncaughtExceptionHandler) {
      Thread.setDefaultUncaughtExceptionHandler(originalUncaughtExceptionHandler)
    }

    synchronized(lock) {
      redirectedOut.flush()
      redirectedErr.flush()
      logOutput.flush()
      logOutput.close()
    }
  }

  private inner class FileApplicationLogger(
    private val delegate: ApplicationLogger,
  ) : ApplicationLogger {
    override fun log(tag: String, message: String) {
      delegate.log(tag, message)
      writeLog("INFO", tag, message)
    }

    override fun log(tag: String, message: String, exception: Throwable) {
      delegate.log(tag, message, exception)
      writeLog("INFO", tag, message, exception)
    }

    override fun error(tag: String, message: String) {
      delegate.error(tag, message)
      writeLog("ERROR", tag, message)
    }

    override fun error(tag: String, message: String, exception: Throwable) {
      delegate.error(tag, message, exception)
      writeLog("ERROR", tag, message, exception)
    }

    override fun debug(tag: String, message: String) {
      delegate.debug(tag, message)
      writeLog("DEBUG", tag, message)
    }

    override fun debug(tag: String, message: String, exception: Throwable) {
      delegate.debug(tag, message, exception)
      writeLog("DEBUG", tag, message, exception)
    }
  }

  private fun writeLog(level: String, tag: String, message: String, exception: Throwable? = null) {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.ROOT).format(Date())
    val record = StringBuilder("$timestamp [$level] [$tag] $message\n")
    if (exception != null) {
      val stackTrace = StringWriter()
      exception.printStackTrace(PrintWriter(stackTrace))
      record.append(stackTrace)
    }

    synchronized(lock) {
      logOutput.write(record.toString().toByteArray(StandardCharsets.UTF_8))
      logOutput.flush()
    }
  }

  companion object {
    fun install(context: Context): AndroidRuntimeLogging {
      val directory = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
      if (!directory.exists() && !directory.mkdirs()) {
        throw IOException("Could not create Android log directory: ${directory.absolutePath}")
      }

      val logFile = File(directory, "walkure.log")
      val logOutput = FileOutputStream(logFile, false)
      return AndroidRuntimeLogging(
        logFile = logFile,
        logOutput = logOutput,
        lock = Any(),
        originalOut = System.out,
        originalErr = System.err,
      )
    }
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
