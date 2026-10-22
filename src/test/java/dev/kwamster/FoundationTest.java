package dev.kwamster;
import dev.kwamster.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class FoundationTest {
 @TempDir Path dir;
 @Test void configRoundTrip() throws Exception { var store=new ConfigStore(dir.resolve("config.json"));var c=store.load();c.display=Config.Display.BOTH;c.analytics.x=.7;store.save(c);var loaded=store.load();assertEquals(Config.Display.BOTH,loaded.display);assertEquals(.7,loaded.analytics.x); }
 @Test void corruptionPreservesOriginal() throws Exception {var p=dir.resolve("config.json");Files.writeString(p,"{");assertThrows(java.io.IOException.class,()->new ConfigStore(p).load());assertEquals("{",Files.readString(p));try(var files=Files.list(dir)){assertEquals(2,files.count());}}
 @Test void invalidValuesBounded() { var c=new Config();c.analytics.x=Double.NaN;c.retention=-1;c.volume=4;c.validate();assertEquals(0,c.analytics.x);assertEquals(1,c.retention);assertEquals(1,c.volume); }
}
