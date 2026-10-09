#pragma once

#include <algorithm>
#include <cstdint>

namespace skycraft
{
    // Visual timing only. Minecraft retains the player's physical position and velocity.
    class SupportRenderHandoff
    {
    public:
        void Reset() { weight = 0.0; groundedTicks = 0; lastGroundTick = 0; }

        double Update(bool movingSupport, bool onGround, std::int64_t tick, double dt)
        {
            if (movingSupport) {
                weight = 1.0;
                groundedTicks = 0;
                lastGroundTick = tick;
            } else if (weight > 0.0) {
                // Leaving a deck must not replay earlier, lower world-space positions.
                // Keep the same frame clock throughout the real jump/fall from it.
                if (!onGround) {
                    weight = 1.0;
                    groundedTicks = 0;
                    lastGroundTick = tick;
                } else {
                    if (tick != 0 && tick != lastGroundTick) {
                        lastGroundTick = tick;
                        ++groundedTicks;
                    }
                    // One stale grounded tick at the edge is not a completed landing.
                    if (groundedTicks >= 2)
                        weight = std::max(0.0, weight - std::clamp(dt, 0.0, 0.05) * 5.0);
                }
            }
            return weight;
        }

    private:
        double weight = 0.0;
        unsigned groundedTicks = 0;
        std::int64_t lastGroundTick = 0;
    };
}
