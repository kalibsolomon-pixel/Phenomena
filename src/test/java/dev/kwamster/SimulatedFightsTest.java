package dev.kwamster;

import static dev.kwamster.Sim.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kwamster.analytics.*;
import dev.kwamster.combat.events.CombatEvent.Attribution;
import dev.kwamster.combat.fights.FightRecord;
import dev.kwamster.combat.fights.Resolution.Classification;
import dev.kwamster.combat.metrics.Combos.Side;
import dev.kwamster.combat.quality.AttackTiming;
import dev.kwamster.sharing.ReportExporter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fifteen deterministic scenarios from the 0.1.3 validation plan. Each one exports a real
 * report and checks that the embedded payload (what the graphs draw) agrees with the analytics and
 * the engine summary.
 */
class SimulatedFightsTest {
  @TempDir Path dir;

  private JsonObject payload(FightRecord f) throws Exception {
    var html =
        Files.readString(
            ReportExporter.export(dir.resolve(f.id()), f, ReportExporter.Options.DEFAULT));
    String open = "<script id=\"data\" type=\"application/json\">";
    int a = html.indexOf(open) + open.length();
    int b = html.indexOf("</script>", a);
    return JsonParser.parseString(html.substring(a, b)).getAsJsonObject();
  }

  /** Graph data and numeric summaries must agree. */
  private void consistent(FightRecord f) throws Exception {
    var a = FightAnalytics.of(f);
    var p = payload(f);
    var d = p.getAsJsonObject("derived");
    var s = d.getAsJsonObject("summary");
    assertEquals(f.statistics().hits, d.getAsJsonArray("hits").size());
    assertEquals(f.statistics().hits, s.get("hits").getAsInt());
    var marks = d.getAsJsonObject("combos").getAsJsonArray("marks");
    long localMarks = 0;
    for (var m : marks)
      if (m.getAsJsonObject().get("side").getAsString().equals("LOCAL")) localMarks++;
    assertEquals(f.statistics().hits, localMarks, "combo timeline draws every confirmed hit");
    int longest = 0;
    for (var st : d.getAsJsonObject("combos").getAsJsonArray("streaks")) {
      var o = st.getAsJsonObject();
      if (o.get("side").getAsString().equals("LOCAL"))
        longest = Math.max(longest, o.get("length").getAsInt());
    }
    assertEquals(f.statistics().longestCombo, longest);
    assertEquals(f.statistics().longestCombo, s.get("longestCombo").getAsInt());
    assertEquals(
        a.dominance.values.length, d.getAsJsonObject("dominance").getAsJsonArray("values").size());
    assertEquals(
        a.dominance.values.length,
        d.getAsJsonObject("dominance").get("states").getAsString().length());
    assertEquals(a.reach.eligible(), d.getAsJsonObject("reach").getAsJsonArray("values").size());
    assertEquals(a.timingSamples.size(), d.getAsJsonArray("timingSamples").size());
    assertEquals(a.incoming.size(), d.getAsJsonArray("incomingDetail").size());
    assertEquals(
        f.resolution().classification().name(),
        d.getAsJsonObject("resolution").get("classification").getAsString());
    assertEquals(ReportExporter.FORMAT_VERSION, p.get("version").getAsInt());
  }

  @Test
  void s01WinWithoutReceivingAHit() throws Exception {
    var s = new Sim();
    s.chat(0, MM_START);
    for (int i = 0; i < 6; i++) s.hit(3 + i * .6);
    s.chat(7, "PlayerB was killed by PlayerA!");
    s.chat(7, MM_WIN);
    var f = s.last();
    assertEquals(Classification.CONFIRMED_WIN, f.resolution().classification());
    assertEquals(0, f.statistics().incoming);
    assertEquals(6, f.statistics().longestCombo);
    assertEquals(Dominance.Availability.AVAILABLE, FightAnalytics.of(f).dominance.availability);
    consistent(f);
  }

  @Test
  void s02LossAfterLongOpponentCombo() throws Exception {
    var s = new Sim();
    s.chat(0, MM_START);
    s.hit(3);
    for (int i = 0; i < 5; i++) s.incoming(3.6 + i * .55);
    s.chat(7, "PlayerA was killed by PlayerB!");
    s.chat(7, MM_LOSS);
    var f = s.last();
    assertEquals(Classification.CONFIRMED_LOSS, f.resolution().classification());
    assertEquals(5, f.statistics().longestOpponentCombo);
    assertTrue(FightAnalytics.of(f).dominance.share(Dominance.State.OPPONENT) > 50);
    consistent(f);
  }

  @Test
  void s03AlternatingExchanges() throws Exception {
    var s = new Sim("unconfigured.test");
    for (int i = 0; i < 6; i++) s.hit(1 + i).incoming(1.5 + i);
    var f = s.finish(8);
    assertEquals(1, f.statistics().longestCombo);
    assertEquals(0, f.statistics().comboCount);
    var a = FightAnalytics.of(f);
    assertTrue(a.dominance.changes >= 0);
    consistent(f);
  }

  @Test
  void s04FourHitComboThenRetaliation() throws Exception {
    var s = new Sim("unconfigured.test");
    s.hit(1).hit(1.6).hit(2.2).hit(2.8).incoming(3.2).incoming(3.8);
    var f = s.finish(5);
    var a = FightAnalytics.of(f);
    assertEquals(4, a.longestCombo());
    assertEquals(2, a.combos.longest(Side.OPPONENT));
    consistent(f);
  }

  @Test
  void s05ShieldBlockThenConfirmedHit() throws Exception {
    var s = new Sim("unconfigured.test");
    s.attack(1, B, 3, true, true).shield(1.03, "BLOCK", "opponent");
    s.hit(1.7);
    var f = s.finish(3);
    assertEquals(1, f.statistics().shieldBlocks);
    assertEquals(100, f.statistics().accuracy(), 1e-9);
    consistent(f);
  }

  @Test
  void s06AxeShieldDisable() throws Exception {
    var s = new Sim("unconfigured.test");
    s.attack(1, B, 3, true, true).shield(1.02, "DISABLE", "opponent");
    s.hit(1.9).hit(2.5);
    var f = s.finish(3);
    var a = FightAnalytics.of(f);
    assertEquals(1, a.shields.disables());
    assertEquals(2, a.shields.hitsAfterDisable());
    consistent(f);
  }

  @Test
  void s07SameTickJumpReset() throws Exception {
    var s = new Sim("unconfigured.test");
    s.hit(.5).incoming(1).jump(1.01);
    var f = s.finish(2);
    assertEquals(1, f.statistics().jumpCandidates);
    assertEquals("IDEAL_CANDIDATE", FightAnalytics.of(f).incoming.getFirst().jumpClass());
    consistent(f);
  }

  @Test
  void s08EarlyAndLateJumpResets() throws Exception {
    var s = new Sim("unconfigured.test");
    s.hit(.5);
    s.jump(.9).incoming(1.0); // early by 2 ticks
    s.incoming(2.0).jump(2.15); // late by 3 ticks
    var a = FightAnalytics.of(s.finish(3));
    assertEquals(-2L, a.incoming.get(0).jumpOffsetTicks());
    assertEquals(3L, a.incoming.get(1).jumpOffsetTicks());
    assertTrue(a.incoming.stream().allMatch(i -> i.jumpClass().equals("ATTEMPT_CANDIDATE")));
    consistent(a.fight);
  }

  @Test
  void s09NearThreeBlockHit() throws Exception {
    var s = new Sim("unconfigured.test");
    s.hit(1, 3.04).hit(1.6, 2.4);
    var f = s.finish(3);
    assertEquals(1, f.statistics().perfectReachHits);
    assertEquals(1, FightAnalytics.of(f).reach.within());
    consistent(f);
  }

  @Test
  void s10WeaponSwitchDuringTiming() throws Exception {
    var t = new AttackTiming();
    t.attack("minecraft:diamond_sword;slot=0", 0, 12.5, 1);
    t.held("minecraft:diamond_axe;slot=1");
    var r = t.attack("minecraft:diamond_axe;slot=1", 13, 20, 1);
    assertEquals(AttackTiming.Grade.OPENER, r.grade(), "a switch resets the sequence");
    assertEquals(
        AttackTiming.Grade.TOO_FAST, t.attack("minecraft:diamond_axe;slot=1", 25, 20, 1).grade());
  }

  @Test
  void s11DisconnectDuringActiveMatch() throws Exception {
    var s = new Sim();
    s.chat(0, MM_START);
    s.hit(3).hit(4);
    s.disconnect(5);
    s.tick(9);
    var f = s.last();
    assertEquals(Classification.INTERRUPTED, f.resolution().classification());
    consistent(f);
  }

  @Test
  void s12DelayedWinnerAnnouncement() throws Exception {
    var s = new Sim();
    s.chat(0, MM_START);
    s.hit(3).hit(3.6);
    s.chat(4, "PlayerB was killed by PlayerA!");
    s.chat(6.5, MM_WIN); // 2.5 s later, inside the grace period
    var f = s.last();
    assertEquals(1, s.out.size());
    assertEquals(Classification.CONFIRMED_WIN, f.resolution().classification());
    consistent(f);
  }

  @Test
  void s13RematchAgainstSameOpponent() throws Exception {
    var s = new Sim();
    s.chat(0, MM_START);
    s.hit(3);
    s.chat(5, MM_WIN);
    s.chat(6, MM_START);
    s.hit(9).incoming(9.5);
    s.chat(12, MM_LOSS);
    assertEquals(2, s.out.size());
    assertEquals(Classification.CONFIRMED_WIN, s.out.get(0).resolution().classification());
    assertEquals(Classification.CONFIRMED_LOSS, s.out.get(1).resolution().classification());
    consistent(s.out.get(1));
  }

  @Test
  void s14IncompleteIncomingAttribution() throws Exception {
    var s = new Sim("unconfigured.test");
    for (int i = 0; i < 8; i++) {
      s.hit(1 + i * .5);
      if (i % 2 == 0) s.unattributed(1.2 + i * .5);
    }
    var f = s.finish(6);
    var a = FightAnalytics.of(f);
    assertEquals(Dominance.Availability.INCOMPLETE, a.dominance.availability);
    assertEquals("Unavailable", a.opponentComboAvailability());
    assertEquals(0, f.statistics().incoming);
    consistent(f);
  }

  @Test
  void s15MultiOpponentCombat() throws Exception {
    var s = new Sim("unconfigured.test");
    s.hit(1).incoming(1.5, C, Attribution.OTHER_ENTITY, true).hit(2);
    var f = s.finish(3);
    assertEquals(1, f.statistics().thirdPartyIncoming);
    assertEquals(2, f.statistics().longestCombo, "a third party's hit is not retaliation");
    consistent(f);
    var t = new Sim("unconfigured.test");
    t.hit(1).attack(2, C, 3, false, false);
    assertEquals(Classification.INTERRUPTED, t.out.getFirst().resolution().classification());
  }

  @Test
  void exampleReportIsRichAndConsistent() throws Exception {
    var f = ReportExample.build().last();
    assertEquals(Classification.CONFIRMED_WIN, f.resolution().classification());
    var a = FightAnalytics.of(f);
    assertEquals(1, a.shields.confirmedBlocks() - a.shields.disables());
    assertEquals(1, a.shields.disables());
    assertTrue(a.incoming.stream().anyMatch(i -> i.jumpClass().equals("IDEAL_CANDIDATE")));
    assertTrue(a.combos.marks().stream().anyMatch(dev.kwamster.combat.metrics.Combos.Mark::traded));
    assertEquals(1, a.serverCrits);
    consistent(f);
  }

  @Test
  void interpretationTextIsEscapedInsideTheReport() throws Exception {
    var s = new Sim("unconfigured.test");
    s.host = "unknown.example";
    s.hit(1);
    var f = s.finish(2);
    var p = payload(f);
    var html = Files.readString(dir.resolve(f.id()).resolve("index.html"));
    assertFalse(html.contains("</script><"), "payload cannot close its script element");
    assertTrue(p.getAsJsonObject("derived").getAsJsonArray("interpretations").size() >= 1);
  }
}
