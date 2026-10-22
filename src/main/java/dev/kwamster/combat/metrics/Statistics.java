package dev.kwamster.combat.metrics;
import dev.kwamster.combat.events.CombatEvent.Attack;
public final class Statistics {
 public int attempts,hits,emptyMisses,uncertain,contacts,criticalConditions,sprintHits,jumpCandidates,currentCombo,longestCombo,incoming;
 public double reachSum,maxReach,cooldownSum,intervalSum,intervalSquared; public int reachCount,intervalCount;public long lastHit,comboStart;public double momentum;
 public void attempt(Attack a){attempts++;if(a==null)emptyMisses++;else{uncertain++;if(a.rayContact())contacts++;}}
 public void hit(Attack a,long time,double comboSeconds){hits++;uncertain--;if(a.reach()>=0){reachSum+=a.reach();reachCount++;maxReach=Math.max(maxReach,a.reach());}if(a.criticalConditions())criticalConditions++;if(a.sprinting())sprintHits++;cooldownSum+=a.cooldown();
  if(lastHit>0){double interval=(time-lastHit)/1e9;intervalSum+=interval;intervalSquared+=interval*interval;intervalCount++;if(interval>comboSeconds)currentCombo=0;}if(currentCombo==0)comboStart=time;currentCombo++;longestCombo=Math.max(longestCombo,currentCombo);lastHit=time;momentum=Math.min(1,momentum+.2);
 }
 public void incoming(){incoming++;currentCombo=0;momentum=Math.max(-1,momentum-.25);}
 public double accuracy(){return attempts==0?0:100.0*hits/attempts;}public double averageReach(){return reachCount==0?0:reachSum/reachCount;}public double averageInterval(){return intervalCount==0?0:intervalSum/intervalCount;}public double intervalDeviation(){return intervalCount==0?0:Math.sqrt(Math.max(0,intervalSquared/intervalCount-Math.pow(averageInterval(),2)));}
 public Statistics copy(){return dev.kwamster.recording.storage.AtomicJson.GSON.fromJson(dev.kwamster.recording.storage.AtomicJson.GSON.toJson(this),Statistics.class);}
}
