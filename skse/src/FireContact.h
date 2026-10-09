#pragma once
#include <algorithm>
#include <cmath>
#include <string_view>

namespace skycraft::fire_contact {
    inline bool IsFixture(std::string_view name) {
        if (name.find("unlit") != name.npos || name.find("cold") != name.npos ||
            name.find("ashes") != name.npos || name.find("nofire") != name.npos) return false;
        return name.find("campfire") != name.npos || name.find("woodfire") != name.npos ||
            name.find("firepit") != name.npos || name.find("fxfire") != name.npos ||
            name.find("firewithembers") != name.npos || name.find("bonfire") != name.npos;
    }
    // Vertical capsule footprint, expressed in the caller's coordinate units.
    inline bool Overlaps(float x, float y, float feet, float radius, float height,
                         float fireX, float fireY, float fireBottom, float fireTop, float fireRadius) {
        const float dx = x - fireX, dy = y - fireY;
        const float reach = radius + fireRadius;
        return dx * dx + dy * dy < reach * reach && feet < fireTop && feet + height > fireBottom;
    }
    inline bool IntersectsColumn(double x, double z, double radius, int cellX, int cellZ) {
        const double dx = x - std::clamp(x, double(cellX), double(cellX + 1));
        const double dz = z - std::clamp(z, double(cellZ), double(cellZ + 1));
        return dx * dx + dz * dz < radius * radius;
    }
}
