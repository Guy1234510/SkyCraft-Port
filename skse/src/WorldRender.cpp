#include "Dig.h"
#include "Game.h"
#include "Perf.h"
#include "RenderView.h"
#include "RenderRaster.h"
#include "ProjectileSurface.h"

#include "Collision.h"

#include <d3dcompiler.h>

#include <filesystem>
#include <fstream>

namespace skycraft
{
	namespace
	{
		template <class T>
		void Release(T*& a_ptr)
		{
			if (a_ptr) {
				a_ptr->Release();
				a_ptr = nullptr;
			}
		}

		using Vertex = proto::RenVertex;
		ID3D11DeviceContext* NativeDrawContext()
		{
			// DrawTriangles (AE ID77263) loads this global before every COM draw.
			// RendererData.context can expose a different context/interface vtable.
			// Use the engine's actual drawing context for state and interception.
			if (REL::Module::IsAE()) {
				static REL::Relocation<ID3D11DeviceContext**> context{ REL::ID(411391) };
				if (*context) return *context;
			}
			auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
			return renderer ? reinterpret_cast<ID3D11DeviceContext*>(renderer->GetRuntimeData().context) : nullptr;
		}
		constexpr std::uint32_t kFlagCutout = 1;
		constexpr std::uint32_t kFlagTranslucent = 2;
		constexpr std::uint32_t kFlagUntextured = 4;
		constexpr std::uint32_t kFlagNoMip = 8;                // entity textures: always full detail, like Minecraft
		constexpr std::uint32_t kFlagNormalFromFaces = 7u << 4;  // lit by the triangle's own normal
		constexpr std::uint32_t kFullSkyLight = 15u << 8;
		constexpr float         kUnits = static_cast<float>(proto::kUnitsPerBlock);
		constexpr float         kPi = 3.14159265f;

		constexpr char kShader[] = R"(
cbuffer Frame : register(b0)
{
	row_major float4x4 viewProj;  // camera-relative Skyrim units -> clip
	float4 depthParams;           // near, far, reversed, occlusion enabled
	float4 screen;                // 1/width, 1/height of the back buffer
	float4 sunDir;                // towards the sun, moon or interior light (Skyrim axes); w: exposure
	float4 sunColor;              // rgb; w: point light count
	float4 ambient[6];            // Skyrim's directional ambient: +X, -X, +Y, -Y, +Z, -Z
	float4 fogRange;              // 1/(far-near), near/(far-near), power, max amount
	float4 fogNear;               // rgb; w: fog on
	float4 fogFar;                // rgb
	float4 grade;                 // Skyrim's cinematic saturation, brightness, contrast; w: grading on
	float4 tint;                  // rgb, amount
	row_major float4x4 lightViewProj;  // camera-relative Skyrim units -> sun shadow map
	row_major float4x4 invViewProj;    // screen (ndc x, y, Skyrim depth) -> camera-relative Skyrim units
	row_major float4x4 prevViewProj;   // last frame's camera, re-based on this frame's (motion vectors)
	float4 shadowParams;          // x: sun shadows on, y: normal offset (units), z: shadow map texel (uv), w: depth bias
	float4 shadowExtra;           // x: Skyrim's shadow mask usable, y: squared reach of the shadow map (units)
	float4 renderMode;            // x: into Skyrim's HDR scene (Skyrim tone maps and grades), y: Skyrim's depth buffer occludes
	float4 camRight;              // camera axes (Skyrim world), for view-space normals
	float4 camUp;
	float4 camForward;
	row_major float4x4 skyShadowProj[2];  // Skyrim's sun shadow cascades: camera-relative -> shadow map uv, depth
	float4 skyShadowSplits;       // cascade 0 end, cascade 1 end (view depth), -, cascade count
	float4 skyShadowParams;       // slice of cascade 0, slice of cascade 1, texel size (uv), +1 nearer = smaller depth / -1 reversed
	float4 lightPos[16];          // camera-relative; w: 1/radius
	float4 lightColor[16];        // rgb
	float4 aoParams;              // x: contact shadow count
	float4 aoBlobs[24];           // Minecraft players' and mobs' feet, camera-relative; w: radius
	float4 aoVolume;              // contactVolume's corner, camera-relative in Minecraft blocks (x, y up, z); w: on
	float4 projectionJitter;      // current scene's clip-space TAA shift; motion remains unjittered
	float4 waterPlane;            // surface height relative to camera (Skyrim Z), valid
};
cbuffer Object : register(b1)
{
	float4 offset;                // camera-relative Skyrim position of the mesh's Minecraft origin
	float4 previousOffset;
	row_major float4x4 model;      // section-local Minecraft axes -> rotated Minecraft axes
	row_major float4x4 previousModel;
};
Texture2D atlas : register(t0);
Texture2D<float> sceneDepth : register(t1);
Texture2D<float> shadowMap : register(t2);    // Minecraft blocks seen from the sun
Texture2D skyrimShadow : register(t3);        // Skyrim's screen-space shadow mask (r: sun, 1 = lit)
SamplerState atlasSampler : register(s0);
SamplerState pointSampler : register(s1);
SamplerComparisonState shadowSampler : register(s2);
Texture2DArray<float> skyrimShadowMaps : register(t4);  // Skyrim's own shadow maps
// What's solid around the camera, per Minecraft block (x, y up, z): bit 0 a Minecraft block; bit 1
// Skyrim geometry, bits 2-19 the box around it (lo x, y, z, hi x, y, z in eighths, hi inclusive).
Texture3D<uint> contactVolume : register(t5);
Texture2D<float> surfaceDepth : register(t6); // intact Skyrim MAIN: water surface or opaque geometry
Texture2D<float4> effectLayer : register(t7); // premultiplied native local smoke/fire, isolated from terrain

struct VSIn
{
	float3 pos : POSITION;
	float2 uv : TEXCOORD0;
	float4 color : COLOR0;
	uint light : TEXCOORD1;
	uint flags : TEXCOORD2;
};
struct VSOut
{
	float4 pos : SV_Position;
	float2 uv : TEXCOORD0;
	float4 color : COLOR0;
	float2 light : TEXCOORD1;
	nointerpolation uint flags : TEXCOORD2;
	float viewZ : TEXCOORD3;
	float3 rel : TEXCOORD4;       // camera-relative Skyrim position
	float4 curClip : TEXCOORD5;
	float4 prevClip : TEXCOORD6;
};
struct PSOut
{
	float4 color : SV_Target0;
	float4 motion : SV_Target1;       // Skyrim's motion vectors (for its TAA), when bound
	float4 normal : SV_Target2;       // Skyrim's normals + TAA/reflection masks, when bound
};

// Skyrim's normal buffer encoding (view space: x right, y up, z forward): nothing special for
// TAA, no screen-space reflections.
float4 EncodeSkyrimNormal(float3 n)
{
	float3 v = float3(dot(n, camRight.xyz), dot(n, camUp.xyz), dot(n, camForward.xyz));
	float  f = max(0.001, sqrt(8.0 - 8.0 * v.z));
	return float4(v.xy / f + 0.5, 0.0, 0.0);
}

float4 CameraClip(float3 rel, uint flags)
{
	// The colour and copied-depth passes must produce identical depth, including
	// compiler rounding. Only surfaces deliberately laid over another surface
	// receive a small, distance-independent offset (0.002 Minecraft blocks).
	precise float4 clip = mul(viewProj, float4(rel, 1.0));
	if ((flags & 128) && clip.w > 0.14) {
		float4 forwardClip = mul(viewProj, float4(camForward.xyz, 0.0));
		float4 nearer = clip - forwardClip * 0.14;
		clip.z = nearer.z * clip.w / nearer.w;
	}
	return clip;
}

VSOut VSMain(VSIn i)
{
	VSOut o;
	float3 world = mul(model, float4(i.pos, 1.0)).xyz;
	float3 rel = offset.xyz + float3(world.x, -world.z, world.y) * 70.0;  // Minecraft -> Skyrim axes
	o.pos = CameraClip(rel, i.flags);
	o.viewZ = o.pos.w;
	o.uv = i.uv;
	o.color = i.color;
	o.light = float2(i.light & 0xFF, (i.light >> 8) & 0xFF) / 15.0;
	o.flags = i.flags;
	o.rel = rel;
	o.curClip = o.pos;
	o.curClip.xy -= projectionJitter.xy * o.curClip.w;
	float3 previous = mul(previousModel, float4(i.pos, 1.0)).xyz;
	float3 previousRel = previousOffset.xyz + float3(previous.x, -previous.z, previous.y) * 70.0;
	o.prevClip = mul(prevViewProj, float4(previousRel, 1.0));
	return o;
}

float SceneViewDepth(float d)
{
	float n = depthParams.x, f = depthParams.y;
	return depthParams.z > 0.5 ? n * f / (n + d * (f - n)) : n * f / (f - d * (f - n));
}

float Curve(float l) { return l / (4.0 - 3.0 * l); }  // Minecraft's light-level falloff

// Face normals by Minecraft Direction ordinal + 1, in Skyrim axes (x east, y north, z up).
static const float3 kNormals[8] = {
	float3(0, 0, 0), float3(0, 0, -1), float3(0, 0, 1), float3(0, 1, 0),
	float3(0, -1, 0), float3(-1, 0, 0), float3(1, 0, 0), float3(0, 0, 0) };

// Skyrim's ambient cube, as its own lighting shader applies it.
float3 Ambient(float3 n)
{
	float3 n2 = n * n;
	return n2.x * (n.x >= 0 ? ambient[0].rgb : ambient[1].rgb)
	     + n2.y * (n.y >= 0 ? ambient[2].rgb : ambient[3].rgb)
	     + n2.z * (n.z >= 0 ? ambient[4].rgb : ambient[5].rgb);
}

// How much sun reaches a camera-relative point past the Minecraft blocks: 1 lit, 0 shadowed.
// n pushes the lookup off the surface so faces don't shadow themselves.
float SunShadow(float3 rel, float3 n)
{
	if (shadowParams.x < 0.5) {
		return 1.0;
	}
	float4 lp = mul(lightViewProj, float4(rel + n * shadowParams.y, 1.0));
	float  edge = max(abs(lp.x), abs(lp.y));
	if (edge >= 1.0 || lp.z >= 1.0) {
		return 1.0;
	}
	float2 uv = float2(lp.x * 0.5 + 0.5, 0.5 - lp.y * 0.5);
	float  ref = lp.z - shadowParams.w;
	float  lit = 0.0;
	[unroll] for (int y = -1; y <= 1; ++y) {
		[unroll] for (int x = -1; x <= 1; ++x) {
			lit += shadowMap.SampleCmpLevelZero(shadowSampler, uv + float2(x, y) * shadowParams.z, ref);
		}
	}
	return lerp(1.0, lit / 9.0, saturate((1.0 - edge) * 8.0));  // fades out towards the map's edge
}

// Sample one of Skyrim's sun-shadow cascades. A point outside the cascade is lit.
float SkyrimShadowCascade(float3 rel, float3 n, uint cascade)
{
	float4 ls = mul(skyShadowProj[cascade], float4(rel + n * 6.0, 1.0));
	ls.xyz /= ls.w;
	// Out-of-range depth is outside this cascade too. Sampling it as a valid
	// occluder made whole faces go black as the view crossed cascade boundaries.
	if (any(ls.xy <= 0.0) || any(ls.xy >= 1.0) || ls.z <= 0.0 || ls.z >= 1.0) {
		return 1.0;
	}
	float slice = cascade == 0 ? skyShadowParams.x : skyShadowParams.y;
	float lit = 0.0;
	[unroll] for (int y = -1; y <= 1; ++y) {
		[unroll] for (int x = -1; x <= 1; ++x) {
			float d = skyrimShadowMaps.SampleLevel(pointSampler, float3(ls.xy + float2(x, y) * skyShadowParams.z, slice), 0);
			lit += skyShadowParams.w > 0.0 ? (ls.z - 0.0005 <= d ? 1.0 : 0.0) : (ls.z + 0.0005 >= d ? 1.0 : 0.0);
		}
	}
	return lit / 9.0;
}

// How much sun reaches a point past Skyrim's own geometry. Blend the maps near their
// split instead of letting camera rotation switch the block's lighting in one frame.
float SkyrimSunShadow(float3 rel, float3 n, float viewZ)
{
	if (skyShadowSplits.w < 0.5) return 1.0;
	float firstEnd = skyShadowSplits.x;
	if (skyShadowSplits.w > 1.5 && viewZ >= firstEnd * 0.90 && viewZ < firstEnd) {
		float a = SkyrimShadowCascade(rel, n, 0);
		float b = SkyrimShadowCascade(rel, n, 1);
		float t = saturate((viewZ - firstEnd * 0.90) / (firstEnd * 0.10));
		t = t * t * (3.0 - 2.0 * t);
		return lerp(a, b, t);
	}
	uint cascade = viewZ < firstEnd ? 0 : 1;
	if (cascade >= (uint)skyShadowSplits.w) return 1.0;
	float end = cascade == 0 ? firstEnd : skyShadowSplits.y;
	if (viewZ >= end) return 1.0;
	float shadow = SkyrimShadowCascade(rel, n, cascade);
	if (cascade == 1) {
		shadow = lerp(shadow, 1.0, saturate((viewZ - end * 0.85) / (end * 0.15)));
	}
	return shadow;
}

// Skyrim's lights on a Minecraft surface. Minecraft's sky light still darkens what Minecraft
// blocks roof over, and its block light (torches, glowstone) still glows.
float3 Lighting(VSOut i, float3 n, bool hasNormal, float sunVisible)
{
	if (i.flags & 4096) return float3(1.0, 1.0, 1.0); // emissive material, independent of ambient/night vision
	float  sky = Curve(i.light.y);
	float3 amb = hasNormal ? Ambient(n)
	           : (ambient[0].rgb + ambient[1].rgb + ambient[2].rgb + ambient[3].rgb + 2.0 * ambient[4].rgb) / 6.0;
	float  sun = hasNormal ? saturate(dot(n, sunDir.xyz)) : 0.35 + 0.4 * saturate(sunDir.z);
	// Moving structures retain the ordinary ambient floor; refreshed vertex light controls skylight.
	float3 lit = amb * lerp(0.3, 1.0, sky) + sunColor.rgb * (sun * sky * sky * sunVisible);
	uint count = (uint)sunColor.w;
	for (uint k = 0; k < count; ++k) {
		float3 d = lightPos[k].xyz - i.rel;
		float  f = saturate(length(d) * lightPos[k].w);
		float  l = hasNormal ? saturate(dot(n, normalize(d))) : 0.75;
		lit += lightColor[k].rgb * ((1.0 - f * f) * l);
	}
	lit = renderMode.x > 0.5 ? max(lit, 0.0) : 1.0 - exp(-max(lit, 0.0) * sunDir.w);
	float block = Curve(i.light.x);
	lit = max(lit, block * float3(1.0, 0.85, 0.65));
	// Night vision illuminates Minecraft materials, including sealed rooms.
	// Its neutral light floor is independent of the bounded Skyrim image lift.
	return lerp(lit, max(lit, float3(0.85, 0.85, 0.85)), saturate(screen.w));
}

// Soft contact shadows under Minecraft players and mobs (their bodies are drawn after Skyrim's own
// ambient occlusion, so they'd otherwise look pasted onto the ground): the ground darkens most right
// under the feet and fades out over about a body's width. Ground-facing surfaces only.
float BlobAO(float3 rel, float3 n)
{
	float up = saturate(n.z * 1.25);
	float ao = 1.0;
	if (up <= 0.0) {
		return ao;
	}
	for (int k = 0; k < (int)aoParams.x; ++k) {
		float3 dv = rel - aoBlobs[k].xyz;
		float  r = aoBlobs[k].w;
		float  h = length(dv.xy) / r;
		if (h < 1.0 && dv.z > -0.45 * r && dv.z < 0.4 * r) {
			float fall = 1.0 - h;
			float vert = 1.0 - saturate(abs(dv.z) / (0.45 * r));
			ao *= 1.0 - 0.6 * fall * fall * vert * up;
		}
	}
	return ao;
}

)"  // MSVC caps one string literal at 16 KB
			R"(// The cosine-weighted share of the sky that a sphere hides from a point with normal nor (exact:
// Quilez; Lagarde & de Rousiers where it crosses the horizon).
float SphereOcclusion(float3 pos, float3 nor, float4 sph)
{
	float3 di = sph.xyz - pos;
	float  l = length(di);
	float  h = l / sph.w;
	if (h <= 1.0) {
		return 0.8;  // inside: right against it
	}
	float nl = dot(nor, di / l);
	float h2 = h * h;
	float k2 = 1.0 - h2 * nl * nl;
	float res = max(0.0, nl) / h2;
	if (k2 > 0.001) {
		res = nl * acos(clamp(-nl * sqrt((h2 - 1.0) / (1.0 - nl * nl)), -1.0, 1.0)) - sqrt(k2 * (h2 - 1.0));
		res = res / h2 + atan(sqrt(k2 / (h2 - 1.0)));
		res /= 3.141593;
	}
	return saturate(res);
}

// Contact shadows where Minecraft blocks meet Skyrim's world, from contactVolume: what's solid in
// the 3x3x3 blocks around a point hides part of its sky, each solid thing counted as a sphere of
// its volume. Skyrim's surfaces are darkened by Minecraft blocks (minecraft = true); the blocks'
// own faces by Skyrim's geometry (Minecraft's own occlusion already darkens them by other blocks),
// at half strength.
float VoxelAO(float3 rel, float3 nSky, bool minecraft)
{
	if (aoVolume.w < 0.5) {
		return 1.0;
	}
	float3 fromCam = float3(rel.x, rel.z, -rel.y) / 70.0;  // Minecraft axes, blocks
	float3 p = fromCam - aoVolume.xyz;
	float3 n = float3(nSky.x, nSky.z, -nSky.y);
	uint   w, h, d;
	contactVolume.GetDimensions(w, h, d);
	int3 c = (int3)floor(p + n * 0.01);
	if (any(c < 1) || c.x >= (int)w - 1 || c.y >= (int)h - 1 || c.z >= (int)d - 1) {
		return 1.0;
	}
	// Fades out well inside the volume, which follows the camera, so it never pops at the edge.
	float fade = 1.0 - max(saturate((max(abs(fromCam.x), abs(fromCam.z)) - 22.0) / 6.0), saturate((abs(fromCam.y) - 9.0) / 4.0));
	if (fade <= 0.0) {
		return 1.0;
	}
	float keep = 1.0;
	[loop] for (int dz = -1; dz <= 1; ++dz) {
		[loop] for (int dy = -1; dy <= 1; ++dy) {
			[loop] for (int dx = -1; dx <= 1; ++dx) {
				int3   q = c + int3(dx, dy, dz);
				uint   v = contactVolume.Load(int4(q, 0));
				float3 lo, hi;
				if (minecraft) {
					if (!(v & 1)) {
						continue;
					}
					lo = q;
					hi = q + 1.0;
				} else {
					if (!(v & 2) || (v & 1)) {
						continue;  // nothing, or buried inside a Minecraft block (hidden)
					}
					lo = q + float3((v >> 2) & 7, (v >> 5) & 7, (v >> 8) & 7) / 8.0;
					hi = q + float3(((v >> 11) & 7) + 1, ((v >> 14) & 7) + 1, ((v >> 17) & 7) + 1) / 8.0;
				}
				// Only what's in front of the surface can hide its sky (its sphere could reach through).
				if (dot(n, lerp(lo, hi, step(0.0, n)) - p) <= 0.02) {
					continue;
				}
				float3 size = hi - lo;
				keep *= 1.0 - SphereOcclusion(p, n, float4((lo + hi) * 0.5, 0.62 * pow(max(size.x * size.y * size.z, 1e-4), 1.0 / 3.0)));
			}
		}
	}
	return lerp(1.0, keep, (minecraft ? 0.85 : 0.42) * fade);  // lighter on the blocks' own faces
}

float4 EffectCompositePS(float4 pos : SV_Position) : SV_Target
{
    return effectLayer.Load(int3(int2(pos.xy), 0));
}

PSOut PSMain(VSOut i, bool frontFace : SV_IsFrontFace)
{
	if ((i.flags & 32768) && !frontFace) discard;
	PSOut  po;
	// The camera, not the entity's height, selects the underwater opaque path.
	// An above-water camera retains revision54's surface/refraction clipping.
	bool submergedEntity = renderMode.w > 0.5 && waterPlane.y > 0.5 && i.rel.z < waterPlane.x;
	float passMode = submergedEntity ? 0.0 : renderMode.z;
	float2 cur = i.curClip.xy / i.curClip.w, prev = i.prevClip.xy / i.prevClip.w;
	po.motion = float4(float2(-0.5, 0.5) * (cur - prev), 0.0, 1.0);  // Skyrim's convention: previous uv - current uv
	float3 faceN = normalize(cross(ddy(i.rel), ddx(i.rel)));
	float2 samplePos = i.pos.xy;
	if (renderMode.y > 1.5) samplePos += float2(projectionJitter.x, -projectionJitter.y) * 0.5 / screen.xy;
	float2 suv = samplePos * screen.xy;
	float  sceneZ = 1e9;
	float surfaceZ = 1e9;
	if (passMode > 0.5) {
		// MAIN holds the water surface; sceneDepth holds opaque terrain.
		surfaceZ = SceneViewDepth(surfaceDepth.Load(int3(int2(samplePos), 0)));
	}
	if (depthParams.w > 0.5) {
		sceneZ = SceneViewDepth(sceneDepth.SampleLevel(pointSampler, suv, 0));
		// Use a private SkyCraft depth buffer while compositing in-frame. Testing
		// Skyrim's DSV directly makes coplanar terrain surfaces fight for depth;
		// compare against its saved opaque depth here instead.
		if (i.viewZ > sceneZ * 1.002 + 1.5) {
			discard;  // behind Skyrim geometry
		}
	}
	float3 viewerN = dot(faceN, i.rel) > 0 ? -faceN : faceN;
	bool waterPixel = surfaceZ + 0.5 < sceneZ;
	// A nearer MAIN depth is not necessarily water (particles and temporal depth
	// can also differ from the opaque snapshot). Dry airborne bodies never enter
	// the refraction-only path just because those depth copies differ.
	bool behindWater = waterPixel && i.viewZ > surfaceZ + 0.25
		&& (renderMode.w > 0.5 || (waterPlane.y > 0.5 && i.rel.z < waterPlane.x));
	bool underwaterGeometry = renderMode.w > 0.5 && waterPlane.y > 0.5 && i.rel.z < waterPlane.x;
	// Foreground is revision54: submerged fragments never overwrite water.
	// Refraction writes ONLY those fragments into private native water inputs.
	if (passMode > 1.5) {
		if (!(renderMode.w > 0.5 ? underwaterGeometry : behindWater)) discard;
	} else if (passMode > 0.5 && (behindWater || underwaterGeometry)) {
		discard;
	}
	po.normal = EncodeSkyrimNormal(viewerN);
	if (i.flags & 4) {
		po.color = i.color;
		if (renderMode.y < 1.5 && passMode > 0.5 && passMode < 1.5 && (i.flags & 2)) po.color.a = 0.0;
		return po;
	}
	float4 t = (i.flags & 8) ? atlas.SampleLevel(atlasSampler, i.uv, 0) : atlas.Sample(atlasSampler, i.uv);
	if (!(i.flags & (2 | 16384)) && t.a < 0.5) {
		discard;
	}
	// Private particle depth needs coverage, cutouts and water clipping only.
	if (screen.z > 0.5) { po.color = 0; return po; }
	uint   ni = (i.flags >> 4) & 7;
	// WorldExporter sends directions 1..6 for stationary world sections: those normals are
	// already in Skyrim axes. Moving sections use 7, whose derivatives include VSMain's model
	// transform. Keep ordinary lighting independent of the moving object's constant buffer.
	float3 n = ni == 7 ? viewerN : kNormals[ni];
	po.normal = EncodeSkyrimNormal(ni == 0 ? viewerN : n);
	// Sun blocked by other Minecraft blocks, and by Skyrim's own geometry (its sun shadow maps).
	// (Not Skyrim's screen-space shadow mask: that holds whatever Skyrim surface is behind the block.)
	float sunVisible = (i.flags & 4096) ? 1.0 : SunShadow(i.rel, n) * SkyrimSunShadow(i.rel, n, i.viewZ);
	float3 c = t.rgb * i.color.rgb * Lighting(i, n, ni != 0, sunVisible);
	if (ni >= 1 && ni <= 6) {
		// Players and mobs standing on Minecraft blocks; the block against Skyrim's ground and walls.
		c *= BlobAO(i.rel, n) * VoxelAO(i.rel, n, false);
	}
	// Keep dust's power tint, but light it normally: unpowered dust is not emissive.
	// Exclude the camera-selected Skyrim shadow cascade which changed its apparent
	// power; local lights, ambient light and world-space MC shadows still apply.
	if (i.flags & 512) {
		float3 dustTint = (i.flags & 256) ? i.color.rgb : float3(0.3, 0.0, 0.0);
		c = t.rgb * dustTint * Lighting(i, n, ni != 0, SunShadow(i.rel, n));
	}
	if (fogNear.w > 0.5) {
		float f = min(pow(saturate(length(i.rel) * fogRange.x - fogRange.y), fogRange.z), fogRange.w);
		c = (i.flags & 8192) ? c * (1.0 - f) : lerp(c, lerp(fogNear.rgb, fogFar.rgb, f), f);
	}
	if (grade.w > 0.5 && renderMode.x < 0.5 && !(i.flags & (4096 | 8192))) {
		// Skyrim's image space: saturation, tint, brightness, contrast.
		float lum = dot(c, float3(0.2125, 0.7154, 0.0721));
		c = lerp(lum.xxx, c, grade.x);
		c = lerp(c, lum * tint.rgb, tint.a);
		c = lerp(0.35, c * grade.y, grade.z);
	}
	po.color = float4(renderMode.x > 0.5 ? c : saturate(c), ((i.flags & 2) ? t.a * i.color.a : 1.0));
	if (renderMode.y > 1.5) {
		// Repaint after native temporal resolve. Keep real smoke/fire coverage
		// in RGB; opacity belongs to the MC material, never to terrain behind it.
		float4 effect = effectLayer.Load(int3(int2(samplePos), 0));
		// Additive glow is drawn over colour that already contains the native effect.
		// Re-adding its RGB lights the entire model, including black/empty glow texels.
		// Only attenuate that emission; ordinary alpha materials still composite coverage.
		po.color.rgb *= 1.0 - saturate(effect.a);
		if (!(i.flags & 8192)) po.color.rgb += saturate(effect.rgb);
	}
	if (!(i.flags & 2)) {
		po.color.a = 1.0;
	}
	if (renderMode.y < 1.5 && passMode > 0.5 && passMode < 1.5 && (i.flags & 2)) po.color.a = 0.0;
	return po;
}

// 16 depth samples across the screen, for detecting Skyrim's depth convention.
static const float2 kProbe[16] = {
	float2(0.1, 0.1), float2(0.3, 0.1), float2(0.5, 0.1), float2(0.7, 0.1), float2(0.9, 0.1),
	float2(0.2, 0.35), float2(0.5, 0.35), float2(0.8, 0.35),
	float2(0.2, 0.65), float2(0.5, 0.65), float2(0.8, 0.65),
	float2(0.1, 0.9), float2(0.3, 0.9), float2(0.5, 0.9), float2(0.7, 0.9), float2(0.9, 0.9) };
float4 ProbeVS(uint id : SV_VertexID) : SV_Position
{
	float2 uv = float2((id << 1) & 2, id & 2);
	return float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
}
float2 ProbePS(float4 pos : SV_Position) : SV_Target
{
	float2 uv = kProbe[(uint)pos.x];
	return float2(sceneDepth.SampleLevel(pointSampler, uv, 0), skyrimShadow.SampleLevel(pointSampler, uv, 0).r);
}

// Debug: for the 16 probe points, Skyrim's surface there projected into its sun shadow map:
// (our shadow-map depth, stored depth, stored depth with v flipped, cascade + 10 if outside).
float4 SkyShadowProbePS(float4 pos : SV_Position) : SV_Target
{
	float2 uv = kProbe[(uint)pos.x];
	float  d = sceneDepth.SampleLevel(pointSampler, uv, 0);
	float4 h = mul(invViewProj, float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, d, 1.0));
	float3 rel = h.xyz / h.w;
	float  viewZ = dot(rel, camForward.xyz);
	uint   cascade = viewZ < skyShadowSplits.x ? 0 : 1;
	float4 ls = mul(skyShadowProj[cascade], float4(rel, 1.0));
	ls.xyz /= ls.w;
	float slice = cascade == 0 ? skyShadowParams.x : skyShadowParams.y;
	float a = skyrimShadowMaps.SampleLevel(pointSampler, float3(ls.xy, slice), 0);
	float b = skyrimShadowMaps.SampleLevel(pointSampler, float3(ls.x, 1.0 - ls.y, slice), 0);
	bool  outside = any(ls.xy < 0.0) || any(ls.xy > 1.0);
	return float4(ls.z, a, b, cascade + (outside ? 10 : 0) + (viewZ >= skyShadowSplits.y ? 100 : 0));
}

// Minecraft blocks into the sun's shadow map (depth only; cutout texels don't cast).
struct ShadowOut
{
	float4 pos : SV_Position;
	float2 uv : TEXCOORD0;
	nointerpolation uint flags : TEXCOORD1;
};
ShadowOut ShadowVS(VSIn i)
{
	ShadowOut o;
	float3 world = mul(model, float4(i.pos, 1.0)).xyz;
	float3 rel = offset.xyz + float3(world.x, -world.z, world.y) * 70.0;
	o.pos = mul(lightViewProj, float4(rel, 1.0));
	o.uv = i.uv;
	o.flags = i.flags;
	return o;
}
ShadowOut DepthVS(VSIn i)
{
	ShadowOut o;
	float3 world = mul(model, float4(i.pos, 1.0)).xyz;
	float3 rel = offset.xyz + float3(world.x, -world.z, world.y) * 70.0;
	o.pos = CameraClip(rel, i.flags);
	o.uv = i.uv;
	o.flags = i.flags;
	return o;
}
void ShadowPS(ShadowOut i, bool frontFace : SV_IsFrontFace)
{
	if ((i.flags & 32768) && !frontFace) discard;
	// Depth copies must cover exactly the texels PSMain accepts. Sampling every
	// surface at mip zero creates invisible occluders when colour uses a mip.
	float alpha = (i.flags & 8) ? atlas.SampleLevel(atlasSampler, i.uv, 0).a : atlas.Sample(atlasSampler, i.uv).a;
	if (!(i.flags & (4 | 16384)) && alpha < 0.5) {
		discard;
	}
}

// The blocks' shadows on Skyrim: each pixel's position comes back from Skyrim's depth buffer;
// where a block hides it from the sun, the sun's share of its light is taken away. Drawn with a
// multiplying blend before the blocks themselves.
float4 OverlayPS(float4 pos : SV_Position) : SV_Target
{
	float2 uv = pos.xy * screen.xy;
	float  d = sceneDepth.SampleLevel(pointSampler, uv, 0);
	float4 h = mul(invViewProj, float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, d, 1.0));
	float3 rel = h.xyz / h.w;
	float3 n = normalize(cross(ddy(rel), ddx(rel)));
	n = dot(n, rel) > 0 ? -n : n;
	bool isSky = depthParams.z > 0.5 ? d <= 1e-6 : d >= 0.999999;
	if (isSky) {
		discard;
	}
	float shadow = dot(rel, rel) > shadowExtra.y ? 1.0 : SunShadow(rel, n);
	float ao = BlobAO(rel, n);  // under players and mobs (not around blocks: the user wants Skyrim's ground left as it is there)
	if (shadow > 0.999 && ao > 0.999) {
		discard;
	}
	float  skyrimLit = shadowExtra.x > 0.5 ? saturate(skyrimShadow.SampleLevel(pointSampler, uv, 0).r) : 1.0;
	float3 lumW = float3(0.2125, 0.7154, 0.0721);
	float  ambL = dot(Ambient(n), lumW);
	float  sunL = dot(sunColor.rgb, lumW) * saturate(dot(n, sunDir.xyz));
	float  keep = (ambL + 0.02) / (ambL + sunL + 0.02);
	return float4(lerp(1.0, keep, (1.0 - shadow) * skyrimLit).xxx * ao, 1.0);
}
)";

		constexpr int kMaxLights = 16;
		constexpr int kMaxBlobs = 24;

		// The sun's shadow map: Minecraft blocks within kShadowRadius (sideways from the sun) of the
		// camera, 2048 texels across (1/16 block each), kShadowDepth either way along the sun.
		constexpr UINT  kShadowSize = 2048;
		constexpr float kShadowRadius = 64.0f * 70.0f;
		constexpr float kShadowDepth = 12000.0f;

		struct alignas(16) FrameConstants
		{
			float viewProj[4][4];
			float depthParams[4];
			float screen[4];
			float sunDir[4];
			float sunColor[4];
			float ambient[6][4];
			float fogRange[4];
			float fogNear[4];
			float fogFar[4];
			float grade[4];
			float tint[4];
			float lightViewProj[4][4];
			float invViewProj[4][4];
			float prevViewProj[4][4];
			float shadowParams[4];
			float shadowExtra[4];
			float renderMode[4];
			float camRight[4];
			float camUp[4];
			float camForward[4];
			float skyShadowProj[2][4][4];
			float skyShadowSplits[4];
			float skyShadowParams[4];
			float lightPos[kMaxLights][4];
			float lightColor[kMaxLights][4];
			float aoParams[4];
			float aoBlobs[kMaxBlobs][4];
			float aoVolume[4];
			float projectionJitter[4];
			float waterPlane[4];
		};

		struct alignas(16) ObjectConstants
		{
			float offset[4];
			float previousOffset[4];
			float model[4][4];
			float previousModel[4][4];
		};
		static_assert(sizeof(ObjectConstants) == 160);
		static_assert(offsetof(ObjectConstants, model) == 32);
		static_assert(offsetof(ObjectConstants, previousModel) == 96);

		struct Section
		{
			ID3D11Buffer* vb{ nullptr };
			std::uint32_t opaque{ 0 };
			std::uint32_t translucent{ 0 };
			std::int32_t  sx{ 0 }, sy{ 0 }, sz{ 0 };
		};

		ID3D11Device*             device = nullptr;
		bool                      initFailed = false;
		ID3D11VertexShader*       vs = nullptr;
		ID3D11PixelShader*        ps = nullptr;
		ID3D11VertexShader*       probeVs = nullptr;
		ID3D11PixelShader*        probePs = nullptr;
		ID3D11InputLayout*        layout = nullptr;
		ID3D11Buffer*             frameCb = nullptr;
		ID3D11Buffer*             objectCb = nullptr;
		ID3D11Buffer*             dynVb = nullptr;
		UINT                      dynCapacity = 0;
		ID3D11SamplerState*       atlasSampler = nullptr;
		ID3D11SamplerState*       pointSampler = nullptr;
		ID3D11RasterizerState*    raster = nullptr;
		ID3D11RasterizerState*    rasterCullBack = nullptr;
		ID3D11RasterizerState*    rasterCullBackModel = nullptr;
		ID3D11DepthStencilState*  depthWrite = nullptr;  // standard Z (0 near, 1 far)
		ID3D11DepthStencilState*  depthRead = nullptr;
		ID3D11DepthStencilState*  depthWriteRev = nullptr;  // reversed Z
		ID3D11DepthStencilState*  depthReadRev = nullptr;
		ID3D11BlendState*         noBlend = nullptr;
		ID3D11BlendState*         alphaBlend = nullptr;
		ID3D11BlendState*         additiveBlend = nullptr;
		ID3D11Texture2D*          ownDepth = nullptr;
		ID3D11DepthStencilView*   ownDsv = nullptr;
		UINT                      ownDepthW = 0, ownDepthH = 0;
		ID3D11Texture2D*          atlasTex = nullptr;
		ID3D11ShaderResourceView* atlasSrv = nullptr;
		bool atlasUploading = false;
		ID3D11Texture2D*          probeTex = nullptr;
		ID3D11RenderTargetView*   probeRtv = nullptr;
		ID3D11Texture2D*          probeStaging = nullptr;
		bool                      probePending = false;
		ID3D11VertexShader*       shadowVs = nullptr;
		ID3D11VertexShader*       depthVs = nullptr;  // blocks into Skyrim's depth copies
		ID3D11PixelShader*        shadowPs = nullptr;
		ID3D11PixelShader*        overlayPs = nullptr;
		ID3D11PixelShader*        skyProbePs = nullptr;  // debug: Skyrim shadow map conventions
		// The sun's shadow cascades, copied the moment Skyrim finishes rendering them (the shared
		// shadow map array is reused for other lights later in the frame).
		ID3D11Texture2D*          sunShadowCopy = nullptr;
		ID3D11ShaderResourceView* sunShadowCopySrv = nullptr;
		// Each cascade's transform and split as they were when the copy was taken (Skyrim updates them
		// at other times; a copy drawn with one frame's transform and read with another's jumps).
		struct CascadeSnapshot
		{
			float        m[4][4];
			float        split;
			std::uint32_t slice;
		};
		std::array<CascadeSnapshot, 2> cascadeSnapshot{};
		std::uint32_t                  cascadeSnapshotCount = 0;
		// When they were last captured: Skyrim doesn't redraw the sun's shadows every frame.
		std::chrono::steady_clock::time_point sunShadowCaptureTime{};
		ID3D11Texture2D*          skyProbeTex = nullptr;
		ID3D11RenderTargetView*   skyProbeRtv = nullptr;
		ID3D11Texture2D*          skyProbeStaging = nullptr;
		bool                      skyProbePending = false;
		int                       skyProbeRuns = 0;
		float                     skyProbeTimer = 2.0f;
		ID3D11RasterizerState*    shadowRaster = nullptr;
		ID3D11RasterizerState*    shadowRasterCullBack = nullptr;
		ID3D11SamplerState*       shadowSampler = nullptr;
		ID3D11BlendState*         multiplyBlend = nullptr;
		ID3D11BlendState*         solidBlend = nullptr;  // colour RGB, plus every channel of the extra targets
		ID3D11BlendState*         foregroundBlend = nullptr;
		ID3D11BlendState*         avatarSolidBlend = nullptr;
		ID3D11Texture2D*          shadowTex = nullptr;
		ID3D11DepthStencilView*   shadowDsv = nullptr;
		ID3D11ShaderResourceView* shadowSrv = nullptr;
		bool                      shadowMaskUsable = false;  // Skyrim's shadow mask looks like one (see ReadProbe)
		float                     probeTimer = 1.0f;

		std::unordered_map<std::uint64_t, Section> sections;
		std::vector<const Section*> visibleSections;
		std::unordered_map<std::uint32_t, render::ProjectileStep> projectileSteps;
		struct ArrowSurface {
			render::SurfacePoint impact{}, direction{};
			McVec point{};
			unsigned attempts = 0;
		};
		std::unordered_map<std::uint32_t, ArrowSurface> arrowSurfaces;
		unsigned surfaceQueriesLeft = 1;
		double projectileTime = 0;

		// The player's third-person body (see proto::RenAvatar) and the textures it uses.
		struct EntityTexture
		{
			ID3D11Texture2D*          tex{ nullptr };
			ID3D11ShaderResourceView* srv{ nullptr };
		};
		std::unordered_map<std::uint32_t, EntityTexture> entityTextures;
		// Geometry Minecraft's entity renderer drew this frame, by texture.
		struct Mesh
		{
			std::vector<proto::RenBatch> batches;
			ID3D11Buffer*                vb{ nullptr };
			UINT                         capacity{ 0 };
			double                       origin[3]{};  // MC block the positions are relative to (scene)
		};
		Mesh avatar;  // the player's body in third person, relative to the feet
		double avatarFeet[3]{}, previousAvatarFeet[3]{};
		bool avatarHistory = false;
		std::uint32_t avatarPoseEpoch = 0;
		Mesh scene;   // every other entity and all particles

		struct MovingMesh
		{
			Mesh mesh;
			double transform[16]{};
			double previous[16]{};
			bool positioned{ false };
		};
		std::unordered_map<std::uint64_t, MovingMesh> movingMeshes;

		// The player's Minecraft body on Skyrim's ragdoll when they die: Minecraft's latest
		// standing snapshot (proto::kRenRagdoll), its parts pinned to the skeleton's bones at the
		// moment of death so they fall and flop with Skyrim's physics.
		struct Ragdoll
		{
			std::vector<proto::RenBatch> batches;  // flags bits 8-11: proto::RagdollPart
			std::vector<Vertex>          verts;    // Minecraft model space: feet at 0, facing +Z
		};
		Ragdoll ragdollSnapshot;
		struct BoundRagdoll
		{
			bool                                                          active = false;
			Ragdoll                                                       body;
			std::vector<RE::NiPoint3>                                     local;  // per vertex, Skyrim axes and units
			std::vector<std::uint8_t>                                     part;   // per vertex
			std::array<RE::NiPointer<RE::NiAVObject>, proto::kPartCount> bones;
			std::array<RE::NiTransform, proto::kPartCount>               offsets;
			RE::NiPointer<RE::NiAVObject>                                 root;
			std::vector<RE::NiPointer<RE::BSGeometry>>                    hidden;  // the Skyrim body's meshes
		};
		BoundRagdoll ragdoll;
		Mesh         ragdollMesh;
		std::vector<Vertex>                        scratch;
		std::vector<Vertex>                        dynVerts;
		proto::WorldEntities                       entities{};

		// Skyrim's depth convention, learned from the depth buffer itself (see Probe).
		bool depthReversed = false;
		bool depthUsable = true;
		bool depthKnown = false;

		std::uint64_t Key(std::int32_t a_x, std::int32_t a_y, std::int32_t a_z)
		{
			return (std::uint64_t(std::uint32_t(a_x) & 0x1FFFFF) << 42) | (std::uint64_t(std::uint32_t(a_y) & 0x1FFFFF) << 21) | (std::uint32_t(a_z) & 0x1FFFFF);
		}

		bool Compile(const char* a_entry, const char* a_target, ID3DBlob** a_out)
		{
			ID3DBlob*  errors = nullptr;
			const auto hr = D3DCompile(kShader, sizeof(kShader) - 1, "skycraft_world", nullptr, nullptr, a_entry, a_target, D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, a_out, &errors);
			if (FAILED(hr)) {
				logger::error("world shader {} failed: {}", a_entry, errors ? static_cast<const char*>(errors->GetBufferPointer()) : "?");
				Release(errors);
				return false;
			}
			Release(errors);
			return true;
		}

		bool Init(ID3D11Device* a_device)
		{
			if (device) {
				return true;
			}
			if (initFailed) {
				return false;
			}
			ID3DBlob *vsBlob = nullptr, *psBlob = nullptr, *pvsBlob = nullptr, *ppsBlob = nullptr;
			ID3DBlob *svsBlob = nullptr, *spsBlob = nullptr, *opsBlob = nullptr, *dvsBlob = nullptr, *kpsBlob = nullptr;
			if (!Compile("VSMain", "vs_5_0", &vsBlob) || !Compile("PSMain", "ps_5_0", &psBlob) || !Compile("ProbeVS", "vs_5_0", &pvsBlob) ||
				!Compile("ProbePS", "ps_5_0", &ppsBlob) || !Compile("ShadowVS", "vs_5_0", &svsBlob) || !Compile("ShadowPS", "ps_5_0", &spsBlob) ||
				!Compile("OverlayPS", "ps_5_0", &opsBlob) || !Compile("DepthVS", "vs_5_0", &dvsBlob) || !Compile("SkyShadowProbePS", "ps_5_0", &kpsBlob)) {
				initFailed = true;
				return false;
			}
			a_device->CreateVertexShader(svsBlob->GetBufferPointer(), svsBlob->GetBufferSize(), nullptr, &shadowVs);
			a_device->CreatePixelShader(spsBlob->GetBufferPointer(), spsBlob->GetBufferSize(), nullptr, &shadowPs);
			a_device->CreatePixelShader(opsBlob->GetBufferPointer(), opsBlob->GetBufferSize(), nullptr, &overlayPs);
			a_device->CreateVertexShader(dvsBlob->GetBufferPointer(), dvsBlob->GetBufferSize(), nullptr, &depthVs);
			a_device->CreatePixelShader(kpsBlob->GetBufferPointer(), kpsBlob->GetBufferSize(), nullptr, &skyProbePs);
			Release(kpsBlob);
			{
				D3D11_TEXTURE2D_DESC kd{};
				kd.Width = 16;
				kd.Height = 1;
				kd.MipLevels = 1;
				kd.ArraySize = 1;
				kd.Format = DXGI_FORMAT_R32G32B32A32_FLOAT;
				kd.SampleDesc.Count = 1;
				kd.Usage = D3D11_USAGE_DEFAULT;
				kd.BindFlags = D3D11_BIND_RENDER_TARGET;
				a_device->CreateTexture2D(&kd, nullptr, &skyProbeTex);
				if (skyProbeTex) {
					a_device->CreateRenderTargetView(skyProbeTex, nullptr, &skyProbeRtv);
				}
				kd.Usage = D3D11_USAGE_STAGING;
				kd.BindFlags = 0;
				kd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
				a_device->CreateTexture2D(&kd, nullptr, &skyProbeStaging);
			}
			Release(svsBlob);
			Release(spsBlob);
			Release(opsBlob);
			Release(dvsBlob);
			a_device->CreateVertexShader(vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(), nullptr, &vs);
			a_device->CreatePixelShader(psBlob->GetBufferPointer(), psBlob->GetBufferSize(), nullptr, &ps);
			a_device->CreateVertexShader(pvsBlob->GetBufferPointer(), pvsBlob->GetBufferSize(), nullptr, &probeVs);
			a_device->CreatePixelShader(ppsBlob->GetBufferPointer(), ppsBlob->GetBufferSize(), nullptr, &probePs);
			const D3D11_INPUT_ELEMENT_DESC elements[] = {
				{ "POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0 },
				{ "TEXCOORD", 0, DXGI_FORMAT_R32G32_FLOAT, 0, 12, D3D11_INPUT_PER_VERTEX_DATA, 0 },
				{ "COLOR", 0, DXGI_FORMAT_R8G8B8A8_UNORM, 0, 20, D3D11_INPUT_PER_VERTEX_DATA, 0 },
				{ "TEXCOORD", 1, DXGI_FORMAT_R32_UINT, 0, 24, D3D11_INPUT_PER_VERTEX_DATA, 0 },
				{ "TEXCOORD", 2, DXGI_FORMAT_R32_UINT, 0, 28, D3D11_INPUT_PER_VERTEX_DATA, 0 },
			};
			a_device->CreateInputLayout(elements, 5, vsBlob->GetBufferPointer(), vsBlob->GetBufferSize(), &layout);
			Release(vsBlob);
			Release(psBlob);
			Release(pvsBlob);
			Release(ppsBlob);

			D3D11_BUFFER_DESC cbd{};
			cbd.Usage = D3D11_USAGE_DYNAMIC;
			cbd.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
			cbd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
			cbd.ByteWidth = sizeof(FrameConstants);
			a_device->CreateBuffer(&cbd, nullptr, &frameCb);
			cbd.ByteWidth = sizeof(ObjectConstants);
			a_device->CreateBuffer(&cbd, nullptr, &objectCb);

			D3D11_SAMPLER_DESC sd{};
			sd.Filter = D3D11_FILTER_MIN_MAG_POINT_MIP_LINEAR;  // crisp pixels like Minecraft, mipmapped at range
			sd.AddressU = sd.AddressV = sd.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
			sd.MaxLOD = D3D11_FLOAT32_MAX;
			a_device->CreateSamplerState(&sd, &atlasSampler);
			sd.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
			a_device->CreateSamplerState(&sd, &pointSampler);
			sd.Filter = D3D11_FILTER_COMPARISON_MIN_MAG_LINEAR_MIP_POINT;
			sd.ComparisonFunc = D3D11_COMPARISON_LESS_EQUAL;
			a_device->CreateSamplerState(&sd, &shadowSampler);

			auto rd = ModelRasterizer(false);
			a_device->CreateRasterizerState(&rd, &raster);
			rd = ModelRasterizer(true);
			a_device->CreateRasterizerState(&rd, &rasterCullBackModel);
			// Water and stained glass cull their back faces, as in Minecraft (its fluid renderer adds
			// a reversed top face where one should be seen from below).
			rd = FluidRasterizer();
			a_device->CreateRasterizerState(&rd, &rasterCullBack);
			rd = ModelRasterizer(false, true);  // casters beyond the map's depth range still cast
			a_device->CreateRasterizerState(&rd, &shadowRaster);
			rd = ModelRasterizer(true, true);
			a_device->CreateRasterizerState(&rd, &shadowRasterCullBack);

			D3D11_DEPTH_STENCIL_DESC dd{};
			dd.DepthEnable = TRUE;
			dd.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
			dd.DepthFunc = D3D11_COMPARISON_LESS_EQUAL;
			a_device->CreateDepthStencilState(&dd, &depthWrite);
			dd.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ZERO;
			a_device->CreateDepthStencilState(&dd, &depthRead);
			dd.DepthFunc = D3D11_COMPARISON_GREATER_EQUAL;
			a_device->CreateDepthStencilState(&dd, &depthReadRev);
			dd.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
			a_device->CreateDepthStencilState(&dd, &depthWriteRev);

			D3D11_BLEND_DESC bd{};
			bd.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_RED | D3D11_COLOR_WRITE_ENABLE_GREEN | D3D11_COLOR_WRITE_ENABLE_BLUE;
			a_device->CreateBlendState(&bd, &noBlend);
			bd.RenderTarget[0].BlendEnable = TRUE;
			bd.RenderTarget[0].SrcBlend = D3D11_BLEND_SRC_ALPHA;
			bd.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
			bd.RenderTarget[0].BlendOp = D3D11_BLEND_OP_ADD;
			bd.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ZERO;
			bd.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_ONE;
			bd.RenderTarget[0].BlendOpAlpha = D3D11_BLEND_OP_ADD;
			a_device->CreateBlendState(&bd, &alphaBlend);
			bd.RenderTarget[0].SrcBlend = D3D11_BLEND_ONE;
			bd.RenderTarget[0].DestBlend = D3D11_BLEND_ONE;
			a_device->CreateBlendState(&bd, &additiveBlend);
			bd.RenderTarget[0].SrcBlend = D3D11_BLEND_ZERO;  // result = what's there * our colour
			bd.RenderTarget[0].DestBlend = D3D11_BLEND_SRC_COLOR;
			a_device->CreateBlendState(&bd, &multiplyBlend);
			D3D11_BLEND_DESC sb{};
			sb.IndependentBlendEnable = TRUE;
			sb.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_RED | D3D11_COLOR_WRITE_ENABLE_GREEN | D3D11_COLOR_WRITE_ENABLE_BLUE;
			for (int k = 1; k < 8; ++k) {
				sb.RenderTarget[k].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
			}
			a_device->CreateBlendState(&sb, &solidBlend);
			sb.RenderTarget[0] = bd.RenderTarget[0];
			sb.RenderTarget[0].SrcBlend = D3D11_BLEND_SRC_ALPHA;
			sb.RenderTarget[0].DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
			sb.RenderTarget[0].SrcBlendAlpha = D3D11_BLEND_ONE;
			sb.RenderTarget[0].DestBlendAlpha = D3D11_BLEND_ZERO;
			sb.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
			a_device->CreateBlendState(&sb, &foregroundBlend);
			D3D11_BLEND_DESC avatarBd{};
			avatarBd.RenderTarget[0].RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
			a_device->CreateBlendState(&avatarBd, &avatarSolidBlend);

			D3D11_TEXTURE2D_DESC pd{};
			pd.Width = 16;
			pd.Height = 1;
			pd.MipLevels = 1;
			pd.ArraySize = 1;
			pd.Format = DXGI_FORMAT_R32G32_FLOAT;  // Skyrim depth, Skyrim shadow mask
			pd.SampleDesc.Count = 1;
			pd.Usage = D3D11_USAGE_DEFAULT;
			pd.BindFlags = D3D11_BIND_RENDER_TARGET;
			a_device->CreateTexture2D(&pd, nullptr, &probeTex);
			if (probeTex) {
				a_device->CreateRenderTargetView(probeTex, nullptr, &probeRtv);
			}
			pd.Usage = D3D11_USAGE_STAGING;
			pd.BindFlags = 0;
			pd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
			a_device->CreateTexture2D(&pd, nullptr, &probeStaging);

			const bool ok = vs && ps && probeVs && probePs && layout && frameCb && objectCb && atlasSampler && pointSampler && raster && depthWrite &&
			                depthRead && depthWriteRev && depthReadRev && noBlend && alphaBlend && probeRtv && probeStaging && shadowVs && shadowPs &&
			                overlayPs && shadowRaster && shadowRasterCullBack && rasterCullBackModel && rasterCullBack && shadowSampler &&
			                multiplyBlend && solidBlend && foregroundBlend && avatarSolidBlend && depthVs;
			if (!ok) {
				logger::error("world renderer failed to initialize");
				initFailed = true;
				return false;
			}
			device = a_device;
			logger::info("world renderer107 ready (Minecraft model winding corrected; fluid culling preserved; immediate settled-scene dig restore)");
			return true;
		}

		// ---- messages from Minecraft -----------------------------------------------------------
		void ClearSections()
		{
			for (auto& [key, s] : sections) {
				Release(s.vb);
			}
			sections.clear();
		}

		void ClearAvatar()
		{
			for (auto& [id, t] : entityTextures) {
				Release(t.srv);
				Release(t.tex);
			}
			entityTextures.clear();
			avatar.batches.clear();
			avatarHistory = false;
			scene.batches.clear();
			for (auto& [id, moving] : movingMeshes) Release(moving.mesh.vb);
			movingMeshes.clear();
		}

		void OnTexture(const std::uint8_t* a_data, std::uint32_t a_bytes)
		{
			if (a_bytes < sizeof(proto::RenTexture)) {
				return;
			}
			const auto* hdr = reinterpret_cast<const proto::RenTexture*>(a_data);
			if (!hdr->width || !hdr->height || hdr->width > 4096 || hdr->height > 4096 ||
				(a_bytes != sizeof(proto::RenTexture) &&
                a_bytes < sizeof(proto::RenTexture) + std::uint64_t(hdr->width) * hdr->height * 4)) {
				return;
			}
			auto& t = entityTextures[hdr->id];
			Release(t.srv);
			Release(t.tex);
			D3D11_TEXTURE2D_DESC td{};
			td.Width = hdr->width;
			td.Height = hdr->height;
			td.MipLevels = 1;
			td.ArraySize = 1;
			td.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
			td.SampleDesc.Count = 1;
			td.Usage = D3D11_USAGE_DEFAULT;
			td.BindFlags = D3D11_BIND_SHADER_RESOURCE;
			D3D11_SUBRESOURCE_DATA init{ a_data + sizeof(proto::RenTexture), hdr->width * 4, 0 };
			if (FAILED(device->CreateTexture2D(&td, a_bytes == sizeof(proto::RenTexture) ? nullptr : &init, &t.tex)) || FAILED(device->CreateShaderResourceView(t.tex, nullptr, &t.srv))) {
				Release(t.tex);
				entityTextures.erase(hdr->id);
				return;
			}
			logger::info("received Minecraft entity texture {} ({}x{})", hdr->id, hdr->width, hdr->height);
		}

        void OnTextureRegion(ID3D11DeviceContext* context, const std::uint8_t* data, std::uint32_t bytes)
        {
            if (bytes < sizeof(proto::RenTextureRegion)) return;
            const auto* hdr = reinterpret_cast<const proto::RenTextureRegion*>(data);
            auto found = entityTextures.find(hdr->id);
            if (found == entityTextures.end() || !found->second.tex || !hdr->width || !hdr->height) return;
            D3D11_TEXTURE2D_DESC desc{};
            found->second.tex->GetDesc(&desc);
            if (std::uint64_t(hdr->x) + hdr->width > desc.Width || std::uint64_t(hdr->y) + hdr->height > desc.Height ||
                bytes < sizeof(*hdr) + std::uint64_t(hdr->width) * hdr->height * 4) return;
            D3D11_BOX box{hdr->x, hdr->y, 0, hdr->x + hdr->width, hdr->y + hdr->height, 1};
            context->UpdateSubresource(found->second.tex, 0, &box, data + sizeof(*hdr), hdr->width * 4, 0);
        }

		// RenAvatar / RenScene: batches + vertices into a mesh's dynamic vertex buffer.
		void OnMesh(ID3D11DeviceContext* a_context, Mesh& a_mesh, const std::uint8_t* a_data, std::uint32_t a_bytes, bool a_hasOrigin)
		{
			a_mesh.batches.clear();
			const std::size_t head = (a_hasOrigin ? sizeof(proto::RenScene) : sizeof(proto::RenAvatar));
			if (a_bytes < head) {
				return;
			}
			std::uint32_t batchCount, vertexCount;
			if (a_hasOrigin) {
				const auto* hdr = reinterpret_cast<const proto::RenScene*>(a_data);
				a_mesh.origin[0] = hdr->originX;
				a_mesh.origin[1] = hdr->originY;
				a_mesh.origin[2] = hdr->originZ;
				batchCount = hdr->batchCount;
				vertexCount = hdr->vertexCount;
			} else {
				const auto* hdr = reinterpret_cast<const proto::RenAvatar*>(a_data);
				batchCount = hdr->batchCount;
				vertexCount = hdr->vertexCount;
			}
			const std::uint64_t need = head + std::uint64_t(batchCount) * sizeof(proto::RenBatch) + std::uint64_t(vertexCount) * sizeof(Vertex);
			if (batchCount == 0 || vertexCount == 0 || a_bytes < need) {
				return;
			}
			const auto* batches = reinterpret_cast<const proto::RenBatch*>(a_data + head);
			const auto* verts = reinterpret_cast<const Vertex*>(batches + batchCount);
			const UINT bytes = vertexCount * sizeof(Vertex);
			if (bytes > a_mesh.capacity) {
				Release(a_mesh.vb);
				D3D11_BUFFER_DESC bd{};
				bd.ByteWidth = std::max<UINT>(bytes * 2, 256 * 1024);
				bd.Usage = D3D11_USAGE_DYNAMIC;
				bd.BindFlags = D3D11_BIND_VERTEX_BUFFER;
				bd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
				if (FAILED(device->CreateBuffer(&bd, nullptr, &a_mesh.vb))) {
					a_mesh.capacity = 0;
					return;
				}
				a_mesh.capacity = bd.ByteWidth;
			}
			D3D11_MAPPED_SUBRESOURCE mapped{};
			if (FAILED(a_context->Map(a_mesh.vb, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
				return;
			}
			std::memcpy(mapped.pData, verts, bytes);
			a_context->Unmap(a_mesh.vb, 0);
			for (std::uint32_t b = 0; b < batchCount; ++b) {
				if (batches[b].first + batches[b].count <= vertexCount) {
					a_mesh.batches.push_back(batches[b]);
				}
			}
			if (&a_mesh == &avatar) {
				static unsigned alphaAudits = 0;
				if (alphaAudits < 3) {
					++alphaAudits;
					for (const auto& b : a_mesh.batches) {
						unsigned low = 255, high = 0, blended = 0;
						for (unsigned v = b.first; v < b.first + b.count; ++v) {
							const auto alpha = verts[v].color >> 24;
							low = std::min(low, alpha); high = std::max(high, alpha);
							blended += (verts[v].flags & kFlagTranslucent) != 0;
						}
						logger::info("avatar62 export: texture{} batchBlend{} vertexAlpha{}..{} blendedVertices{}/{}",
							b.texture, (b.flags & 1) != 0, low, high, blended, b.count);
					}
				}
			}
			if (!a_hasOrigin && DiagnosticsEnabled()) {
				// Diagnostics: where the body sits around the feet it's drawn at (should be centred).
				static std::int64_t nextLog = 0;
				LARGE_INTEGER       now, freq;
				::QueryPerformanceCounter(&now);
				::QueryPerformanceFrequency(&freq);
				if (now.QuadPart >= nextLog) {
					nextLog = now.QuadPart + freq.QuadPart * 10;
					float lo[3] = { 1e9f, 1e9f, 1e9f }, hi[3] = { -1e9f, -1e9f, -1e9f };
					for (std::uint32_t v = 0; v < vertexCount; ++v) {
						const float p[3] = { verts[v].x, verts[v].y, verts[v].z };
						for (int k = 0; k < 3; ++k) {
							lo[k] = std::min(lo[k], p[k]);
							hi[k] = std::max(hi[k], p[k]);
						}
					}
					logger::info("third-person model: centre ({:.2f}, {:.2f}) blocks from the feet, {:.2f} x {:.2f} wide, {:.2f} to {:.2f} high",
						(lo[0] + hi[0]) * 0.5f, (lo[2] + hi[2]) * 0.5f, hi[0] - lo[0], hi[2] - lo[2], lo[1], hi[1]);
				}
			}
			static bool loggedAvatar = false, loggedScene = false;
			if (!(a_hasOrigin ? loggedScene : loggedAvatar)) {
				(a_hasOrigin ? loggedScene : loggedAvatar) = true;
				logger::info("{}: {} triangles in {} texture batches", a_hasOrigin ? "Minecraft entities and particles" : "third-person player model", vertexCount / 3,
					batchCount);
			}
		}

		void OnRagdoll(const std::uint8_t* a_data, std::uint32_t a_bytes)
		{
			if (a_bytes < sizeof(proto::RenAvatar)) {
				return;
			}
			const auto* hdr = reinterpret_cast<const proto::RenAvatar*>(a_data);
			const std::uint64_t need = sizeof(proto::RenAvatar) + std::uint64_t(hdr->batchCount) * sizeof(proto::RenBatch) + std::uint64_t(hdr->vertexCount) * sizeof(Vertex);
			if (!hdr->batchCount || !hdr->vertexCount || a_bytes < need) {
				return;
			}
			const auto* batches = reinterpret_cast<const proto::RenBatch*>(a_data + sizeof(proto::RenAvatar));
			const auto* verts = reinterpret_cast<const Vertex*>(batches + hdr->batchCount);
			ragdollSnapshot.batches.assign(batches, batches + hdr->batchCount);
			ragdollSnapshot.verts.assign(verts, verts + hdr->vertexCount);
			static bool logged = false;
			if (!std::exchange(logged, true)) {
				logger::info("received the player's Minecraft body for the death ragdoll: {} triangles in {} part batches", hdr->vertexCount / 3, hdr->batchCount);
			}
		}

		bool atlasMipsStale = false;  // an animated sprite changed: rebuild the atlas mipmaps once

		// An animated sprite's current frame (water, lava, fire, ...) into the atlas.
		void OnAtlasRegion(ID3D11DeviceContext* a_context, const std::uint8_t* a_data, std::uint32_t a_bytes)
		{
			if (!atlasTex || a_bytes < sizeof(proto::RenAtlasRegion)) {
				return;
			}
			const auto* hdr = reinterpret_cast<const proto::RenAtlasRegion*>(a_data);
			D3D11_TEXTURE2D_DESC td{};
			atlasTex->GetDesc(&td);
			if (!hdr->width || !hdr->height || hdr->x + hdr->width > td.Width || hdr->y + hdr->height > td.Height ||
				a_bytes < sizeof(proto::RenAtlasRegion) + std::uint64_t(hdr->width) * hdr->height * 4) {
				return;
			}
			const D3D11_BOX box{ hdr->x, hdr->y, 0, hdr->x + hdr->width, hdr->y + hdr->height, 1 };
			a_context->UpdateSubresource(atlasTex, 0, &box, a_data + sizeof(proto::RenAtlasRegion), hdr->width * 4, 0);
			if (!atlasUploading) atlasMipsStale = true;
		}

		void OnAtlas(ID3D11DeviceContext* a_context, const std::uint8_t* a_data, std::uint32_t a_bytes)
		{
			if (a_bytes < sizeof(proto::RenAtlas)) {
				return;
			}
			const auto* hdr = reinterpret_cast<const proto::RenAtlas*>(a_data);
			const bool streamed = a_bytes == sizeof(proto::RenAtlas);
			if ((!streamed && a_bytes < sizeof(proto::RenAtlas) + std::uint64_t(hdr->width) * hdr->height * 4) ||
				!hdr->width || !hdr->height || hdr->width > D3D11_REQ_TEXTURE2D_U_OR_V_DIMENSION ||
				hdr->height > D3D11_REQ_TEXTURE2D_U_OR_V_DIMENSION) {
				return;
			}
			atlasUploading = true;
			atlasMipsStale = false;
			Release(atlasSrv);
			Release(atlasTex);
			D3D11_TEXTURE2D_DESC td{};
			td.Width = hdr->width;
			td.Height = hdr->height;
			td.MipLevels = 5;  // 16px sprites stay inside their own cell down to 1px
			td.ArraySize = 1;
			td.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
			td.SampleDesc.Count = 1;
			td.Usage = D3D11_USAGE_DEFAULT;
			td.BindFlags = D3D11_BIND_SHADER_RESOURCE | D3D11_BIND_RENDER_TARGET;
			td.MiscFlags = D3D11_RESOURCE_MISC_GENERATE_MIPS;
			if (FAILED(device->CreateTexture2D(&td, nullptr, &atlasTex)) || FAILED(device->CreateShaderResourceView(atlasTex, nullptr, &atlasSrv))) {
				logger::error("atlas texture {}x{} failed", hdr->width, hdr->height);
				Release(atlasTex);
				Release(atlasSrv);
				return;
			}
			if (streamed) {
				logger::info("receiving Minecraft texture atlas {}x{} in regions (full resolution)", hdr->width, hdr->height);
				return;
			}
			a_context->UpdateSubresource(atlasTex, 0, nullptr, a_data + sizeof(proto::RenAtlas), hdr->width * 4, 0);
			a_context->GenerateMips(atlasSrv);
			atlasUploading = false;
			logger::info("received Minecraft texture atlas {}x{}", hdr->width, hdr->height);
		}

		void OnSection(const std::uint8_t* a_data, std::uint32_t a_bytes)
		{
			if (a_bytes < sizeof(proto::RenSection)) {
				return;
			}
			const auto* hdr = reinterpret_cast<const proto::RenSection*>(a_data);
			const auto  key = Key(hdr->sx, hdr->sy, hdr->sz);
			if (auto it = sections.find(key); it != sections.end()) {
				Release(it->second.vb);
				sections.erase(it);
			}
			const std::uint32_t count = hdr->vertexCount - hdr->vertexCount % 3;
			if (count == 0 || a_bytes < sizeof(proto::RenSection) + std::uint64_t(count) * sizeof(Vertex)) {
				return;
			}
			// Opaque/cutout triangles first, translucent (water, stained glass) after.
			const auto* src = reinterpret_cast<const Vertex*>(a_data + sizeof(proto::RenSection));
			// A few received meshes distinguish an exporter colour/light fault from native lighting.
			// Read only on the first eight uploads, not on every frame or section rebuild.
			static int lightingMeshLogs = 0;
			if (lightingMeshLogs < 8) {
				++lightingMeshLogs;
				std::uint32_t minColor = 255, maxColor = 0, minSky = 15, maxSky = 0, maxBlock = 0, black = 0;
				for (std::uint32_t v = 0; v < count; ++v) {
					const auto color = src[v].color;
					const auto r = color & 255u, g = (color >> 8) & 255u, b = (color >> 16) & 255u;
					minColor = std::min(minColor, std::min(r, std::min(g, b)));
					maxColor = std::max(maxColor, std::max(r, std::max(g, b)));
					black += (r | g | b) == 0;
					const auto sky = (src[v].light >> 8) & 255u;
					minSky = std::min(minSky, sky);
					maxSky = std::max(maxSky, sky);
					maxBlock = std::max(maxBlock, src[v].light & 255u);
				}
				logger::info("ordinary mesh lighting {} {} {}: RGB channels {}..{}, black {}/{} vertices, sky {}..{}, block max {} (static world normals)",
					hdr->sx, hdr->sy, hdr->sz, minColor, maxColor, black, count, minSky, maxSky, maxBlock);
			}
			scratch.clear();
			scratch.reserve(count);
			for (int pass = 0; pass < 2; ++pass) {
				for (std::uint32_t t = 0; t < count; t += 3) {
					const bool translucent = (src[t].flags & kFlagTranslucent) != 0;
					if (translucent == (pass == 1)) {
						scratch.insert(scratch.end(), src + t, src + t + 3);
					}
				}
				if (pass == 0) {
					sections[key].opaque = static_cast<std::uint32_t>(scratch.size());
				}
			}
			Section& s = sections[key];
			s.translucent = static_cast<std::uint32_t>(scratch.size()) - s.opaque;
			s.sx = hdr->sx;
			s.sy = hdr->sy;
			s.sz = hdr->sz;
			D3D11_BUFFER_DESC bd{};
			bd.ByteWidth = static_cast<UINT>(scratch.size() * sizeof(Vertex));
			bd.Usage = D3D11_USAGE_IMMUTABLE;
			bd.BindFlags = D3D11_BIND_VERTEX_BUFFER;
			D3D11_SUBRESOURCE_DATA init{ scratch.data(), 0, 0 };
			if (FAILED(device->CreateBuffer(&bd, &init, &s.vb))) {
				sections.erase(key);
			}
		}


		void OnMovingMesh(ID3D11DeviceContext* a_context, const std::uint8_t* a_data, std::uint32_t a_bytes)
		{
			if (a_bytes < 8 + sizeof(proto::RenAvatar)) return;
			std::uint64_t id;
			std::memcpy(&id, a_data, sizeof(id));
			if (!id) return;
			auto& moving = movingMeshes[id];
			const bool first = !moving.mesh.vb;
			OnMesh(a_context, moving.mesh, a_data + 8, a_bytes - 8, false);
			static int logs = 0;
			if (first && logs++ < 12) logger::info("received moving Minecraft mesh {} ({} batches)", id, moving.mesh.batches.size());
		}

		void OnMovingPose(const std::uint8_t* a_data, std::uint32_t a_bytes)
		{
			if (a_bytes < sizeof(proto::RenMovingPose)) return;
			proto::RenMovingPose pose;
			std::memcpy(&pose, a_data, sizeof(pose));
			if (!pose.id) return;
			for (double value : pose.matrix) if (!std::isfinite(value)) return;
			auto& moving = movingMeshes[pose.id];
			std::memcpy(moving.transform, pose.matrix, sizeof(pose.matrix));
			if (!moving.positioned) std::memcpy(moving.previous, pose.matrix, sizeof(pose.matrix));
			moving.positioned = true;
		}

		void OnMovingRemove(const std::uint8_t* a_data, std::uint32_t a_bytes)
		{
			if (a_bytes < 8) return;
			std::uint64_t id;
			std::memcpy(&id, a_data, sizeof(id));
			const auto found = movingMeshes.find(id);
			if (found == movingMeshes.end()) return;
			Release(found->second.mesh.vb);
			movingMeshes.erase(found);
		}

		void DrainMessages(ID3D11DeviceContext* a_context)
		{
			Link::Get().DrainRender(
				[&](std::uint32_t a_type, const std::uint8_t* a_data, std::uint32_t a_bytes) {
					switch (a_type) {
					case proto::kRenAtlas:
						OnAtlas(a_context, a_data, a_bytes);
						break;
					case proto::kRenAtlasReady:
						if (atlasTex && atlasSrv && atlasUploading) {
							a_context->GenerateMips(atlasSrv);
							atlasUploading = false;
							atlasMipsStale = false;
							D3D11_TEXTURE2D_DESC td{};
							atlasTex->GetDesc(&td);
							logger::info("received Minecraft texture atlas {}x{} (streamed, full resolution)", td.Width, td.Height);
						}
						break;
					case proto::kRenMovingMesh:
						OnMovingMesh(a_context, a_data, a_bytes);
						break;
					case proto::kRenMovingPose:
						OnMovingPose(a_data, a_bytes);
						break;
					case proto::kRenMovingRemove:
						OnMovingRemove(a_data, a_bytes);
						break;
					case proto::kRenPhysicsInterest:
						Collision::Get().OnPhysicsInterest(a_data,a_bytes);
						break;
					case proto::kRenSection:
						OnSection(a_data, a_bytes);
						break;
					case proto::kRenClearAll:
						atlasUploading = true;
						atlasMipsStale = false;
						ClearSections();
						ClearAvatar();
						BlockLights::Clear();
						NpcBlocks::Clear();
						Dig::Clear();
						break;
					case proto::kRenLights:
						BlockLights::OnLights(a_data, a_bytes);
						break;
					case proto::kRenRagdoll:
						OnRagdoll(a_data, a_bytes);
						break;
					case proto::kRenSolids:
						NpcBlocks::OnSolids(a_data, a_bytes);
						break;
					case proto::kRenDug:
						Dig::OnDug(a_data, a_bytes);
						break;
					case proto::kRenTextureRegion:
                        OnTextureRegion(a_context, a_data, a_bytes);
                        break;
					case proto::kRenTexture:
						OnTexture(a_data, a_bytes);
						break;
					case proto::kRenAvatar:
						OnMesh(a_context, avatar, a_data, a_bytes, false);
						break;
					case proto::kRenScene:
						OnMesh(a_context, scene, a_data, a_bytes, true);
						break;
					case proto::kRenAtlasRegion:
						OnAtlasRegion(a_context, a_data, a_bytes);
						break;
					default:
						break;
					}
				},
				48ull << 20);
			if (std::exchange(atlasMipsStale, false) && atlasSrv) {
				a_context->GenerateMips(atlasSrv);  // distant water and lava animate too
			}
		}

		// ---- per-frame geometry for entities and the outline -----------------------------------
		void Quad(const float a_p[4][3], const float a_uv[4], std::uint32_t a_color = 0xFFFFFFFFu, std::uint32_t a_flags = kFlagCutout)
		{
			const float uv[4][2] = { { a_uv[0], a_uv[1] }, { a_uv[2], a_uv[1] }, { a_uv[2], a_uv[3] }, { a_uv[0], a_uv[3] } };
			for (int k : { 0, 1, 2, 0, 2, 3 }) {
				dynVerts.push_back({ a_p[k][0], a_p[k][1], a_p[k][2], uv[k][0], uv[k][1], a_color, kFullSkyLight, a_flags });
			}
		}

		// A brightness times a tint, as a vertex colour.
		std::uint32_t Shade(float a_shade, std::uint32_t a_tint = 0)
		{
			float r = a_shade, g = a_shade, b = a_shade;
			if (a_tint) {
				r *= float(a_tint & 0xFF) / 255.0f;
				g *= float((a_tint >> 8) & 0xFF) / 255.0f;
				b *= float((a_tint >> 16) & 0xFF) / 255.0f;
			}
			return 0xFF000000u | (std::uint32_t(b * 255.0f) << 16) | (std::uint32_t(g * 255.0f) << 8) | std::uint32_t(r * 255.0f);
		}

		// An axis-aligned box (rotated a_yaw about its vertical centre line) with one texture per
		// face group: sides a_side, top a_top, bottom a_bottom. Lit by Skyrim like the blocks.
		void Box(const float a_min[3], const float a_size[3], float a_yaw, const float a_side[4], const float a_top[4], const float a_bottom[4],
			std::uint32_t a_topTint, std::uint32_t a_flags, bool a_shaded)
		{
			const float cx = a_min[0] + a_size[0] * 0.5f, cz = a_min[2] + a_size[2] * 0.5f;
			const float c = std::cos(a_yaw), s = std::sin(a_yaw);
			auto        corner = [&](int a_i, float a_out[3]) {
                const float lx = ((a_i & 1) ? 0.5f : -0.5f) * a_size[0], lz = ((a_i & 4) ? 0.5f : -0.5f) * a_size[2];
                a_out[0] = cx + lx * c - lz * s;
                a_out[1] = a_min[1] + ((a_i & 2) ? a_size[1] : 0.0f);
                a_out[2] = cz + lx * s + lz * c;
			};
			// corner bits: 1 = +x, 2 = +y, 4 = +z; each face listed TL, TR, BR, BL seen from outside
			static constexpr int kFaces[6][4] = {
				{ 6, 7, 5, 4 },  // south (+z)
				{ 3, 2, 0, 1 },  // north (-z)
				{ 7, 3, 1, 5 },  // east (+x)
				{ 2, 6, 4, 0 },  // west (-x)
				{ 2, 3, 7, 6 },  // top
				{ 4, 5, 1, 0 },  // bottom
			};
			for (int f = 0; f < 6; ++f) {
				float p[4][3];
				for (int k = 0; k < 4; ++k) {
					corner(kFaces[f][k], p[k]);
				}
				const float*        uv = f == 4 ? a_top : f == 5 ? a_bottom : a_side;
				const std::uint32_t color = a_shaded ? Shade(1.0f, f == 4 ? a_topTint : 0) : 0xFFFFFFFFu;
				Quad(p, uv, color, a_flags | kFlagNormalFromFaces);
			}
		}

		// Minecraft's arrow model is 0.9 blocks long with fins 0.22 wide: chunky next to Skyrim's arrows
		// and people. Same shape, smaller.
		constexpr float kArrowScale = 0.55f;

		// Minecraft's arrow (or a trident) at a position, flying along d (Minecraft axes, unit length).
		// Around that axis, s is the horizontal side and u the "up"; Minecraft's fins sit at 45 degrees
		// between them.
		void BuildArrow(float px, float py, float pz, const float d[3], const float* a_uvSide, const float* a_uvBack, bool a_trident)
		{
			float s[3] = { d[2], 0.0f, -d[0] };
			float sl = std::sqrt(s[0] * s[0] + s[2] * s[2]);
			if (sl < 1e-3f) {
				s[0] = 1.0f, s[2] = 0.0f, sl = 1.0f;
			}
			s[0] /= sl, s[2] /= sl;
			const float u[3] = { s[1] * d[2] - s[2] * d[1], s[2] * d[0] - s[0] * d[2], s[0] * d[1] - s[1] * d[0] };
			constexpr float r = 0.70710678f;
			const float     fins[2][3] = { { (u[0] + s[0]) * r, (u[1] + s[1]) * r, (u[2] + s[2]) * r }, { (u[0] - s[0]) * r, (u[1] - s[1]) * r, (u[2] - s[2]) * r } };
			auto            at = [&](float a_along, const float* a_q, float a_side, const float* a_q2, float a_side2, float a_out[3]) {
                for (int k = 0; k < 3; ++k) {
                    a_out[k] = (k == 0 ? px : k == 1 ? py : pz) + d[k] * a_along + a_q[k] * a_side + (a_q2 ? a_q2[k] * a_side2 : 0.0f);
                }
			};
			if (!a_trident) {
				// Minecraft's ArrowModel (1/16 block units, scaled 0.9): two fins 16 long and 4
				// wide from x -12 (fletching) to +4 (head), and a 4x4 back plate at x -11.
				constexpr float k = 0.9f / 16.0f * kArrowScale;
				for (const auto& q : fins) {
					float p[4][3];
					at(-12 * k, q, -2 * k, nullptr, 0, p[0]);  // TL: fletching end
					at(4 * k, q, -2 * k, nullptr, 0, p[1]);    // TR: head
					at(4 * k, q, 2 * k, nullptr, 0, p[2]);     // BR
					at(-12 * k, q, 2 * k, nullptr, 0, p[3]);   // BL
					Quad(p, a_uvSide, 0xFFFFFFFFu, kFlagCutout | kFlagNoMip);
				}
				float p[4][3];
				at(-11 * k, fins[0], -2 * k, fins[1], -2 * k, p[0]);
				at(-11 * k, fins[0], 2 * k, fins[1], -2 * k, p[1]);
				at(-11 * k, fins[0], 2 * k, fins[1], 2 * k, p[2]);
				at(-11 * k, fins[0], -2 * k, fins[1], 2 * k, p[3]);
				Quad(p, a_uvBack, 0xFFFFFFFFu, kFlagCutout | kFlagNoMip);
			} else {
				// Tridents: the item icon, whose diagonal runs handle (bottom-left) to tip (top-right).
				constexpr float h = 0.9f;
				for (const auto& q : fins) {
					float p[4][3];
					at(0, q, h, nullptr, 0, p[0]);   // TL
					at(h, q, 0, nullptr, 0, p[1]);   // TR: tip
					at(0, q, -h, nullptr, 0, p[2]);  // BR
					at(-h, q, 0, nullptr, 0, p[3]);  // BL: handle
					Quad(p, a_uvSide, 0xFFFFFFFFu, kFlagCutout | kFlagNoMip);
				}
			}
		}

		// Minecraft arrows stuck in Skyrim actors, pinned to a bone of the skeleton so they follow
		// its animation (and ragdoll). Minecraft itself removes arrows that hit a creature.
		struct StuckArrow
		{
			RE::ActorHandle               actor;
			RE::NiPointer<RE::NiAVObject> root;  // the actor's 3D it was pinned in (replaced = drop)
			RE::NiPointer<RE::NiAVObject> bone;
			RE::NiPoint3                  localPos, localDir;
		};
		std::deque<StuckArrow> stuckArrows;
		float                  lastArrowUv[2][4]{};  // side view, back plate: from the last arrow Minecraft showed
		bool                   haveArrowUv = false;

		void BuildStuckArrows(const double a_o[3])
		{
			if (!haveArrowUv) {
				return;
			}
			for (auto it = stuckArrows.begin(); it != stuckArrows.end();) {
				auto actor = it->actor.get();
				if (!actor || actor->IsDisabled() || actor->Get3D() != it->root.get()) {
					it = stuckArrows.erase(it);
					continue;
				}
				const auto&        w = it->bone->world;
				const RE::NiPoint3 pos = w.translate + w.rotate * (it->localPos * w.scale);
				const RE::NiPoint3 dir = w.rotate * it->localDir;
				const float        len = dir.Length();
				if (len > 1e-4f) {
					const float k = 1.0f / static_cast<float>(proto::kUnitsPerBlock);
					const float d[3] = { dir.x / len, dir.z / len, -dir.y / len };  // Skyrim -> Minecraft axes
					BuildArrow(float(pos.x * k - a_o[0]), float(pos.z * k - a_o[1]), float(-pos.y * k - a_o[2]), d, lastArrowUv[0], lastArrowUv[1], false);
				}
				++it;
			}
		}

		// Positions are relative to the integer Minecraft origin a_o. Cracks are translucent and go
		// to a_cracks so they can be drawn after the solid things.
		void BuildEntities(const double a_o[3], std::vector<Vertex>& a_cracks)
		{
			BuildStuckArrows(a_o);
			for (std::uint32_t i = 0; i < entities.count; ++i) {
				const auto& e = entities.entities[i];
				float px = float(e.x - a_o[0]), py = float(e.y - a_o[1]), pz = float(e.z - a_o[2]);
				if (e.kind == proto::kWeArrow || e.kind == proto::kWeTrident) {
					if (auto it = projectileSteps.find(e.id); it != projectileSteps.end()) {
						const auto p = it->second.Get(projectileTime);
						px = float(p[0]-a_o[0]); py = float(p[1]-a_o[1]); pz = float(p[2]-a_o[2]);
					}
				}
				if (e.kind == proto::kWeShadow) {
					continue;  // not geometry: see GatherContactShadows
				}
				if (e.kind == proto::kWeBlock) {
					// A dropped block: a small cube spinning about its centre, like in Minecraft.
					const float s = e.scale;
					const float mn[3] = { px - s * 0.5f, py - s * 0.5f, pz - s * 0.5f };
					const float sz[3] = { s, s, s };
					Box(mn, sz, e.yaw * kPi / 180.0f, e.uv[0], e.uv[1], e.uv[2], e.tint, kFlagCutout | kFlagNoMip, true);
					continue;
				}
				if (e.kind == proto::kWeCrack) {
					const float mn[3] = { px, py, pz };
					const auto  before = dynVerts.size();
					Box(mn, e.ext, 0.0f, e.uv[0], e.uv[0], e.uv[0], 0, kFlagTranslucent | kFlagNoMip, false);
					a_cracks.insert(a_cracks.end(), dynVerts.begin() + static_cast<std::ptrdiff_t>(before), dynVerts.end());
					dynVerts.resize(before);
					continue;
				}
				if (e.kind == proto::kWeArrow || e.kind == proto::kWeTrident) {
					// Minecraft arrows face (sin yaw, sin pitch, cos yaw).
					const float yaw = e.yaw * kPi / 180.0f, pitch = e.pitch * kPi / 180.0f;
					const float d[3] = { std::sin(yaw) * std::cos(pitch), std::sin(pitch), std::cos(yaw) * std::cos(pitch) };
					if (e.ext[1] > 0.5f && e.kind == proto::kWeArrow) {
						auto& surface = arrowSurfaces[e.id];
						const render::SurfacePoint impact{e.x,e.y,e.z}, direction{d[0],d[1],d[2]};
						if (surface.impact != impact || surface.direction != direction) {
							surface = {impact,direction,{e.x,e.y,e.z},0};
						}
						if (surface.attempts < 8 && surfaceQueriesLeft) {
							--surfaceQueriesLeft;
							++surface.attempts;
							McVec visible{};
							bool pending = false;
							if (Dig::ProjectileSurface({e.x,e.y,e.z},{d[0],d[1],d[2]},visible,pending)) {
								surface.point = visible;
								static unsigned logs = 0;
								if (logs++ < 8) logger::info("arrow {} visible impact shift {:.4f} {:.4f} {:.4f} blocks",e.id,visible.x-e.x,visible.y-e.y,visible.z-e.z);
							}
							if (!pending) surface.attempts = 8;
						}
						// The impact describes the tip, not the origin of the shortened model.
						constexpr float setback = 4.0f*0.9f/16.0f*kArrowScale-0.04f;
						px = float(surface.point.x-a_o[0])-d[0]*setback;
						py = float(surface.point.y-a_o[1])-d[1]*setback;
						pz = float(surface.point.z-a_o[2])-d[2]*setback;
					}
					if (e.kind == proto::kWeArrow) {
						std::memcpy(lastArrowUv, e.uv, sizeof(lastArrowUv));  // for arrows stuck in Skyrim actors
						haveArrowUv = true;
					}
					BuildArrow(px, py, pz, d, e.uv[0], e.uv[1], e.kind == proto::kWeTrident);
				} else if (e.kind == proto::kWeItem) {
					// A flat sprite turning about the vertical, like a dropped item.
					const float spin = e.yaw * kPi / 180.0f, half = e.scale * 0.5f;
					const float rx = std::cos(spin) * half, rz = std::sin(spin) * half;
					const float p[4][3] = {
						{ px - rx, py + half, pz - rz },
						{ px + rx, py + half, pz + rz },
						{ px + rx, py - half, pz + rz },
						{ px - rx, py - half, pz - rz },
					};
					Quad(p, e.uv[0], 0xFFFFFFFFu, kFlagCutout | kFlagNoMip);
				}
			}
		}

		void BuildOutline(const double a_o[3])
		{
			if (!entities.hasSelection) {
				return;
			}
			constexpr float g = 0.002f;
			const float     lo[3] = { float(entities.selMin[0] - a_o[0]) - g, float(entities.selMin[1] - a_o[1]) - g, float(entities.selMin[2] - a_o[2]) - g };
			const float     hi[3] = { float(entities.selMax[0] - a_o[0]) + g, float(entities.selMax[1] - a_o[1]) + g, float(entities.selMax[2] - a_o[2]) + g };
			auto            corner = [&](int a_i) {
                return std::array<float, 3>{ (a_i & 1) ? hi[0] : lo[0], (a_i & 2) ? hi[1] : lo[1], (a_i & 4) ? hi[2] : lo[2] };
			};
			static constexpr int kEdges[12][2] = { { 0, 1 }, { 2, 3 }, { 4, 5 }, { 6, 7 }, { 0, 2 }, { 1, 3 }, { 4, 6 }, { 5, 7 }, { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };
			constexpr std::uint32_t kOutline = 0x73000000u;  // black, 45% (Minecraft's outline)
			for (const auto& edge : kEdges) {
				for (int k : edge) {
					const auto c = corner(k);
					dynVerts.push_back({ c[0], c[1], c[2], 0, 0, kOutline, kFullSkyLight, kFlagUntextured });
				}
			}
		}

		bool UploadDynamic(ID3D11DeviceContext* a_context)
		{
			if (dynVerts.empty()) {
				return false;
			}
			const UINT bytes = static_cast<UINT>(dynVerts.size() * sizeof(Vertex));
			if (bytes > dynCapacity) {
				Release(dynVb);
				D3D11_BUFFER_DESC bd{};
				bd.ByteWidth = std::max<UINT>(bytes * 2, 64 * 1024);
				bd.Usage = D3D11_USAGE_DYNAMIC;
				bd.BindFlags = D3D11_BIND_VERTEX_BUFFER;
				bd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
				if (FAILED(device->CreateBuffer(&bd, nullptr, &dynVb))) {
					dynCapacity = 0;
					return false;
				}
				dynCapacity = bd.ByteWidth;
			}
			D3D11_MAPPED_SUBRESOURCE mapped{};
			if (FAILED(a_context->Map(dynVb, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
				return false;
			}
			std::memcpy(mapped.pData, dynVerts.data(), bytes);
			a_context->Unmap(dynVb, 0);
			return true;
		}

		void SetObjectOffset(ID3D11DeviceContext* a_context, const double a_skyrim[3], const RE::NiPoint3& a_cam,
			const double* a_model = nullptr, const double* a_previous = nullptr, bool a_entity = false,
			const double* a_previousFeet = nullptr)
		{
			D3D11_MAPPED_SUBRESOURCE mapped{};
			if (SUCCEEDED(a_context->Map(objectCb, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
				auto* o = static_cast<ObjectConstants*>(mapped.pData);
				o->offset[0] = float(a_skyrim[0] - a_cam.x);
				o->offset[1] = float(a_skyrim[1] - a_cam.y);
				o->offset[2] = float(a_skyrim[2] - a_cam.z);
				o->offset[3] = a_entity ? 1.0f : 0.0f;
				std::memcpy(o->previousOffset, o->offset, sizeof(o->offset));
				if (a_previousFeet) {
					o->previousOffset[0] = float(a_previousFeet[0] * kUnits - a_cam.x);
					o->previousOffset[1] = float(-a_previousFeet[2] * kUnits - a_cam.y);
					o->previousOffset[2] = float(a_previousFeet[1] * kUnits - a_cam.z);
				}
				std::memset(o->model, 0, sizeof(o->model));
				std::memset(o->previousModel, 0, sizeof(o->previousModel));
				for (int row = 0; row < 4; row++) o->model[row][row] = o->previousModel[row][row] = 1.0f;
				if (a_model) {
					const double* previous = a_previous ? a_previous : a_model;
					for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
						o->model[row][col] = float(a_model[col * 4 + row]);
						o->previousModel[row][col] = float(previous[col * 4 + row]);
					}
					o->previousOffset[0] = float(previous[12] * proto::kUnitsPerBlock - a_cam.x);
					o->previousOffset[1] = float(-previous[14] * proto::kUnitsPerBlock - a_cam.y);
					o->previousOffset[2] = float(previous[13] * proto::kUnitsPerBlock - a_cam.z);
				}
				a_context->Unmap(objectCb, 0);
			}
		}

		// Minecraft block coordinates -> Skyrim units (double precision).
		void McToSkyD(double a_x, double a_y, double a_z, double a_out[3])
		{
			a_out[0] = a_x * proto::kUnitsPerBlock;
			a_out[1] = -a_z * proto::kUnitsPerBlock;
			a_out[2] = a_y * proto::kUnitsPerBlock;
		}

		// A mesh from Minecraft's entity renderer at its origin, one texture at a time: its solid
		// batches or its blended ones.
		void DrawMesh(ID3D11DeviceContext* a_context, const Mesh& a_mesh, const double a_mcOrigin[3], const RE::NiPoint3& a_cam, bool a_blended,
			const double* a_model = nullptr, const double* a_previous = nullptr, bool a_shadow = false)
		{
			if (a_mesh.batches.empty() || !a_mesh.vb) {
				return;
			}
			double origin[3];
			McToSkyD(a_mcOrigin[0], a_mcOrigin[1], a_mcOrigin[2], origin);
			SetObjectOffset(a_context, origin, a_cam, a_model, a_previous, true,
				&a_mesh == &avatar && avatarHistory ? previousAvatarFeet : nullptr);
			const UINT stride = sizeof(Vertex), zero = 0;
			a_context->IASetVertexBuffers(0, 1, &a_mesh.vb, &stride, &zero);
			bool culling = false;
			auto* baseRaster = a_shadow ? shadowRaster : raster;
			auto* culledRaster = a_shadow ? shadowRasterCullBack : rasterCullBackModel;
			for (const auto& b : a_mesh.batches) {
				if (((b.flags & 1) != 0) != a_blended) {
					continue;
				}
				ID3D11ShaderResourceView* srv = atlasSrv;
				if (b.texture != 0) {
					const auto it = entityTextures.find(b.texture);
					if (it == entityTextures.end()) {
						continue;
					}
					srv = it->second.srv;
				}
				a_context->PSSetShaderResources(0, 1, &srv);
				const bool batchCulling = (b.flags & 4) != 0;
				if (batchCulling != culling) {
					a_context->RSSetState(batchCulling ? culledRaster : baseRaster);
					culling = batchCulling;
				}
				if (a_blended && (b.flags & 2)) {
					ID3D11BlendState* saved = nullptr;
					float factor[4]{}; UINT mask = 0;
					a_context->OMGetBlendState(&saved, factor, &mask);
					a_context->OMSetBlendState(additiveBlend, nullptr, 0xFFFFFFFF);
					a_context->Draw(b.count, b.first);
					a_context->OMSetBlendState(saved, factor, mask);
					Release(saved);
				} else a_context->Draw(b.count, b.first);
			}
			if (culling) a_context->RSSetState(baseRaster);
			a_context->PSSetShaderResources(0, 1, &atlasSrv);
		}

		// The ragdoll's parts where their bones are now, into ragdollMesh (Minecraft coords
		// relative to a_origin).
		bool PoseRagdoll(ID3D11DeviceContext* a_context, double a_origin[3])
		{
			if (!ragdoll.active || ragdoll.body.verts.empty()) {
				return false;
			}
			std::array<RE::NiTransform, proto::kPartCount> world;
			for (std::uint32_t p = 0; p < proto::kPartCount; ++p) {
				world[p] = ragdoll.bones[p] ? ragdoll.bones[p]->world * ragdoll.offsets[p] : ragdoll.offsets[p];
			}
			const auto centre = SkyToMc(ragdoll.root ? ragdoll.root->world.translate : RE::NiPoint3{});
			a_origin[0] = std::floor(centre.x), a_origin[1] = std::floor(centre.y), a_origin[2] = std::floor(centre.z);
			static std::vector<Vertex> posed;
			posed = ragdoll.body.verts;
			for (std::size_t i = 0; i < posed.size(); ++i) {
				const auto sky = world[ragdoll.part[i]] * ragdoll.local[i];
				const auto mc = SkyToMc(sky);
				posed[i].x = float(mc.x - a_origin[0]);
				posed[i].y = float(mc.y - a_origin[1]);
				posed[i].z = float(mc.z - a_origin[2]);
			}
			const UINT bytes = UINT(posed.size() * sizeof(Vertex));
			if (bytes > ragdollMesh.capacity) {
				Release(ragdollMesh.vb);
				D3D11_BUFFER_DESC bd{};
				bd.ByteWidth = std::max<UINT>(bytes * 2, 128 * 1024);
				bd.Usage = D3D11_USAGE_DYNAMIC;
				bd.BindFlags = D3D11_BIND_VERTEX_BUFFER;
				bd.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
				if (FAILED(device->CreateBuffer(&bd, nullptr, &ragdollMesh.vb))) {
					ragdollMesh.capacity = 0;
					return false;
				}
				ragdollMesh.capacity = bd.ByteWidth;
			}
			D3D11_MAPPED_SUBRESOURCE mapped{};
			if (FAILED(a_context->Map(ragdollMesh.vb, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
				return false;
			}
			std::memcpy(mapped.pData, posed.data(), bytes);
			a_context->Unmap(ragdollMesh.vb, 0);
			ragdollMesh.batches = ragdoll.body.batches;
			for (auto& b : ragdollMesh.batches) {
				b.flags &= 7;  // preserve alpha/additive/culling draw flags, remove the part number
			}
			return true;
		}

		// The player's body (at the feet the camera follows) and every other entity.
		void DrawEntities(ID3D11DeviceContext* a_context, const RE::NiPoint3& a_cam, bool a_blended, bool a_avatar = true, bool a_others = true, bool a_shadow = false)
		{
			if (a_avatar && avatarHistory && !ragdoll.active) {
				// All colour, particle-depth and post-depth passes share one pose
				// anchor, even when fast flight advances the live player state.
				ID3D11BlendState* oldBlend = nullptr;
				float oldFactor[4]{};
				UINT oldMask = 0;
				if (!a_blended) {
					a_context->OMGetBlendState(&oldBlend, oldFactor, &oldMask);
					a_context->OMSetBlendState(avatarSolidBlend, nullptr, 0xFFFFFFFF);
				}
				DrawMesh(a_context, avatar, avatarFeet, a_cam, a_blended, nullptr, nullptr, a_shadow);
				if (!a_blended) {
					a_context->OMSetBlendState(oldBlend, oldFactor, oldMask);
					Release(oldBlend);
				}
			}
			if (!a_others) return;
			if (ragdoll.active) {
				static double origin[3]{};
				if (!a_blended) {
					PoseRagdoll(a_context, origin);  // once a frame: the solid pass comes first
				}
				DrawMesh(a_context, ragdollMesh, origin, a_cam, a_blended, nullptr, nullptr, a_shadow);
			}
			DrawMesh(a_context, scene, scene.origin, a_cam, a_blended, nullptr, nullptr, a_shadow);
			for (const auto& [id, moving] : movingMeshes) {
				if (!moving.positioned) continue;
				const double origin[3] = { moving.transform[12], moving.transform[13], moving.transform[14] };
				DrawMesh(a_context, moving.mesh, origin, a_cam, a_blended, moving.transform, moving.previous, a_shadow);
			}
		}

		// Minecraft surfaces are drawn after Skyrim's tone mapping, so Skyrim's light values go
		// through a soft exposure curve (1 - e^-kx) to land in displayable range.
		constexpr float kExposure = 1.6f;

		void Set4(float a_out[4], float a_x, float a_y, float a_z, float a_w)
		{
			a_out[0] = a_x, a_out[1] = a_y, a_out[2] = a_z, a_out[3] = a_w;
		}

		void SetColor(float a_out[4], const RE::Color& a_c, float a_w = 0.0f)
		{
			Set4(a_out, a_c.red / 255.0f, a_c.green / 255.0f, a_c.blue / 255.0f, a_w);
		}

		// An interior cell's lighting values, from its lighting template where the cell inherits them.
		template <class F>
		void InteriorValue(RE::TESObjectCELL* a_cell, RE::INTERIOR_DATA::Inherit a_flag, F a_get)
		{
			auto*      own = a_cell->GetLighting();
			auto*      tmpl = a_cell->GetRuntimeData().lightingTemplate;
			const bool inherit = tmpl && own->lightingTemplateInheritanceFlags.any(a_flag);
			a_get(inherit ? tmpl->data : *own, inherit ? tmpl : nullptr);
		}

		// Skyrim's lighting, for Minecraft surfaces to be lit the way Skyrim lights its own: the
		// sun, moon or interior directional light, the directional ambient, the nearest point
		// lights (torches, fires, spells), fog, and the image space's colour grading.
		// The feet of Minecraft players and mobs near the camera, for their contact shadows (BlobAO).
		void GatherContactShadows(FrameConstants& a_fc, const RE::NiPoint3& a_cam)
		{
			struct Blob
			{
				float d2;
				float v[4];
			};
			static std::vector<Blob> blobs;
			blobs.clear();
			for (std::uint32_t i = 0; i < entities.count; ++i) {
				const auto& e = entities.entities[i];
				if (e.kind != proto::kWeShadow) {
					continue;
				}
				double sky[3];
				McToSkyD(e.x, e.y, e.z, sky);
				const float rx = float(sky[0] - a_cam.x), ry = float(sky[1] - a_cam.y), rz = float(sky[2] - a_cam.z);
				const float radius = std::max(e.scale, 0.5f) * 1.25f * float(proto::kUnitsPerBlock);
				blobs.push_back({ rx * rx + ry * ry + rz * rz, { rx, ry, rz, radius } });
			}
			std::ranges::sort(blobs, {}, &Blob::d2);
			const auto count = std::min<std::size_t>(blobs.size(), kMaxBlobs);
			for (std::size_t k = 0; k < count; ++k) {
				std::memcpy(a_fc.aoBlobs[k], blobs[k].v, sizeof(blobs[k].v));
			}
			Set4(a_fc.aoParams, float(count), 0.0f, 0.0f, 0.0f);
		}

		// Contact shadows where blocks meet Skyrim (VoxelAO): what's solid, per block, in a box around
		// the camera. Rebuilt when the camera has moved a few blocks, or (at most 4 times a second)
		// when blocks or Skyrim's collision in it change.
		constexpr std::int32_t     kAoSize[3] = { 64, 32, 64 };  // blocks: x, y (up), z
		ID3D11Texture3D*           aoTex = nullptr;
		ID3D11ShaderResourceView*  aoSrv = nullptr;
		bool                       aoFailed = false, aoBuilt = false;
		std::vector<std::uint32_t> aoCells;
		std::int32_t               aoOrigin[3]{};
		std::uint32_t              aoMcGen = 0, aoSkyGen = 0;
		std::int64_t               aoBuiltQpc = 0;

		int aoRebuilds = 0;  // diagnostics

		// Diagnostics switch: an empty file "skycraft_nocontact" next to SkyCraft.log turns the blocks'
		// contact shadows off (checked every 2 s), to measure what they cost.
		bool ContactShadowsWanted()
		{
			static bool          wanted = true;
			static std::int64_t  next = 0;
			LARGE_INTEGER        now, freq;
			::QueryPerformanceCounter(&now);
			::QueryPerformanceFrequency(&freq);
			if (now.QuadPart >= next) {
				next = now.QuadPart + freq.QuadPart * 2;
				const auto dir = SKSE::log::log_directory();
				const bool off = dir && std::filesystem::exists(*dir / "skycraft_nocontact");
				if (wanted == off) {
					logger::info("contact shadows {}", off ? "switched off (skycraft_nocontact)" : "switched on");
				}
				wanted = !off;
			}
			return wanted;
		}

		bool UpdateContactVolume(ID3D11DeviceContext* a_context, const McVec& a_cam, FrameConstants& a_fc)
		{
			Set4(a_fc.aoVolume, 0.0f, 0.0f, 0.0f, 0.0f);
			if (aoFailed || !device || !ContactShadowsWanted()) {
				return false;
			}
			if (!aoTex) {
				D3D11_TEXTURE3D_DESC td{};
				td.Width = kAoSize[0];
				td.Height = kAoSize[1];
				td.Depth = kAoSize[2];
				td.MipLevels = 1;
				td.Format = DXGI_FORMAT_R32_UINT;
				td.Usage = D3D11_USAGE_DEFAULT;
				td.BindFlags = D3D11_BIND_SHADER_RESOURCE;
				if (FAILED(device->CreateTexture3D(&td, nullptr, &aoTex)) || FAILED(device->CreateShaderResourceView(aoTex, nullptr, &aoSrv))) {
					aoFailed = true;
					Release(aoTex);
					logger::warn("contact shadows: couldn't create the volume texture; blocks go without them");
					return false;
				}
				aoCells.resize(std::size_t(kAoSize[0]) * kAoSize[1] * kAoSize[2]);
			}
			const std::int32_t want[3] = { std::int32_t(std::floor(a_cam.x)) - kAoSize[0] / 2, std::int32_t(std::floor(a_cam.y)) - kAoSize[1] / 2,
				std::int32_t(std::floor(a_cam.z)) - kAoSize[2] / 2 };
			bool moved = !aoBuilt;
			for (int k = 0; k < 3; ++k) {
				moved |= std::abs(want[k] - aoOrigin[k]) > 2;
			}
			const auto    mcGen = NpcBlocks::Generation(), skyGen = Collision::Get().BoxesGeneration();
			LARGE_INTEGER now, freq;
			::QueryPerformanceCounter(&now);
			::QueryPerformanceFrequency(&freq);
			const bool changed = mcGen != aoMcGen || skyGen != aoSkyGen;
			if (moved || (changed && now.QuadPart - aoBuiltQpc > freq.QuadPart / 4)) {
				if (moved) {
					std::memcpy(aoOrigin, want, sizeof(aoOrigin));
				}
				std::ranges::fill(aoCells, 0u);
				NpcBlocks::CopySolids(aoOrigin, kAoSize, aoCells.data(), 1u);
				Collision::Get().CopyBoxes(aoOrigin, kAoSize, aoCells.data());
				a_context->UpdateSubresource(aoTex, 0, nullptr, aoCells.data(), kAoSize[0] * 4, kAoSize[0] * kAoSize[1] * 4);
				aoBuilt = true;
				++aoRebuilds;
				aoMcGen = mcGen;
				aoSkyGen = skyGen;
				aoBuiltQpc = now.QuadPart;
				static int logged = 0;
				if (logged < 3) {
					++logged;
					const auto blocks = std::ranges::count_if(aoCells, [](std::uint32_t v) { return (v & 1) != 0; });
					const auto boxes = std::ranges::count_if(aoCells, [](std::uint32_t v) { return (v & Collision::kBoxPresent) != 0; });
					logger::info("contact shadows: {} Minecraft blocks and {} blocks' worth of Skyrim geometry around the camera", blocks, boxes);
				}
			}
			Set4(a_fc.aoVolume, float(aoOrigin[0] - a_cam.x), float(aoOrigin[1] - a_cam.y), float(aoOrigin[2] - a_cam.z), 1.0f);
			return true;
		}

		void GatherLighting(FrameConstants& a_fc, const RE::NiPoint3& a_cam)
		{
			// Fallbacks, should any of Skyrim's lighting be missing: plain daylight.
			Set4(a_fc.sunDir, 0.3f, -0.4f, 0.87f, kExposure);
			Set4(a_fc.sunColor, 0.9f, 0.85f, 0.75f, 0.0f);
			for (auto& a : a_fc.ambient) {
				Set4(a, 0.45f, 0.47f, 0.5f, 0.0f);
			}
			Set4(a_fc.fogNear, 0, 0, 0, 0);
			Set4(a_fc.grade, 1, 1, 1, 0);

			auto*      player = RE::PlayerCharacter::GetSingleton();
			auto*      cell = player ? player->GetParentCell() : nullptr;
			const bool interior = cell && cell->IsInteriorCell() && cell->GetLighting();
			auto*      sky = RE::Sky::GetSingleton();
			auto*      ssn = RE::BSShaderManager::State::GetSingleton().shadowSceneNode[0];

			// The directional light Skyrim's shaders use: sun or moon outside, the cell's light inside.
			RE::NiDirectionalLight* dirLight = nullptr;
			if (ssn) {
				auto* bsSun = ssn->GetRuntimeData().sunLight;
				if (bsSun && bsSun->light) {
					dirLight = netimmerse_cast<RE::NiDirectionalLight*>(bsSun->light.get());
				}
			}
			float sunFade = 1.0f;
			if (dirLight) {
				const auto  dir = dirLight->GetWorldDirection();
				const float len = dir.Length();
				if (len > 1e-4f) {
					Set4(a_fc.sunDir, -dir.x / len, -dir.y / len, -dir.z / len, kExposure);
				}
				const auto& ld = dirLight->GetLightRuntimeData();
				sunFade = ld.fade > 0.0f && ld.fade < 16.0f ? ld.fade : 1.0f;
				Set4(a_fc.sunColor, ld.diffuse.red * sunFade, ld.diffuse.green * sunFade, ld.diffuse.blue * sunFade, 0.0f);
			}

			// Directional ambient ("DALC"): six colours named by the way the light travels, so a
			// face looking along +Z (up) takes the Z- colour, the sky's light coming down. (Checked
			// in game: Z- is the bright sky colour, and the sides facing the sun take X-/Y-.)
			using Inherit = RE::INTERIOR_DATA::Inherit;
			if (interior) {
				InteriorValue(cell, Inherit::kAmbientColor, [&](const RE::INTERIOR_DATA& a_d, RE::BGSLightingTemplate* a_t) {
					const auto& dal = a_t ? a_t->directionalAmbientLightingColors.directional : a_d.directionalAmbientLightingColors.directional;
					SetColor(a_fc.ambient[0], dal.x.min);
					SetColor(a_fc.ambient[1], dal.x.max);
					SetColor(a_fc.ambient[2], dal.y.min);
					SetColor(a_fc.ambient[3], dal.y.max);
					SetColor(a_fc.ambient[4], dal.z.min);
					SetColor(a_fc.ambient[5], dal.z.max);
				});
			} else if (sky) {
				// Skyrim's blended copy of the weather's colours: [axis][0] is the + colour, [axis][1] the - one.
				for (int axis = 0; axis < 3; ++axis) {
					const auto& plus = sky->directionalAmbientColors[axis][0];
					const auto& minus = sky->directionalAmbientColors[axis][1];
					Set4(a_fc.ambient[axis * 2], minus.red, minus.green, minus.blue, 0.0f);  // faces looking +axis
					Set4(a_fc.ambient[axis * 2 + 1], plus.red, plus.green, plus.blue, 0.0f);
				}
			}

			// Fog, as Skyrim fades its own geometry with distance.
			float fogNearD = 0, fogFarD = 0, fogPower = 1, fogMax = 0;
			if (interior) {
				InteriorValue(cell, Inherit::kFogNear, [&](const RE::INTERIOR_DATA& a_d, auto*) { fogNearD = a_d.fogNear; });
				InteriorValue(cell, Inherit::kFogFar, [&](const RE::INTERIOR_DATA& a_d, auto*) { fogFarD = a_d.fogFar; });
				InteriorValue(cell, Inherit::kFogPower, [&](const RE::INTERIOR_DATA& a_d, auto*) { fogPower = a_d.fogPower; });
				InteriorValue(cell, Inherit::kFogMax, [&](const RE::INTERIOR_DATA& a_d, auto*) { fogMax = a_d.fogClamp; });
				InteriorValue(cell, Inherit::kFogColor, [&](const RE::INTERIOR_DATA& a_d, auto*) {
					SetColor(a_fc.fogNear, a_d.fogColorNear);
					SetColor(a_fc.fogFar, a_d.fogColorFar);
				});
			} else if (sky) {
				fogNearD = sky->fogNear, fogFarD = sky->fogFar, fogPower = sky->fogPower, fogMax = sky->fogClamp;
				const auto& n = sky->skyColor[RE::TESWeather::ColorTypes::kFogNear];
				const auto& f = sky->skyColor[RE::TESWeather::ColorTypes::kFogFar];
				Set4(a_fc.fogNear, n.red, n.green, n.blue, 0.0f);
				Set4(a_fc.fogFar, f.red, f.green, f.blue, 0.0f);
			}
			if (fogFarD > fogNearD + 1.0f && fogMax > 0.0f) {
				Set4(a_fc.fogRange, 1.0f / (fogFarD - fogNearD), fogNearD / (fogFarD - fogNearD), fogPower > 0.0f ? fogPower : 1.0f, std::min(fogMax, 1.0f));
				a_fc.fogNear[3] = 1.0f;
			}

			// The image space's colour grading (Skyrim's look: saturation, tint, brightness, contrast).
			if (auto* ism = RE::ImageSpaceManager::GetSingleton()) {
				const auto& base = ism->GetImageSpaceData().baseData;
				const auto& cin = base.cinematic;
				if (cin.saturation >= 0.0f && cin.saturation < 4.0f && cin.brightness > 0.05f && cin.brightness < 4.0f && cin.contrast > 0.05f && cin.contrast < 4.0f) {
					Set4(a_fc.grade, cin.saturation, cin.brightness, cin.contrast, 1.0f);
					Set4(a_fc.tint, base.tint.color.red, base.tint.color.green, base.tint.color.blue, std::clamp(base.tint.amount, 0.0f, 1.0f));
				}
			}

			// The point lights nearest to reaching the camera.
			struct Candidate
			{
				float        reach;
				RE::NiPoint3 pos;
				float        radius;
				RE::NiColor  color;
			};
			static std::vector<Candidate> candidates;
			candidates.clear();
			auto consider = [&](RE::BSLight* a_light) {
				if (!a_light || !a_light->pointLight || !a_light->light) {
					return;
				}
				auto* light = a_light->light.get();
				if (BlockLights::IsOurs(light)) {
					return;  // Minecraft's own block light is already in the blocks' vertices
				}
				const auto& ld = light->GetLightRuntimeData();
				const float radius = ld.radius.x;
				if (!(radius > 1.0f) || light->GetFlags().any(RE::NiAVObject::Flag::kHidden)) {
					return;
				}
				const float dimmer = std::clamp(a_light->lodDimmer, 0.0f, 1.0f) * ld.fade;
				const auto  pos = light->world.translate;
				const float reach = pos.GetDistance(a_cam) - radius;
				if (reach > 6000.0f || std::fabs(dimmer) < 1e-3f) {
					return;
				}
				candidates.push_back({ reach, pos, radius, { ld.diffuse.red * dimmer, ld.diffuse.green * dimmer, ld.diffuse.blue * dimmer } });
			};
			if (ssn) {
				auto& rd = ssn->GetRuntimeData();
				for (auto& light : rd.activeLights) {
					consider(light.get());
				}
				for (auto& light : rd.activeShadowLights) {
					consider(light.get());
				}
			}
			std::ranges::sort(candidates, {}, &Candidate::reach);
			const auto count = std::min<std::size_t>(candidates.size(), kMaxLights);
			for (std::size_t k = 0; k < count; ++k) {
				const auto& c = candidates[k];
				Set4(a_fc.lightPos[k], c.pos.x - a_cam.x, c.pos.y - a_cam.y, c.pos.z - a_cam.z, 1.0f / c.radius);
				Set4(a_fc.lightColor[k], c.color.red, c.color.green, c.color.blue, 0.0f);
			}
			a_fc.sunColor[3] = float(count);
			a_fc.shadowParams[0] = (!interior && dirLight && a_fc.sunDir[2] > 0.03f) ? 1.0f : 0.0f;

			// What Skyrim gave us, now and then, for tuning.
			static RE::TESObjectCELL* loggedCell = nullptr;
			static auto               lastLog = std::chrono::steady_clock::time_point{};
			static int                initialLightingLogs = 0;
			const auto                now = std::chrono::steady_clock::now();
			if ((DiagnosticsEnabled() || initialLightingLogs < 3) && (cell != loggedCell || now - lastLog > std::chrono::seconds(30))) {
				++initialLightingLogs;
				loggedCell = cell;
				lastLog = now;
				const float* up = a_fc.ambient[4];
				const float* down = a_fc.ambient[5];
				const float* east = a_fc.ambient[0];
				logger::info("lighting ({}{}): sun dir ({:.2f} {:.2f} {:.2f}) colour ({:.2f} {:.2f} {:.2f}) fade {:.2f}; ambient up ({:.2f} {:.2f} {:.2f}) down ({:.2f} {:.2f} {:.2f}) east ({:.2f} {:.2f} {:.2f})",
					interior ? "interior" : "exterior", dirLight ? "" : ", no directional light", a_fc.sunDir[0], a_fc.sunDir[1], a_fc.sunDir[2],
					a_fc.sunColor[0], a_fc.sunColor[1], a_fc.sunColor[2], sunFade, up[0], up[1], up[2], down[0], down[1], down[2], east[0], east[1], east[2]);
				static RE::TESWeather* loggedWeather = nullptr;
				if (sky && sky->currentWeather && sky->currentWeather != loggedWeather) {
					loggedWeather = sky->currentWeather;
					// The weather's own record (X+, X-, Y+, Y-, Z+, Z-) beside the blended runtime copy, to check the order.
					const auto& d = sky->currentWeather->directionalAmbientLightingColors[RE::TESWeather::ColorTime::kDay].directional;
					const auto& r = sky->directionalAmbientColors;
					auto        avg = [](const RE::Color& a_c) { return (a_c.red + a_c.green + a_c.blue) / 765.0f; };
					auto        avgN = [](const RE::NiColor& a_c) { return (a_c.red + a_c.green + a_c.blue) / 3.0f; };
					logger::info("lighting: weather {:08X} day ambient X+ {:.2f} X- {:.2f} Y+ {:.2f} Y- {:.2f} Z+ {:.2f} Z- {:.2f}; runtime [x] {:.2f} {:.2f} [y] {:.2f} {:.2f} [z] {:.2f} {:.2f}",
						sky->currentWeather->GetFormID(), avg(d.x.max), avg(d.x.min), avg(d.y.max), avg(d.y.min), avg(d.z.max), avg(d.z.min), avgN(r[0][0]), avgN(r[0][1]),
						avgN(r[1][0]), avgN(r[1][1]), avgN(r[2][0]), avgN(r[2][1]));
				}
				if (sky) {
					const auto& su = sky->directionalAmbientColors[2][1];
					const auto& sa = sky->skyColor[RE::TESWeather::ColorTypes::kAmbient];
					const auto& sl = sky->skyColor[RE::TESWeather::ColorTypes::kSunlight];
					logger::info("lighting: sky mode {} ambient-up ({:.2f} {:.2f} {:.2f}) ambient ({:.2f} {:.2f} {:.2f}) sunlight ({:.2f} {:.2f} {:.2f}); fog {:.0f}-{:.0f} power {:.2f} max {:.2f} ({})",
						static_cast<int>(sky->mode.get()), su.red, su.green, su.blue, sa.red, sa.green, sa.blue, sl.red, sl.green, sl.blue, fogNearD, fogFarD, fogPower,
						fogMax, a_fc.fogNear[3] > 0.5f ? "on" : "off");
				}
				logger::info("lighting: grade saturation {:.2f} brightness {:.2f} contrast {:.2f} tint ({:.2f} {:.2f} {:.2f}) x{:.2f} ({}); {} point lights of {} in range",
					a_fc.grade[0], a_fc.grade[1], a_fc.grade[2], a_fc.tint[0], a_fc.tint[1], a_fc.tint[2], a_fc.tint[3], a_fc.grade[3] > 0.5f ? "on" : "off", count,
					candidates.size());
				for (std::size_t k = 0; k < std::min<std::size_t>(count, 3); ++k) {
					const auto& c = candidates[k];
					logger::info("lighting:   light {:.0f} away, radius {:.0f}, colour ({:.2f} {:.2f} {:.2f})", c.reach + c.radius, c.radius, c.color.red, c.color.green,
						c.color.blue);
				}
			}
		}

		// Reads back the 16 depth probes rendered a few frames ago and decides how to read
		// Skyrim's depth: standard (1 = far) or reversed (0 = far), or unusable (cleared).
		void ReadProbe(ID3D11DeviceContext* a_context)
		{
			if (!probePending) {
				return;
			}
			D3D11_MAPPED_SUBRESOURCE mapped{};
			if (a_context->Map(probeStaging, 0, D3D11_MAP_READ, D3D11_MAP_FLAG_DO_NOT_WAIT, &mapped) != S_OK) {
				return;
			}
			std::array<float, 16> v{}, m{};
			const auto*           texels = static_cast<const float*>(mapped.pData);
			for (int k = 0; k < 16; ++k) {
				v[k] = texels[k * 2];
				m[k] = texels[k * 2 + 1];
			}
			a_context->Unmap(probeStaging, 0);
			probePending = false;

			// Skyrim's shadow mask should hold 0-1 (1 = sunlit); some of it lit whenever it's in use.
			auto maskSorted = m;
			std::ranges::sort(maskSorted);
			const bool maskUsable = maskSorted[0] >= -0.001f && maskSorted[15] <= 1.001f && maskSorted[15] > 0.5f;
			static bool maskLogged = false;
			static auto maskLogTime = std::chrono::steady_clock::time_point{};
			const auto  now = std::chrono::steady_clock::now();
			if (!maskLogged || (maskUsable != shadowMaskUsable && now - maskLogTime > std::chrono::seconds(60))) {
				maskLogged = true;
				maskLogTime = now;
				logger::info("Skyrim shadow mask probe: min {:.3f} median {:.3f} max {:.3f} -> {}", maskSorted[0], maskSorted[8], maskSorted[15],
					maskUsable ? "used (no double shadows; blocks in Skyrim's shade)" : "not used");
			}
			shadowMaskUsable = maskUsable;
			auto sorted = v;
			std::ranges::sort(sorted);
			const float median = sorted[8];
			const bool  flat = sorted[15] - sorted[0] < 1e-7f;
			const bool  wasReversed = depthReversed, wasUsable = depthUsable, wasKnown = depthKnown;
			depthUsable = !flat;
			depthKnown = true;
			if (!wasKnown || wasReversed != depthReversed || wasUsable != depthUsable) {
				logger::info("Skyrim depth probe: min {:.6f} median {:.6f} max {:.6f} -> {}", sorted[0], median, sorted[15],
					!depthUsable ? "flat (camera projection retained)" : depthReversed ? "reversed Z" : "standard Z");
			}
		}

		void RunProbe(ID3D11DeviceContext* a_context, ID3D11ShaderResourceView* a_depthSrv, ID3D11ShaderResourceView* a_maskSrv)
		{
			D3D11_VIEWPORT vp{ 0, 0, 16, 1, 0, 1 };
			a_context->OMSetRenderTargets(1, &probeRtv, nullptr);
			a_context->RSSetViewports(1, &vp);
			a_context->OMSetBlendState(noBlend, nullptr, 0xFFFFFFFF);
			a_context->OMSetDepthStencilState(nullptr, 0);
			a_context->IASetInputLayout(nullptr);
			a_context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
			a_context->VSSetShader(probeVs, nullptr, 0);
			a_context->PSSetShader(probePs, nullptr, 0);
			a_context->PSSetShaderResources(1, 1, &a_depthSrv);
			a_context->PSSetShaderResources(3, 1, &a_maskSrv);
			a_context->PSSetSamplers(1, 1, &pointSampler);
			a_context->Draw(3, 0);
			a_context->CopyResource(probeStaging, probeTex);
			probePending = true;
		}

		bool EnsureShadowMap()
		{
			if (shadowDsv) {
				return true;
			}
			D3D11_TEXTURE2D_DESC td{};
			td.Width = td.Height = kShadowSize;
			td.MipLevels = 1;
			td.ArraySize = 1;
			td.Format = DXGI_FORMAT_R32_TYPELESS;
			td.SampleDesc.Count = 1;
			td.Usage = D3D11_USAGE_DEFAULT;
			td.BindFlags = D3D11_BIND_DEPTH_STENCIL | D3D11_BIND_SHADER_RESOURCE;
			D3D11_DEPTH_STENCIL_VIEW_DESC dv{};
			dv.Format = DXGI_FORMAT_D32_FLOAT;
			dv.ViewDimension = D3D11_DSV_DIMENSION_TEXTURE2D;
			D3D11_SHADER_RESOURCE_VIEW_DESC sv{};
			sv.Format = DXGI_FORMAT_R32_FLOAT;
			sv.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
			sv.Texture2D.MipLevels = 1;
			if (FAILED(device->CreateTexture2D(&td, nullptr, &shadowTex)) || FAILED(device->CreateDepthStencilView(shadowTex, &dv, &shadowDsv)) ||
				FAILED(device->CreateShaderResourceView(shadowTex, &sv, &shadowSrv))) {
				logger::error("shadow map {}x{} failed", kShadowSize, kShadowSize);
				Release(shadowSrv);
				Release(shadowDsv);
				Release(shadowTex);
				return false;
			}
			logger::info("sun shadow map ready ({}x{}, {:.0f} blocks across)", kShadowSize, kShadowSize, 2.0f * kShadowRadius / 70.0f);
			return true;
		}

		// An orthographic view down the sun's rays, centred on the camera and snapped to whole
		// shadow texels so shadow edges hold still as the camera moves.
		void SetSunShadowMatrix(FrameConstants& a_fc, const RE::NiPoint3& a_cam)
		{
			// The shadow map follows the sun in small steps (like Skyrim's own shadows): turned a
			// little every frame, its texels crawl, and shadow edges and self-shadowing flicker on
			// skin and armour as the sun moves.
			static double stable[3] = { 0.0, 0.0, 0.0 };
			const double  now[3] = { -a_fc.sunDir[0], -a_fc.sunDir[1], -a_fc.sunDir[2] };  // the way sunlight travels
			const double  cosStep = stable[0] * now[0] + stable[1] * now[1] + stable[2] * now[2];
			if (cosStep < 0.99999) {  // about a quarter of a degree
				std::memcpy(stable, now, sizeof(stable));
			}
			const double f[3] = { stable[0], stable[1], stable[2] };
			const double up[3] = { 0.0, std::fabs(f[2]) > 0.99 ? 1.0 : 0.0, std::fabs(f[2]) > 0.99 ? 0.0 : 1.0 };
			double       r[3] = { f[1] * up[2] - f[2] * up[1], f[2] * up[0] - f[0] * up[2], f[0] * up[1] - f[1] * up[0] };
			const double rl = std::sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
			for (auto& c : r) {
				c /= rl;
			}
			const double u[3] = { r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0] };
			const double texel = 2.0 * kShadowRadius / kShadowSize;
			const double a = r[0] * a_cam.x + r[1] * a_cam.y + r[2] * a_cam.z;
			const double b = u[0] * a_cam.x + u[1] * a_cam.y + u[2] * a_cam.z;
			const double offX = a - std::floor(a / texel) * texel, offY = b - std::floor(b / texel) * texel;
			auto&        m = a_fc.lightViewProj;
			for (int k = 0; k < 3; ++k) {
				m[0][k] = float(r[k] / kShadowRadius);
				m[1][k] = float(u[k] / kShadowRadius);
				m[2][k] = float(f[k] / (2.0 * kShadowDepth));
				m[3][k] = 0.0f;
			}
			m[0][3] = float(offX / kShadowRadius);
			m[1][3] = float(offY / kShadowRadius);
			m[2][3] = 0.5f;
			m[3][3] = 1.0f;
			Set4(a_fc.shadowParams, 1.0f, float(texel * 1.5), 1.0f / kShadowSize, float(3.0 / (2.0 * kShadowDepth)));
			a_fc.shadowExtra[1] = (kShadowRadius * 1.5f) * (kShadowRadius * 1.5f);
		}

		DXGI_FORMAT DepthReadFormat(DXGI_FORMAT a_typeless)
		{
			switch (a_typeless) {
			case DXGI_FORMAT_R16_TYPELESS:
			case DXGI_FORMAT_D16_UNORM:
				return DXGI_FORMAT_R16_UNORM;
			case DXGI_FORMAT_R24G8_TYPELESS:
			case DXGI_FORMAT_D24_UNORM_S8_UINT:
				return DXGI_FORMAT_R24_UNORM_X8_TYPELESS;
			case DXGI_FORMAT_R32_TYPELESS:
			case DXGI_FORMAT_D32_FLOAT:
				return DXGI_FORMAT_R32_FLOAT;
			case DXGI_FORMAT_R32G8X24_TYPELESS:
				return DXGI_FORMAT_R32_FLOAT_X8X24_TYPELESS;
			default:
				return DXGI_FORMAT_UNKNOWN;
			}
		}

		// Called right after Skyrim renders the sun's shadow maps: copy its cascades.
		void CaptureSunShadows(RE::BSShadowLight* a_sun)
		{
			auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
			if (!device || !renderer || !a_sun || !State().puppeting) {
				return;
			}
			auto& descs = a_sun->GetRuntimeData().shadowmapDescriptors;
			const auto count = std::min<std::uint32_t>(descs.size(), 2);
			if (count == 0) {
				return;
			}
			auto* context = NativeDrawContext();
			// Where they went: the depth buffer still bound, else the shared shadow map array.
			ID3D11Resource*         src = nullptr;
			ID3D11DepthStencilView* bound = nullptr;
			context->OMGetRenderTargets(0, nullptr, &bound);
			if (bound) {
				bound->GetResource(&src);
				Release(bound);
			}
			if (!src) {
				auto* tex = reinterpret_cast<ID3D11Texture2D*>(renderer->GetDepthStencilData().depthStencils[RE::RENDER_TARGETS_DEPTHSTENCIL::kSHADOWMAPS].texture);
				if (tex) {
					tex->AddRef();
					src = tex;
				}
			}
			if (!src) {
				return;
			}
			D3D11_TEXTURE2D_DESC sd{};
			static_cast<ID3D11Texture2D*>(src)->GetDesc(&sd);
			if (!sunShadowCopy) {
				D3D11_TEXTURE2D_DESC cd = sd;
				cd.ArraySize = 2;
				cd.MipLevels = 1;
				cd.BindFlags = D3D11_BIND_SHADER_RESOURCE;
				cd.Usage = D3D11_USAGE_DEFAULT;
				cd.CPUAccessFlags = 0;
				cd.MiscFlags = 0;
				D3D11_SHADER_RESOURCE_VIEW_DESC vd{};
				vd.Format = DepthReadFormat(sd.Format);
				vd.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2DARRAY;
				vd.Texture2DArray.MipLevels = 1;
				vd.Texture2DArray.ArraySize = 2;
				const bool ok = vd.Format != DXGI_FORMAT_UNKNOWN && sd.SampleDesc.Count == 1 && SUCCEEDED(device->CreateTexture2D(&cd, nullptr, &sunShadowCopy)) &&
				                SUCCEEDED(device->CreateShaderResourceView(sunShadowCopy, &vd, &sunShadowCopySrv));
				logger::info("Skyrim shadows on blocks: capturing the sun's cascades from a {}x{} x{} format {} depth array ({})", sd.Width, sd.Height, sd.ArraySize,
					static_cast<int>(sd.Format), ok ? "ok" : "can't read that format");
				if (!ok) {
					Release(sunShadowCopySrv);
					Release(sunShadowCopy);
					Release(src);
					return;
				}
			}
			D3D11_TEXTURE2D_DESC cd{};
			sunShadowCopy->GetDesc(&cd);
			if (cd.Width != sd.Width || cd.Height != sd.Height || cd.Format != sd.Format) {
				Release(sunShadowCopySrv);
				Release(sunShadowCopy);
				Release(src);
				return;  // recreated next frame
			}
			const auto& dsl = static_cast<RE::BSShadowDirectionalLight*>(a_sun)->GetShadowDirectionalLightRuntimeData();
			for (std::uint32_t k = 0; k < count; ++k) {
				const UINT slice = descs[k].shadowmapIndex;
				if (slice < sd.ArraySize) {
					context->CopySubresourceRegion(sunShadowCopy, D3D11CalcSubresource(0, k, 1), 0, 0, 0, src, D3D11CalcSubresource(0, slice, sd.MipLevels), nullptr);
				}
				std::memcpy(cascadeSnapshot[k].m, &descs[k].lightTransform.m, sizeof(cascadeSnapshot[k].m));
				cascadeSnapshot[k].split = k < 3 ? dsl.endSplitDistances[k] : 0.0f;
				cascadeSnapshot[k].slice = slice;
			}
			cascadeSnapshotCount = count;
			Release(src);
			sunShadowCaptureTime = std::chrono::steady_clock::now();
		}

		// Skyrim's sun shadow cascades for this frame (outside only). Returns the shadow map array to
		// read, or null. The cascades' lightTransform maps a world position straight to shadow-map
		// uv and depth; it's re-based on the camera here like everything else.
		ID3D11ShaderResourceView* SetSkyrimShadows(FrameConstants& a_fc, const RE::NiPoint3& a_cam)
		{
			a_fc.skyShadowSplits[3] = 0.0f;
			static int failLogs = 0;
			auto       fail = [&](const char* a_why) -> ID3D11ShaderResourceView* {
                if (failLogs < 3) {
                    ++failLogs;
                    logger::info("Skyrim shadows on blocks: not this frame ({})", a_why);
                }
                return nullptr;
			};
			auto* ssn = RE::BSShaderManager::State::GetSingleton().shadowSceneNode[0];
			auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
			if (!ssn || !renderer) {
				return fail("no shadow scene");
			}
			// The sun's shadow light: its own slot, or the directional one among the active shadow lights.
			RE::BSShadowLight* sun = ssn->GetRuntimeData().sunShadowDirLight;
			if (!sun) {
				for (auto& light : ssn->GetRuntimeData().activeShadowLights) {
					if (light && light->GetIsDirectionalLight()) {
						sun = light.get();
						break;
					}
				}
			}
			if (!sun) {
				return fail("no sun shadow light");
			}
			auto&      descs = sun->GetRuntimeData().shadowmapDescriptors;
			const auto count = std::min<std::uint32_t>(descs.size(), 2);
			if (count == 0) {
				return fail("sun shadow light has no cascades");
			}
			const auto& dsl = static_cast<RE::BSShadowDirectionalLight*>(sun)->GetShadowDirectionalLightRuntimeData();
			static bool dumped = false;
			if (!dumped) {
				dumped = true;
				for (std::uint32_t k = 0; k < std::min<std::uint32_t>(descs.size(), 4); ++k) {
					const auto& d = descs[k];
					const auto& m = d.lightTransform.m;
					const auto* port = reinterpret_cast<const std::int32_t*>(&d.port);  // left, right, top, bottom
					logger::info("sun shadow cascade {} of {}: target {} slice {} port ({} {} {} {}) enabled {} camera {} split {:.0f}-{:.0f}; transform rows ({:.3g} {:.3g} {:.3g} {:.3g}) ({:.3g} {:.3g} {:.3g} {:.3g}) ({:.3g} {:.3g} {:.3g} {:.3g}) ({:.3g} {:.3g} {:.3g} {:.3g})",
						k, descs.size(), static_cast<std::int64_t>(d.renderTarget), d.shadowmapIndex, port[0], port[1], port[2], port[3], d.isEnabled,
						d.camera ? d.camera->GetRTTI() ? "yes" : "?" : "none", k < 3 ? dsl.startSplitDistances[k] : 0.0f, k < 3 ? dsl.endSplitDistances[k] : 0.0f, m[0][0], m[0][1],
						m[0][2], m[0][3], m[1][0], m[1][1], m[1][2], m[1][3], m[2][0], m[2][1], m[2][2], m[2][3], m[3][0], m[3][1], m[3][2], m[3][3]);
				}
			}
			// Vanilla renders the sun's cascades into the shared shadow map array.
			auto target = descs[0].renderTarget;
			if (static_cast<std::uint32_t>(target) >= RE::RENDER_TARGETS_DEPTHSTENCIL::kTOTAL) {
				target = RE::RENDER_TARGETS_DEPTHSTENCIL::kSHADOWMAPS;
			}
			(void)target;
			if (!sunShadowCopySrv || std::chrono::steady_clock::now() - sunShadowCaptureTime > std::chrono::seconds(1)) {
				return fail("the sun's shadow maps haven't been captured lately");
			}
			auto* srv = sunShadowCopySrv;
			D3D11_SHADER_RESOURCE_VIEW_DESC sd{};
			srv->GetDesc(&sd);
			if (sd.ViewDimension != D3D11_SRV_DIMENSION_TEXTURE2DARRAY) {
				static bool warned = false;
				if (!warned) {
					warned = true;
					logger::warn("Skyrim shadows on blocks: shadow maps aren't a texture array (view {}); left out", static_cast<int>(sd.ViewDimension));
				}
				return nullptr;
			}
			ID3D11Resource* res = nullptr;
			srv->GetResource(&res);
			D3D11_TEXTURE2D_DESC td{};
			static_cast<ID3D11Texture2D*>(res)->GetDesc(&td);
			Release(res);

			bool standard = true;
			if (cascadeSnapshotCount < count) {
				return fail("no cascade snapshot yet");
			}
			for (std::uint32_t k = 0; k < count; ++k) {
				const auto& m = cascadeSnapshot[k].m;  // the transform the copy was drawn with
				// Row-vector convention (v * M, translation in the last row) unless the matrix says otherwise.
				const bool rowVector = std::fabs(m[0][3]) + std::fabs(m[1][3]) + std::fabs(m[2][3]) < 1e-6f;
				auto       at = [&](int a_r, int a_c) { return double(rowVector ? m[a_r][a_c] : m[a_c][a_r]); };  // as v * M
				// HLSL gets mul(P, float4(rel, 1)) with P[out][in] = M[in][out], translation re-based on the camera.
				for (int out = 0; out < 4; ++out) {
					for (int in = 0; in < 3; ++in) {
						a_fc.skyShadowProj[k][out][in] = float(at(in, out));
					}
					a_fc.skyShadowProj[k][out][3] = float(at(3, out) + at(0, out) * a_cam.x + at(1, out) * a_cam.y + at(2, out) * a_cam.z);
				}
				if (k == 0) {
					// Depth grows along the sunlight (standard) or against it (reversed).
					const double g = at(0, 2) * -a_fc.sunDir[0] + at(1, 2) * -a_fc.sunDir[1] + at(2, 2) * -a_fc.sunDir[2];
					standard = g > 0.0;
					static bool logged = false;
					if (!logged) {
						logged = true;
						// Where the camera lands in cascade 0: should be inside (0-1) with a sane depth.
						const float u = a_fc.skyShadowProj[0][0][3], v = a_fc.skyShadowProj[0][1][3], z = a_fc.skyShadowProj[0][2][3], w = a_fc.skyShadowProj[0][3][3];
						logger::info("Skyrim shadows on blocks: {} cascades, slices {} {}, maps {}x{} x{} format {}, splits {:.0f} {:.0f}; matrix {}, depth {}; camera at uv ({:.3f}, {:.3f}) depth {:.4f} w {:.3f}",
							count, descs[0].shadowmapIndex, count > 1 ? descs[1].shadowmapIndex : 0, td.Width, td.Height, td.ArraySize, static_cast<int>(td.Format),
							dsl.endSplitDistances[0], dsl.endSplitDistances[1], rowVector ? "row-vector" : "column-vector", standard ? "standard" : "reversed", u / w, v / w, z / w, w);
					}
				}
			}
			a_fc.skyShadowSplits[0] = cascadeSnapshot[0].split;
			a_fc.skyShadowSplits[1] = count > 1 ? cascadeSnapshot[1].split : cascadeSnapshot[0].split;
			a_fc.skyShadowSplits[3] = float(count);
			Set4(a_fc.skyShadowParams, 0.0f, count > 1 ? 1.0f : 0.0f, 1.0f / float(std::max<UINT>(td.Width, 1)), standard ? 1.0f : -1.0f);
			return srv;
		}

		// 4x4 inverse (row-major), for turning Skyrim's depth back into positions.
		bool Invert(const float a_m[4][4], float a_out[4][4])
		{
			double m[16], inv[16];
			for (int k = 0; k < 16; ++k) {
				m[k] = a_m[k / 4][k % 4];
			}
			inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
			inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
			inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
			inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
			inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
			inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
			inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
			inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
			inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
			inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
			inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
			inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
			inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
			inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
			inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
			inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
			const double det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
			if (std::fabs(det) < 1e-30) {
				return false;
			}
			for (int k = 0; k < 16; ++k) {
				a_out[k / 4][k % 4] = float(inv[k] / det);
			}
			return true;
		}

		bool EnsureOwnDepth(UINT a_w, UINT a_h)
		{
			if (ownDsv && ownDepthW == a_w && ownDepthH == a_h) {
				return true;
			}
			Release(ownDsv);
			Release(ownDepth);
			D3D11_TEXTURE2D_DESC td{};
			td.Width = a_w;
			td.Height = a_h;
			td.MipLevels = 1;
			td.ArraySize = 1;
			td.Format = DXGI_FORMAT_D32_FLOAT;
			td.SampleDesc.Count = 1;
			td.Usage = D3D11_USAGE_DEFAULT;
			td.BindFlags = D3D11_BIND_DEPTH_STENCIL;
			if (FAILED(device->CreateTexture2D(&td, nullptr, &ownDepth)) || FAILED(device->CreateDepthStencilView(ownDepth, nullptr, &ownDsv))) {
				Release(ownDepth);
				return false;
			}
			ownDepthW = a_w;
			ownDepthH = a_h;
			return true;
		}

		// Everything Draw touches, saved and put back so Skyrim's renderer never notices.
		struct StateBackup
		{
			ID3D11RenderTargetView*   rtv[D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT]{};
			ID3D11DepthStencilView*   dsv{};
			ID3D11BlendState*         blend{};
			float                     factor[4]{};
			UINT                      mask{};
			ID3D11RasterizerState*    raster{};
			ID3D11DepthStencilState*  depth{};
			UINT                      stencil{};
			D3D11_VIEWPORT            vps[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE]{};
			UINT                      vpCount{ D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE };
			D3D11_PRIMITIVE_TOPOLOGY  topo{};
			ID3D11InputLayout*        layout{};
			ID3D11Buffer*             vb{};
			UINT                      stride{}, offset{};
			ID3D11VertexShader*       vs{};
			ID3D11PixelShader*        ps{};
			ID3D11GeometryShader*     gs{};
			ID3D11HullShader*         hs{};
			ID3D11DomainShader*       ds{};
			ID3D11Buffer*             vsCb[2]{};
			ID3D11Buffer*             psCb[2]{};
			ID3D11ShaderResourceView* srv[4]{};
			ID3D11SamplerState*       samplers[3]{};
			// Binding a depth buffer or render target unbinds it wherever it's bound for reading;
			// put every stage's resources back so Skyrim's renderer finds what it left.
			static constexpr UINT     kSlots = D3D11_COMMONSHADER_INPUT_RESOURCE_SLOT_COUNT;
			ID3D11ShaderResourceView* vsAll[kSlots]{};
			ID3D11ShaderResourceView* psAll[kSlots]{};
			ID3D11ShaderResourceView* csAll[kSlots]{};
			ID3D11ShaderResourceView* gsAll[kSlots]{};
			ID3D11ShaderResourceView* hsAll[kSlots]{};
			ID3D11ShaderResourceView* dsAll[kSlots]{};

			void Save(ID3D11DeviceContext* a_c)
			{
				a_c->OMGetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, rtv, &dsv);
				a_c->OMGetBlendState(&blend, factor, &mask);
				a_c->RSGetState(&raster);
				a_c->OMGetDepthStencilState(&depth, &stencil);
				a_c->RSGetViewports(&vpCount, vps);
				a_c->IAGetPrimitiveTopology(&topo);
				a_c->IAGetInputLayout(&layout);
				a_c->IAGetVertexBuffers(0, 1, &vb, &stride, &offset);
				a_c->VSGetShader(&vs, nullptr, nullptr);
				a_c->PSGetShader(&ps, nullptr, nullptr);
				a_c->GSGetShader(&gs, nullptr, nullptr);
				a_c->HSGetShader(&hs, nullptr, nullptr);
				a_c->DSGetShader(&ds, nullptr, nullptr);
				a_c->VSGetConstantBuffers(0, 2, vsCb);
				a_c->PSGetConstantBuffers(0, 2, psCb);
				a_c->PSGetShaderResources(0, 4, srv);
				a_c->PSGetSamplers(0, 3, samplers);
				a_c->VSGetShaderResources(0, kSlots, vsAll);
				a_c->PSGetShaderResources(0, kSlots, psAll);
				a_c->CSGetShaderResources(0, kSlots, csAll);
				a_c->GSGetShaderResources(0, kSlots, gsAll);
				a_c->HSGetShaderResources(0, kSlots, hsAll);
				a_c->DSGetShaderResources(0, kSlots, dsAll);
			}

			void Restore(ID3D11DeviceContext* a_c)
			{
				a_c->OMSetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, rtv, dsv);
				a_c->OMSetBlendState(blend, factor, mask);
				a_c->RSSetState(raster);
				a_c->OMSetDepthStencilState(depth, stencil);
				a_c->RSSetViewports(vpCount, vps);
				a_c->IASetPrimitiveTopology(topo);
				a_c->IASetInputLayout(layout);
				a_c->IASetVertexBuffers(0, 1, &vb, &stride, &offset);
				a_c->VSSetShader(vs, nullptr, 0);
				a_c->PSSetShader(ps, nullptr, 0);
				a_c->GSSetShader(gs, nullptr, 0);
				a_c->HSSetShader(hs, nullptr, 0);
				a_c->DSSetShader(ds, nullptr, 0);
				a_c->VSSetConstantBuffers(0, 2, vsCb);
				a_c->PSSetConstantBuffers(0, 2, psCb);
				a_c->PSSetShaderResources(0, 4, srv);
				a_c->PSSetSamplers(0, 3, samplers);
				a_c->VSSetShaderResources(0, kSlots, vsAll);
				a_c->PSSetShaderResources(0, kSlots, psAll);
				a_c->CSSetShaderResources(0, kSlots, csAll);
				a_c->GSSetShaderResources(0, kSlots, gsAll);
				a_c->HSSetShaderResources(0, kSlots, hsAll);
				a_c->DSSetShaderResources(0, kSlots, dsAll);
				for (auto* list : { vsAll, psAll, csAll, gsAll, hsAll, dsAll }) {
					for (UINT k = 0; k < kSlots; ++k) {
						if (list[k]) {
							list[k]->Release();
						}
					}
				}
				for (auto*& r : rtv) {
					Release(r);
				}
				Release(dsv);
				Release(blend);
				Release(raster);
				Release(depth);
				Release(layout);
				Release(vb);
				Release(vs);
				Release(ps);
				Release(gs);
				Release(hs);
				Release(ds);
				for (auto*& b : vsCb) {
					Release(b);
				}
				for (auto*& b : psCb) {
					Release(b);
				}
				for (auto*& s : srv) {
					Release(s);
				}
				for (auto*& s : samplers) {
					Release(s);
				}
			}
		};

		// ---- debug frame capture -------------------------------------------------------------
		// Creating <SKSE log dir>/skycraft_capture.request saves the next presented frame as
		// skycraft_capture.png next to it (used to check the renderer without watching the game).
		std::uint32_t Crc(const std::uint8_t* a_p, std::size_t a_n, std::uint32_t a_crc = 0)
		{
			static const auto table = [] {
				std::array<std::uint32_t, 256> t{};
				for (std::uint32_t i = 0; i < 256; ++i) {
					std::uint32_t c = i;
					for (int k = 0; k < 8; ++k) {
						c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
					}
					t[i] = c;
				}
				return t;
			}();
			a_crc = ~a_crc;
			for (std::size_t i = 0; i < a_n; ++i) {
				a_crc = table[(a_crc ^ a_p[i]) & 0xFF] ^ (a_crc >> 8);
			}
			return ~a_crc;
		}

		void WritePng(const std::filesystem::path& a_path, int a_w, int a_h, const std::vector<std::uint8_t>& a_rgb)
		{
			std::vector<std::uint8_t> raw;
			raw.reserve(std::size_t(a_h) * (a_w * 3 + 1));
			for (int y = 0; y < a_h; ++y) {
				raw.push_back(0);
				raw.insert(raw.end(), a_rgb.begin() + std::size_t(y) * a_w * 3, a_rgb.begin() + std::size_t(y + 1) * a_w * 3);
			}
			std::vector<std::uint8_t> z{ 0x78, 0x01 };  // zlib, stored blocks
			std::uint32_t             a = 1, b = 0;
			for (std::size_t pos = 0; pos < raw.size();) {
				const std::size_t n = std::min<std::size_t>(65535, raw.size() - pos);
				z.push_back(pos + n == raw.size() ? 1 : 0);
				z.push_back(std::uint8_t(n)), z.push_back(std::uint8_t(n >> 8));
				z.push_back(std::uint8_t(~n)), z.push_back(std::uint8_t(~n >> 8));
				z.insert(z.end(), raw.begin() + pos, raw.begin() + pos + n);
				for (std::size_t i = 0; i < n; ++i) {
					a = (a + raw[pos + i]) % 65521;
					b = (b + a) % 65521;
				}
				pos += n;
			}
			const std::uint32_t adler = (b << 16) | a;
			for (int s = 24; s >= 0; s -= 8) {
				z.push_back(std::uint8_t(adler >> s));
			}
			std::ofstream out(a_path, std::ios::binary);
			auto          be32 = [&](std::uint32_t a_v) {
                const std::uint8_t bytes[4] = { std::uint8_t(a_v >> 24), std::uint8_t(a_v >> 16), std::uint8_t(a_v >> 8), std::uint8_t(a_v) };
                out.write(reinterpret_cast<const char*>(bytes), 4);
			};
			auto chunk = [&](const char* a_type, const std::vector<std::uint8_t>& a_data) {
				be32(static_cast<std::uint32_t>(a_data.size()));
				std::vector<std::uint8_t> crcData(a_type, a_type + 4);
				crcData.insert(crcData.end(), a_data.begin(), a_data.end());
				out.write(reinterpret_cast<const char*>(crcData.data()), crcData.size());
				be32(Crc(crcData.data(), crcData.size()));
			};
			const std::uint8_t sig[8] = { 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
			out.write(reinterpret_cast<const char*>(sig), 8);
			std::vector<std::uint8_t> ihdr = { std::uint8_t(a_w >> 24), std::uint8_t(a_w >> 16), std::uint8_t(a_w >> 8), std::uint8_t(a_w),
				std::uint8_t(a_h >> 24), std::uint8_t(a_h >> 16), std::uint8_t(a_h >> 8), std::uint8_t(a_h), 8, 2, 0, 0, 0 };
			chunk("IHDR", ihdr);
			chunk("IDAT", z);
			chunk("IEND", {});
		}

		float captureCheckTimer = 1.0f;

		// Read-only, opt-in snapshots to diagnose water without modifying its targets.
		// Request: skycraft_water_capture.request beside the SKSE log. Raw resources
		// stay in that local log directory and must never be included in releases.
		bool waterCaptureActive = false;
		std::filesystem::path waterCaptureDir;
		std::vector<std::uint32_t> waterCapturedPasses;
		// Inspect after the actual water draw, before RestoreGeometry removes its bindings.
		// SetupGeometry alone is insufficient: Skyrim can defer binding until the draw.
		void CaptureWaterBindings(RE::BSRenderPass* a_pass)
		{
			if (!waterCaptureActive || !a_pass || waterCapturedPasses.size() >= 8 ||
				std::find(waterCapturedPasses.begin(), waterCapturedPasses.end(), a_pass->passEnum) != waterCapturedPasses.end()) return;
			auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
			if (!renderer || !device) return;
			waterCapturedPasses.push_back(a_pass->passEnum);
			auto& rd = renderer->GetRuntimeData();
			auto* context = NativeDrawContext();
			const auto stem = std::format("water-pass-{}", a_pass->passEnum);
			std::ofstream out;
			if (waterCaptureActive) out.open(waterCaptureDir / (stem + "-bindings.txt"));
			out << "Water RestoreGeometry; actual post-draw bindings; technique " << a_pass->passEnum << '\n';
			ID3D11ShaderResourceView* inputs[16]{};
			context->PSGetShaderResources(0, 16, inputs);
			for (UINT slot = 0; slot < 16; ++slot) {
				if (!inputs[slot]) continue;
				ID3D11Resource* resource = nullptr;
				inputs[slot]->GetResource(&resource);
				D3D11_SHADER_RESOURCE_VIEW_DESC view{};
				inputs[slot]->GetDesc(&view);
				if (waterCaptureActive) out << "t" << slot << " srvFormat=" << int(view.Format) << " dimension=" << int(view.ViewDimension);
				for (int k = 0; k < RE::RENDER_TARGETS::kTOTAL; ++k)
					if (resource == reinterpret_cast<ID3D11Resource*>(rd.renderTargets[k].texture)) out << " colourTarget=" << k;
				const auto& depths = renderer->GetDepthStencilData().depthStencils;
				for (int k = 0; k < RE::RENDER_TARGETS_DEPTHSTENCIL::kTOTAL; ++k)
					if (resource == reinterpret_cast<ID3D11Resource*>(depths[k].texture) && waterCaptureActive) out << " depthTarget=" << k;
				ID3D11Texture2D* texture = nullptr;
				if (SUCCEEDED(resource->QueryInterface(__uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&texture)))) {
					D3D11_TEXTURE2D_DESC desc{};
					texture->GetDesc(&desc);
					if (waterCaptureActive) out << " textureFormat=" << int(desc.Format) << " size=" << desc.Width << 'x' << desc.Height;
					// Refraction/depth candidates only; never extract normal maps or other assets.
					if ((slot == 1 || slot == 7) && desc.SampleDesc.Count == 1 && desc.ArraySize == 1 && desc.MipLevels == 1) {
						desc.Usage = D3D11_USAGE_STAGING;
						desc.BindFlags = 0;
						desc.MiscFlags = 0;
						desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
						ID3D11Texture2D* staging = nullptr;
						if (SUCCEEDED(device->CreateTexture2D(&desc, nullptr, &staging))) {
							context->CopyResource(staging, texture);
							D3D11_MAPPED_SUBRESOURCE mapped{};
							if (SUCCEEDED(context->Map(staging, 0, D3D11_MAP_READ, 0, &mapped))) {
								const auto name = std::format("{}-t{}", stem, slot);
								std::ofstream raw(waterCaptureDir / (name + ".bin"), std::ios::binary);
								raw.write(static_cast<const char*>(mapped.pData), std::streamsize(mapped.RowPitch) * desc.Height);
								std::ofstream meta(waterCaptureDir / (name + ".json"));
								meta << std::format("{{\"width\":{},\"height\":{},\"format\":{},\"rowPitch\":{}}}", desc.Width, desc.Height, int(desc.Format), mapped.RowPitch);
								context->Unmap(staging, 0);
							}
							Release(staging);
						}
					}
					Release(texture);
				}
				if (waterCaptureActive) out << '\n';
				Release(resource);
				Release(inputs[slot]);
			}
			if (waterCaptureActive) out << "Known targets: MAIN=" << int(RE::RENDER_TARGETS::kMAIN)
				<< " MAIN_COPY=" << int(RE::RENDER_TARGETS::kMAIN_COPY)
				<< " WATER_1=" << int(RE::RENDER_TARGETS::kWATER_1)
				<< " WATER_2=" << int(RE::RENDER_TARGETS::kWATER_2)
				<< " opaqueDepth=" << int(RE::RENDER_TARGETS_DEPTHSTENCIL::kPOST_ZPREPASS_COPY) << '\n';
		}

		thread_local bool waterGeometryActive = false;
		thread_local bool mainWaterStage = false;
		thread_local bool injectingRefraction = false;
		RE::BSShader* waterShaderInstance = nullptr;
		struct WaterTechniqueHook
		{
			static bool thunk(RE::BSShader* a_shader, std::uint32_t a_technique)
			{
				waterShaderInstance = a_shader;
				return func(a_shader, a_technique);
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};
		std::uint64_t refractionFrame = 0;
		void EndNativeWaterRefraction();
		struct WaterSetupGeometryHook
		{
			static void thunk(RE::BSShader* a_shader, RE::BSRenderPass* a_pass, std::uint32_t a_flags)
			{
				waterShaderInstance = a_shader;
				func(a_shader, a_pass, a_flags);
				waterGeometryActive = true;
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};
		struct WaterRestoreGeometryHook
		{
			static void thunk(RE::BSShader* a_shader, RE::BSRenderPass* a_pass, std::uint32_t a_flags)
			{
				waterShaderInstance = a_shader;
				CaptureWaterBindings(a_pass);
				EndNativeWaterRefraction();
				waterGeometryActive = false;
				func(a_shader, a_pass, a_flags);
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};
		void CaptureWaterDepths(ID3D11DeviceContext* a_context, const char* a_stage)
		{
			auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
			if (!renderer || !device || !waterCaptureActive) return;
			const auto& depths = renderer->GetDepthStencilData().depthStencils;
			static constexpr auto targets = std::array{
				RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN,
				RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN_COPY,
				RE::RENDER_TARGETS_DEPTHSTENCIL::kPOST_ZPREPASS_COPY,
				RE::RENDER_TARGETS_DEPTHSTENCIL::kPOST_WATER_COPY
			};
			static constexpr const char* names[] = { "main", "main-copy", "opaque", "post-water" };
			StateBackup backup;
			backup.Save(a_context);
			a_context->OMSetRenderTargets(0, nullptr, nullptr);
			auto capture = [&](ID3D11Texture2D* source, const std::string& name) {
				if (!source) return;
				D3D11_TEXTURE2D_DESC desc{};
				source->GetDesc(&desc);
				if (desc.SampleDesc.Count != 1 || desc.ArraySize != 1 || desc.MipLevels != 1) return;
				desc.BindFlags = 0;
				desc.MiscFlags = 0;
				desc.Usage = D3D11_USAGE_STAGING;
				desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
				ID3D11Texture2D* staging = nullptr;
				if (FAILED(device->CreateTexture2D(&desc, nullptr, &staging))) return;
				a_context->CopyResource(staging, source);
				D3D11_MAPPED_SUBRESOURCE mapped{};
				if (SUCCEEDED(a_context->Map(staging, 0, D3D11_MAP_READ, 0, &mapped))) {
					const auto stem = std::string(a_stage) + "-" + name;
					std::ofstream raw(waterCaptureDir / (stem + ".bin"), std::ios::binary);
					raw.write(static_cast<const char*>(mapped.pData), std::streamsize(mapped.RowPitch) * desc.Height);
					std::ofstream meta(waterCaptureDir / (stem + ".json"));
					meta << std::format("{{\"width\":{},\"height\":{},\"format\":{},\"rowPitch\":{}}}",
						desc.Width, desc.Height, static_cast<int>(desc.Format), mapped.RowPitch);
					a_context->Unmap(staging, 0);
				}
				Release(staging);
			};
			for (std::size_t k = 0; k < targets.size(); ++k) {
				capture(reinterpret_cast<ID3D11Texture2D*>(depths[targets[k]].texture), names[k]);
			}
			// Colour and refraction inputs must be observed in the same frame as depth.
			// A depth-only snapshot cannot tell whether water reads MAIN or an older copy.
			const auto& colours = renderer->GetRuntimeData().renderTargets;
			static constexpr auto colourTargets = std::array{
				RE::RENDER_TARGETS::kMAIN, RE::RENDER_TARGETS::kMAIN_COPY,
				RE::RENDER_TARGETS::kWATER_1, RE::RENDER_TARGETS::kWATER_2
			};
			static constexpr const char* colourNames[] = { "colour-main", "colour-main-copy", "colour-water-1", "colour-water-2" };
			for (std::size_t k = 0; k < colourTargets.size(); ++k) {
				capture(reinterpret_cast<ID3D11Texture2D*>(colours[colourTargets[k]].texture), colourNames[k]);
			}
			backup.Restore(a_context);
		}

		// Where the Minecraft world is drawn this frame.
		struct Target
		{
			ID3D11RenderTargetView*   rtv{ nullptr };
			ID3D11RenderTargetView*   motion{ nullptr };      // Skyrim's motion vectors (in frame), or none
			ID3D11RenderTargetView*   normals{ nullptr };     // Skyrim's normals + TAA/reflection masks (in frame), or none
			ID3D11DepthStencilView*   dsv{ nullptr };         // Skyrim's depth (in frame) or our own
			ID3D11ShaderResourceView* sceneDepth{ nullptr };  // Skyrim's depth to read (a copy when dsv is Skyrim's)
			UINT                      width{ 0 }, height{ 0 };
			bool                      hdr{ false };           // Skyrim's scene before its post-processing
			bool                      skyrimDepth{ false };   // dsv is Skyrim's: its geometry occludes by depth test
			bool                      foreground{ false };
			bool                      refraction{ false }; // reuse frame; write native private refraction inputs
			bool                      depthOnly{ false };   // private depth for native local particles
			ID3D11ShaderResourceView* surfaceDepth{ nullptr };
			// Skyrim's copies of its depth, which its later passes read (fog, volumetric light,
			// depth of field, ...): the blocks are depth-tested into each so they see them too.
			std::array<ID3D11DepthStencilView*, 3> depthCopies{};
			std::array<ID3D11DepthStencilView*, 3> avatarDepthCopies{};
		};

		bool   inFrameDrawn = false;   // drawn inside Skyrim's frame; Present leaves it alone
		bool   inFrameEnabled = true;  // Skyrim's scene target checked out (see DrawInFrame)
		double prevW2C[4][4]{};        // last frame's camera, for motion vectors
		bool   havePrevW2C = false;

		ID3D11Texture2D*          depthCopyTex = nullptr;  // Skyrim's depth, readable while its depth buffer is bound
		ID3D11ShaderResourceView* depthCopySrv = nullptr;
		ID3D11Texture2D* surfaceCopyTex = nullptr;
		ID3D11ShaderResourceView* surfaceCopySrv = nullptr;

		bool EnsureDepthCopy(ID3D11Texture2D* a_depth, ID3D11ShaderResourceView* a_skyrimSrv, ID3D11Texture2D*& copyTex = depthCopyTex, ID3D11ShaderResourceView*& copySrv = depthCopySrv)
		{
			D3D11_TEXTURE2D_DESC dd{};
			a_depth->GetDesc(&dd);
			if (copyTex) {
				D3D11_TEXTURE2D_DESC cd{};
				copyTex->GetDesc(&cd);
				if (cd.Width == dd.Width && cd.Height == dd.Height && cd.Format == dd.Format) {
					return true;
				}
				Release(copySrv);
				Release(copyTex);
			}
			if (!a_skyrimSrv) {
				return false;
			}
			D3D11_SHADER_RESOURCE_VIEW_DESC sv{};
			a_skyrimSrv->GetDesc(&sv);
			dd.BindFlags = D3D11_BIND_SHADER_RESOURCE;
			dd.Usage = D3D11_USAGE_DEFAULT;
			dd.CPUAccessFlags = 0;
			dd.MiscFlags = 0;
			if (FAILED(device->CreateTexture2D(&dd, nullptr, &copyTex)) || FAILED(device->CreateShaderResourceView(copyTex, &sv, &copySrv))) {
				Release(copyTex);
				return false;
			}
			return true;
		}

		// Diagnostics: what SkyCraft's passes cost inside Skyrim's frame, on the GPU (timestamp queries,
		// read back a few frames later so nothing stalls) and the CPU; logged every 10 s.
		struct PassTimer
		{
			static constexpr int kSlots = 6;  // frames in flight
			static constexpr int kMarks = 6;  // start, sun shadows, surface shading, blocks, water/glass, depth copies
			ID3D11Query*         disjoint[kSlots]{};
			ID3D11Query*         marks[kSlots][kMarks]{};
			bool                 issued[kSlots]{};
			int                  slot = 0;
			bool                 failed = false;
			double               gpuMs[kMarks]{};
			int                  gpuFrames = 0;
			double               cpuMs = 0.0, cpuMax = 0.0;
			int                  cpuFrames = 0;
			std::int64_t         cpuStart = 0, nextLog = 0;

			void Begin(ID3D11DeviceContext* a_context)
			{
				if (!DiagnosticsEnabled()) {
					return;
				}
				LARGE_INTEGER now;
				::QueryPerformanceCounter(&now);
				cpuStart = now.QuadPart;
				if (failed || !device) {
					return;
				}
				if (!disjoint[0]) {
					D3D11_QUERY_DESC dq{ D3D11_QUERY_TIMESTAMP_DISJOINT, 0 }, tq{ D3D11_QUERY_TIMESTAMP, 0 };
					for (int s = 0; s < kSlots && !failed; ++s) {
						failed |= FAILED(device->CreateQuery(&dq, &disjoint[s]));
						for (int m = 0; m < kMarks && !failed; ++m) {
							failed |= FAILED(device->CreateQuery(&tq, &marks[s][m]));
						}
					}
					if (failed) {
						logger::warn("pass timing: couldn't create GPU queries");
						return;
					}
				}
				// This slot's last use (kSlots frames ago): add it up if the GPU has finished it.
				if (issued[slot]) {
					issued[slot] = false;
					D3D11_QUERY_DATA_TIMESTAMP_DISJOINT dj{};
					UINT64                              t[kMarks]{};
					bool                                ready = a_context->GetData(disjoint[slot], &dj, sizeof(dj), D3D11_ASYNC_GETDATA_DONOTFLUSH) == S_OK && !dj.Disjoint;
					for (int m = 0; m < kMarks && ready; ++m) {
						ready = a_context->GetData(marks[slot][m], &t[m], sizeof(t[m]), D3D11_ASYNC_GETDATA_DONOTFLUSH) == S_OK;
					}
					if (ready && dj.Frequency) {
						for (int m = 1; m < kMarks; ++m) {
							gpuMs[m] += double(t[m] - t[m - 1]) * 1000.0 / double(dj.Frequency);
						}
						++gpuFrames;
					}
				}
				a_context->Begin(disjoint[slot]);
				a_context->End(marks[slot][0]);
			}

			void Mark(ID3D11DeviceContext* a_context, int a_mark)
			{
				if (DiagnosticsEnabled() && !failed && disjoint[0]) {
					a_context->End(marks[slot][a_mark]);
				}
			}

			void Finish(ID3D11DeviceContext* a_context)
			{
				if (!DiagnosticsEnabled()) {
					return;
				}
				Mark(a_context, kMarks - 1);
				if (!failed && disjoint[0]) {
					a_context->End(disjoint[slot]);
					issued[slot] = true;
					slot = (slot + 1) % kSlots;
				}
				LARGE_INTEGER now, freq;
				::QueryPerformanceCounter(&now);
				::QueryPerformanceFrequency(&freq);
				const double ms = double(now.QuadPart - cpuStart) * 1000.0 / double(freq.QuadPart);
				cpuMs += ms;
				cpuMax = std::max(cpuMax, ms);
				++cpuFrames;
				if (now.QuadPart < nextLog) {
					return;
				}
				nextLog = now.QuadPart + freq.QuadPart * 10;
				if (cpuFrames > 0) {
					const double g = gpuFrames > 0 ? 1.0 / gpuFrames : 0.0;
					double       total = 0.0;
					for (int m = 1; m < kMarks; ++m) {
						total += gpuMs[m] * g;
					}
					logger::info("SkyCraft's drawing per frame: GPU {:.2f} ms (sun shadows {:.2f}, Skyrim surfaces: block + contact shadows {:.2f}, blocks & entities {:.2f}, "
								 "water/glass {:.2f}, depth copies {:.2f}); CPU {:.2f} ms avg, {:.2f} max; contact shadows {}, volume rebuilt {} times; {} frames",
						total, gpuMs[1] * g, gpuMs[2] * g, gpuMs[3] * g, gpuMs[4] * g, gpuMs[5] * g, cpuMs / cpuFrames, cpuMax, ContactShadowsWanted() ? "on" : "off",
						aoRebuilds, cpuFrames);
				}
				std::fill(std::begin(gpuMs), std::end(gpuMs), 0.0);
				gpuFrames = cpuFrames = 0;
				cpuMs = cpuMax = 0.0;
				aoRebuilds = 0;
			}
		} passTimer;

		FrameConstants waterFrame{};
		render::FrameOrigin frameOrigin;
		float frameWorldToCam[4][4]{};
		UINT frameEntityVerts = 0, frameCrackVerts = 0, frameOutlineVerts = 0;
		bool frameHaveDyn = false;
		double frameDynamicOrigin[3]{};
        bool localEffectGeometryActive = false;
        RE::BSShader* localEffectShaders[2]{};
        void EndNativeLocalEffect();
        bool BeginNativeLocalEffect(ID3D11DeviceContext* context);

        template<int Kind> struct LocalEffectGeometryHook {
            static void thunk(RE::BSShader* shader, RE::BSRenderPass* pass, std::uint32_t flags) {
                EndNativeLocalEffect();
                localEffectGeometryActive = false;
                localEffectShaders[Kind] = shader;
                func(shader, pass, flags);
                if (!inFrameDrawn || !pass || !pass->geometry) return;
                const auto& bound = pass->geometry->worldBound;
                // Exclude distant sky/fog sheets and uninitialized bounds. Retain
                // actual local effect geometry from this frame only.
                if constexpr (Kind == 0) {
                    if (!std::isfinite(bound.radius) || bound.radius <= 0 || bound.radius > 8192) return;
                }
                localEffectGeometryActive = true;
                // SetupGeometry populates deferred state. Redirect only after
                // the actual draw-state flush, when this material's textures
                // and constants are bound; an earlier redirect uses old inputs.
            }
            static inline REL::Relocation<decltype(thunk)> func;
        };
        template<int Kind> struct LocalEffectRestoreHook {
            static void thunk(RE::BSShader* shader, RE::BSRenderPass* pass, std::uint32_t flags) {
                EndNativeLocalEffect();
                localEffectGeometryActive = false;
                func(shader, pass, flags);
            }
            static inline REL::Relocation<decltype(thunk)> func;
        };
		void RenderPasses(ID3D11DeviceContext* a_context, const Target& a_t, RE::NiCamera* a_camera, ID3D11ShaderResourceView* a_maskSrv)
		{
			passTimer.Begin(a_context);
			// Skyrim may leave water geometry/tessellation stages bound at this hook.
			// Our triangle shaders supply the complete pipeline; restore these in StateBackup.
			a_context->GSSetShader(nullptr, nullptr, 0);
			a_context->HSSetShader(nullptr, nullptr, 0);
			a_context->DSSetShader(nullptr, nullptr, 0);
			// Camera: Skyrim's own world->clip matrix, re-based on the camera position so the GPU
			// only ever sees small camera-relative coordinates.
			const bool firstPass = !a_t.foreground && !a_t.refraction;
			const auto liveCam = a_camera->world.translate;
			if (firstPass) {
				std::memcpy(frameWorldToCam, a_camera->GetRuntimeData().worldToCam, sizeof(frameWorldToCam));
				frameOrigin.Begin(frameWorldToCam, {liveCam.x, liveCam.y, liveCam.z});
			}
			const auto& w2c = frameWorldToCam;
			const auto& frustum = a_camera->GetRuntimeData2().viewFrustum;
			const auto cameraOrigin = frameOrigin.Get({liveCam.x, liveCam.y, liveCam.z});
			const RE::NiPoint3 cam{float(cameraOrigin[0]), float(cameraOrigin[1]), float(cameraOrigin[2])};
			FrameConstants fc{};
			bool contact = false, canUnproject = false, sunShadows = false;
			ID3D11ShaderResourceView* skyrimShadowSrv = nullptr;
			if (!a_t.foreground && !a_t.refraction) {
			const auto currentPoseEpoch = State().avatarPoseEpoch.load();
			if (currentPoseEpoch != avatarPoseEpoch) {
				avatarPoseEpoch = currentPoseEpoch;
				avatarHistory = false;
			}
			double feet[3]{};
			if (!avatar.batches.empty() && Game::RenderedFeet(cam, feet)) {
				const double dx = feet[0] - avatarFeet[0], dy = feet[1] - avatarFeet[1], dz = feet[2] - avatarFeet[2];
				std::memcpy(previousAvatarFeet, avatarHistory ? avatarFeet : feet, sizeof(feet));
				std::memcpy(avatarFeet, feet, sizeof(feet));
				avatarHistory = true;
				// Retain a few numeric flight samples automatically if another renderer
				// still interferes. No screenshots, textures or personal data are recorded.
				static auto nextFlightLog = std::chrono::steady_clock::time_point{};
				static unsigned flightLogs = 0;
				const auto now = std::chrono::steady_clock::now();
				if (dx*dx + dy*dy + dz*dz > 0.5625 && flightLogs < 12 && now >= nextFlightLog) {
					nextFlightLog = now + std::chrono::seconds(1);
					++flightLogs;
					logger::info("flight80: frame travel {:.3f} blocks, matrix/node camera delta {:.3f} units; one origin shared by colour, water, smoke depth and motion", std::sqrt(dx*dx + dy*dy + dz*dz), cam.GetDistance(liveCam));
				}
			} else {
				avatarHistory = false;
			}
			for (int r = 0; r < 4; ++r) {
				for (int c = 0; c < 3; ++c) {
					fc.viewProj[r][c] = w2c[r][c];
				}
				fc.viewProj[r][3] = float(double(w2c[r][3]) + double(w2c[r][0]) * cam.x + double(w2c[r][1]) * cam.y + double(w2c[r][2]) * cam.z);
				for (int c = 0; c < 3; ++c) {
					fc.prevViewProj[r][c] = float(havePrevW2C ? prevW2C[r][c] : w2c[r][c]);
				}
				const double* pr = havePrevW2C ? prevW2C[r] : nullptr;
				fc.prevViewProj[r][3] = pr ? float(pr[3] + pr[0] * cam.x + pr[1] * cam.y + pr[2] * cam.z) : fc.viewProj[r][3];
			}
			for (int r = 0; r < 4; ++r) {
				for (int c = 0; c < 4; ++c) {
					prevW2C[r][c] = w2c[r][c];
				}
			}
			havePrevW2C = true;
			// NiCamera's CPU matrix is unjittered. Skyrim's depth and HDR colour
			// use BSGraphics' per-frame projection shift. Match it in both our
			// colour and depth passes; keep motion vectors free of that shift.
			if (a_t.hdr) {
				if (auto* graphics = RE::BSGraphics::State::GetSingleton()) {
					const float jx = graphics->projectionPosScaleX, jy = graphics->projectionPosScaleY;
					if (std::isfinite(jx) && std::isfinite(jy) && std::fabs(jx) < 0.02f && std::fabs(jy) < 0.02f) {
						fc.projectionJitter[0] = jx;
						fc.projectionJitter[1] = jy;
						for (int c = 0; c < 4; ++c) {
							fc.viewProj[0][c] += jx * fc.viewProj[3][c];
							fc.viewProj[1][c] += jy * fc.viewProj[3][c];
						}
					}
				}
			}
			fc.depthParams[0] = frustum.fNear;
			fc.depthParams[1] = frustum.fFar;
			// Depth convention comes from this camera's projection, never the
			// distribution of visible terrain/sky in a sparse readback.
			RE::NiPoint3 cameraForward{w2c[3][0], w2c[3][1], w2c[3][2]};
			const float forwardLength = cameraForward.Length();
			if (forwardLength > 1e-6f) cameraForward /= forwardLength;
			auto projectedDepth = [&](float distance) {
				const auto p = cameraForward * distance;
				const auto& m = fc.viewProj;
				return (m[2][0]*p.x + m[2][1]*p.y + m[2][2]*p.z + m[2][3]) /
				       (m[3][0]*p.x + m[3][1]*p.y + m[3][2]*p.z + m[3][3]);
			};
			depthReversed = projectedDepth(100.0f) > projectedDepth(10000.0f);
			depthKnown = true;
			fc.depthParams[2] = depthReversed ? 1.0f : 0.0f;
			// A clear sky depth is valid. A sparse 16-pixel probe becoming flat
			// must not switch occlusion conventions during high-altitude flight.
			fc.depthParams[3] = (a_t.sceneDepth && depthKnown) ? 1.0f : 0.0f;
			fc.screen[0] = 1.0f / float(a_t.width);
			fc.screen[1] = 1.0f / float(a_t.height);
			fc.renderMode[0] = a_t.hdr ? 1.0f : 0.0f;
			fc.renderMode[1] = a_t.skyrimDepth ? 1.0f : 0.0f;
			const auto& R = a_camera->world.rotate;  // columns: forward, up, right
			Set4(fc.camForward, cameraForward.x, cameraForward.y, cameraForward.z, 0.0f);
			Set4(fc.camUp, R.entry[0][1], R.entry[1][1], R.entry[2][1], 0.0f);
			Set4(fc.camRight, R.entry[0][2], R.entry[1][2], R.entry[2][2], 0.0f);
			GatherLighting(fc, cam);
			GatherContactShadows(fc, cam);
			contact = UpdateContactVolume(a_context, SkyToMc(cam), fc);
			canUnproject = fc.depthParams[3] > 0.5f && Invert(fc.viewProj, fc.invViewProj);  // Skyrim's pixels -> positions
			sunShadows = fc.shadowParams[0] > 0.5f && canUnproject && EnsureShadowMap();
			if (sunShadows) {
				SetSunShadowMatrix(fc, cam);
			} else {
				fc.shadowParams[0] = 0.0f;
			}
			fc.shadowExtra[0] = (a_maskSrv && shadowMaskUsable && fc.depthParams[3] > 0.5f && sunShadows) ? 1.0f : 0.0f;
			skyrimShadowSrv = sunShadows ? SetSkyrimShadows(fc, cam) : nullptr;
			waterFrame = fc;
			} else {
				// Reuse the exact camera history, lighting and shadows from the first
				// pass. Advancing them twice per frame corrupts motion and interpolation.
				fc = waterFrame;
				contact = fc.aoVolume[3] > 0.5f;
				sunShadows = fc.shadowParams[0] > 0.5f;
				skyrimShadowSrv = sunShadows ? SetSkyrimShadows(fc, cam) : nullptr;
			}
            fc.renderMode[2] = a_t.surfaceDepth ? (a_t.refraction ? 2.0f : a_t.foreground ? 1.0f : 0.0f) : 0.0f;
			fc.screen[2] = a_t.depthOnly ? 1.0f : 0.0f;
			fc.screen[3] = State().nightVision.load();
			if (!a_t.foreground && !a_t.refraction) {
			if (auto* player = RE::PlayerCharacter::GetSingleton()) {
				if (auto* cell = player->GetParentCell()) {
					float waterHeight = 0.0f;
					auto* tes = RE::TES::GetSingleton();
					auto* cameraCell = cell->IsInteriorCell() || !tes ? cell : tes->GetCell(cam);
					const bool hasWater = cameraCell && cameraCell->GetWaterHeight(cam, waterHeight)
                        && std::isfinite(waterHeight) && waterHeight > -1.0e6f;
					fc.renderMode[3] = hasWater && cam.z < waterHeight ? 1.0f : 0.0f;
					fc.waterPlane[0] = hasWater ? waterHeight - cam.z : 0.0f;
					fc.waterPlane[1] = hasWater ? 1.0f : 0.0f;
				}
			}
				waterFrame.renderMode[3] = fc.renderMode[3];
				std::memcpy(waterFrame.waterPlane, fc.waterPlane, sizeof(fc.waterPlane));
			}
			if (skyProbePending && skyProbeStaging) {
				D3D11_MAPPED_SUBRESOURCE pm{};
				if (a_context->Map(skyProbeStaging, 0, D3D11_MAP_READ, D3D11_MAP_FLAG_DO_NOT_WAIT, &pm) == S_OK) {
					const auto* v = static_cast<const float*>(pm.pData);
					std::string line;
					for (int k = 0; k < 16; ++k) {
						line += std::format(" [{:.4f} {:.4f} {:.4f} c{:.0f}]", v[k * 4], v[k * 4 + 1], v[k * 4 + 2], v[k * 4 + 3]);
					}
					a_context->Unmap(skyProbeStaging, 0);
					skyProbePending = false;
					logger::info("Skyrim shadow probe (our depth, stored, stored v-flipped, cascade):{}", line);
				}
			}
			D3D11_MAPPED_SUBRESOURCE mapped{};
			if (SUCCEEDED(a_context->Map(frameCb, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
				std::memcpy(mapped.pData, &fc, sizeof(fc));
				a_context->Unmap(frameCb, 0);
			}

			// Depth convention: Skyrim's own buffer as probed, or ours following Skyrim's matrix.
			bool reversed = depthReversed;
			if (!a_t.skyrimDepth) {
				RE::NiPoint3 fwd{ fc.camForward[0], fc.camForward[1], fc.camForward[2] };
				auto         ndcZ = [&](float a_dist) {
                    const RE::NiPoint3 p = fwd * a_dist;
                    const float        z = fc.viewProj[2][0] * p.x + fc.viewProj[2][1] * p.y + fc.viewProj[2][2] * p.z + fc.viewProj[2][3];
                    const float        w = fc.viewProj[3][0] * p.x + fc.viewProj[3][1] * p.y + fc.viewProj[3][2] * p.z + fc.viewProj[3][3];
                    return std::fabs(w) > 1e-6f ? z / w : 0.0f;
				};
				reversed = ndcZ(100.0f) > ndcZ(10000.0f);
			}

			static bool checkedMatrix = false;
			if (!checkedMatrix) {
				// One-time sanity check of the matrix convention against Skyrim's own projection.
				checkedMatrix = true;
				RE::NiPoint3 fwd{ a_camera->world.rotate.entry[0][0], a_camera->world.rotate.entry[1][0], a_camera->world.rotate.entry[2][0] };
				const auto   pt = cam + fwd * 500.0f;
				float        sx = 0, sy = 0, sz = 0;
				RE::NiCamera::WorldPtToScreenPt3(w2c, a_camera->GetRuntimeData2().port, pt, sx, sy, sz, 1e-5f);
				const RE::NiPoint3 rel = pt - cam;
				float clip[4];
				for (int r = 0; r < 4; ++r) {
					clip[r] = fc.viewProj[r][0] * rel.x + fc.viewProj[r][1] * rel.y + fc.viewProj[r][2] * rel.z + fc.viewProj[r][3];
				}
				logger::info("world renderer matrix check: Skyrim screen ({:.3f}, {:.3f}, {:.4f}), ours ({:.3f}, {:.3f}, {:.4f}, w {:.1f} for 500 units); near {} far {}; {} Z",
					sx, sy, sz, (clip[0] / clip[3] + 1) * 0.5f, (clip[1] / clip[3] + 1) * 0.5f, clip[2] / clip[3], clip[3], frustum.fNear, frustum.fFar,
					reversed ? "reversed" : "standard");
			}

			// Arrows, dropped items (solid), block cracks (blended), the block outline (lines).
			const auto camMc = SkyToMc(cam);
			if (firstPass) {
				visibleSections.clear();
				for (const auto& [key, section] : sections) {
					const std::array<double,3> centre{
						(section.sx*16.0+8.0)*70.0-cam.x,
						-(section.sz*16.0+8.0)*70.0-cam.y,
						(section.sy*16.0+8.0)*70.0-cam.z};
					// Expand past the section bounds for models crossing voxel edges.
					if (render::VisibleBox(fc.viewProj, centre, 700.0)) visibleSections.push_back(&section);
				}
			dynVerts.clear();
			static std::vector<Vertex> cracks;
			cracks.clear();
			const double o[3] = { std::floor(camMc.x), std::floor(camMc.y), std::floor(camMc.z) };
			BuildEntities(o, cracks);
			const auto entityVerts = static_cast<UINT>(dynVerts.size());
			dynVerts.insert(dynVerts.end(), cracks.begin(), cracks.end());
			const auto crackVerts = static_cast<UINT>(cracks.size());
			BuildOutline(o);
			const auto outlineVerts = static_cast<UINT>(dynVerts.size()) - entityVerts - crackVerts;
			const bool haveDyn = UploadDynamic(a_context);
			frameEntityVerts = entityVerts; frameCrackVerts = crackVerts; frameOutlineVerts = outlineVerts;
			frameHaveDyn = haveDyn;
			McToSkyD(o[0], o[1], o[2], frameDynamicOrigin);
			}
			const auto entityVerts = frameEntityVerts, crackVerts = frameCrackVerts, outlineVerts = frameOutlineVerts;
			const bool haveDyn = frameHaveDyn;
			const auto& originSky = frameDynamicOrigin;
			const UINT stride = sizeof(Vertex), zero = 0;
			ID3D11Buffer* cbs[2] = { frameCb, objectCb };
			a_context->VSSetConstantBuffers(0, 2, cbs);
			a_context->PSSetConstantBuffers(0, 2, cbs);
			ID3D11SamplerState* samplers[3] = { atlasSampler, pointSampler, shadowSampler };
			a_context->PSSetSamplers(0, 3, samplers);
			a_context->IASetInputLayout(layout);
			a_context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);

			// The sun's view of the blocks (and dropped items, arrows, the player).
			if (sunShadows && !a_t.foreground && !a_t.refraction) {
				ID3D11ShaderResourceView* unbind[4] = {};
				a_context->PSSetShaderResources(0, 4, unbind);
				a_context->OMSetRenderTargets(0, nullptr, shadowDsv);
				a_context->ClearDepthStencilView(shadowDsv, D3D11_CLEAR_DEPTH, 1.0f, 0);
				D3D11_VIEWPORT svp{ 0, 0, float(kShadowSize), float(kShadowSize), 0, 1 };
				a_context->RSSetViewports(1, &svp);
				a_context->RSSetState(shadowRaster);
				a_context->OMSetBlendState(noBlend, nullptr, 0xFFFFFFFF);
				a_context->OMSetDepthStencilState(depthWrite, 0);
				a_context->VSSetShader(shadowVs, nullptr, 0);
				a_context->PSSetShader(shadowPs, nullptr, 0);
				a_context->PSSetShaderResources(0, 1, &atlasSrv);
				const double reach = (kShadowRadius + kShadowDepth) / 70.0 + 16.0;
				for (auto& [key, s] : sections) {
					const double dx = s.sx * 16.0 + 8.0 - camMc.x, dy = s.sy * 16.0 + 8.0 - camMc.y, dz = s.sz * 16.0 + 8.0 - camMc.z;
					if (!s.opaque || dx * dx + dy * dy + dz * dz > reach * reach) {
						continue;
					}
					double origin[3];
					McToSkyD(s.sx * 16.0, s.sy * 16.0, s.sz * 16.0, origin);
					SetObjectOffset(a_context, origin, cam);
					a_context->IASetVertexBuffers(0, 1, &s.vb, &stride, &zero);
					a_context->Draw(s.opaque, 0);
				}
				if (haveDyn && entityVerts) {
					SetObjectOffset(a_context, originSky, cam);
					a_context->IASetVertexBuffers(0, 1, &dynVb, &stride, &zero);
					a_context->Draw(entityVerts, 0);
				}
				DrawEntities(a_context, cam, false, true, true, true);
			}

			passTimer.Mark(a_context, 1);

			// Debug probe of Skyrim's shadow map conventions (a few times per session).
			skyProbeTimer -= 1.0f / 60.0f;
			if (!a_t.foreground && !a_t.refraction && skyrimShadowSrv && a_t.sceneDepth && skyProbeRtv && !skyProbePending && skyProbeRuns < 4 && skyProbeTimer <= 0.0f) {
				skyProbeTimer = 5.0f;
				++skyProbeRuns;
				ID3D11ShaderResourceView* unbind[5] = {};
				a_context->PSSetShaderResources(0, 5, unbind);
				D3D11_VIEWPORT pvp{ 0, 0, 16, 1, 0, 1 };
				a_context->OMSetRenderTargets(1, &skyProbeRtv, nullptr);
				a_context->RSSetViewports(1, &pvp);
				a_context->RSSetState(raster);
				a_context->OMSetBlendState(noBlend, nullptr, 0xFFFFFFFF);
				a_context->OMSetDepthStencilState(nullptr, 0);
				a_context->IASetInputLayout(nullptr);
				a_context->VSSetShader(probeVs, nullptr, 0);
				a_context->PSSetShader(skyProbePs, nullptr, 0);
				ID3D11ShaderResourceView* probeSrvs[5] = { nullptr, a_t.sceneDepth, nullptr, nullptr, skyrimShadowSrv };
				a_context->PSSetShaderResources(0, 5, probeSrvs);
				a_context->Draw(3, 0);
				a_context->CopyResource(skyProbeStaging, skyProbeTex);
				skyProbePending = true;
				a_context->PSSetShaderResources(0, 5, unbind);
				a_context->IASetInputLayout(layout);
			}

			D3D11_VIEWPORT vp{ 0, 0, float(a_t.width), float(a_t.height), 0, 1 };
			a_context->RSSetViewports(1, &vp);
			a_context->RSSetState(raster);
			ID3D11ShaderResourceView* srvs[8] = { atlasSrv, a_t.sceneDepth, sunShadows ? shadowSrv : nullptr, fc.shadowExtra[0] > 0.5f ? a_maskSrv : nullptr,
				skyrimShadowSrv, contact ? aoSrv : nullptr, a_t.surfaceDepth, nullptr };

			// The blocks' shadows on Skyrim's ground, walls and trees, and the contact shadows around
			// blocks and under players and mobs.
			if (!a_t.foreground && !a_t.refraction && canUnproject && (sunShadows || contact || fc.aoParams[0] > 0.0f)) {
				a_context->OMSetRenderTargets(1, &a_t.rtv, nullptr);
				a_context->OMSetBlendState(multiplyBlend, nullptr, 0xFFFFFFFF);
				a_context->IASetInputLayout(nullptr);
				a_context->VSSetShader(probeVs, nullptr, 0);
				a_context->PSSetShader(overlayPs, nullptr, 0);
				a_context->PSSetShaderResources(0, 6, srvs);
				a_context->Draw(3, 0);
				a_context->IASetInputLayout(layout);
			}
			passTimer.Mark(a_context, 2);

			if (!a_t.skyrimDepth && !a_t.refraction) {
				a_context->ClearDepthStencilView(a_t.dsv, D3D11_CLEAR_DEPTH, reversed ? 0.0f : 1.0f, 0);
			}
			// Solid things also write Skyrim's motion vectors and normals, so its TAA, ambient
			// occlusion and reflections see them rather than whatever is behind them.
			ID3D11RenderTargetView* solidTargets[3] = { a_t.rtv, a_t.motion, a_t.normals };
			a_context->OMSetRenderTargets(a_t.normals ? 3 : a_t.motion ? 2 : 1, solidTargets, a_t.dsv);
			a_context->VSSetShader(vs, nullptr, 0);
			a_context->PSSetShader(ps, nullptr, 0);
			a_context->PSSetShaderResources(0, 8, srvs);

			// Opaque and cutout blocks.
			a_context->OMSetBlendState(a_t.foreground ? foregroundBlend : solidBlend, nullptr, 0xFFFFFFFF);
			a_context->OMSetDepthStencilState(reversed ? depthWriteRev : depthWrite, 0);
			for (const auto* visible : visibleSections) {
				const auto& s = *visible;
				const double dx = s.sx * 16.0 + 8.0 - camMc.x, dy = s.sy * 16.0 + 8.0 - camMc.y, dz = s.sz * 16.0 + 8.0 - camMc.z;
				if (dx * dx + dy * dy + dz * dz > 320.0 * 320.0 || !s.opaque) {
					continue;
				}
				double origin[3];
				McToSkyD(s.sx * 16.0, s.sy * 16.0, s.sz * 16.0, origin);
				SetObjectOffset(a_context, origin, cam);
				a_context->IASetVertexBuffers(0, 1, &s.vb, &stride, &zero);
				a_context->Draw(s.opaque, 0);
			}

			DrawEntities(a_context, cam, false, a_t.depthOnly || a_t.refraction || fc.renderMode[3] > 0.5f ||
				(a_t.foreground && State().mcThirdPerson.load()));

			if (haveDyn && entityVerts) {
				SetObjectOffset(a_context, originSky, cam, nullptr, nullptr, true);
				a_context->IASetVertexBuffers(0, 1, &dynVb, &stride, &zero);
				a_context->Draw(entityVerts, 0);
			}

			passTimer.Mark(a_context, 3);
			if (a_t.depthOnly) {
				ID3D11ShaderResourceView* none[8]{};
				a_context->PSSetShaderResources(0, 8, none);
				passTimer.Finish(a_context);
				return;
			}

			// Water, stained glass: blended over what's already there, no depth writes.
			a_context->OMSetRenderTargets(1, &a_t.rtv, a_t.dsv);
			a_context->OMSetBlendState(alphaBlend, nullptr, 0xFFFFFFFF);
			a_context->OMSetDepthStencilState(reversed ? depthReadRev : depthRead, 0);
			if (rasterCullBack) {
				a_context->RSSetState(rasterCullBack);
			}
			for (const auto* visible : visibleSections) {
				const auto& s = *visible;
				if (!s.translucent) {
					continue;
				}
				double origin[3];
				McToSkyD(s.sx * 16.0, s.sy * 16.0, s.sz * 16.0, origin);
				SetObjectOffset(a_context, origin, cam);
				a_context->IASetVertexBuffers(0, 1, &s.vb, &stride, &zero);
				a_context->Draw(s.translucent, s.opaque);
			}
			a_context->RSSetState(raster);

			if (a_t.refraction || fc.renderMode[3] > 0.5f) DrawEntities(a_context, cam, true);
			if (haveDyn && crackVerts) {
				SetObjectOffset(a_context, originSky, cam);
				a_context->IASetVertexBuffers(0, 1, &dynVb, &stride, &zero);
				a_context->Draw(crackVerts, entityVerts);
			}
			if (haveDyn && outlineVerts) {
				SetObjectOffset(a_context, originSky, cam);
				a_context->IASetVertexBuffers(0, 1, &dynVb, &stride, &zero);
				a_context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_LINELIST);
				a_context->Draw(outlineVerts, entityVerts + crackVerts);
			}

			passTimer.Mark(a_context, 4);

			// Post-processing must see opaque entities' depth, rather than terrain
			// behind it. PSMain retains revision54's water rejection; MAIN surface
			// is sampled from a private copy so binding the native DSV cannot unbind it.
			// Stencil writes remain disabled. Above-water cameras retain water rejection.
			if (a_t.foreground && std::ranges::any_of(a_t.avatarDepthCopies, [](auto* d) { return d != nullptr; })) {
                // Coverage/depth only: retain water and texture-alpha rejection,
                // skip full lighting/voxel AO for every native depth destination.
                fc.screen[2] = 1.0f;
                D3D11_MAPPED_SUBRESOURCE coverageConstants{};
                if (FAILED(a_context->Map(frameCb, 0, D3D11_MAP_WRITE_DISCARD, 0, &coverageConstants))) return;
                std::memcpy(coverageConstants.pData, &fc, sizeof(fc));
                a_context->Unmap(frameCb, 0);
                // Merge only dry visible geometry BEFORE native transparency. Use
                // the intact water surface to avoid replacing its depth/stencil.
                const float savedMode = fc.renderMode[2], savedUnderwater = fc.renderMode[3];
                if (!a_t.foreground) {
                    fc.renderMode[2] = 1.0f;
                    fc.renderMode[3] = 0.0f;
                    D3D11_MAPPED_SUBRESOURCE depthConstants{};
                    if (FAILED(a_context->Map(frameCb, 0, D3D11_MAP_WRITE_DISCARD, 0, &depthConstants))) return;
                    std::memcpy(depthConstants.pData, &fc, sizeof(fc));
                    a_context->Unmap(frameCb, 0);
                }
                a_context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
				a_context->VSSetShader(vs, nullptr, 0);
				a_context->PSSetShader(ps, nullptr, 0);
				a_context->PSSetShaderResources(0, 8, srvs);
				a_context->OMSetDepthStencilState(reversed ? depthWriteRev : depthWrite, 0);
				for (auto* copy : a_t.avatarDepthCopies) {
					if (!copy) continue;
					a_context->OMSetRenderTargets(0, nullptr, copy);
                    for (const auto* visible : visibleSections) {
                        const auto& section = *visible;
                        if (!section.vb || !section.opaque) continue;
                        double blockOrigin[3];
                        McToSkyD(section.sx * 16.0, section.sy * 16.0, section.sz * 16.0, blockOrigin);
                        SetObjectOffset(a_context, blockOrigin, cam);
                        a_context->IASetVertexBuffers(0, 1, &section.vb, &stride, &zero);
                        a_context->Draw(section.opaque, 0);
                    }
                    DrawEntities(a_context, cam, false);
					if (haveDyn && entityVerts) {
						SetObjectOffset(a_context, originSky, cam, nullptr, nullptr, true);
						a_context->IASetVertexBuffers(0, 1, &dynVb, &stride, &zero);
						a_context->Draw(entityVerts, 0);
					}
				}
                fc.screen[2] = 0.0f;
                fc.renderMode[2] = savedMode;
                fc.renderMode[3] = savedUnderwater;
                D3D11_MAPPED_SUBRESOURCE restoredConstants{};
                if (FAILED(a_context->Map(frameCb, 0, D3D11_MAP_WRITE_DISCARD, 0, &restoredConstants))) return;
                std::memcpy(restoredConstants.pData, &fc, sizeof(fc));
                a_context->Unmap(frameCb, 0);
            }

            // Skyrim's depth copies: the solid blocks, the player and items, depth only.
			if (std::ranges::any_of(a_t.depthCopies, [](auto* d) { return d != nullptr; })) {
				ID3D11ShaderResourceView* unbind[5] = {};
				a_context->PSSetShaderResources(0, 5, unbind);
				a_context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
				a_context->OMSetBlendState(noBlend, nullptr, 0xFFFFFFFF);
				a_context->OMSetDepthStencilState(reversed ? depthWriteRev : depthWrite, 0);
				a_context->VSSetShader(depthVs, nullptr, 0);
				a_context->PSSetShader(shadowPs, nullptr, 0);
				a_context->PSSetShaderResources(0, 1, &atlasSrv);
				for (auto* copy : a_t.depthCopies) {
					if (!copy) {
						continue;
					}
					a_context->OMSetRenderTargets(0, nullptr, copy);
					for (const auto* visible : visibleSections) {
						const auto& s = *visible;
								const double dx = s.sx * 16.0 + 8.0 - camMc.x, dy = s.sy * 16.0 + 8.0 - camMc.y, dz = s.sz * 16.0 + 8.0 - camMc.z;
						if (dx * dx + dy * dy + dz * dz > 320.0 * 320.0 || !s.opaque) {
							continue;
						}
						double origin[3];
						McToSkyD(s.sx * 16.0, s.sy * 16.0, s.sz * 16.0, origin);
						SetObjectOffset(a_context, origin, cam);
						a_context->IASetVertexBuffers(0, 1, &s.vb, &stride, &zero);
						a_context->Draw(s.opaque, 0);
					}
					DrawEntities(a_context, cam, false);
					if (haveDyn && entityVerts) {
						SetObjectOffset(a_context, originSky, cam);
						a_context->IASetVertexBuffers(0, 1, &dynVb, &stride, &zero);
						a_context->Draw(entityVerts, 0);
					}
				}
			}

			ID3D11ShaderResourceView* none[8] = {};
			a_context->PSSetShaderResources(0, 8, none);
			if (!a_t.hdr || a_t.foreground)
				for (auto& [id, moving] : movingMeshes) std::memcpy(moving.previous, moving.transform, sizeof(moving.transform));
			passTimer.Finish(a_context);
		}

		#include "NativeEffects.inl"


		// Stable F5 colour and MC particles after Skyrim's temporal/fog resolve.
		// Native depth/refraction and private smoke coverage remain in the earlier passes.
		void DrawFinalMeshes(ID3D11DeviceContext* context, IDXGISwapChain* chain)
		{
			if (!frameOrigin.valid || waterFrame.renderMode[3] > 0.5f || !ownDsv) return;
			ID3D11Texture2D* texture{};
			if (FAILED(chain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&texture)))) return;
			static ID3D11Texture2D* cachedTexture{};
			static ID3D11RenderTargetView* finalRtv{};
			if (texture != cachedTexture) {
				Release(finalRtv); Release(cachedTexture);
				if (FAILED(device->CreateRenderTargetView(texture, nullptr, &finalRtv))) { Release(texture); return; }
				cachedTexture = texture; cachedTexture->AddRef();
			}
			D3D11_TEXTURE2D_DESC desc{}; texture->GetDesc(&desc); Release(texture);
			FrameConstants fc = waterFrame;
			fc.renderMode[0] = 0; fc.renderMode[1] = 2; fc.renderMode[2] = 1;
			fc.screen[0] = 1.0f/desc.Width; fc.screen[1] = 1.0f/desc.Height; fc.screen[2] = 0;
			fc.screen[3] = State().nightVision.load();
            // Reuse the exact projection that wrote ownDsv. Removing jitter here
            // makes one side of the body fail depth while the other side is repainted.
            fc.projectionJitter[0] = fc.projectionJitter[1] = 0;
			D3D11_MAPPED_SUBRESOURCE mapped{};
			if (FAILED(context->Map(frameCb, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) return;
			std::memcpy(mapped.pData, &fc, sizeof(fc)); context->Unmap(frameCb,0);
			context->GSSetShader(nullptr,nullptr,0); context->HSSetShader(nullptr,nullptr,0); context->DSSetShader(nullptr,nullptr,0);
			context->OMSetRenderTargets(1,&finalRtv,ownDsv);
			D3D11_VIEWPORT viewport{0,0,float(desc.Width),float(desc.Height),0,1};
			context->RSSetViewports(1,&viewport); context->RSSetState(raster);
			context->IASetInputLayout(layout); context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
			context->VSSetShader(vs,nullptr,0); context->PSSetShader(ps,nullptr,0);
			ID3D11Buffer* buffers[]{frameCb,objectCb};
			context->VSSetConstantBuffers(0,2,buffers); context->PSSetConstantBuffers(0,2,buffers);
			ID3D11SamplerState* samplers[]{atlasSampler,pointSampler,shadowSampler};
			context->PSSetSamplers(0,3,samplers);
			ID3D11ShaderResourceView* resources[]{atlasSrv,depthCopySrv,shadowSrv,nullptr,SetSkyrimShadows(fc,RE::NiPoint3{float(frameOrigin.position[0]),float(frameOrigin.position[1]),float(frameOrigin.position[2])}),aoSrv,surfaceCopySrv,nativeEffects.ready?nativeEffects.srv:nullptr};
			context->PSSetShaderResources(0,8,resources);
			const RE::NiPoint3 cam{float(frameOrigin.position[0]),float(frameOrigin.position[1]),float(frameOrigin.position[2])};
			context->OMSetBlendState(avatarSolidBlend,nullptr,0xffffffff);
			context->OMSetDepthStencilState(depthReversed?depthWriteRev:depthWrite,0);
			if (avatarHistory && !ragdoll.active) DrawMesh(context,avatar,avatarFeet,cam,false);
			context->OMSetBlendState(alphaBlend,nullptr,0xffffffff);
			context->OMSetDepthStencilState(depthReversed?depthReadRev:depthRead,0);
			if (avatarHistory && !ragdoll.active) DrawMesh(context,avatar,avatarFeet,cam,true);
			DrawEntities(context,cam,true,false,true);
            // In-frame foreground blending masks translucent alpha for Skyrim's resolve.
            // The ordinary block crack buffer must also reach the final visible pass.
            if (frameHaveDyn && frameCrackVerts) {
                SetObjectOffset(context, frameDynamicOrigin, cam);
                const UINT stride = sizeof(Vertex), zero = 0;
                context->PSSetShaderResources(0, 1, &atlasSrv);
                context->IASetVertexBuffers(0, 1, &dynVb, &stride, &zero);
                context->Draw(frameCrackVerts, frameEntityVerts);
            }
		}

		// Private copies of the ACTUAL bound refraction colour and opaque depth.
		// Skyrim's originals and its water stencil are never written. Prepare once per
		// source/frame, then substitute only while issuing a native water draw.
		struct RefractionResources
		{
			ID3D11Texture2D* colour{};
			ID3D11Texture2D* depth{};
			ID3D11RenderTargetView* rtv{};
			ID3D11DepthStencilView* dsv{};
			ID3D11ShaderResourceView* colourSrv{};
			ID3D11ShaderResourceView* depthSrv{};
			ID3D11Texture2D* sourceColour{};
			ID3D11Texture2D* sourceDepth{};
			std::uint64_t prepared{};
			void Reset()
			{
				Release(rtv); Release(dsv); Release(colourSrv); Release(depthSrv);
				Release(colour); Release(depth); Release(sourceColour); Release(sourceDepth);
				prepared = 0;
			}
		} refractionResources;
		struct WaterDrawStats
		{
			unsigned draws = 0, candidates = 0, prepared = 0;
			const char* reason = "no water shader draw";
		} waterDrawStats;

		bool PrepareRefraction(ID3D11DeviceContext* context, ID3D11ShaderResourceView* colourInput, ID3D11ShaderResourceView* depthInput)
		{
			auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
			auto* camera = RE::Main::WorldRootCamera();
			if (!renderer || !camera || !device || !colourInput) { waterDrawStats.reason = "no refraction colour bound"; return false; }
			// The native UNDERWATER technique has no t7. Its colour still needs
			// submerged geometry, using the original opaque snapshot for occlusion.
			if (!depthInput) depthInput = reinterpret_cast<ID3D11ShaderResourceView*>(renderer->GetDepthStencilData().depthStencils[RE::RENDER_TARGETS_DEPTHSTENCIL::kPOST_ZPREPASS_COPY].depthSRV);
			if (!depthInput) { waterDrawStats.reason = "no opaque depth available"; return false; }
			ID3D11Resource* cr = nullptr;
			ID3D11Resource* dr = nullptr;
			colourInput->GetResource(&cr); depthInput->GetResource(&dr);
			ID3D11Texture2D* ct = nullptr;
			ID3D11Texture2D* dt = nullptr;
			cr->QueryInterface(__uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&ct));
			dr->QueryInterface(__uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&dt));
			Release(cr); Release(dr);
			if (!ct || !dt) { waterDrawStats.reason = "inputs are not Texture2D"; Release(ct); Release(dt); return false; }
			const auto& depths = renderer->GetDepthStencilData().depthStencils;
			const auto& opaque = depths[RE::RENDER_TARGETS_DEPTHSTENCIL::kPOST_ZPREPASS_COPY];
			const auto& surface = depths[RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN];
			D3D11_TEXTURE2D_DESC cd{}, dd{}, sd{};
			ct->GetDesc(&cd); dt->GetDesc(&dd);
			if (surface.texture) reinterpret_cast<ID3D11Texture2D*>(surface.texture)->GetDesc(&sd);
			D3D11_SHADER_RESOURCE_VIEW_DESC csv{}, dsv{};
			colourInput->GetDesc(&csv); depthInput->GetDesc(&dsv);
			// Runtime capture proved t7 is opaque depth target7, NOT MAIN water depth.
			// Refuse unrelated techniques, reflection cameras and stale-sized resources.
			if (dt != reinterpret_cast<ID3D11Texture2D*>(opaque.texture) || !opaque.views[0] || !surface.depthSRV ||
				ct == reinterpret_cast<ID3D11Texture2D*>(renderer->GetRuntimeData().renderTargets[RE::RENDER_TARGETS::kMAIN].texture) ||
				csv.ViewDimension != D3D11_SRV_DIMENSION_TEXTURE2D || dsv.ViewDimension != D3D11_SRV_DIMENSION_TEXTURE2D ||
				cd.Width != dd.Width || cd.Height != dd.Height || cd.Width != sd.Width || cd.Height != sd.Height ||
				cd.SampleDesc.Count != 1 || dd.SampleDesc.Count != 1 || cd.ArraySize != 1 || dd.ArraySize != 1 ||
				cd.MipLevels != 1 || dd.MipLevels != 1 || cd.Format != DXGI_FORMAT_R16G16B16A16_FLOAT) {
				waterDrawStats.reason = "water input descriptor or opaque identity differs from capture";
				static bool loggedInputs = false;
				if (!loggedInputs) {
					loggedInputs = true;
					logger::info("water61 rejected inputs: colour {}x{} format{} mips{} view{}; depth {}x{} format{} opaqueMatch{}; MAIN {}x{}", cd.Width, cd.Height, int(cd.Format), cd.MipLevels, int(csv.ViewDimension), dd.Width, dd.Height, int(dd.Format), dt == reinterpret_cast<ID3D11Texture2D*>(opaque.texture), sd.Width, sd.Height);
				}
				Release(ct); Release(dt); return false;
			}
			auto& r = refractionResources;
			if (r.sourceColour != ct || r.sourceDepth != dt) {
				r.Reset();
				r.sourceColour = ct; r.sourceDepth = dt;
				ct->AddRef(); dt->AddRef();
				cd.Usage = dd.Usage = D3D11_USAGE_DEFAULT;
				cd.CPUAccessFlags = dd.CPUAccessFlags = 0;
				cd.MiscFlags = dd.MiscFlags = 0;
				cd.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
				dd.BindFlags = D3D11_BIND_DEPTH_STENCIL | D3D11_BIND_SHADER_RESOURCE;
				D3D11_DEPTH_STENCIL_VIEW_DESC depthView{};
				reinterpret_cast<ID3D11DepthStencilView*>(opaque.views[0])->GetDesc(&depthView);
				depthView.Flags = 0;
				if (FAILED(device->CreateTexture2D(&cd, nullptr, &r.colour)) ||
					FAILED(device->CreateTexture2D(&dd, nullptr, &r.depth)) ||
					FAILED(device->CreateRenderTargetView(r.colour, nullptr, &r.rtv)) ||
					FAILED(device->CreateDepthStencilView(r.depth, &depthView, &r.dsv)) ||
					FAILED(device->CreateShaderResourceView(r.colour, &csv, &r.colourSrv)) ||
					FAILED(device->CreateShaderResourceView(r.depth, &dsv, &r.depthSrv))) {
					r.Reset(); Release(ct); Release(dt);
					waterDrawStats.reason = "private allocation failed";
					logger::warn("water refraction60: private inputs allocation failed");
					return false;
				}
			}
			if (r.prepared != refractionFrame) {
				StateBackup backup;
				backup.Save(context);
				context->OMSetRenderTargets(0, nullptr, nullptr);
				// Copy native BACKGROUND, never the already-composited MAIN scene.
				context->CopyResource(r.colour, ct);
				context->CopyResource(r.depth, dt);
				Target t;
				t.rtv = r.rtv; t.dsv = r.dsv; t.width = cd.Width; t.height = cd.Height;
				t.hdr = true; t.refraction = true;
				t.sceneDepth = depthInput;
				t.surfaceDepth = reinterpret_cast<ID3D11ShaderResourceView*>(surface.depthSRV);
				injectingRefraction = true;
				RenderPasses(context, t, camera, nullptr);
				injectingRefraction = false;
				backup.Restore(context);
				r.prepared = refractionFrame;
				static bool logged = false;
				if (!logged) {
					logged = true;
					logger::info("water refraction60: populated private {}x{} colour/depth BEFORE native draw; native t7=opaque, MAIN surface/stencil untouched", cd.Width, cd.Height);
				}
			}
			Release(ct); Release(dt);
			waterDrawStats.reason = "private refraction supplied";
			return true;
		}

		bool IsWaterDraw(ID3D11DeviceContext* context)
		{
			if (waterGeometryActive) return true;
			// Cached geometry can skip SetupGeometry. Check the actual pixel shader
			// against BSWaterShader's shader table after the renderer has flushed state.
			if (!waterShaderInstance) return false;
			ID3D11PixelShader* actual = nullptr;
			context->PSGetShader(&actual, nullptr, nullptr);
			bool found = false;
			for (auto* shader : waterShaderInstance->pixelShaders) {
				if (shader && reinterpret_cast<ID3D11PixelShader*>(shader->shader) == actual) { found = true; break; }
			}
			Release(actual);
			return found;
		}

		template<class F>
		void DispatchWaterDraw(ID3D11DeviceContext* context, F draw)
		{
			if (!mainWaterStage || injectingRefraction) { draw(); return; }
			++waterDrawStats.draws;
			if (!IsWaterDraw(context)) { draw(); return; }
			++waterDrawStats.candidates;
			ID3D11ShaderResourceView* colour = nullptr;
			ID3D11ShaderResourceView* depth = nullptr;
			context->PSGetShaderResources(1, 1, &colour);
			context->PSGetShaderResources(7, 1, &depth);
			const bool prepared = PrepareRefraction(context, colour, depth);
			if (prepared) ++waterDrawStats.prepared;
			if (prepared) {
				context->PSSetShaderResources(1, 1, &refractionResources.colourSrv);
				context->PSSetShaderResources(7, 1, &refractionResources.depthSrv);
			}
			draw();
			if (prepared) {
				context->PSSetShaderResources(1, 1, &colour);
				context->PSSetShaderResources(7, 1, &depth);
			}
			Release(colour); Release(depth);
		}

		using DrawFn = void (STDMETHODCALLTYPE*)(ID3D11DeviceContext*, UINT, UINT);
		using DrawIndexedFn = void (STDMETHODCALLTYPE*)(ID3D11DeviceContext*, UINT, UINT, INT);
		using DrawInstancedFn = void (STDMETHODCALLTYPE*)(ID3D11DeviceContext*, UINT, UINT, UINT, UINT);
		using DrawIndexedInstancedFn = void (STDMETHODCALLTYPE*)(ID3D11DeviceContext*, UINT, UINT, UINT, INT, UINT);
		using DrawIndirectFn = void (STDMETHODCALLTYPE*)(ID3D11DeviceContext*, ID3D11Buffer*, UINT);
		using DrawAutoFn = void (STDMETHODCALLTYPE*)(ID3D11DeviceContext*);
		struct NativeDrawMethods {
			DrawFn draw{};
			DrawIndexedFn indexed{};
			DrawInstancedFn instanced{};
			DrawIndexedInstancedFn indexedInstanced{};
			DrawIndirectFn indexedIndirect{}, indirect{};
			DrawAutoFn automatic{};
		};
		std::unordered_map<void**, NativeDrawMethods> nativeContexts;
		const NativeDrawMethods& NativeMethods(ID3D11DeviceContext* c)
		{ return nativeContexts.at(*reinterpret_cast<void***>(c)); }
		void STDMETHODCALLTYPE WaterDraw(ID3D11DeviceContext* c, UINT count, UINT start)
		{ DispatchWaterDraw(c, [&] { NativeMethods(c).draw(c, count, start); }); }
		void STDMETHODCALLTYPE WaterDrawIndexed(ID3D11DeviceContext* c, UINT count, UINT start, INT base)
		{ DispatchWaterDraw(c, [&] { NativeMethods(c).indexed(c, count, start, base); }); }
		void STDMETHODCALLTYPE WaterDrawInstanced(ID3D11DeviceContext* c, UINT count, UINT instances, UINT start, UINT first)
		{ DispatchWaterDraw(c, [&] { NativeMethods(c).instanced(c, count, instances, start, first); }); }
		void STDMETHODCALLTYPE WaterDrawIndexedInstanced(ID3D11DeviceContext* c, UINT count, UINT instances, UINT start, INT base, UINT first)
		{ DispatchWaterDraw(c, [&] { NativeMethods(c).indexedInstanced(c, count, instances, start, base, first); }); }
		void STDMETHODCALLTYPE WaterDrawIndexedIndirect(ID3D11DeviceContext* c, ID3D11Buffer* buffer, UINT offset)
		{ DispatchWaterDraw(c, [&] { NativeMethods(c).indexedIndirect(c, buffer, offset); }); }
		void STDMETHODCALLTYPE WaterDrawIndirect(ID3D11DeviceContext* c, ID3D11Buffer* buffer, UINT offset)
		{ DispatchWaterDraw(c, [&] { NativeMethods(c).indirect(c, buffer, offset); }); }
		void STDMETHODCALLTYPE WaterDrawAuto(ID3D11DeviceContext* c)
		{ DispatchWaterDraw(c, [&] { NativeMethods(c).automatic(c); }); }

		void InstallContextDrawHooks(ID3D11DeviceContext* context)
		{
			if (!context) return;
			auto** table = *reinterpret_cast<void***>(context);
			if (nativeContexts.contains(table)) return;
			DWORD protection = 0;
			if (!::VirtualProtect(&table[12], 29 * sizeof(void*), PAGE_EXECUTE_READWRITE, &protection)) return;
			nativeContexts.emplace(table, NativeDrawMethods{
				reinterpret_cast<DrawFn>(table[13]), reinterpret_cast<DrawIndexedFn>(table[12]),
				reinterpret_cast<DrawInstancedFn>(table[21]), reinterpret_cast<DrawIndexedInstancedFn>(table[20]),
				reinterpret_cast<DrawIndirectFn>(table[39]), reinterpret_cast<DrawIndirectFn>(table[40]),
				reinterpret_cast<DrawAutoFn>(table[38]) });
			table[12] = reinterpret_cast<void*>(&WaterDrawIndexed);
			table[13] = reinterpret_cast<void*>(&WaterDraw);
			table[20] = reinterpret_cast<void*>(&WaterDrawIndexedInstanced);
			table[21] = reinterpret_cast<void*>(&WaterDrawInstanced);
			table[38] = reinterpret_cast<void*>(&WaterDrawAuto);
			table[39] = reinterpret_cast<void*>(&WaterDrawIndexedIndirect);
			table[40] = reinterpret_cast<void*>(&WaterDrawIndirect);
			::VirtualProtect(&table[12], 29 * sizeof(void*), protection, &protection);
		}
		thread_local ID3D11ShaderResourceView* savedWaterColour = nullptr;
		thread_local ID3D11ShaderResourceView* savedWaterDepth = nullptr;
		thread_local bool nativeWaterInputsActive = false;
		void EndNativeWaterRefraction()
		{
			if (!nativeWaterInputsActive) return;
			auto* context = NativeDrawContext();
			context->PSSetShaderResources(1, 1, &savedWaterColour);
			context->PSSetShaderResources(7, 1, &savedWaterDepth);
			Release(savedWaterColour); Release(savedWaterDepth);
			nativeWaterInputsActive = false;
		}
		struct NativeDrawStateHook
		{
			static void thunk(bool a_force)
			{
				// The runtime rewrites its embedded COM vtable. Hook Skyrim's stable
				// state-flush call instead, after deferred water resources become real.
				EndNativeWaterRefraction();
				EndNativeLocalEffect();
				func(a_force);
				if (!mainWaterStage || injectingRefraction) return;
				auto* context = NativeDrawContext();
				++waterDrawStats.draws;
				if (!IsWaterDraw(context)) { BeginNativeLocalEffect(context); return; }
				++waterDrawStats.candidates;
				context->PSGetShaderResources(1, 1, &savedWaterColour);
				context->PSGetShaderResources(7, 1, &savedWaterDepth);
				if (PrepareRefraction(context, savedWaterColour, savedWaterDepth)) {
					context->PSSetShaderResources(1, 1, &refractionResources.colourSrv);
					context->PSSetShaderResources(7, 1, &refractionResources.depthSrv);
					nativeWaterInputsActive = true;
					++waterDrawStats.prepared;
				} else {
					Release(savedWaterColour); Release(savedWaterDepth);
				}
			}
			static inline REL::Relocation<decltype(thunk)> func{ REL::ID(77386) };
		};
		struct AdditionalEffectDrawStateHook
		{
			static void thunk(bool a_force)
			{
				NativeDrawStateHook::thunk(a_force);
				if (nativeEffects.bound) ++nativeEffects.additionalDrawCaptures;
			}
		};
		void InstallWaterDrawHooks()
		{
			// Particle systems and dynamic triangle/line buffers use separate engine
			// draw helpers. Cover their validated resource-flush calls as well; hooking
			// only the three static triangle helpers left smoke below the foreground
			// avatar repaint. Shader, main-view, blend and geometry checks still apply.
			for (const auto [id, offset] : std::array<std::pair<std::uint64_t, std::size_t>, 10>{
				std::pair{77263ULL, 0x63ULL}, {77264ULL, 0x63ULL}, {77265ULL, 0x61ULL},
				{77275ULL, 0x63ULL}, {77276ULL, 0x5BULL}, {77277ULL, 0x9AULL},
				{77278ULL, 0x137ULL}, {77282ULL, 0x5BULL}, {77289ULL, 0x63ULL}, {77290ULL, 0x63ULL} }) {
				const auto site = REL::ID(id).address() + offset;
				const auto* code = reinterpret_cast<const std::uint8_t*>(site);
				std::int32_t displacement = 0;
				std::memcpy(&displacement, code + 1, 4);
				if (code[0] != 0xE8 || site + 5 + static_cast<std::intptr_t>(displacement) != NativeDrawStateHook::func.address()) {
					logger::error("water63: native draw {} state-flush call differs; not patched", id);
					continue;
				}
				SKSE::GetTrampoline().write_call<5>(site, id <= 77265 ? NativeDrawStateHook::thunk : AdditionalEffectDrawStateHook::thunk);
				logger::info("effects76: installed stable native draw {} hook AFTER resource flush", id);
			}
		}

		// Before Skyrim's post-resolve accumulation: put Minecraft in the HDR scene and main
		// depth before water, smoke and other transparent geometry composite over it.
		void DrawInFrame(bool a_foreground = false)
		{
			auto& st = State();
			auto* camera = RE::Main::WorldRootCamera();
			auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
			if (camera && st.puppeting) {
				Game::NoteFrameStep('D');
				Game::NoteRenderedCamera(camera->world.translate, camera->world.rotate);
			}
			// A flat depth probe from Present (menus/loading) must not disable drawing into a
			// valid live depth target. Its hardware depth test still handles opaque occlusion.
			if ((!a_foreground && inFrameDrawn) || (a_foreground && !inFrameDrawn) || !device || !atlasSrv || atlasUploading || !inFrameEnabled || !camera || !renderer || !st.renderWorld || st.skyrimMenuOpen) {
				return;
			}
			if (!a_foreground) {
				surfaceQueriesLeft = 1;
				const auto sequence = entities.seq;
				Link::Get().ReadWorldEntities(entities);
				projectileTime = std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
				if (sequence != entities.seq) {
					std::unordered_set<std::uint32_t> live;
					for (std::uint32_t i=0; i<entities.count; ++i) {
						const auto& entity = entities.entities[i];
						if (entity.kind != proto::kWeArrow && entity.kind != proto::kWeTrident) continue;
						live.insert(entity.id);
						projectileSteps[entity.id].Retarget({entity.x, entity.y, entity.z}, projectileTime, entity.ext[0] > 0.5f || entity.ext[1] > 0.5f);
					}
					std::erase_if(projectileSteps, [&](const auto& entry) { return !live.contains(entry.first); });
					std::erase_if(arrowSurfaces, [&](const auto& entry) { return !live.contains(entry.first); });
				}
			}
			if (sections.empty() && movingMeshes.empty() && entities.count == 0 && !entities.hasSelection && avatar.batches.empty() && scene.batches.empty()) {
				return;
			}
			auto&       rd = renderer->GetRuntimeData();
			auto*       context = NativeDrawContext();
			const auto& scene = rd.renderTargets[RE::RENDER_TARGETS::kMAIN];
			const auto& motion = rd.renderTargets[RE::RENDER_TARGETS::kMOTION_VECTOR];
			const auto& depth = renderer->GetDepthStencilData().depthStencils[RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN];
			auto*       sceneTex = reinterpret_cast<ID3D11Texture2D*>(scene.texture);
			auto*       depthTex = reinterpret_cast<ID3D11Texture2D*>(depth.texture);
			auto*       rtv = reinterpret_cast<ID3D11RenderTargetView*>(scene.RTV);
			auto*       dsv = reinterpret_cast<ID3D11DepthStencilView*>(depth.views[0]);
			auto*       depthSrv = reinterpret_cast<ID3D11ShaderResourceView*>(depth.depthSRV);
			if (!context || !sceneTex || !depthTex || !rtv || !dsv || !depthSrv) {
				inFrameEnabled = false;
				logger::warn("in-frame block drawing: Skyrim's scene target or depth buffer is missing; blocks are drawn on the finished frame");
				return;
			}
			D3D11_TEXTURE2D_DESC sd{}, dd{};
			sceneTex->GetDesc(&sd);
			depthTex->GetDesc(&dd);

			static bool checked = false;
			if (!checked) {
				checked = true;
				// What Skyrim has bound here, slot by slot (render target indices; -1 none, -2 unknown).
				ID3D11RenderTargetView* boundAll[6]{};
				ID3D11DepthStencilView* boundDsv = nullptr;
				context->OMGetRenderTargets(6, boundAll, &boundDsv);
				std::string slots;
				for (auto*& b : boundAll) {
					int index = b ? -2 : -1;
					for (int i = 0; b && i < RE::RENDER_TARGETS::kTOTAL; ++i) {
						if (reinterpret_cast<ID3D11RenderTargetView*>(rd.renderTargets[i].RTV) == b) {
							index = i;
						}
					}
					slots += std::format("{} ", index);
					Release(b);
				}
				const bool anyDepth = boundDsv != nullptr;
				bool       boundMainDepth = false;
				for (int k = 0; k < 8; ++k) {
					boundMainDepth |= boundDsv && (reinterpret_cast<ID3D11DepthStencilView*>(depth.views[k]) == boundDsv ||
													  reinterpret_cast<ID3D11DepthStencilView*>(depth.readOnlyViews[k]) == boundDsv);
				}
				Release(boundDsv);
				D3D11_TEXTURE2D_DESC md{};
				if (motion.texture) {
					reinterpret_cast<ID3D11Texture2D*>(motion.texture)->GetDesc(&md);
				}
				const bool hdr = sd.Format == DXGI_FORMAT_R16G16B16A16_FLOAT || sd.Format == DXGI_FORMAT_R11G11B10_FLOAT || sd.Format == DXGI_FORMAT_R32G32B32A32_FLOAT;
				const bool sizesMatch = sd.Width == dd.Width && sd.Height == dd.Height && sd.SampleDesc.Count == 1 && dd.SampleDesc.Count == 1;
				D3D11_TEXTURE2D_DESC nd{};
				if (rd.renderTargets[RE::RENDER_TARGETS::kNORMAL_TAAMASK_SSRMASK].texture) {
					reinterpret_cast<ID3D11Texture2D*>(rd.renderTargets[RE::RENDER_TARGETS::kNORMAL_TAAMASK_SSRMASK].texture)->GetDesc(&nd);
				}
				logger::info("in-frame block drawing ({}): scene {}x{} format {} ({}), depth {}x{} format {}, motion vectors {}x{} format {}, normals {}x{} format {}; bound: targets [ {}] depth {}",
					"before post-resolve accumulation", sd.Width, sd.Height, static_cast<int>(sd.Format), hdr ? "HDR" : "not HDR", dd.Width,
					dd.Height, static_cast<int>(dd.Format), md.Width, md.Height, static_cast<int>(md.Format), nd.Width, nd.Height, static_cast<int>(nd.Format), slots,
					boundMainDepth ? "main" : anyDepth ? "other" : "none");
				inFrameEnabled = hdr && sizesMatch;
				if (!inFrameEnabled) {
					logger::warn("in-frame block drawing: unexpected targets; blocks are drawn on the finished frame");
					return;
				}
			}
			// MAIN can already contain the water stencil's depth at this hook.
			// It must not reject submerged geometry before refraction gets its colour.
			// Use the opaque z-prepass snapshot for visibility, while retaining MAIN's
			// water depth for the remaining Skyrim passes.
			const auto& opaque = renderer->GetDepthStencilData().depthStencils[RE::RENDER_TARGETS_DEPTHSTENCIL::kPOST_ZPREPASS_COPY];
			auto* opaqueTex = reinterpret_cast<ID3D11Texture2D*>(opaque.texture);
			auto* opaqueDsv = reinterpret_cast<ID3D11DepthStencilView*>(opaque.views[0]);
			auto* opaqueSrv = reinterpret_cast<ID3D11ShaderResourceView*>(opaque.depthSRV);
			bool useOpaqueDepth = false;
			if (opaqueTex && opaqueDsv && opaqueSrv && opaqueTex != depthTex) {
				D3D11_TEXTURE2D_DESC od{};
				opaqueTex->GetDesc(&od);
				useOpaqueDepth = od.Width == dd.Width && od.Height == dd.Height && od.Format == dd.Format && od.SampleDesc.Count == 1;
			}
			if (!EnsureDepthCopy(useOpaqueDepth ? opaqueTex : depthTex, useOpaqueDepth ? opaqueSrv : depthSrv)) {
				return;
			}
			if (!EnsureDepthCopy(depthTex, depthSrv, surfaceCopyTex, surfaceCopySrv)) return;
			// Never let an unavailable probe make the pre-water pass write Skyrim's
			// water/stencil depth. Failure to allocate our own depth skips this draw.
			const bool privateDepth = EnsureOwnDepth(sd.Width, sd.Height);
			if (!privateDepth) return;
			StateBackup backup;
			backup.Save(context);
			context->CopyResource(depthCopyTex, useOpaqueDepth ? opaqueTex : depthTex);
			context->CopyResource(surfaceCopyTex, depthTex);
			if (privateDepth) {
				context->ClearDepthStencilView(ownDsv, D3D11_CLEAR_DEPTH, depthReversed ? 0.0f : 1.0f, 0);
			}
			Target t;
			t.rtv = rtv;
			t.motion = a_foreground && motion.RTV && motion.texture ? reinterpret_cast<ID3D11RenderTargetView*>(motion.RTV) : nullptr;
			const auto& normals = rd.renderTargets[RE::RENDER_TARGETS::kNORMAL_TAAMASK_SSRMASK];
			t.normals = a_foreground && normals.RTV && normals.texture ? reinterpret_cast<ID3D11RenderTargetView*>(normals.RTV) : nullptr;
			t.dsv = ownDsv;
			t.sceneDepth = depthCopySrv;
			t.width = sd.Width;
			t.height = sd.Height;
			t.hdr = true;
			t.skyrimDepth = false;
			t.foreground = a_foreground;
			t.surfaceDepth = surfaceCopySrv;
			if (a_foreground) {
				SnapshotNativePostDepth(context, t);
			}
			// Colour uses private self-depth. Only visible, solid avatar fragments
			// merge depth after water; revision54's submerged rejection also applies
			// to that merge, and native water stencil is retained.
			RenderPasses(context, t, camera, reinterpret_cast<ID3D11ShaderResourceView*>(rd.renderTargets[RE::RENDER_TARGETS::kSHADOW_MASK].SRV));
			backup.Restore(context);
			static bool loggedIsolation = false;
			if (!loggedIsolation) {
				loggedIsolation = true;
				logger::info("render v62: engine draw context; dry avatar depth and moving feet history; revision54 submerged rejection preserved");
			}
			inFrameDrawn = true;
		}

		// BSShadowDirectionalLight::Render: the sun's shadow maps are complete when it returns.
		struct SunShadowRenderHook
		{
			static void thunk(RE::BSShadowLight* a_this, std::uint32_t& a_index)
			{
				func(a_this, a_index);
				CaptureSunShadows(a_this);
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};

		// Main world call only: reflections and shadow-map accumulators must not draw MC.
		struct WorldAccumulationHook
		{
			static void thunk(RE::NiCamera* a_camera, RE::BSShaderAccumulator* a_accumulator, std::uint32_t a_flags)
			{
				static int logged = 0;
				if (logged < 1 && State().puppeting && device) {
					++logged;
					if (auto* renderer = RE::BSGraphics::Renderer::GetSingleton()) {
						auto&                   rd = renderer->GetRuntimeData();
						auto*                   context = NativeDrawContext();
						ID3D11RenderTargetView* bound = nullptr;
						ID3D11DepthStencilView* boundDsv = nullptr;
						context->OMGetRenderTargets(1, &bound, &boundDsv);
						int boundIndex = -1;
						for (int i = 0; i < RE::RENDER_TARGETS::kTOTAL; ++i) {
							if (bound && reinterpret_cast<ID3D11RenderTargetView*>(rd.renderTargets[i].RTV) == bound) {
								boundIndex = i;
							}
						}
						logger::info("late accumulation pass starts with render target {} bound (depth {})", boundIndex, boundDsv ? "bound" : "none");
						Release(bound);
						Release(boundDsv);
					}
				}
				static auto nextCaptureCheck = std::chrono::steady_clock::time_point{};
				const auto now = std::chrono::steady_clock::now();
				if (device && State().puppeting && now >= nextCaptureCheck) {
					nextCaptureCheck = now + std::chrono::seconds(1);
					if (auto dir = SKSE::log::log_directory()) {
						std::error_code ec;
						const auto request = *dir / "skycraft_water_capture.request";
						if (std::filesystem::exists(request, ec)) {
							waterCaptureDir = *dir / "skycraft-water-capture";
							std::filesystem::create_directories(waterCaptureDir, ec);
							waterCaptureActive = !ec;
							waterCapturedPasses.clear();
							if (waterCaptureActive) std::filesystem::remove(request, ec);
						}
					}
				}
				if (waterCaptureActive) {
					auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
					if (renderer) CaptureWaterDepths(NativeDrawContext(), "before");
				}
				localEffectGeometryActive = false;
				DrawInFrame(false);
				if (inFrameDrawn) PrepareNativeLocalEffects(NativeDrawContext(), a_camera);
				++refractionFrame;
				waterDrawStats = {};
				mainWaterStage = inFrameDrawn && State().renderWorld;
				func(a_camera, a_accumulator, a_flags);
				EndNativeWaterRefraction();
				EndNativeLocalEffect();
				mainWaterStage = false;
				waterGeometryActive = false;
				localEffectGeometryActive = false;
				static unsigned summaryCount = 0;
				static auto nextDrawSummary = std::chrono::steady_clock::time_point{};
				if (inFrameDrawn && (summaryCount < 4 || now >= nextDrawSummary ||
					(waterDrawStats.candidates && !waterDrawStats.prepared && summaryCount < 8))) {
					++summaryCount;
					nextDrawSummary = now + std::chrono::seconds(10);
					logger::info("water63 draw audit: {} actual draws, {} water candidates, {} private inputs supplied; {}", waterDrawStats.draws, waterDrawStats.candidates, waterDrawStats.prepared, waterDrawStats.reason);
				}
				if (waterCaptureActive) {
					auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
					if (renderer) CaptureWaterDepths(NativeDrawContext(), "after");
					waterCaptureActive = false;
					logger::info("water diagnostic56: captured depth/colour and actual input bindings for {} water techniques", waterCapturedPasses.size());
				}
				{
					Perf::Scope timer(Perf::kInFrame);
					DrawInFrame(true);
					CompositeNativeLocalEffects(NativeDrawContext());
				}
			}
			static inline REL::Relocation<decltype(thunk)> func;
		};
	}

	namespace WorldRender
	{
		void FinishFrame(ID3D11DeviceContext* a_context) {
			RestoreNativePostDepth(a_context);
		}

		void UpdateRagdoll(RE::PlayerCharacter* a_player, bool a_minecraftBody)
		{
			auto*      root = a_player->Get3D(false);
			const bool dead = a_minecraftBody && a_player->IsDead() && root;
			if (ragdoll.active && (!dead || root != ragdoll.root.get())) {
				// Alive again (a save loaded) or a new body: give Skyrim its own body back.
				for (auto& mesh : ragdoll.hidden) {
					if (mesh) {
						mesh->SetAppCulled(false);
					}
				}
				ragdoll = BoundRagdoll{};
				logger::info("Minecraft death ragdoll released");
			}
			if (!dead || ragdoll.active || ragdollSnapshot.verts.empty()) {
				return;
			}
			// Bind: the Minecraft body standing where the Skyrim one stands, facing the same way;
			// each part keeps its place relative to its bone from here on.
			static constexpr const char* kBones[proto::kPartCount] = { nullptr, "NPC Head [Head]", "NPC Spine2 [Spn2]", "NPC R UpperArm [RUar]",
				"NPC L UpperArm [LUar]", "NPC R Thigh [RThg]", "NPC L Thigh [LThg]" };
			ragdoll.active = true;
			ragdoll.root.reset(root);
			ragdoll.body = ragdollSnapshot;
			const float     a = kPi - a_player->GetAngleZ();  // Minecraft's +Z facing to the Skyrim heading
			RE::NiTransform placed;
			placed.rotate.entry[0][0] = std::cos(a), placed.rotate.entry[0][1] = -std::sin(a), placed.rotate.entry[0][2] = 0.0f;
			placed.rotate.entry[1][0] = std::sin(a), placed.rotate.entry[1][1] = std::cos(a), placed.rotate.entry[1][2] = 0.0f;
			placed.rotate.entry[2][0] = 0.0f, placed.rotate.entry[2][1] = 0.0f, placed.rotate.entry[2][2] = 1.0f;
			placed.translate = a_player->GetPosition();
			placed.scale = 1.0f;
			int found = 0;
			for (std::uint32_t p = 1; p < proto::kPartCount; ++p) {
				auto* bone = root->GetObjectByName(kBones[p]);
				ragdoll.bones[p].reset(bone);
				ragdoll.offsets[p] = bone ? bone->world.Invert() * placed : placed;
				found += bone ? 1 : 0;
			}
			ragdoll.offsets[0] = placed;
			const float k = float(proto::kUnitsPerBlock);
			ragdoll.local.resize(ragdoll.body.verts.size());
			ragdoll.part.assign(ragdoll.body.verts.size(), 0);
			for (const auto& b : ragdoll.body.batches) {
				const auto part = std::uint8_t(std::min<std::uint32_t>((b.flags >> 8) & 0xF, proto::kPartCount - 1));
				for (std::uint32_t i = b.first; i < b.first + b.count && i < ragdoll.body.verts.size(); ++i) {
					ragdoll.part[i] = part;
				}
			}
			for (std::size_t i = 0; i < ragdoll.body.verts.size(); ++i) {
				const auto& v = ragdoll.body.verts[i];
				ragdoll.local[i] = { v.x * k, -v.z * k, v.y * k };  // Minecraft axes -> Skyrim's
			}
			// Hide Skyrim's own body (its meshes only; the skeleton and physics carry on).
			RE::BSVisit::TraverseScenegraphGeometries(root, [&](RE::BSGeometry* a_mesh) {
				if (!a_mesh->GetAppCulled()) {
					a_mesh->SetAppCulled(true);
					ragdoll.hidden.emplace_back(a_mesh);
				}
				return RE::BSVisit::BSVisitControl::kContinue;
			});
			logger::info("Minecraft death ragdoll: body parts pinned to Skyrim's skeleton ({} of 6 bones found), {} Skyrim meshes hidden", found,
				ragdoll.hidden.size());
		}

		void StickArrow(RE::FormID a_actor, float a_x, float a_y, float a_z, float a_yawDeg, float a_pitchDeg)
		{
			auto* actor = RE::TESForm::LookupByID<RE::Actor>(a_actor);
			auto* root = actor ? actor->Get3D() : nullptr;
			if (!root) {
				return;
			}
			const RE::NiPoint3 hit = McToSky(a_x, a_y, a_z);
			const float        yaw = a_yawDeg * kPi / 180.0f, pitch = a_pitchDeg * kPi / 180.0f;
			const RE::NiPoint3 d{ std::sin(yaw) * std::cos(pitch), -std::cos(yaw) * std::cos(pitch), std::sin(pitch) };  // Minecraft -> Skyrim axes
			// The bone the arrow's path passes closest to (skeleton nodes, not weapons or the camera).
			RE::NiAVObject* best = nullptr;
			float           bestDist = 1e9f;
			RE::NiPoint3    bestPoint;
			RE::BSVisit::TraverseScenegraphObjects(root, [&](RE::NiAVObject* a_node) {
				const char* name = a_node->name.c_str();
				if (a_node == root || !a_node->AsNode() || !name || !std::strchr(name, '[') || std::strstr(name, "Weapon") || std::strstr(name, "Shield") ||
					std::strstr(name, "Quiver") || std::strstr(name, "Camera") || std::strstr(name, "Magic")) {
					return RE::BSVisit::BSVisitControl::kContinue;
				}
				const RE::NiPoint3 pos = a_node->world.translate;
				const float        t = std::clamp((pos - hit).Dot(d), 0.0f, 50.0f);
				const RE::NiPoint3 closest = hit + d * t;
				const float        dist = pos.GetDistance(closest);
				if (dist < bestDist) {
					bestDist = dist;
					best = a_node;
					bestPoint = closest;
				}
				return RE::BSVisit::BSVisitControl::kContinue;
			});
			if (!best || bestDist > 60.0f) {
				return;  // passed beside the body
			}
			// The head just past the bone, the shaft sticking out.
			constexpr float    kHeadReach = 4.0f * 0.9f / 16.0f * kArrowScale * static_cast<float>(proto::kUnitsPerBlock);
			const RE::NiPoint3 p = bestPoint - d * (kHeadReach - 2.0f);
			const auto&        w = best->world;
			const auto         inv = w.rotate.Transpose();
			const float        scale = w.scale > 1e-4f ? w.scale : 1.0f;
			StuckArrow         arrow{ actor->GetHandle(), RE::NiPointer<RE::NiAVObject>(root), RE::NiPointer<RE::NiAVObject>(best), inv * (p - w.translate) / scale,
						inv * d };
			// At most 16 per actor: the oldest go first.
			auto mine = std::ranges::count_if(stuckArrows, [&](const StuckArrow& a_a) { return a_a.actor == arrow.actor; });
			for (auto it = stuckArrows.begin(); it != stuckArrows.end() && mine >= 16;) {
				if (it->actor == arrow.actor) {
					it = stuckArrows.erase(it);
					--mine;
				} else {
					++it;
				}
			}
			stuckArrows.push_back(std::move(arrow));
			while (stuckArrows.size() > 160) {
				stuckArrows.pop_front();
			}
		}

		void Draw(ID3D11Device* a_device, ID3D11DeviceContext* a_context, IDXGISwapChain* a_swapChain)
		{
			if (!Init(a_device)) {
				return;
			}
			Perf::Scope timer(Perf::kDraw);
			// Keep every pass on the same geometry snapshot. Consume next-frame messages
            // only after the final colour pass has used this frame's depth and poses.
            struct DrainAfterFrame {
                ID3D11DeviceContext* context;
                ~DrainAfterFrame() { DrainMessages(context); }
            } drainAfterFrame{a_context};
			{
				Perf::Scope readbacks(Perf::kReadbacks);
				Dig::ServiceReadbacks(a_device, a_context);
			}
			{
				Perf::Scope grass(Perf::kGrass);
				Dig::ServiceGrass(a_device, a_context);
			}
			ReadProbe(a_context);
			const bool drawnInFrame = std::exchange(inFrameDrawn, false);

			auto& st = State();
			auto* camera = RE::Main::WorldRootCamera();
			if (!camera || !st.renderWorld || st.skyrimMenuOpen || !atlasSrv || atlasUploading) {
				return;
			}

			ID3D11ShaderResourceView* depthSrv = nullptr;
			ID3D11ShaderResourceView* maskSrv = nullptr;
			if (auto* renderer = RE::BSGraphics::Renderer::GetSingleton()) {
				depthSrv = reinterpret_cast<ID3D11ShaderResourceView*>(
					renderer->GetDepthStencilData().depthStencils[RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN].depthSRV);
				maskSrv = reinterpret_cast<ID3D11ShaderResourceView*>(renderer->GetRuntimeData().renderTargets[RE::RENDER_TARGETS::kSHADOW_MASK].SRV);
			}

			StateBackup backup;
			backup.Save(a_context);
			probeTimer -= 1.0f / 60.0f;
			if (depthSrv && probeTimer <= 0.0f && !probePending) {
				probeTimer = depthKnown ? 5.0f : 0.5f;
				RunProbe(a_context, depthSrv, maskSrv);
			}
			if (drawnInFrame) {
				DrawFinalMeshes(a_context, a_swapChain);
				backup.Restore(a_context);  // already drawn inside Skyrim's frame
				return;
			}

			// Fallback: on top of the finished frame.
			Link::Get().ReadWorldEntities(entities);
			if (sections.empty() && entities.count == 0 && !entities.hasSelection && avatar.batches.empty() && scene.batches.empty()) {
				backup.Restore(a_context);
				return;
			}
			ID3D11Texture2D* backBuffer = nullptr;
			if (FAILED(a_swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&backBuffer)))) {
				backup.Restore(a_context);
				return;
			}
			D3D11_TEXTURE2D_DESC bbDesc{};
			backBuffer->GetDesc(&bbDesc);
			ID3D11RenderTargetView* rtv = nullptr;
			const auto              hr = device->CreateRenderTargetView(backBuffer, nullptr, &rtv);
			Release(backBuffer);
			if (SUCCEEDED(hr) && EnsureOwnDepth(bbDesc.Width, bbDesc.Height)) {
				Target t;
				t.rtv = rtv;
				t.dsv = ownDsv;
				t.sceneDepth = depthSrv;
				t.width = bbDesc.Width;
				t.height = bbDesc.Height;
				RenderPasses(a_context, t, camera, maskSrv);
			}
			backup.Restore(a_context);
			Release(rtv);
		}

		void Install()
		{
			if (!REL::Module::IsAE()) {
				logger::info("in-frame block drawing: only set up for Skyrim AE; blocks are drawn on the finished frame");
				return;
			}
			auto hookCall = [](std::uintptr_t a_site, std::uintptr_t a_target, const char* a_what) -> bool {
				const auto* code = reinterpret_cast<const std::uint8_t*>(a_site);
				std::int32_t rel = 0;
				std::memcpy(&rel, code + 1, 4);
				if (code[0] != 0xE8 || a_site + 5 + static_cast<std::intptr_t>(rel) != a_target) {
					logger::warn("in-frame block drawing: {} isn't where expected; blocks are drawn on the finished frame", a_what);
					return false;
				}
				return true;
			};
			REL::Relocation<std::uintptr_t> sunVtbl{ RE::VTABLE_BSShadowDirectionalLight[0] };
			SunShadowRenderHook::func = sunVtbl.write_vfunc(0x0A, SunShadowRenderHook::thunk);
			logger::info("Skyrim shadows on blocks: hooked the sun's shadow map rendering");
			REL::Relocation<std::uintptr_t> waterVtbl{ RE::VTABLE_BSWaterShader[0] };
			WaterTechniqueHook::func = waterVtbl.write_vfunc(0x02, WaterTechniqueHook::thunk);
			WaterSetupGeometryHook::func = waterVtbl.write_vfunc(0x06, WaterSetupGeometryHook::thunk);
			WaterRestoreGeometryHook::func = waterVtbl.write_vfunc(0x07, WaterRestoreGeometryHook::thunk);
			InstallWaterDrawHooks();
            REL::Relocation<std::uintptr_t> effectVtbl{ RE::VTABLE_BSEffectShader[0] };
            REL::Relocation<std::uintptr_t> particleVtbl{ RE::VTABLE_BSParticleShader[0] };
            LocalEffectGeometryHook<0>::func = effectVtbl.write_vfunc(0x06, LocalEffectGeometryHook<0>::thunk);
            LocalEffectGeometryHook<1>::func = particleVtbl.write_vfunc(0x06, LocalEffectGeometryHook<1>::thunk);
            LocalEffectRestoreHook<0>::func = effectVtbl.write_vfunc(0x07, LocalEffectRestoreHook<0>::thunk);
            LocalEffectRestoreHook<1>::func = particleVtbl.write_vfunc(0x07, LocalEffectRestoreHook<1>::thunk);
			logger::info("entities67: opaque path only for submerged entity fragments and underwater CAMERA; above-surface fragments retain water compositing");
			// Draw before this call, not after RenderWorld or Present: smoke is alpha blended
			// during the remaining scene passes and cannot obscure geometry drawn afterward.
			const auto lateSite = REL::ID(107142).address() + 0x2DF;
			if (hookCall(lateSite, REL::ID(106438).address(), "FinishAccumulatingPostResolveDepth's call")) {
				WorldAccumulationHook::func = SKSE::GetTrampoline().write_call<5>(lateSite, WorldAccumulationHook::thunk);
				logger::info("in-frame block drawing: hooked before post-resolve accumulation (smoke/water compositing)");
			}
		}

		void CaptureIfRequested(ID3D11DeviceContext* a_context, IDXGISwapChain* a_swapChain)
		{
			captureCheckTimer -= 1.0f / 60.0f;
			if (captureCheckTimer > 0.0f || !device) {
				return;
			}
			captureCheckTimer = 1.0f;
			const auto dir = SKSE::log::log_directory();
			if (!dir) {
				return;
			}
			const auto request = *dir / "skycraft_capture.request";
			std::error_code ec;
			if (!std::filesystem::exists(request, ec)) {
				return;
			}
			std::filesystem::remove(request, ec);

			ID3D11Texture2D* backBuffer = nullptr;
			if (FAILED(a_swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&backBuffer)))) {
				return;
			}
			D3D11_TEXTURE2D_DESC desc{};
			backBuffer->GetDesc(&desc);
			const bool bgra = desc.Format == DXGI_FORMAT_B8G8R8A8_UNORM || desc.Format == DXGI_FORMAT_B8G8R8A8_UNORM_SRGB;
			const bool rgba = desc.Format == DXGI_FORMAT_R8G8B8A8_UNORM || desc.Format == DXGI_FORMAT_R8G8B8A8_UNORM_SRGB;
			if ((!bgra && !rgba) || desc.SampleDesc.Count != 1) {
				logger::warn("capture: unsupported back buffer format {}", static_cast<int>(desc.Format));
				Release(backBuffer);
				return;
			}
			D3D11_TEXTURE2D_DESC sd = desc;
			sd.Usage = D3D11_USAGE_STAGING;
			sd.BindFlags = 0;
			sd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
			sd.MiscFlags = 0;
			ID3D11Texture2D* staging = nullptr;
			if (FAILED(device->CreateTexture2D(&sd, nullptr, &staging))) {
				Release(backBuffer);
				return;
			}
			a_context->CopyResource(staging, backBuffer);
			Release(backBuffer);
			D3D11_MAPPED_SUBRESOURCE mapped{};
			if (SUCCEEDED(a_context->Map(staging, 0, D3D11_MAP_READ, 0, &mapped))) {
				const int                 step = desc.Width > 1600 ? 2 : 1;
				const int                 w = int(desc.Width) / step, h = int(desc.Height) / step;
				std::vector<std::uint8_t> rgb(std::size_t(w) * h * 3);
				for (int y = 0; y < h; ++y) {
					const auto* row = static_cast<const std::uint8_t*>(mapped.pData) + std::size_t(y * step) * mapped.RowPitch;
					for (int x = 0; x < w; ++x) {
						const auto* p = row + std::size_t(x * step) * 4;
						auto*       q = &rgb[(std::size_t(y) * w + x) * 3];
						q[0] = bgra ? p[2] : p[0];
						q[1] = p[1];
						q[2] = bgra ? p[0] : p[2];
					}
				}
				a_context->Unmap(staging, 0);
				WritePng(*dir / "skycraft_capture.png", w, h, rgb);
				logger::info("capture: saved {}x{} frame ({} sections, {} entities, depth {})", w, h, sections.size(), entities.count,
					!depthKnown ? "unknown" : !depthUsable ? "flat" : depthReversed ? "reversed" : "standard");
			}
			Release(staging);
		}
	}
}
