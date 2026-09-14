package net.sunnysmp.proxy.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The {@code [sentry]} section of {@code sunny.toml}.
 *
 * @param dsn the Sentry DSN, or {@code null} when Sentry should stay disabled
 * @param environment the environment reported with every event, or {@code null} for the SDK default
 * @param serverName the server name reported with every event, or {@code null} to let the SDK
 *     resolve the host name
 * @param tags tags applied to every event, for filtering and grouping in Sentry
 */
public record SentrySettings(@Nullable String dsn, @Nullable String environment,
    @Nullable String serverName, Map<String, String> tags) {

  /**
   * Canonicalises the tag map so callers never have to null-check or worry about mutation.
   *
   * @param dsn the Sentry DSN
   * @param environment the environment
   * @param serverName the server name
   * @param tags the tags
   */
  public SentrySettings {
    tags = tags == null ? Map.of() : Map.copyOf(tags);
  }

  /**
   * Reads the section, defaulting every missing key so that an older config still loads.
   *
   * @param section the {@code [sentry]} section, possibly empty
   * @return the settings
   */
  public static SentrySettings from(final UnmodifiableConfig section) {
    return new SentrySettings(
        trimToNull(section.getOrElse("dsn", "")),
        trimToNull(section.getOrElse("environment", "")),
        trimToNull(section.getOrElse("server-name", "")),
        readTags(section.get("tags")));
  }

  /**
   * Whether a DSN was configured.
   *
   * @return {@code true} if Sentry should be started
   */
  public boolean isEnabled() {
    return dsn != null;
  }

  /**
   * Flattens the {@code [sentry.tags]} table into plain strings.
   *
   * <p>TOML values are typed, so a number or boolean written without quotes arrives as an Integer
   * or Boolean. Sentry tags are strings, so everything is converted rather than rejected.</p>
   *
   * @param tags the tags table, or {@code null} if the section has none
   * @return the tags, never {@code null}
   */
  private static Map<String, String> readTags(final @Nullable UnmodifiableConfig tags) {
    if (tags == null) {
      return Map.of();
    }

    final Map<String, String> read = new LinkedHashMap<>();
    for (final Map.Entry<String, Object> entry : tags.valueMap().entrySet()) {
      final Object value = entry.getValue();
      if (value == null || value instanceof UnmodifiableConfig) {
        // Sentry tags are flat, so a nested table has no meaning here.
        continue;
      }
      final String text = trimToNull(String.valueOf(value));
      if (text != null) {
        read.put(entry.getKey(), text);
      }
    }
    return Map.copyOf(read);
  }

  private static @Nullable String trimToNull(final @Nullable String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
