package net.sunnysmp.proxy.config.migration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import org.apache.logging.log4j.Logger;

/**
 * Rewrites an out-of-date {@code sunny.toml} in place.
 *
 * <p>Only needed when a setting changes shape: renames, moves between sections, or a value whose
 * meaning changed. Adding a new setting or a whole new section needs no migration, because every
 * setting supplies its own default when the key is absent.</p>
 *
 * <p>An implementation must set {@code config-version} to the version it produces as the last thing
 * it does, so that migrations chain and none of them runs twice.</p>
 */
public interface ConfigurationMigration {

  /**
   * Whether this migration applies to the given config.
   *
   * @param config the loaded config
   * @return {@code true} if {@link #migrate} should run
   */
  boolean shouldMigrate(CommentedFileConfig config);

  /**
   * Rewrites the config. The caller saves it.
   *
   * @param config the loaded config
   * @param logger the logger to report what changed
   * @throws IOException if the migration needs to touch the filesystem and fails
   */
  void migrate(CommentedFileConfig config, Logger logger) throws IOException;

  /**
   * Gets the configuration version.
   *
   * <p>A file with no version predates the key and is treated as the first version.</p>
   *
   * @param config the loaded config
   * @return the configuration version
   */
  default double configVersion(final CommentedFileConfig config) {
    final String stringVersion = config.getOrElse("config-version", "1.0");
    try {
      return Double.parseDouble(stringVersion);
    } catch (final NumberFormatException e) {
      return 1.0;
    }
  }
}
