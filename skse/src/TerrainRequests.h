#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <unordered_set>
#include <vector>

namespace skycraft::TerrainRequests {
	struct Request { float lo[3], hi[3], anchor[3]; };
	static_assert(sizeof(Request)==36);
	struct Bounds { float lo[3], hi[3]; };
	struct Candidate { std::array<int,3> region; double distance; };
	inline bool Valid(const Request& r) {
		for(int k=0;k<3;k++) if(!std::isfinite(r.lo[k])||!std::isfinite(r.hi[k])||!std::isfinite(r.anchor[k])
			||std::abs(r.lo[k])>30000000||std::abs(r.hi[k])>30000000||r.lo[k]>r.hi[k]) return false;
		return true;
	}
	inline bool Contains(const Request& r,const std::array<int,3>& region) {
		for(int k=0;k<3;k++) if((region[k]+1)*8.0f<r.lo[k]||region[k]*8.0f>r.hi[k]) return false;
		return true;
	}
	// Only request regions touching real, loaded Havok bodies. Never invent unknown ground.
	inline std::vector<Candidate> Plan(const std::vector<Request>& requests,const std::vector<Bounds>& bodies) {
		std::vector<Candidate> result; std::unordered_set<std::uint64_t> seen;
		for(const auto& r:requests) for(const auto& b:bodies) {
			int lo[3],hi[3]; bool overlaps=true;
			for(int k=0;k<3;k++) {
				float lower=std::max(r.lo[k],b.lo[k]),upper=std::min(r.hi[k],b.hi[k]);
				if(!std::isfinite(lower)||!std::isfinite(upper)||lower>upper) { overlaps=false; break; }
				lo[k]=int(std::floor(lower/8)); hi[k]=int(std::floor(upper/8));
			}
			if(!overlaps) continue;
			for(int x=lo[0];x<=hi[0]&&result.size()<4096;x++)
				for(int z=lo[2];z<=hi[2]&&result.size()<4096;z++)
					for(int y=lo[1];y<=hi[1]&&result.size()<4096;y++) {
						auto key=(std::uint64_t(std::uint32_t(x)&0x1FFFFF)<<42)|(std::uint64_t(std::uint32_t(y)&0x1FFFFF)<<21)|(std::uint32_t(z)&0x1FFFFF);
						if(!seen.insert(key).second) continue;
						double dx=(x+.5)*8-r.anchor[0],dy=(y+.5)*8-r.anchor[1],dz=(z+.5)*8-r.anchor[2];
						result.push_back({{x,y,z},dx*dx+dz*dz+dy*dy*.25});
					}
		}
		std::ranges::sort(result,{},&Candidate::distance); return result;
	}
}
