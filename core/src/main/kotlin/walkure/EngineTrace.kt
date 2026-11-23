package walkure

/**
 * Temporary runtime tracer used to locate the transition/render performance regression. Writes to
 * `System.out`, which both launchers tee into `walkure.log` (see `DesktopFileLogging` and
 * `AndroidRuntimeLogging`).
 *
 * Enabled by default while the investigation is open. Disable with `-Dwalkure.trace=off`; adjust
 * the slow-frame reporting threshold with `-Dwalkure.trace.slowFrameMs=25`.
 *
 * Per-frame nested-timeline logging is far more verbose (string building plus a synchronized log
 * write for every nested layer every frame, which visibly stutters animation playback), so it is a
 * separate opt-in flag: `-Dwalkure.trace.nested=on`.
 */
object EngineTrace {
  val enabled: Boolean = !System.getProperty("walkure.trace", "on").equals("off", ignoreCase = true)

  val nestedTraceEnabled: Boolean =
    System.getProperty("walkure.trace.nested", "off").equals("on", ignoreCase = true)

  private val slowFrameMs: Long =
    System.getProperty("walkure.trace.slowFrameMs", "25")?.toLongOrNull() ?: 25L

  private val startNanos: Long = System.nanoTime()
  private val lock = Any()

  /** Monotonic nanoseconds, paired with [msSince] to time individual calls. */
  fun nowNanos(): Long = System.nanoTime()

  /** Whole milliseconds elapsed since [sinceNanos]. */
  fun msSince(sinceNanos: Long): Long = (System.nanoTime() - sinceNanos) / 1_000_000L

  /** Milliseconds since process start, for timing work that happens before [elapsedMs] began. */
  fun msSinceProcessStart(): Long =
    System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().startTime

  /** Milliseconds since process start, for correlating lines across subsystems. */
  fun elapsedMs(): Long = (System.nanoTime() - startNanos) / 1_000_000L

  /** Frames slower than this are reported by the render loop. */
  fun slowFrameThresholdMs(): Long = slowFrameMs

  /** Identity of a spec instance, so a re-consumed (sticky) spec is visible in the log. */
  fun id(target: Any?): String =
    if (target == null) "null" else "@" + Integer.toHexString(System.identityHashCode(target))

  fun log(message: String) {
    if (!enabled) return
    synchronized(lock) { println("[trace +${elapsedMs()}ms] $message") }
  }
}
