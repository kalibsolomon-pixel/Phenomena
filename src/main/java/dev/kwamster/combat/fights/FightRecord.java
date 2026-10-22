package dev.kwamster.combat.fights;
import dev.kwamster.combat.events.CombatEvent;
import dev.kwamster.combat.metrics.Statistics;
import java.util.List;
public record FightRecord(int schemaVersion,String id,long startEpochMillis,long endEpochMillis,long durationNanos,String opponent,Outcome outcome,String reason,Statistics statistics,List<CombatEvent> events) {
 public enum Outcome { WIN, LOSS, DRAW, INTERRUPTED, UNKNOWN }
 public FightRecord { events=List.copyOf(events); }
}
