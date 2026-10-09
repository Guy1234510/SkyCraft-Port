#pragma once
#include <array>
#include <cmath>

namespace skycraft::render
{
    using SurfacePoint = std::array<double, 3>;
    // Two-sided segment/triangle intersection in a mesh's local coordinates.
    inline double SurfaceIntersection(SurfacePoint from, SurfacePoint to,
        SurfacePoint a, SurfacePoint b, SurfacePoint c)
    {
        auto sub = [](SurfacePoint p, SurfacePoint q) { return SurfacePoint{p[0]-q[0],p[1]-q[1],p[2]-q[2]}; };
        auto cross = [](SurfacePoint p, SurfacePoint q) { return SurfacePoint{p[1]*q[2]-p[2]*q[1],p[2]*q[0]-p[0]*q[2],p[0]*q[1]-p[1]*q[0]}; };
        auto dot = [](SurfacePoint p, SurfacePoint q) { return p[0]*q[0]+p[1]*q[1]+p[2]*q[2]; };
        auto d = sub(to,from), e1 = sub(b,a), e2 = sub(c,a), p = cross(d,e2);
        double determinant = dot(e1,p);
        if (!std::isfinite(determinant) || std::abs(determinant)<1e-12) return -1;
        auto offset = sub(from,a), q = cross(offset,e1);
        double u = dot(offset,p)/determinant, v = dot(d,q)/determinant;
        double t = dot(e2,q)/determinant;
        return u>=-1e-9 && v>=-1e-9 && u+v<=1+1e-9 && t>=0 && t<=1 ? t : -1;
    }
}
