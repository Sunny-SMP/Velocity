package net.sunnysmp.proxy.sentry;

import io.sentry.ScopesAdapter;
import io.sentry.Sentry;
import io.sentry.SentryIntegrationPackageStorage;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryId;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.sunnysmp.proxy.config.SentrySettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Owns the lifecycle of the Sentry SDK and of the {@link SentryAppender} attached to the root
 * logger.
 *
 * <p>Configuration comes from the {@code [sentry]} section of {@code sunny.toml}; see
 * {@link SentrySettings}.</p>
 */
public final class SentryManager {

  private static final Logger logger = LogManager.getLogger(SentryManager.class);

  private static final String APPENDER_NAME = "SunnySentry";
  private static final long SHUTDOWN_FLUSH_MILLIS = TimeUnit.SECONDS.toMillis(5);

  private static final long POLL_INTERVAL_MILLIS = 50;

  private static final SentrySettings DISABLED = new SentrySettings(null, null, null, Map.of());

  private static volatile @Nullable SentryAppender appender;
  private static volatile SentrySettings settings = DISABLED;

  private SentryManager() {
    throw new AssertionError();
  }

  /**
   * Initializes Sentry from the {@code [sentry]} section of {@code sunny.toml} and attaches the
   * appender to the root logger.
   *
   * <p>A blank or missing DSN disables Sentry entirely: the SDK is a no-op and no appender is
   * installed, so the proxy runs exactly as it does without this.</p>
   *
   * <p>Anything the config doesn't cover is left at the SDK's defaults. External configuration
   * stays enabled, so {@code SENTRY_*} environment variables can still fill in the rest.</p>
   *
   * @param configured the settings to start with
   */
  public static synchronized void start(final SentrySettings configured) {
    if (appender != null) {
      logger.warn("Sentry is already running; ignoring duplicate start request.");
      return;
    }
    if (!configured.isEnabled()) {
      return;
    }

    Sentry.init((SentryOptions options) -> {
      options.setDsn(configured.dsn());
      options.setEnvironment(configured.environment());
      options.setEnableExternalConfiguration(true);
      if (configured.serverName() != null) {
        options.setServerName(configured.serverName());
        options.setAttachServerName(false);
      }
      configured.tags().forEach(options::setTag);
      options.setSendDefaultPii(false);
      options.addInAppInclude("com.velocitypowered");
      options.addInAppInclude("net.sunnysmp.proxy");
    });

    SentryIntegrationPackageStorage.getInstance().addIntegration("Log4j2");
    settings = configured;

    appender = installAppender();
    logger.info("Sentry enabled (environment: {}, server: {}, tags: {}).",
        configured.environment() == null ? "unset" : configured.environment(),
        configured.serverName() == null ? "host name" : configured.serverName(),
        configured.tags().isEmpty() ? "none" : configured.tags().keySet());
  }

  /**
   * Applies a freshly read configuration, restarting Sentry only when something actually changed.
   *
   * <p>Called from the proxy's reload, so the {@code [sentry]} section of {@code sunny.toml}
   * behaves like the rest of the configuration: adding a DSN switches reporting on, removing it
   * switches it off, and changing the environment, server name or tags takes effect without a
   * restart.</p>
   *
   * <p>Identical settings are left alone, so a reload that doesn't touch the section never drops
   * events that are still queued.</p>
   *
   * @param configured the settings just read from disk
   */
  public static synchronized void reload(final SentrySettings configured) {
    if (!configured.isEnabled()) {
      if (appender != null) {
        stop();
        logger.info("Sentry disabled; the configuration no longer has a DSN.");
      }
      return;
    }

    if (appender != null) {
      if (configured.equals(settings)) {
        return;
      }
      stop();
    }
    start(configured);
  }

  /**
   * Detaches the appender and shuts the SDK down, flushing anything still queued.
   *
   * <p>Safe to call when Sentry was never started. Call this after the logger context has been
   * shut down, so that events still queued by the async loggers are appended first.</p>
   */
  public static synchronized void stop() {
    final SentryAppender current = appender;
    if (current == null) {
      return;
    }
    appender = null;
    settings = DISABLED;

    final LoggerContext context = (LoggerContext) LogManager.getContext(false);
    final Configuration configuration = context.getConfiguration();
    configuration.getRootLogger().removeAppender(APPENDER_NAME);
    context.updateLoggers();
    current.stop();

    Sentry.flush(SHUTDOWN_FLUSH_MILLIS);
    Sentry.close();
  }

  /**
   * The settings Sentry was started with, for {@code /sentry status}.
   *
   * @return the settings, all-null when Sentry isn't running
   */
  public static SentrySettings settings() {
    return settings;
  }

  /**
   * The id of the most recently captured event.
   *
   * @return the last event id, or {@link SentryId#EMPTY_ID} if nothing has been captured
   */
  public static SentryId lastEventId() {
    return Sentry.getLastEventId();
  }

  /**
   * Waits for a newly logged event to be captured and sent.
   *
   * <p>The proxy runs Log4j2 with async loggers, so a log call returns long before the appender
   * sees it. Callers that need to report on a specific event have to wait for the id to change
   * rather than reading it straight after logging.</p>
   *
   * <p>Blocks for up to {@code timeoutMillis}, so call it off the calling thread.</p>
   *
   * @param previous      the last event id observed before the log call
   * @param timeoutMillis how long to wait for the event to appear
   * @return the new event id, or {@link SentryId#EMPTY_ID} if nothing was captured in time
   */
  public static SentryId awaitEvent(final SentryId previous, final long timeoutMillis) {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    SentryId current = Sentry.getLastEventId();
    while (current.equals(previous) && System.nanoTime() < deadline) {
      try {
        Thread.sleep(POLL_INTERVAL_MILLIS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return SentryId.EMPTY_ID;
      }
      current = Sentry.getLastEventId();
    }

    if (current.equals(previous)) {
      return SentryId.EMPTY_ID;
    }
    Sentry.flush(timeoutMillis);
    return current;
  }

  /**
   * Whether Sentry is currently running and capturing events.
   *
   * @return {@code true} if the appender is installed
   */
  public static boolean isEnabled() {
    return appender != null;
  }

  private static SentryAppender installAppender() {
    final LoggerContext context = (LoggerContext) LogManager.getContext(false);
    final Configuration configuration = context.getConfiguration();

    final SentryAppender created = new SentryAppender(
        APPENDER_NAME, null, null,
        null, ScopesAdapter.getInstance());
    created.start();
    configuration.addAppender(created);
    configuration.getRootLogger().addAppender(created, null, null);
    context.updateLoggers();
    return created;
  }
}
