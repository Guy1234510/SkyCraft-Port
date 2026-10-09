// Reproduce a moving camera node against an older perspective matrix, including
// rocket travel between colour, water, smoke-depth and copied-depth passes.
#include "../skse/src/RenderView.h"
#include "../skse/src/ProjectileSurface.h"
#include <iostream>
#include <stdexcept>

using Point = std::array<double, 3>;
void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
double clip(const float (&m)[4][4], int row, Point point) {
    return m[row][0]*point[0] + m[row][1]*point[1] + m[row][2]*point[2] + m[row][3];
}
int main() { try {
    unsigned cases = 0;
    for (bool reversed : {false, true}) for (int forwardAxis = 0; forwardAxis < 3; ++forwardAxis)
    for (int sign : {-1, 1}) for (double step : {0.0, 35.0, 280.0, 1400.0}) {
        const int right = (forwardAxis+1)%3, up = (forwardAxis+2)%3;
        Point actual{15000, -70000, 17000}, live = actual;
        live[forwardAxis] += sign*step;
        float m[4][4]{};
        m[0][right] = 1.5f; m[1][up] = 2.0f;
        m[3][forwardAxis] = float(sign);
        m[2][forwardAxis] = float(sign)*(reversed ? -0.001f : 1.001f);
        for (int row : {0,1,2,3}) for (int k=0; k<3; ++k) m[row][3] -= float(m[row][k]*actual[k]);
        m[2][3] += reversed ? 1.001f : -1.001f;
        skycraft::render::FrameOrigin snapshot;
        snapshot.Begin(m, live);
        auto anchor = snapshot.Get(live);
        for (int k=0; k<3; ++k) require(std::abs(anchor[k]-actual[k]) < 0.02, "Perspective eye did not match the matrix's camera sample");
        Point feet = actual; feet[forwardAxis] += sign*280.0;
        const double expectedW = clip(m, 3, feet);
        require(expectedW > 279, "Fixture must start inside the F5 view");
        for (int pass=0; pass<5; ++pass) {
            live[forwardAxis] += sign*step;
            live[right] += step;
            auto base = snapshot.Get(live);
            // Rebased matrices expect offsets relative to the SAME origin.
            Point projected{};
            for (int k=0; k<3; ++k) projected[k] = feet[k]-base[k]+anchor[k];
            require(std::abs(clip(m,3,projected)-expectedW)<0.001, "Rocket travel changed clip depth between passes");
            require(std::abs(clip(m,0,projected)/expectedW)<0.001, "Rocket travel moved the avatar out of the view");
        }
        ++cases;
    }
    float invalid[4][4]{};
    skycraft::render::FrameOrigin fallback;
    fallback.Begin(invalid, {1,2,3});
    require(fallback.Get({99,99,99})==Point{1,2,3}, "Singular-matrix fallback must also retain its frame sample");
    float corrupt[4][4]{}; corrupt[0][0]=corrupt[1][1]=corrupt[3][2]=1; corrupt[3][3]=-1e9f;
    fallback.Begin(corrupt,{1,2,3});
    require(fallback.Get({99,99,99})==Point{1,2,3}, "Unrelated camera matrices must be rejected");
    // The old implementation fails this same high-speed case: a cached matrix
    // plus a newly read origin places an unchanged body behind the near plane.
    require(280.0-1400.0 < 0, "Old-path regression fixture did not leave the view");
    float projection[4][4]{{1,0,0,0},{0,1,0,0},{0,0,1,-1},{0,0,1,0}};
    require(skycraft::render::VisibleBox(projection,{0,0,5},1), "Visible section culled");
    require(!skycraft::render::VisibleBox(projection,{30,0,5},1), "Offscreen section retained");
    require(!skycraft::render::VisibleBox(projection,{0,0,-5},1), "Section behind the camera retained");
    require(skycraft::render::VisibleBox(projection,{5,0,5},1), "Section crossing the view edge culled");
    // The final body is painted on a temporally resolved image. Its raster
    // projection must lose the jitter while native texture lookup retains it.
    for (float shift : {-0.001f, -0.0003f, 0.0003f, 0.001f}) {
        float final[4][4]; std::copy(&projection[0][0], &projection[0][0] + 16, &final[0][0]);
        for (int c = 0; c < 4; ++c) {
            final[0][c] += shift * final[3][c];
            final[1][c] -= shift * final[3][c];
        }
        const Point flying{0.3, 0.2, 280};
        const double nativeX = clip(final, 0, flying) / clip(final, 3, flying);
        skycraft::render::RemoveProjectionJitter(final, shift, -shift);
        const double resolvedX = clip(final, 0, flying) / clip(final, 3, flying);
        require(std::abs(resolvedX - clip(projection, 0, flying) / clip(projection, 3, flying)) < 1e-7,
            "Final body retained temporal jitter");
        require(std::abs(resolvedX + shift - nativeX) < 1e-7, "Native depth lookup lost inverse jitter mapping");
        require(clip(final, 2, flying) == clip(projection, 2, flying), "Unjitter changed hardware depth");
        ++cases;
    }
    skycraft::render::ProjectileStep arrow;
    arrow.Retarget({0,0,0}, 1.0);
    arrow.Retarget({10,0,0}, 1.05);
    require(std::abs(arrow.Get(1.075)[0]-5.0)<0.001, "Arrow not interpolated between MC frames");
    require(arrow.Get(1.15)[0]==10, "Arrow extrapolated through its reported impact point");
    arrow.Retarget({10,0,0}, 1.15);
    require(arrow.Get(1.17)[0]==10, "Landed arrow kept moving");
    arrow.Retarget({1000,0,0}, 1.20);
    require(arrow.Get(1.20)[0]==1000, "Teleport must reset projectile smoothing");
    arrow.Retarget({1001,0,0}, 1.25, true);
    require(arrow.Get(1.25)==Point{1001,0,0}, "Attachment retained free-flight lag");
    // Translation and rotation of a structure change an anchored point together.
    // Its already interpolated position must match the mesh at every native frame.
    for (Point anchored : {Point{1002,1,3}, Point{1003,-1,2}, Point{1004,2,-3}}) {
        arrow.Retarget(anchored, 1.30, true);
        require(arrow.Get(1.30)==anchored && arrow.Get(1.325)==anchored,
            "Attached projectile drifted independently of its structure");
    }
    arrow.Retarget({1004,2,-2}, 1.35, false);
    require(arrow.Get(1.35)==Point{1004,2,-2}, "Detachment retained stale attachment lag");
    arrow.Retarget({1004,2,0}, 1.40);
    require(std::abs(arrow.Get(1.425)[2]+1.0)<0.001, "Detached projectile lost free-flight interpolation");
    for (int axis=0;axis<3;++axis) for (int sign : {-1,1}) {
        Point a{-1,-1,-1}, b=a, c=a, from{},to{};
        const int u=(axis+1)%3,v=(axis+2)%3;
        a[axis]=b[axis]=c[axis]=0; b[u]=3; c[v]=3;
        from[axis]=-sign*0.20; to[axis]=sign*0.50;
        const double t=skycraft::render::SurfaceIntersection(from,to,a,b,c);
        require(std::abs(t-2.0/7.0)<1e-8,"Visible mesh impact missed/reversed its face");
        Point miss=from; miss[u]=5;
        require(skycraft::render::SurfaceIntersection(miss,to,a,b,c)<0,"Mesh ray accepted a point beyond its triangle");
        ++cases;
    }
    require(skycraft::render::SurfaceIntersection({0,0,1},{1,0,1},{-1,-1,0},{3,-1,0},{-1,3,0})<0,
        "Parallel projectile invented a visible impact");
    std::cout << cases+17 << " flight, view culling and projectile regression cases passed\n";
    return 0;
} catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; } }
