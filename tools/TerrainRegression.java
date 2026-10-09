import dev.skycraft.world.SkyTri;
import dev.skycraft.world.TriCollider;
import java.util.List;

/** Runs actual collision classes without starting either game. */
public class TerrainRegression {
    static List<SkyTri> slope(float rise) {
        return List.of(new SkyTri(new float[]{-10,-10*rise,-10, 10,10*rise,-10, 10,10*rise,10},0,false),
            new SkyTri(new float[]{-10,-10*rise,-10, 10,10*rise,10, -10,-10*rise,10},0,false));
    }
    static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
    public static void main(String[] args) {
        for (float pitch : new float[]{-0.25f,-0.5f,-0.9f,0.25f,0.5f,0.9f}) {
            var terrain=slope(pitch);
            double gradient=-terrain.get(0).nx/terrain.get(0).ny;
            double x=0, y=Math.abs(gradient)*0.15;
            for (int tick=0;tick<10;tick++) {
                double[] d=TriCollider.resolve(terrain,x,y,0,0.3,1.8,0.6,true,0.45,-0.08,0);
                check(Math.abs(d[0]-0.45)<1e-10,"Horizontal progress lost on slope "+pitch);
                x+=d[0]; y+=d[1];
                check(Math.abs(y-(x*gradient+Math.abs(gradient)*0.15))<1e-7,
                    "Floor contact lost on slope "+pitch+" tick "+tick+" y="+y);
            }
            System.out.println("Continuous slope "+pitch+" passed");
        }
        double[] air=TriCollider.resolve(slope(0.5f),0,4,0,0.3,1.8,0.6,false,0.45,-0.08,0);
        check(air[1]==-0.08,"Airborne gravity changed");
        double[] jump=TriCollider.resolve(slope(0.5f),0,0.075,0,0.3,1.8,0.6,true,0.45,0.42,0);
        check(jump[1]==0.42,"Jump glued to slope");
        double[] stationary=TriCollider.resolve(slope(0),0,0,0,0.3,1.8,0.6,true,0,-0.08,0);
        check(stationary[0]==0 && stationary[1]==0 && stationary[2]==0,"Stationary floor changed");
        System.out.println("Gravity, jumping and stationary support passed");
        // A boat is wider and shorter than a player. Test its real hull dimensions,
        // including support across the footprint and walls taller than the beach step.
        for (float pitch : new float[]{0, 0.02f, -0.02f, 0.2f, -0.2f}) {
            var terrain = slope(pitch);
            double x = -4, y = TriCollider.groundAt(terrain, x, 4, 0, 0, null, 0.6875);
            check(TriCollider.canOccupy(terrain, x, y + 0.001, 0, 0.6875, 0.5625, null), "Boat placement on slope " + pitch);
            for (int tick = 0; tick < 100; tick++) {
                double[] d = TriCollider.resolve(terrain, x, y, 0, 0.6875, 0.5625, 0.35, true, 0.08, -0.04, 0, null, 0.6875);
                check(d[0] == 0.08 && d[2] == 0, "Boat blocked on flat/shallow terrain " + pitch + " tick " + tick);
                x += d[0]; y += d[1];
                double floor = TriCollider.groundAt(terrain, x, y, 0, 0.001, null, 0.6875);
                check(Math.abs(y - floor) < 1e-7, "Boat lost floor support");
            }
        }
        var wall = new SkyTri(new float[]{1,0,-2, 1,2,-2, 1,2,2}, 0, false);
        var wall2 = new SkyTri(new float[]{1,0,-2, 1,2,2, 1,0,2}, 0, false);
        var obstacles = new java.util.ArrayList<>(slope(0));
        obstacles.add(wall); obstacles.add(wall2);
        double[] stopped = TriCollider.resolve(obstacles, 0, 0, 0, 0.6875, 0.5625, 0.35, true, 0.6, -0.04, 0, null, 0.6875);
        check(stopped[0] < 0.313, "Boat crossed a real wall");
        check(!TriCollider.canOccupy(obstacles, 0.8, 0, 0, 0.6875, 0.5625, null), "Boat placement inside wall accepted");
        double[] floating = TriCollider.resolve(slope(0), 0, 2, 0, 0.6875, 0.5625, 0.35, false, 0.1, 0.05, 0, null, 0.6875);
        check(floating[0] == 0.1 && floating[1] == 0.05, "Boat buoyancy movement changed above ground");
        System.out.println("Boat placement, 500 shallow/flat moves, real walls and buoyancy passed");
        double boatY = -0.01;
        for(int tick=0;tick<1200;tick++) {
            double[] d=TriCollider.resolve(slope(0),0,boatY,0,0.6875,0.5625,0.02,true,0,-0.04,0,null,0.6875);
            boatY += d[1];
            check(Math.abs(boatY)<1e-9,"Resting boat sank through support at tick "+tick);
        }
        System.out.println("Resting boat: 1,200 gravity ticks and initial penetration recovery passed");
        var floorTris=List.of(new SkyTri(new float[]{-2,0.371f,-2, 2,0.371f,-2, 2,0.371f,2},0,false),
            new SkyTri(new float[]{-2,0.371f,-2, 2,0.371f,2, -2,0.371f,2},0,false));
        var physical=dev.skycraft.world.PhysicsSurfaceBoxes.build(floorTris,0,0,0);
        check(!physical.isEmpty(),"Physics floor missing");
        for(var b:physical) check(Math.abs(b.maxY()-0.371)<0.002,"Physics ground raised above real surface: "+b.maxY());
        var physicalWall=dev.skycraft.world.PhysicsSurfaceBoxes.build(List.of(new SkyTri(new float[]{0.371f,0,0, 0.371f,1,0, 0.371f,1,1},0,false),
            new SkyTri(new float[]{0.371f,0,0, 0.371f,1,1, 0.371f,0,1},0,false)),0,0,0);
        for(var b:physicalWall) check(b.maxX()<0.375 && b.minX()>0.367,"Physics wall blocks before contact");
        var strips=dev.skycraft.world.PhysicsSurfaceBoxes.build(List.of(
            new SkyTri(new float[]{0,0.3f,0, 0.2f,0.3f,0, 0.2f,0.3f,1},0,false),
            new SkyTri(new float[]{0.8f,0.3f,0, 1,0.3f,0, 1,0.3f,1},0,false)),0,0,0);
        for(var b:strips) check(b.maxX()<=0.204 || b.minX()>=0.796,"Physics adapter filled the empty space between separate surfaces");
        System.out.println("Rapier adapter: actual floor height, wall location and empty gaps passed");
        for(double cosine:new double[]{1.0000000000000002,-1.0000000000000002,1,-1,0})
            check(Double.isFinite(Math.acos(Math.max(-1,Math.min(1,cosine)))),"Elytra rotation became NaN");
    }
}
