package dev.kwamster.config;

public final class Config {
  public int schemaVersion = 1;
  public boolean tracking = true,
      recording = true,
      perHitChat = false,
      summary = true,
      sound = true;
  public double alertSeconds = 6;
  public boolean animate = true, floatingText = false;
  public int forwardColor = 0xFF88C8AA, backwardColor = 0xFFE6A278, neutralColor = 0xFF9AA6AC;
  public Feedback[] feedback = {
    new Feedback(0xFF88C8AA, "minecraft:entity.experience_orb.pickup"),
    new Feedback(0xFFA4CBF0, "minecraft:block.note_block.hat"),
    new Feedback(0xFFE4D28A, "minecraft:block.note_block.hat"),
    new Feedback(0xFFE49790, "minecraft:block.note_block.bass")
  };

  public static class Feedback {
    public int color;
    public String sound;
    public float volume = .35f, pitch = 1.2f;
    public boolean visual = true, audio = true;

    public Feedback(int color, String sound) {
      this.color = color;
      this.sound = sound;
    }
  }

  public Display display = Display.HUD;
  public int accent = 0xFF88C8AA, retention = 100;
  public double inactivitySeconds = 8,
      correlationMillis = 250,
      comboSeconds = 3,
      perfectCooldown = .95,
      greatCooldown = .85,
      jumpWindowMillis = 150,
      maxStorageMb = 64;
  public float volume = .35f, pitch = 1.2f;
  public Hud analytics = new Hud(.02, .2, 1, .8f, true, true),
      direction = new Hud(.48, .7, 1, .8f, true, true),
      alert = new Hud(.65, .15, 1, .9f, true, true);

  public enum Display {
    HUD,
    CHAT,
    BOTH,
    NONE;

    public boolean hud() {
      return this == HUD || this == BOTH;
    }

    public boolean chat() {
      return this == CHAT || this == BOTH;
    }
  }

  public static class Hud {
    public double x, y, scale;
    public float opacity;
    public boolean visible, background;
    public boolean rightAligned;

    public Hud() {
      this(.02, .2, 1, .8f, true, true);
    }

    public Hud(
        double x, double y, double scale, float opacity, boolean visible, boolean background) {
      this.x = x;
      this.y = y;
      this.scale = scale;
      this.opacity = opacity;
      this.visible = visible;
      this.background = background;
    }

    public void validate() {
      x = clamp(x, 0, 1);
      y = clamp(y, 0, 1);
      scale = clamp(scale, .5, 2);
      opacity = (float) clamp(opacity, .1, 1);
    }
  }

  public void validate() {
    if (schemaVersion != 1)
      throw new IllegalArgumentException("Unsupported config schema " + schemaVersion);
    if (display == null) display = Display.HUD;
    if (analytics == null) analytics = new Hud();
    if (direction == null) direction = new Hud();
    if (alert == null) alert = new Hud();
    if (feedback == null || feedback.length != 4) feedback = new Config().feedback;
    for (int i = 0; i < 4; i++) {
      if (feedback[i] == null) feedback[i] = new Config().feedback[i];
      feedback[i].volume = (float) clamp(feedback[i].volume, 0, 1);
      feedback[i].pitch = (float) clamp(feedback[i].pitch, .5, 2);
      if (feedback[i].sound == null) feedback[i].sound = "minecraft:entity.experience_orb.pickup";
    }
    alertSeconds = clamp(alertSeconds, 1, 30);
    analytics.validate();
    direction.validate();
    alert.validate();
    retention = Math.max(1, Math.min(1000, retention));
    inactivitySeconds = clamp(inactivitySeconds, 2, 120);
    correlationMillis = clamp(correlationMillis, 50, 500);
    comboSeconds = clamp(comboSeconds, .5, 10);
    jumpWindowMillis = clamp(jumpWindowMillis, 50, 300);
    maxStorageMb = clamp(maxStorageMb, 1, 1024);
    perfectCooldown = clamp(perfectCooldown, .5, 1);
    greatCooldown = clamp(greatCooldown, .1, perfectCooldown);
    volume = (float) clamp(volume, 0, 1);
    pitch = (float) clamp(pitch, .5, 2);
  }

  private static double clamp(double v, double min, double max) {
    return Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : min;
  }
}
