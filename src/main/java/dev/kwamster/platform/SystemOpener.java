package dev.kwamster.platform;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Opens a local folder in the system file manager or a local HTML file in the default browser.
 * Only paths that already exist are accepted; callers resolve them through {@link SafePaths}.
 * Launching runs on a short-lived daemon thread, never the render thread, and failures are
 * reported through the callback instead of being thrown into the game.
 */
public final class SystemOpener {
  public enum Os {
    WINDOWS,
    MAC,
    LINUX
  }

  /** Starts a process for a command; replaceable in tests to record instead of launching. */
  public interface Launcher {
    /** Returns the exit code, or -1 if the process is still running when the wait ends. */
    int launch(List<String> command) throws IOException;
  }

  public static final Launcher PROCESS =
      command -> {
        var process =
            new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
          return process.waitFor(5, TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          return -1;
        }
      };

  private static volatile Launcher launcher = PROCESS;

  private SystemOpener() {}

  public static void setLauncher(Launcher l) {
    launcher = l == null ? PROCESS : l;
  }

  public static Os currentOs() {
    String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (name.contains("win")) return Os.WINDOWS;
    if (name.contains("mac") || name.contains("darwin")) return Os.MAC;
    return Os.LINUX;
  }

  /** The command used to open a path; pure so platform behavior is unit tested. */
  public static List<String> command(Os os, Path path, boolean directory) {
    String abs = path.toAbsolutePath().normalize().toString();
    return switch (os) {
      case WINDOWS ->
          directory
              ? List.of("explorer.exe", abs)
              // A file URI percent-encodes spaces, which FileProtocolHandler cannot take quoted.
              : List.of(
                  "rundll32",
                  "url.dll,FileProtocolHandler",
                  path.toAbsolutePath().normalize().toUri().toString());
      case MAC -> List.of("open", abs);
      case LINUX -> List.of("xdg-open", abs);
    };
  }

  /** Validates and opens asynchronously; {@code done} receives null on success or an error. */
  public static void open(Path path, boolean directory, Consumer<String> done) {
    final Path target;
    try {
      target = path.toAbsolutePath().normalize();
      if (Files.isSymbolicLink(target)) throw new IOException("Refusing symbolic link");
      if (directory ? !Files.isDirectory(target) : !Files.isRegularFile(target))
        throw new IOException((directory ? "Folder" : "File") + " does not exist");
    } catch (IOException | RuntimeException ex) {
      done.accept("Could not open: " + ex.getMessage());
      return;
    }
    var os = currentOs();
    var command = command(os, target, directory);
    var thread =
        new Thread(
            () -> {
              try {
                int exit = launcher.launch(command);
                // Explorer and rundll32 report non-zero codes on success; trust only xdg/open.
                if (os != Os.WINDOWS && exit > 0)
                  done.accept("Could not open (" + command.getFirst() + " exit " + exit + ")");
                else done.accept(null);
              } catch (IOException | RuntimeException ex) {
                done.accept("Could not open: " + command.getFirst() + " unavailable");
              }
            },
            "kwamster-open");
    thread.setDaemon(true);
    thread.start();
  }
}
