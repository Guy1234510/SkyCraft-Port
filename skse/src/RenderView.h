#pragma once

#include <array>
#include <algorithm>
#include <cmath>

namespace skycraft::render
{
    // The resolved back buffer is unjittered; native depth remains jittered.
    // Keep the shift separately so the final pixel shader can sample native depth.
    inline void RemoveProjectionJitter(float (&matrix)[4][4], float x, float y)
    {
        for (int c = 0; c < 4; ++c) {
            matrix[0][c] -= x * matrix[3][c];
            matrix[1][c] -= y * matrix[3][c];
        }
    }

    // A perspective matrix's eye has clip x, y and w equal to zero. Resolve
    // that sample rather than pairing an older matrix with a newer camera node.
    inline bool PerspectiveEye(const float (&matrix)[4][4], std::array<double, 3>& eye)
    {
        double rows[3][4]{};
        constexpr int clipRows[3]{0, 1, 3};
        for (int r = 0; r < 3; ++r) {
            for (int c = 0; c < 3; ++c) rows[r][c] = matrix[clipRows[r]][c];
            rows[r][3] = -double(matrix[clipRows[r]][3]);
        }
        for (int c = 0; c < 3; ++c) {
            int pivot = c;
            for (int r = c + 1; r < 3; ++r)
                if (std::abs(rows[r][c]) > std::abs(rows[pivot][c])) pivot = r;
            if (!std::isfinite(rows[pivot][c]) || std::abs(rows[pivot][c]) < 1e-10) return false;
            for (int k = c; k < 4; ++k) std::swap(rows[c][k], rows[pivot][k]);
            const double scale = rows[c][c];
            for (int k = c; k < 4; ++k) rows[c][k] /= scale;
            for (int r = 0; r < 3; ++r) if (r != c) {
                const double factor = rows[r][c];
                for (int k = c; k < 4; ++k) rows[r][k] -= factor * rows[c][k];
            }
        }
        std::array<double, 3> resolved{rows[0][3], rows[1][3], rows[2][3]};
        for (double v : resolved) if (!std::isfinite(v)) return false;
        eye = resolved;
        return true;
    }

    struct FrameOrigin
    {
        std::array<double, 3> position{};
        bool valid = false;

        void Begin(const float (&matrix)[4][4], std::array<double, 3> live)
        {
            position = live;
            std::array<double, 3> resolved{};
            if (PerspectiveEye(matrix, resolved)) {
                // Reject corrupt/unrelated matrices while allowing large frame hitches.
                double distance2 = 0;
                for (int k = 0; k < 3; ++k) distance2 += (resolved[k]-live[k]) * (resolved[k]-live[k]);
                if (distance2 < 17920.0 * 17920.0) position = resolved;
            }
            valid = true;
        }

        std::array<double, 3> Get(std::array<double, 3> live) const
        {
            return valid ? position : live;
        }
    };

    // Conservative clip-space AABB test, valid for normal and reversed depth.
    inline bool VisibleBox(const float (&matrix)[4][4], const std::array<double, 3>& centre, double halfSize)
    {
        for (int plane = 0; plane < 6; ++plane) {
            const int axis = plane / 2;
            const double sign = (plane & 1) ? -1.0 : 1.0;
            const bool nearPlane = plane == 4;
            double distance = nearPlane ? matrix[2][3] : matrix[3][3] + sign*matrix[axis][3];
            double radius = 0;
            for (int k = 0; k < 3; ++k) {
                const double coefficient = nearPlane ? matrix[2][k] : matrix[3][k] + sign*matrix[axis][k];
                distance += coefficient*centre[k];
                radius += std::abs(coefficient)*halfSize;
            }
            if (distance + radius < -0.001) return false;
        }
        return true;
    }

    struct ProjectileStep
    {
        std::array<double, 3> from{}, target{};
        double at = 0, duration = 0.05;
        bool valid = false, attached = false;

        std::array<double, 3> Get(double now) const
        {
            auto position = target;
            const double t = std::clamp((now-at)/duration, 0.0, 1.0);
            for (int k=0; k<3; ++k) position[k] = from[k] + (target[k]-from[k])*t;
            return position;
        }

        void Retarget(std::array<double, 3> position, double now, bool onStructure = false)
        {
            // Sable has already interpolated an attached projectile with its mesh.
            // A second world-space interpolation buries it when the mesh moves.
            // Snap on attachment/detachment too, rather than carrying free-flight lag.
            auto start = valid && !attached && !onStructure ? Get(now) : position;
            double jump2 = 0;
            for (int k=0; k<3; ++k) jump2 += (position[k]-start[k])*(position[k]-start[k]);
            if (jump2 > 4096.0) start = position;
            duration = valid ? std::clamp(now-at, 0.001, 0.05) : 0.05;
            from = start; target = position; at = now; valid = true; attached = onStructure;
        }
    };
}
