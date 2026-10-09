import dev.skycraft.world.SkyWater;
import java.lang.reflect.*;
import java.util.concurrent.ConcurrentHashMap;

/** Exercises the shipped cache lookup/clear with an active grid moving away. */
public class WaterCacheRegression {
    public static void main(String[] args) throws Exception {
        Class<?> gridType=Class.forName("dev.skycraft.world.SkyWater$Grid");
        var gc=gridType.getDeclaredConstructor(int.class,int.class,int.class,float[].class);
        gc.setAccessible(true);
        var grid=SkyWater.class.getDeclaredField("grid"); grid.setAccessible(true);
        grid.set(null,gc.newInstance(0,0,16,new float[256]));
        Class<?> cacheType=Class.forName("dev.skycraft.world.SkyWater$WaterCache");
        var cc=cacheType.getDeclaredConstructor(int.class,ConcurrentHashMap.class); cc.setAccessible(true);
        var values=new ConcurrentHashMap<Long,Float>();
        values.put(((long)100<<32)^100L,12.75f);
        var known=SkyWater.class.getDeclaredField("known"); known.setAccessible(true);
        known.set(null,cc.newInstance(1,values));
        if (SkyWater.surfaceAt(100,100)!=12.75) throw new AssertionError("Distant water disappeared");
        if (!Double.isNaN(SkyWater.surfaceAt(200,200))) throw new AssertionError("Unknown water invented");
        if (SkyWater.surfaceAt(1,1)!=0) throw new AssertionError("Current grid did not win");
        SkyWater.clear();
        if (!Double.isNaN(SkyWater.surfaceAt(100,100))) throw new AssertionError("Water leaked across disconnect");
        System.out.println("Distant water retention, unknown columns, current-grid priority and clear passed");
    }
}
