#pragma once
#include <cstddef>
#include "skycraft_protocol.h"
#include <cmath>
#include <cstring>
#include <span>
#include <vector>

namespace skycraft::render
{
	template <class Material>
	std::vector<proto::ColTri> VisualTerrainTriangles(std::span<const float> a_positions,
		std::span<const std::uint16_t> a_indices, Material a_material)
	{
		std::vector<proto::ColTri> triangles;
		triangles.reserve(a_indices.size() / 3);
		for (std::size_t i = 0; i + 2 < a_indices.size(); i += 3) {
			proto::ColTri triangle{};
			bool valid = true;
			for (int corner = 0; corner < 3; ++corner) {
				std::size_t vertex = std::size_t(a_indices[i + corner]) * 3;
				if (vertex + 2 >= a_positions.size()) { valid = false; break; }
				for (int axis = 0; axis < 3; ++axis) {
					float v = a_positions[vertex + axis];
					if (!std::isfinite(v) || std::abs(v) >= 1.0e6f) valid = false;
					triangle.v[corner * 3 + axis] = v;
				}
			}
			if (!valid) continue;
			triangle.flags = proto::kTriTerrain | proto::kTriDiggable |
				(std::uint32_t(a_material((triangle.v[0] + triangle.v[3] + triangle.v[6]) / 3.0,
					(triangle.v[2] + triangle.v[5] + triangle.v[8]) / 3.0)) << proto::kTriMaterialShift);
			triangles.push_back(triangle);
		}
		return triangles;
	}

	inline std::vector<std::uint8_t> EncodeVisualTerrain(std::uint64_t a_mesh, std::uint32_t a_epoch, std::span<const proto::ColTri> a_triangles)
	{
		proto::ColVisualTerrain header{ a_mesh, a_epoch, std::uint32_t(a_triangles.size()) };
		std::vector<std::uint8_t> payload(sizeof(header) + a_triangles.size_bytes());
		std::memcpy(payload.data(), &header, sizeof(header));
		if (!a_triangles.empty()) std::memcpy(payload.data() + sizeof(header), a_triangles.data(), a_triangles.size_bytes());
		return payload;
	}
}
