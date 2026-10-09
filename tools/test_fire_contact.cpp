#include "../skse/src/FireContact.h"
#include <iostream>
#include <cstdlib>

int main() {
    using namespace skycraft::fire_contact;
    unsigned tests = 0;
    auto check = [&](bool result, const char* name) {
        if (!result) { std::cerr << "FAILED: " << name << '\n'; std::exit(1); }
        ++tests;
    };
    check(Overlaps(45,0,0,20,128,0,0,-16,48,32), "body edge enters fire while feet center remains outside");
    check(!Overlaps(53,0,0,20,128,0,0,-16,48,32), "actor outside fire does not burn");
    check(Overlaps(0,0,-30,20,128,0,0,-16,48,32), "body intersects elevated flames");
    check(!Overlaps(0,0,49,20,128,0,0,-16,48,32), "actor above flames does not burn");
    check(!Overlaps(0,0,-150,20,128,0,0,-16,48,32), "actor below flames does not burn");
    check(IntersectsColumn(.9,.5,.3,1,0), "neighbor fire block touches body edge");
    check(!IntersectsColumn(.5,.5,.3,1,0), "neighbor block remains beyond body");
    check(!IntersectsColumn(.75,.75,.3,1,1), "empty bounding box corner excluded");
    check(IntersectsColumn(.9,.9,.3,1,1), "actual diagonal contact retained");
    check(IntersectsColumn(-.1,-.5,.3,-1,-1), "negative coordinates retain contact");
    check(IsFixture("effects/fxfirewithembers01.nif"), "native FX flame recognized");
    check(IsFixture("clutter/woodfires/campfire01.nif"), "campfire recognized");
    check(!IsFixture("effects/fxfireunlit01.nif"), "unlit FX excluded");
    check(!IsFixture("clutter/woodfires/coldashes.nif"), "cold ashes excluded");
    check(!IsFixture("clutter/torches/torch01.nif"), "torch excluded");
    std::cout << tests << " fire contact cases passed\n";
}
