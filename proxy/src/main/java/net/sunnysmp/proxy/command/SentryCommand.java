package net.sunnysmp.proxy.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import io.sentry.protocol.SentryId;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.sunnysmp.proxy.config.SentrySettings;
import net.sunnysmp.proxy.sentry.SentryManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Implements {@code /sentry}, for checking that error reporting is actually wired up.
 *
 * <p>The logger here deliberately sits outside {@code net.sunnysmp.proxy.sentry}, which the
 * appender ignores to avoid feeding its own diagnostics back into itself. A test event logged from
 * inside that package would be dropped and look like a failure.</p>
 */
public final class SentryCommand {

  private static final Logger logger = LogManager.getLogger(SentryCommand.class);

  private static final String PERMISSION = "sunny.command.sentry";

  private static final long TEST_TIMEOUT_MILLIS = 10_000;

  private final ProxyServer server;

  public SentryCommand(final ProxyServer server) {
    this.server = server;
  }

  /**
   * Registers the {@code /sentry} command with the proxy's command manager.
   */
  public void register() {
    final LiteralArgumentBuilder<CommandSource> root = BrigadierCommand
        .literalArgumentBuilder("sentry")
        .requires(source -> source.getPermissionValue(PERMISSION) == Tristate.TRUE)
        .executes(this::status)
        .then(BrigadierCommand.literalArgumentBuilder("status").executes(this::status))
        .then(BrigadierCommand.literalArgumentBuilder("test").executes(this::test))
        .then(BrigadierCommand.literalArgumentBuilder("throw").executes(this::throwUncaught))
        .then(BrigadierCommand.literalArgumentBuilder("crash").executes(this::crash));

    final BrigadierCommand command = new BrigadierCommand(root);
    server.getCommandManager().register(
        server.getCommandManager().metaBuilder(command)
            .plugin(VelocityVirtualPlugin.INSTANCE)
            .build(),
        command);
  }

  private int status(final CommandContext<CommandSource> context) {
    final CommandSource source = context.getSource();
    if (!SentryManager.isEnabled()) {
      source.sendMessage(disabledMessage());
      return 1;
    }

    final SentrySettings settings = SentryManager.settings();
    source.sendMessage(Component.text("Sentry is enabled.", NamedTextColor.GREEN));
    source.sendMessage(Component.text("  DSN: " + redact(settings.dsn()), NamedTextColor.GRAY));
    source.sendMessage(Component.text("  Environment: "
        + (settings.environment() == null ? "<unset>" : settings.environment()),
        NamedTextColor.GRAY));
    source.sendMessage(Component.text("  Server name: "
        + (settings.serverName() == null ? "<host name>" : settings.serverName()),
        NamedTextColor.GRAY));
    if (settings.tags().isEmpty()) {
      source.sendMessage(Component.text("  Tags: <none>", NamedTextColor.GRAY));
    } else {
      source.sendMessage(Component.text("  Tags:", NamedTextColor.GRAY));
      settings.tags().forEach((key, value) -> source.sendMessage(
          Component.text("    " + key + " = " + value, NamedTextColor.GRAY)));
    }
    source.sendMessage(Component.text(
        "  /sentry test  - log an error, testing the appender", NamedTextColor.GRAY));
    source.sendMessage(Component.text(
        "  /sentry throw - throw an uncaught exception, testing the crash handler",
        NamedTextColor.GRAY));
    source.sendMessage(Component.text(
        "  /sentry crash - throw straight out of the command, no handling at all",
        NamedTextColor.GRAY));
    return 1;
  }

  private int test(final CommandContext<CommandSource> context) {
    final CommandSource source = context.getSource();
    if (!SentryManager.isEnabled()) {
      source.sendMessage(disabledMessage());
      return 1;
    }

    final SentryId previous = SentryManager.lastEventId();
    logger.error("Sentry test event requested by {}. If this reaches Sentry, reporting works.",
        describe(source), new SentryTestException("Sentry test event - this is not a real error"));
    source.sendMessage(Component.text("Sending test event...", NamedTextColor.GRAY));
    report(source, previous);
    return 1;
  }

  /**
   * Waits for the event off-thread and tells the source what happened.
   *
   * @param source who to report back to
   * @param previous the last event id seen before whatever triggered the event
   */
  private static void report(final CommandSource source, final SentryId previous) {
    CompletableFuture
        .supplyAsync(() -> SentryManager.awaitEvent(previous, TEST_TIMEOUT_MILLIS))
        .thenAccept(id -> {
          if (SentryId.EMPTY_ID.equals(id)) {
            source.sendMessage(Component.text(
                "No event reached Sentry within " + (TEST_TIMEOUT_MILLIS / 1000)
                    + "s. Check the console for SDK errors.", NamedTextColor.RED));
            return;
          }
          source.sendMessage(Component.text("Sent event " + id + ".", NamedTextColor.GREEN));
          source.sendMessage(Component.text(
              "It should be visible in Sentry now. If it isn't, the DSN points at the wrong "
                  + "project.", NamedTextColor.GRAY));
        });
  }

  private static Component disabledMessage() {
    return Component.text("Sentry is disabled. Set a dsn in the [sentry] section of sunny.toml.",
        NamedTextColor.RED);
  }

  private int throwUncaught(final CommandContext<CommandSource> context) {
    final CommandSource source = context.getSource();
    if (!SentryManager.isEnabled()) {
      source.sendMessage(disabledMessage());
      return 1;
    }

    // Thrown on a thread of its own, with nothing catching it, so this goes through Sentry's
    // uncaught exception handler rather than the appender. That is a separate code path, and the
    // one that reports a crash as unhandled, so it is worth being able to test on its own.
    final SentryId previous = SentryManager.lastEventId();
    final Thread thread = new Thread(() -> {
      throw new SentryTestException(
          "Sentry test exception thrown by " + describe(source) + " - this is not a real error");
    }, "sentry-test-throw");
    thread.start();

    source.sendMessage(Component.text("Throwing an uncaught exception on a separate thread. "
        + "It will print a stack trace to the console; that is expected.", NamedTextColor.GRAY));
    report(source, previous);
    return 1;
  }

  /**
   * Throws, and nothing else.
   *
   * <p>No checks, no messages, no waiting: the exception propagates out of the command into
   * whatever the proxy does with it. Useful for seeing how a genuinely unexpected failure is
   * reported, rather than one this class arranged.</p>
   *
   * @param context the command context
   * @return never returns
   */
  private int crash(final CommandContext<CommandSource> context) {
    throw new SentryTestException("Sentry test exception - this is not a real error");
  }

  private static String describe(final CommandSource source) {
    return source instanceof Player player
        ? player.getUsername() : "the console";
  }

  /**
   * Shows enough of the DSN to tell which project it points at, without printing the key.
   *
   * @param dsn the DSN
   * @return the redacted DSN
   */
  private static String redact(final @Nullable String dsn) {
    if (dsn == null) {
      return "<unset>";
    }
    final int at = dsn.indexOf('@');
    return at < 0 ? "<set>" : "***@" + dsn.substring(at + 1);
  }

  /** Carries a stack trace on the test event so it looks like a real issue in Sentry. */
  private static final class SentryTestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SentryTestException(final String message) {
      super(message);
    }
  }
}
