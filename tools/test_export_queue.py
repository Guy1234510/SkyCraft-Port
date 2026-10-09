"""Run the actual exporter queue method with deterministic empty/full transport fixtures."""
from pathlib import Path
import subprocess, tempfile
r=Path(__file__).resolve().parents[1]
clients=[r/'forge/src/main/java/dev/skycraft/client',r/'neoforge/neoforge/src/main/java/java/dev/skycraft/client']
import os, shutil
jdk=Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('javac') or 'javac').resolve().parent
for client in clients:
    text=(client/'render/WorldExporter.java').read_text()
    start=text.index('private static void meshDirtySections(')
    end=text.index('\n\t/** Load the player',start)
    method=text[start:end]
    source='''import java.util.*;
public class ExportQueueTest {
 static class ClientLevel {}
 static class Queue extends LinkedHashSet<Long> {
  long removeFirstLong(){ long n=iterator().next(); remove(n); return n; }
  void addAndMoveToFirst(long n){ remove(n); var old=new ArrayList<Long>(this); clear(); add(n); addAll(old); }
 }
 static final Queue DIRTY=new Queue(),URGENT=new Queue();
 static final int SECTIONS_PER_FRAME=12;
 static boolean renderRingBlocked, blocked;
 static final List<Long> visited=new ArrayList<>();
 static boolean meshSection(ClientLevel level,long key){
  visited.add(key);
  if(key==2 && blocked){ renderRingBlocked=true; DIRTY.add(key); return true; }
  return key!=1;
 }
 static void check(boolean ok){if(!ok)throw new AssertionError(visited.toString());}
 static void reset(){DIRTY.clear();URGENT.clear();visited.clear();blocked=false;}
 public static void main(String[] args){
  for(int i=0;i<20;i++){
   reset(); DIRTY.add(1L); DIRTY.add(3L); meshDirtySections(new ClientLevel(),false);
   check(visited.equals(List.of(1L,3L)));
   reset(); URGENT.add(1L); URGENT.add(3L); meshDirtySections(new ClientLevel(),true);
   check(visited.equals(List.of(1L,3L)));
   reset(); blocked=true; URGENT.add(2L); URGENT.add(3L); meshDirtySections(new ClientLevel(),true);
   check(visited.equals(List.of(2L)) && URGENT.iterator().next()==2L && !DIRTY.contains(2L));
   blocked=false; visited.clear(); meshDirtySections(new ClientLevel(),true);
   check(visited.equals(List.of(2L,3L)));
   reset(); blocked=true; DIRTY.add(2L); DIRTY.add(3L); meshDirtySections(new ClientLevel(),false);
   check(visited.equals(List.of(2L)) && DIRTY.iterator().next()==2L);
  }
  System.out.println("Empty sections, urgent retries and full-ring backpressure passed");
 }
'''+method+'\n}'
    with tempfile.TemporaryDirectory(prefix='skycraft-export-queue-') as d:
        p=Path(d)/'ExportQueueTest.java'; p.write_text(source)
        subprocess.run([str(jdk/'javac.exe'),str(p)],check=True)
        subprocess.run([str(jdk/'java.exe'),'-cp',d,'ExportQueueTest'],check=True)
    print(client.parent.name, 'actual queue method verified')
    edits=text[text.index('public static void markDirty('):text.index('public static void frame(')]
    nearby=text[text.index('private static void prioritizeNearby('):text.index('/** Retry initialization')]
    section=text[text.index('private static LevelChunkSection sectionAt('):text.index('/** Returns true if real meshing')]
    assert text.index('prioritizeNearby(minecraft, level);') < text.index('meshDirtySections(level, true);'), 'Arrival work follows scene traffic'
    methods=(edits+method+nearby+section).replace('dev.skycraft.client.SkyDigClient','SkyDigClient')
    arrival=(r/'tools/ExportQueueArrival107.java').read_text(encoding='utf-8').replace('/* ACTUAL_METHODS */',methods)
    with tempfile.TemporaryDirectory(prefix='skycraft-arrival107-') as d:
        p=Path(d)/'ExportQueueArrival107.java';p.write_text(arrival,encoding='utf-8')
        subprocess.run([str(jdk/'javac.exe'),str(p)],check=True)
        subprocess.run([str(jdk/'java.exe'),'-cp',d,'ExportQueueArrival107'],check=True)
