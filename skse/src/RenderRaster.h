#pragma once

#include <d3d11.h>

namespace skycraft
{
	inline D3D11_RASTERIZER_DESC ModelRasterizer(bool a_cullBack, bool a_shadow = false)
	{
		D3D11_RASTERIZER_DESC desc{};
		desc.FillMode = D3D11_FILL_SOLID;
		desc.CullMode = a_cullBack ? D3D11_CULL_BACK : D3D11_CULL_NONE;
		// Exported Minecraft model quads face counterclockwise in Skyrim's projection.
		desc.FrontCounterClockwise = TRUE;
		desc.DepthClipEnable = a_shadow ? FALSE : TRUE;
		return desc;
	}

	inline D3D11_RASTERIZER_DESC FluidRasterizer()
	{
		auto desc = ModelRasterizer(true);
		// Preserve the existing fluid pass (including its separately emitted reverse top).
		desc.FrontCounterClockwise = FALSE;
		return desc;
	}
}
