package dev.kwamster;

import static org.junit.jupiter.api.Assertions.*;

import dev.kwamster.combat.quality.CriticalConditions;
import org.junit.jupiter.api.Test;

class CriticalTest {
  @Test
  void fallingReadyEligible() {
    assertTrue(CriticalConditions.eligible(1, .2, false, false, false, false, false, false));
  }

  @Test
  void eachVanillaExclusionPreventsEligibility() {
    for (int i = 0; i < 6; i++) {
      boolean[] flags = new boolean[6];
      flags[i] = true;
      assertFalse(
          CriticalConditions.eligible(
              1, .2, flags[0], flags[1], flags[2], flags[3], flags[4], flags[5]));
    }
  }

  @Test
  void readinessAndFallRequired() {
    assertFalse(CriticalConditions.eligible(.9, .2, false, false, false, false, false, false));
    assertFalse(CriticalConditions.eligible(1, 0, false, false, false, false, false, false));
  }

  @Test
  void placementIgnoresContactFace() {
    assertEquals("Center", dev.kwamster.combat.metrics.Geometry.placement(.5, .5, 0));
    assertEquals("Edge", dev.kwamster.combat.metrics.Geometry.placement(.95, .5, 0));
    assertEquals("High", dev.kwamster.combat.metrics.Geometry.placement(.5, .9, 0));
    assertEquals("Low", dev.kwamster.combat.metrics.Geometry.placement(.5, .1, 0));
  }
}
