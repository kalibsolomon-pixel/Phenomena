package dev.kwamster.combat.tracking;

/**
 * One resolver shared by lifecycle, diagnostics and the dashboard. Manual always takes priority.
 */
public record CapturePolicy(Mode mode, ServerCompatibility.Adapter environment) {
  public enum Mode {
    MANUAL,
    DISABLED,
    CONFIGURED_SERVER,
    GENERIC_COMBAT
  }

  public static CapturePolicy resolve(String host, boolean automatic, boolean manual) {
    var environment = Announcements.environment(host);
    return new CapturePolicy(
        manual
            ? Mode.MANUAL
            : !automatic
                ? Mode.DISABLED
                : environment != null ? Mode.CONFIGURED_SERVER : Mode.GENERIC_COMBAT,
        environment);
  }
}
