package dev.skycraft.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Surface adapter for Rapier's box-only voxel API, retaining holes and actual plane heights. */
public final class PhysicsSurfaceBoxes {
    private static final int GRID = 8;
    private static final double PRECISION = 256.0;
    // A triangle clipped against ten half-planes has at most thirteen vertices.
    // Reuse primitive storage across cells instead of allocating every clipped polygon.
    private static final class Scratch {
        final double[] cell = new double[24 * 3], strip = new double[24 * 3], a = new double[24 * 3], b = new double[24 * 3];
        final double[] lo = new double[3], hi = new double[3];
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    // Exact cell-relative polygons recur across sections and streamed regional updates.
    // Cache only their expensive tile clipping; retain final per-cell union/merging unchanged.
    private static final class ClippedSurface {
        final int normal, count, material, hash;
        final boolean terrain, walkable, diggable;
        final double[] vertices;
        ClippedSurface(SkyTri tri, int normal, double[] vertices, int count, boolean copy) {
            this.normal=normal; this.count=count; this.material=tri.material;
            this.terrain=tri.terrain; this.walkable=tri.walkable; this.diggable=tri.diggable;
            this.vertices=copy?java.util.Arrays.copyOf(vertices,count*3):vertices;
            int h=31*(31*(31*normal+material)+(terrain?1:0))+(walkable?1:0);
            h=31*h+(diggable?1:0);
            for(int i=0;i<count*3;i++) h=31*h+Double.hashCode(vertices[i]);
            this.hash=h;
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if(!(other instanceof ClippedSurface key)||hash!=key.hash||normal!=key.normal||count!=key.count
                ||material!=key.material||terrain!=key.terrain||walkable!=key.walkable||diggable!=key.diggable) return false;
            for(int i=0;i<count*3;i++) if(Double.doubleToLongBits(vertices[i])!=Double.doubleToLongBits(key.vertices[i])) return false;
            return true;
        }
    }
    private static final class SurfaceCache {
        final java.util.LinkedHashMap<ClippedSurface,List<Box>> values=new java.util.LinkedHashMap<>(256,.75f,true);
        int boxes;
        void put(ClippedSurface key,List<Box> value) {
            var previous=values.put(key,value); boxes+=value.size()-(previous==null?0:previous.size());
            var iterator=values.entrySet().iterator();
            while(values.size()>4096||boxes>65536) {
                var oldest=iterator.next(); boxes-=oldest.getValue().size(); iterator.remove();
            }
        }
    }
    private static final ThreadLocal<SurfaceCache> SURFACE_CACHE=ThreadLocal.withInitial(SurfaceCache::new);
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double at(int i) { return switch (i) { case 0 -> minX; case 1 -> minY; case 2 -> minZ; case 3 -> maxX; case 4 -> maxY; default -> maxZ; }; }
    }
    private PhysicsSurfaceBoxes() {}

    private record SurfaceKey(double ax,double ay,double az,double bx,double by,double bz,double cx,double cy,double cz,
        boolean stairHelper,boolean terrain,boolean diggable,int material) {
        SurfaceKey(SkyTri t) { this(t.ax,t.ay,t.az,t.bx,t.by,t.bz,t.cx,t.cy,t.cz,t.stairHelper,t.terrain,t.diggable,t.material); }
    }

    /** Neighboring native regions carry the same triangle, with unchanged coordinates/flags. */
    public static List<SkyTri> distinct(List<SkyTri> triangles) {
        if (triangles.size() < 2) return triangles;
        var seen = new HashSet<SurfaceKey>();
        var result = new ArrayList<SkyTri>(triangles.size());
        for (var triangle : triangles) if (seen.add(new SurfaceKey(triangle))) result.add(triangle);
        return result.size() == triangles.size() ? triangles : result;
    }

    public static List<Box> build(List<SkyTri> triangles, int x, int y, int z) {
        var boxes = new HashSet<Box>();
        Scratch scratch = SCRATCH.get();
        for (SkyTri t : triangles) {
            if (t.stairHelper || t.maxX < x || t.minX > x + 1 || t.maxY < y || t.minY > y + 1 || t.maxZ < z || t.minZ > z + 1) continue;
            int normal = Math.abs(t.nx) > Math.abs(t.ny) ? 0 : 1;
            if (Math.abs(t.nz) > Math.abs(normal == 0 ? t.nx : t.ny)) normal = 2;
            int u = (normal + 1) % 3, v = (normal + 2) % 3;
            double[] cell = scratch.cell, aPoints = scratch.a, bPoints = scratch.b;
            aPoints[0]=t.ax-x; aPoints[1]=t.ay-y; aPoints[2]=t.az-z;
            aPoints[3]=t.bx-x; aPoints[4]=t.by-y; aPoints[5]=t.bz-z;
            aPoints[6]=t.cx-x; aPoints[7]=t.cy-y; aPoints[8]=t.cz-z;
            int size = 3;
            for (int axis = 0; axis < 3; axis++) {
                size=clip(aPoints,size,bPoints,axis,0,true);
                size=clip(bPoints,size,aPoints,axis,1,false);
            }
            if (size < 3) continue;
            System.arraycopy(aPoints,0,cell,0,size*3);
            double[] low = scratch.lo, high = scratch.hi; bounds(cell,size,low,high);
            int firstU = Math.max(0,(int)Math.floor(low[u]*GRID)), lastU = Math.min(GRID-1,(int)Math.floor(high[u]*GRID-1e-8));
            int firstV = Math.max(0,(int)Math.floor(low[v]*GRID)), lastV = Math.min(GRID-1,(int)Math.floor(high[v]*GRID-1e-8));
            // A flat polygon covering all four cell corners produces one identical
            // box after the ordinary 8x8 subdivision/merge. Avoid those 64 clips.
            if (size==4 && low[normal]==high[normal]) {
                int corners=0;
                for(int i=0;i<size;i++) {
                    double cu=cell[i*3+u], cv=cell[i*3+v];
                    if((cu==0||cu==1)&&(cv==0||cv==1)) corners|=1<<((int)cu+2*(int)cv);
                }
                if(corners==15) { boxes.add(surfaceBox(t,normal,low,high)); continue; }
            }
            SurfaceCache cache=SURFACE_CACHE.get();
            var lookup=new ClippedSurface(t,normal,cell,size,false);
            List<Box> cached=cache.values.get(lookup);
            if(cached!=null) { boxes.addAll(cached); continue; }
            var tiles=new HashSet<Box>();
            // All eight tiles in a row share the same first two clips. Retain
            // that polygon once; arithmetic and vertex order stay identical.
            for (int a = firstU; a <= lastU; a++) {
                int stripSize=clip(cell,size,aPoints,u,(double)a/GRID,true);
                stripSize=clip(aPoints,stripSize,scratch.strip,u,(double)(a+1)/GRID,false);
                if (stripSize < 3) continue;
                for (int b = firstV; b <= lastV; b++) {
                int part=clip(scratch.strip,stripSize,aPoints,v,(double)b/GRID,true);
                part=clip(aPoints,part,bPoints,v,(double)(b+1)/GRID,false);
                if (part < 3) continue;
                double[] lo = scratch.lo, hi = scratch.hi; bounds(bPoints,part,lo,hi);
                if (hi[u]-lo[u] < 1e-8 || hi[v]-lo[v] < 1e-8) continue;
                Box box = surfaceBox(t,normal,lo,hi);
                if (box.maxX > box.minX && box.maxY > box.minY && box.maxZ > box.minZ) tiles.add(box);
                }
            }
            List<Box> clipped=List.copyOf(tiles);
            cache.put(new ClippedSurface(t,normal,cell,size,true),clipped);
            boxes.addAll(clipped);
        }
        List<Box> result = new ArrayList<>(boxes);
        for (int axis=0;axis<3;axis++) result = merge(result,axis);
        result.sort(Comparator.comparingDouble(Box::minX).thenComparingDouble(Box::minY).thenComparingDouble(Box::minZ)
            .thenComparingDouble(Box::maxX).thenComparingDouble(Box::maxY).thenComparingDouble(Box::maxZ));
        return List.copyOf(result);
    }
    private static Box surfaceBox(SkyTri t,int normal,double[] lo,double[] hi) {
        for(int k=0;k<3;k++) { lo[k]=Math.rint(lo[k]*PRECISION)/PRECISION; hi[k]=Math.rint(hi[k]*PRECISION)/PRECISION; }
        // Keep actual face height and put supporting thickness into the solid side.
        if(hi[normal]-lo[normal]<1/PRECISION) {
            if(normal==1&&t.walkable) lo[normal]=hi[normal]-1/PRECISION;
            else { lo[normal]-=0.5/PRECISION; hi[normal]+=0.5/PRECISION; }
        }
        if(t.terrain&&normal==1&&t.walkable) lo[1]=Math.min(lo[1],0);
        return new Box(lo[0],lo[1],lo[2],hi[0],hi[1],hi[2]);
    }
    private static int clip(double[] points,int count,double[] out,int axis,double boundary,boolean lower) {
        if(count==0) return 0;
        int inside=0;
        for(int i=0;i<count;i++) if(lower?points[i*3+axis]>=boundary:points[i*3+axis]<=boundary) inside++;
        if(inside==0) return 0;
        if(inside==count) { System.arraycopy(points,0,out,0,count*3); return count; }
        int written=0, previous=(count-1)*3;
        boolean previousIn=lower?points[previous+axis]>=boundary:points[previous+axis]<=boundary;
        for(int i=0;i<count;i++) {
            int current=i*3;
            boolean currentIn=lower?points[current+axis]>=boundary:points[current+axis]<=boundary;
            if(currentIn!=previousIn) {
                double ratio=(boundary-points[previous+axis])/(points[current+axis]-points[previous+axis]);
                for(int k=0;k<3;k++) out[written*3+k]=points[previous+k]+ratio*(points[current+k]-points[previous+k]);
                out[written*3+axis]=boundary; written++;
            }
            if(currentIn) { System.arraycopy(points,current,out,written*3,3); written++; }
            previous=current; previousIn=currentIn;
        }
        return written;
    }
    private static void bounds(double[] points,int count,double[] lo,double[] hi) {
        java.util.Arrays.fill(lo,1); java.util.Arrays.fill(hi,0);
        for(int i=0;i<count;i++) for(int k=0;k<3;k++) { lo[k]=Math.min(lo[k],points[i*3+k]); hi[k]=Math.max(hi[k],points[i*3+k]); }
    }
    private static List<Box> merge(List<Box> input,int axis) {
        int u=(axis+1)%3,v=(axis+2)%3;
        input.sort(Comparator.comparingDouble((Box b)->b.at(u)).thenComparingDouble(b->b.at(u+3))
            .thenComparingDouble(b->b.at(v)).thenComparingDouble(b->b.at(v+3)).thenComparingDouble(b->b.at(axis)));
        var out=new ArrayList<Box>();
        for(Box b:input) {
            if(!out.isEmpty()) {
                Box a=out.get(out.size()-1);
                if(a.at(u)==b.at(u)&&a.at(u+3)==b.at(u+3)&&a.at(v)==b.at(v)&&a.at(v+3)==b.at(v+3)&&b.at(axis)<=a.at(axis+3)) {
                    double[] values={a.minX,a.minY,a.minZ,a.maxX,a.maxY,a.maxZ}; values[axis+3]=Math.max(a.at(axis+3),b.at(axis+3));
                    out.set(out.size()-1,new Box(values[0],values[1],values[2],values[3],values[4],values[5])); continue;
                }
            }
            out.add(b);
        }
        return out;
    }
}
