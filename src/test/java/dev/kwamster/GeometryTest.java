package dev.kwamster;
import dev.kwamster.combat.metrics.Geometry;import dev.kwamster.combat.metrics.Geometry.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class GeometryTest {
 Box box=new Box(-.3,0,3,.3,1.8,3.6);Vec eye=new Vec(0,1.62,0);
 @Test void distanceUsesEyeAndHitbox(){assertEquals(3,box.distance(eye),1e-9);assertEquals(3,eye.distance(Geometry.intersect(eye,new Vec(0,0,1),box,4).orElseThrow()),1e-9);}
 @Test void parallelMiss(){assertTrue(Geometry.intersect(eye,new Vec(1,0,0),box,4).isEmpty());}
 @Test void rangeBounded(){assertTrue(Geometry.intersect(eye,new Vec(0,0,1),box,2.9).isEmpty());}
 @Test void insideBoxStartsAtZero(){Vec inside=new Vec(0,1,3.2);assertEquals(inside,Geometry.intersect(inside,new Vec(0,0,1),box,4).orElseThrow());}
 @Test void normalizedImpact(){Vec n=box.normalize(new Vec(0,.9,3));assertEquals(new Vec(.5,.5,0),n);}
 @Test void directionRelativeToFacing(){assertEquals("Forward",Geometry.direction(.1,0,1,0));assertEquals("Backward",Geometry.direction(-.1,0,1,0));assertEquals("Neutral",Geometry.direction(0,.1,1,0));}
}
