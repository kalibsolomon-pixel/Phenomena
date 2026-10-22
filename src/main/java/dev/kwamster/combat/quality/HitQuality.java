package dev.kwamster.combat.quality;
import dev.kwamster.config.Config;
import dev.kwamster.combat.events.CombatEvent.Attack;
/** Readiness feedback, not a damage or skill rating. No reward for central aim or maximum reach. */
public final class HitQuality {
 public enum Grade { PERFECT, GREAT, NORMAL, POOR }
 private HitQuality(){}
 public static Grade classify(Attack a,Config c){if(a.cooldown()>=c.perfectCooldown)return Grade.PERFECT;if(a.cooldown()>=c.greatCooldown)return Grade.GREAT;return a.cooldown()>=.5?Grade.NORMAL:Grade.POOR;}
}
