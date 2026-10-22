package dev.kwamster.gui.screens;

import java.util.List;
import java.util.function.IntConsumer;

public final class ChoiceScreen extends PanelScreen {
  private final List<String> choices;
  private final IntConsumer select;

  public ChoiceScreen(String name, List<String> choices, IntConsumer select) {
    super(name, "Choose a value");
    this.choices = choices;
    this.select = select;
  }

  @Override
  protected void content() {
    for (int i = 0; i < choices.size(); i++) {
      int index = i;
      action(choices.get(i), () -> select.accept(index));
    }
  }
}
