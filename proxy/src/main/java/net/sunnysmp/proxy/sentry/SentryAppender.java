package net.sunnysmp.proxy.sentry;

import io.sentry.Breadcrumb;
import io.sentry.DateUtils;
import io.sentry.Hint;
import io.sentry.IScopes;
import io.sentry.ScopesAdapter;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.TypeCheckHint;
import io.sentry.exception.ExceptionMechanismException;
import io.sentry.protocol.Mechanism;
import io.sentry.protocol.Message;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Log4j2 appender that forwards log events to Sentry.
 *
 * <p>Events at or above {@code minimumEventLevel} are captured as Sentry events; everything at or
 * above {@code minimumBreadcrumbLevel} is recorded as a breadcrumb so that a captured event carries
 * the log lines that led up to it.</p>
 */
@Plugin(name = SentryAppender.PLUGIN_NAME, category = "Core", elementType = "appender",
    printObject = true)
public final class SentryAppender extends AbstractAppender {

  public static final String PLUGIN_NAME = "Sentry";

  /**
   * Reported to Sentry as the source of the synthesized exception attached to an event.
   */
  public static final String MECHANISM_TYPE = "VelocitySentryAppender";

  private static final Level DEFAULT_EVENT_LEVEL = Level.ERROR;
  private static final Level DEFAULT_BREADCRUMB_LEVEL = Level.INFO;

  /**
   * Logger name prefixes whose events are dropped outright.
   *
   * <p>The SDK's own diagnostics must never be fed back into the SDK, or a transport failure turns
   * into an unbounded capture loop.</p>
   */
  private static final List<String> IGNORED_LOGGER_PREFIXES = List.of("io.sentry", "net.sunnysmp.proxy.sentry");

  private final IScopes scopes;
  private final Level minimumEventLevel;
  private final Level minimumBreadcrumbLevel;

  /**
   * Creates an appender.
   *
   * @param name                   the appender name
   * @param filter                 the filter, if any, to apply before an event is converted
   * @param minimumEventLevel      the lowest level captured as a Sentry event
   * @param minimumBreadcrumbLevel the lowest level recorded as a breadcrumb
   * @param scopes                 the scopes to report through
   */
  public SentryAppender(final String name, final @Nullable Filter filter,
                        final @Nullable Level minimumEventLevel, final @Nullable Level minimumBreadcrumbLevel,
                        final IScopes scopes) {
    super(name, filter, null, true, Property.EMPTY_ARRAY);
    this.minimumEventLevel = Objects.requireNonNullElse(minimumEventLevel, DEFAULT_EVENT_LEVEL);
    this.minimumBreadcrumbLevel =
        Objects.requireNonNullElse(minimumBreadcrumbLevel, DEFAULT_BREADCRUMB_LEVEL);
    this.scopes = scopes;
  }

  /**
   * Creates an appender from a {@code log4j2.xml} declaration.
   *
   * <p>The proxy installs this appender programmatically, so this factory exists only so that the
   * appender can also be declared in a Log4j2 configuration file.</p>
   *
   * @param name                   the appender name
   * @param minimumEventLevel      the lowest level captured as a Sentry event
   * @param minimumBreadcrumbLevel the lowest level recorded as a breadcrumb
   * @param filter                 the filter, if any, to apply before an event is converted
   * @return the appender, or {@code null} if no name was given
   */
  @PluginFactory
  public static @Nullable SentryAppender createAppender(
      final @PluginAttribute("name") @Nullable String name,
      final @PluginAttribute("minimumEventLevel") @Nullable Level minimumEventLevel,
      final @PluginAttribute("minimumBreadcrumbLevel") @Nullable Level minimumBreadcrumbLevel,
      final @PluginElement("filter") @Nullable Filter filter) {
    if (name == null) {
      LOGGER.error("No name provided for SentryAppender");
      return null;
    }
    return new SentryAppender(name, filter, minimumEventLevel, minimumBreadcrumbLevel,
        ScopesAdapter.getInstance());
  }

  @Override
  public void append(final LogEvent event) {
    if (!scopes.isEnabled() || isIgnored(event.getLoggerName())) {
      return;
    }

    if (event.getLevel().isMoreSpecificThan(minimumEventLevel)) {
      final Hint hint = new Hint();
      hint.set(TypeCheckHint.SENTRY_SYNTHETIC_EXCEPTION, event);
      scopes.captureEvent(createEvent(event), hint);
    }

    if (event.getLevel().isMoreSpecificThan(minimumBreadcrumbLevel)) {
      final Hint hint = new Hint();
      hint.set(TypeCheckHint.LOG4J_LOG_EVENT, event);
      scopes.addBreadcrumb(createBreadcrumb(event), hint);
    }
  }

  private static boolean isIgnored(final @Nullable String loggerName) {
    if (loggerName == null) {
      return false;
    }
    for (final String prefix : IGNORED_LOGGER_PREFIXES) {
      if (loggerName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Converts a Log4j2 event into a Sentry event.
   *
   * @param logEvent the Log4j2 event
   * @return the Sentry event
   */
  private SentryEvent createEvent(final LogEvent logEvent) {
    final SentryEvent event = new SentryEvent(DateUtils.getDateTime(logEvent.getTimeMillis()));
    event.setLogger(logEvent.getLoggerName());
    event.setLevel(toSentryLevel(logEvent.getLevel()));

    final Message message = new Message();
    message.setMessage(logEvent.getMessage().getFormat());
    message.setFormatted(logEvent.getMessage().getFormattedMessage());
    message.setParams(toParams(logEvent.getMessage().getParameters()));
    event.setMessage(message);

    final Throwable thrown = logEvent.getThrown();
    if (thrown != null) {
      final Mechanism mechanism = new Mechanism();
      mechanism.setType(MECHANISM_TYPE);
      mechanism.setHandled(true);
      event.setThrowable(new ExceptionMechanismException(mechanism, thrown, Thread.currentThread()));
    }

    if (logEvent.getThreadName() != null) {
      event.setExtra("thread_name", logEvent.getThreadName());
    }
    if (logEvent.getMarker() != null) {
      event.setExtra("marker", logEvent.getMarker().toString());
    }

    final Map<String, String> contextData = logEvent.getContextData().toMap();
    if (!contextData.isEmpty()) {
      event.getContexts().put("Context Data", contextData);
    }

    return event;
  }

  /**
   * Converts a Log4j2 event into a Sentry breadcrumb.
   *
   * @param logEvent the Log4j2 event
   * @return the Sentry breadcrumb
   */
  private Breadcrumb createBreadcrumb(final LogEvent logEvent) {
    final Breadcrumb breadcrumb = new Breadcrumb(DateUtils.getDateTime(logEvent.getTimeMillis()));
    breadcrumb.setLevel(toSentryLevel(logEvent.getLevel()));
    breadcrumb.setCategory(logEvent.getLoggerName());
    breadcrumb.setMessage(logEvent.getMessage().getFormattedMessage());
    if (logEvent.getLoggerName() != null) {
      breadcrumb.setData("logger", logEvent.getLoggerName());
    }
    if (logEvent.getThreadName() != null) {
      breadcrumb.setData("thread_name", logEvent.getThreadName());
    }
    return breadcrumb;
  }

  private static List<String> toParams(final Object @Nullable [] arguments) {
    if (arguments == null) {
      return List.of();
    }
    return Arrays.stream(arguments)
        .filter(Objects::nonNull)
        .map(Object::toString)
        .toList();
  }

  /**
   * Maps a Log4j2 level onto the closest Sentry level.
   *
   * @param level the Log4j2 level
   * @return the Sentry level
   */
  private static SentryLevel toSentryLevel(final Level level) {
    if (level.isMoreSpecificThan(Level.FATAL)) {
      return SentryLevel.FATAL;
    } else if (level.isMoreSpecificThan(Level.ERROR)) {
      return SentryLevel.ERROR;
    } else if (level.isMoreSpecificThan(Level.WARN)) {
      return SentryLevel.WARNING;
    } else if (level.isMoreSpecificThan(Level.INFO)) {
      return SentryLevel.INFO;
    } else {
      return SentryLevel.DEBUG;
    }
  }
}
