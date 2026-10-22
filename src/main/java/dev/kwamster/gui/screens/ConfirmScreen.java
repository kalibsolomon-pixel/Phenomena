package dev.kwamster.gui.screens;

public final class ConfirmScreen extends PanelScreen {
  private final Runnable yes, no;

  public ConfirmScreen(String question, Runnable yes, Runnable no) {
    super("Confirm", question);
    this.yes = yes;
    this.no = no;
  }

  @Override
  protected void content() {
    action("Confirm", yes);
    action("Cancel", no);
  }

  @Override
  public void onClose() {
    no.run();
  }
}
