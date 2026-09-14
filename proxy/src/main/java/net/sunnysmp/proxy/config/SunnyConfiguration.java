package net.sunnysmp.proxy.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import net.sunnysmp.proxy.config.migration.ConfigurationMigration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The proxy's Sunny-specific configuration, read from {@code sunny.toml}.
 *
 * <p>This is deliberately a separate file from {@code velocity.toml}. Upstream never reads it, so
 * rebasing the fork onto a new Velocity release can't conflict with anything in here.</p>
 *
 * <p>To add a feature, write a settings record with a {@code from(UnmodifiableConfig)} factory,
 * give it a component here, and add its block to {@code default-sunny.toml}. Every setting must
 * carry its own default so that a config file written before the feature existed still loads.</p>
 *
 * @param sentry the Sentry error reporting settings
 */
public record SunnyConfiguration(SentrySettings sentry) {

  public static final Path DEFAULT_PATH = Path.of("sunny.toml");

  private static final Logger logger = LogManager.getLogger(SunnyConfiguration.class);

  private static final String DEFAULT_RESOURCE = "default-sunny.toml";

  /**
   * The config version this build writes and understands.
   *
   * <p>Bump it, and the version written by the migration that produces it, whenever a setting is
   * renamed, moved or reinterpreted. Purely additive changes don't need a bump.</p>
   */
  private static final double CURRENT_VERSION = 1.0;

  /**
   * Migrations to apply, oldest first.
   *
   * <p>Empty until the config first changes shape; see {@link ConfigurationMigration}.</p>
   */
  private static final ConfigurationMigration[] MIGRATIONS = {};

  /**
   * The configuration the proxy falls back to when {@code sunny.toml} can't be read.
   *
   * @return a configuration with every feature at its default
   */
  public static SunnyConfiguration defaults() {
    return new SunnyConfiguration(SentrySettings.from(Config.inMemory()));
  }

  /**
   * Reads the configuration, falling back to {@link #defaults()} if it can't be read.
   *
   * <p>A broken config must never stop the proxy from booting, so the failure is logged and
   * startup continues with the feature switched off.</p>
   *
   * @param path the file to read
   * @return the configuration
   */
  public static SunnyConfiguration readOrDefault(final Path path) {
    try {
      return read(path);
    } catch (final Exception e) {
      logger.error("Unable to read {}; continuing with defaults.", path, e);
      return defaults();
    }
  }

  /**
   * Reads the configuration, writing the default file first if it does not exist yet.
   *
   * @param path the file to read
   * @return the configuration
   * @throws IOException if the file could not be read or created
   */
  public static SunnyConfiguration read(final Path path) throws IOException {
    final URL defaultConfig =
        SunnyConfiguration.class.getClassLoader().getResource(DEFAULT_RESOURCE);
    if (defaultConfig == null) {
      throw new IOException("Default configuration is missing from the jar.");
    }

    try (CommentedFileConfig config = CommentedFileConfig.builder(path)
        .defaultData(defaultConfig)
        .autosave()
        .preserveInsertionOrder()
        .sync()
        .build()) {
      config.load();

      for (final ConfigurationMigration migration : MIGRATIONS) {
        if (migration.shouldMigrate(config)) {
          migration.migrate(config, logger);
        }
      }
      stampVersion(config, path);

      return new SunnyConfiguration(SentrySettings.from(section(config, "sentry")));
    }
  }

  /**
   * Records the version the config now conforms to, warning if it came from a newer proxy.
   *
   * <p>Writing it back is what stops the migrations above from running again, and it stamps a
   * version onto files that predate the key. The config is autosaved, so the set persists.</p>
   *
   * @param config the loaded config, already migrated
   * @param path the file it was read from, for the warning
   */
  private static void stampVersion(final CommentedFileConfig config, final Path path) {
    final String written = config.get("config-version");
    if (written == null) {
      config.set("config-version", String.valueOf(CURRENT_VERSION));
      return;
    }

    final double version;
    try {
      version = Double.parseDouble(written);
    } catch (final NumberFormatException e) {
      logger.warn("{} has an unreadable config-version of '{}'; rewriting it as {}.",
          path, written, CURRENT_VERSION);
      config.set("config-version", String.valueOf(CURRENT_VERSION));
      return;
    }

    if (version > CURRENT_VERSION) {
      logger.warn("{} is version {}, but this proxy only understands {}. It was probably written "
          + "by a newer build; settings this proxy doesn't know about will be ignored.",
          path, version, CURRENT_VERSION);
      return;
    }

    if (version != CURRENT_VERSION) {
      config.set("config-version", String.valueOf(CURRENT_VERSION));
    }
  }

  /**
   * Returns a named section, or an empty config if the file predates that section.
   *
   * @param config the loaded config
   * @param name the section name
   * @return the section
   */
  private static UnmodifiableConfig section(final UnmodifiableConfig config, final String name) {
    final UnmodifiableConfig existing = config.get(name);
    return existing == null ? Config.inMemory() : existing;
  }
}
