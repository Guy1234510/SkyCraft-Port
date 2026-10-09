// Exercise the actual embedded PSMain on D3D11 WARP, without starting Skyrim.
#include <d3d11.h>
#include <d3dcompiler.h>
#include <d3d11shader.h>
#include <wrl/client.h>
#include <fstream>
#include <iostream>
#include <vector>
#include <cstring>
#include <cmath>
#include <stdexcept>
#include <string>
#include <array>
#include "../skse/src/RenderRaster.h"
using Microsoft::WRL::ComPtr;
void check(HRESULT hr) { if (FAILED(hr)) throw std::runtime_error("D3D failure " + std::to_string(hr)); }
ComPtr<ID3DBlob> compile(const std::string& source, const char* entry, const char* target) {
    ComPtr<ID3DBlob> blob, error;
    const auto hr = D3DCompile(source.data(), source.size(), nullptr, nullptr, nullptr, entry, target, 0, 0, &blob, &error);
    if (FAILED(hr) && error) std::cerr << (char*)error->GetBufferPointer();
    check(hr); return blob;
}
int main(int argc, char** argv) { try {
    if (argc != 2) throw std::runtime_error("Pass WorldRender.cpp path");
    std::ifstream file(argv[1]);
    std::string source((std::istreambuf_iterator<char>(file)), {}), shader;
    auto start = source.find("constexpr char kShader[]"), end = source.find(")\";", start);
    for (auto p = source.find("R\"(", start); p < end; p = source.find("R\"(", p + 1)) {
        auto q = source.find(")\"", p + 3); shader += source.substr(p + 3, q - p - 3); p = q;
    }
    auto pixel = compile(shader, "PSMain", "ps_5_0");
    auto shadowPixel = compile(shader, "ShadowPS", "ps_5_0");
    const std::string vertex = R"(
cbuffer Test : register(b1) { float4 test; float4 winding; };
struct Out { float4 pos:SV_Position; float2 uv:TEXCOORD0; float4 color:COLOR0;
float2 light:TEXCOORD1; nointerpolation uint flags:TEXCOORD2; float viewZ:TEXCOORD3;
float3 rel:TEXCOORD4; float4 curClip:TEXCOORD5; float4 prevClip:TEXCOORD6; };
Out VS(uint id:SV_VertexID) { Out o=(Out)0;
if (winding.x == 0 && id != 0) id = 3-id;
float2 uv=float2((id<<1)&2,id&2);
o.pos=float4(uv*float2(2,-2)+float2(-1,1),0.7,1); o.color=float4(0,1,0,test.z);
o.flags=(uint)test.w; o.viewZ=test.x; o.rel=float3(uv,test.y); o.uv=0.5;
o.curClip=o.prevClip=float4(0,0,0.7,1); return o; }
struct ShadowOut { float4 pos:SV_Position; float2 uv:TEXCOORD0;
nointerpolation uint flags:TEXCOORD1; };
ShadowOut TestShadowVS(uint id:SV_VertexID) { Out v=VS(id);
ShadowOut o; o.pos=v.pos; o.uv=v.uv; o.flags=v.flags; return o; }
)";
    auto vert = compile(vertex, "VS", "vs_5_0");
    auto shadowVert = compile(vertex, "TestShadowVS", "vs_5_0");
    ComPtr<ID3D11Device> dev; ComPtr<ID3D11DeviceContext> ctx;
    check(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION, &dev, nullptr, &ctx));
    ComPtr<ID3D11PixelShader> ps; ComPtr<ID3D11VertexShader> vs;
    check(dev->CreatePixelShader(pixel->GetBufferPointer(), pixel->GetBufferSize(), nullptr, &ps));
    check(dev->CreateVertexShader(vert->GetBufferPointer(), vert->GetBufferSize(), nullptr, &vs));
    ComPtr<ID3D11PixelShader> shadowPs; ComPtr<ID3D11VertexShader> shadowVs;
    check(dev->CreatePixelShader(shadowPixel->GetBufferPointer(), shadowPixel->GetBufferSize(), nullptr, &shadowPs));
    check(dev->CreateVertexShader(shadowVert->GetBufferPointer(), shadowVert->GetBufferSize(), nullptr, &shadowVs));
    ComPtr<ID3D11ShaderReflection> reflect;
    check(D3DReflect(pixel->GetBufferPointer(), pixel->GetBufferSize(), IID_PPV_ARGS(&reflect)));
    auto frame = reflect->GetConstantBufferByName("Frame"); D3D11_SHADER_BUFFER_DESC fd{}; check(frame->GetDesc(&fd));
    std::vector<char> constants(fd.Size);
    auto set = [&](const char* name, float a, float b, float c, float d) {
        D3D11_SHADER_VARIABLE_DESC vd{}; check(frame->GetVariableByName(name)->GetDesc(&vd));
        float values[]{a,b,c,d}; std::memcpy(constants.data()+vd.StartOffset, values, sizeof(values));
    };
    D3D11_BUFFER_DESC bd{}; bd.ByteWidth=fd.Size; bd.Usage=D3D11_USAGE_DEFAULT; bd.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    ComPtr<ID3D11Buffer> cb, test; check(dev->CreateBuffer(&bd,nullptr,&cb)); bd.ByteWidth=160; check(dev->CreateBuffer(&bd,nullptr,&test));
    auto texture = [&](DXGI_FORMAT fmt, UINT bind, const void* data, UINT pitch) {
        D3D11_TEXTURE2D_DESC td{}; td.Width=td.Height=td.MipLevels=td.ArraySize=td.SampleDesc.Count=1;
        td.Format=fmt; td.Usage=D3D11_USAGE_DEFAULT; td.BindFlags=bind;
        D3D11_SUBRESOURCE_DATA init{data,pitch,0}; ComPtr<ID3D11Texture2D> tex;
        check(dev->CreateTexture2D(&td,data?&init:nullptr,&tex)); return tex;
    };
    float backgroundDepth=0.9f, water=0.5f;
    auto opaque=texture(DXGI_FORMAT_R32_TYPELESS,D3D11_BIND_SHADER_RESOURCE|D3D11_BIND_DEPTH_STENCIL,&backgroundDepth,4);
    auto surface=texture(DXGI_FORMAT_R32_FLOAT,D3D11_BIND_SHADER_RESOURCE,&water,4);
    auto depth=texture(DXGI_FORMAT_R32_TYPELESS,D3D11_BIND_SHADER_RESOURCE|D3D11_BIND_DEPTH_STENCIL,nullptr,4);
    auto output=texture(DXGI_FORMAT_R32G32B32A32_FLOAT,D3D11_BIND_RENDER_TARGET,nullptr,16);
    D3D11_SHADER_RESOURCE_VIEW_DESC svd{}; svd.Format=DXGI_FORMAT_R32_FLOAT; svd.ViewDimension=D3D11_SRV_DIMENSION_TEXTURE2D; svd.Texture2D.MipLevels=1;
    unsigned skinPixel=0xff00ff00;
    auto skin=texture(DXGI_FORMAT_R8G8B8A8_UNORM,D3D11_BIND_SHADER_RESOURCE,&skinPixel,4);
    ComPtr<ID3D11ShaderResourceView> opaqueSrv,surfaceSrv,skinSrv;
    check(dev->CreateShaderResourceView(skin.Get(),nullptr,&skinSrv));
    check(dev->CreateShaderResourceView(opaque.Get(),&svd,&opaqueSrv)); check(dev->CreateShaderResourceView(surface.Get(),nullptr,&surfaceSrv));
    ComPtr<ID3D11RenderTargetView> rtv; check(dev->CreateRenderTargetView(output.Get(),nullptr,&rtv));
    D3D11_DEPTH_STENCIL_VIEW_DESC dvd{}; dvd.Format=DXGI_FORMAT_D32_FLOAT; dvd.ViewDimension=D3D11_DSV_DIMENSION_TEXTURE2D;
    ComPtr<ID3D11DepthStencilView> dsv; check(dev->CreateDepthStencilView(depth.Get(),&dvd,&dsv));
    D3D11_DEPTH_STENCIL_DESC dsd{}; dsd.DepthEnable=TRUE; dsd.DepthWriteMask=D3D11_DEPTH_WRITE_MASK_ALL; dsd.DepthFunc=D3D11_COMPARISON_LESS_EQUAL;
    ComPtr<ID3D11DepthStencilState> ds; check(dev->CreateDepthStencilState(&dsd,&ds));
    D3D11_SAMPLER_DESC sm{}; sm.Filter=D3D11_FILTER_MIN_MAG_MIP_POINT; sm.AddressU=sm.AddressV=sm.AddressW=D3D11_TEXTURE_ADDRESS_CLAMP; sm.MaxLOD=D3D11_FLOAT32_MAX;
    ComPtr<ID3D11SamplerState> sampler; check(dev->CreateSamplerState(&sm,&sampler));
    D3D11_BLEND_DESC bl{}; bl.RenderTarget[0].BlendEnable=TRUE; bl.RenderTarget[0].SrcBlend=D3D11_BLEND_SRC_ALPHA;
    bl.RenderTarget[0].DestBlend=D3D11_BLEND_INV_SRC_ALPHA; bl.RenderTarget[0].BlendOp=bl.RenderTarget[0].BlendOpAlpha=D3D11_BLEND_OP_ADD;
    bl.RenderTarget[0].SrcBlendAlpha=D3D11_BLEND_ONE; bl.RenderTarget[0].DestBlendAlpha=D3D11_BLEND_ZERO; bl.RenderTarget[0].RenderTargetWriteMask=15;
    ComPtr<ID3D11BlendState> blend; check(dev->CreateBlendState(&bl,&blend));
    // Test both triangle windings through the production pixel shader. The
    // production mesh pass additionally selects D3D11_CULL_BACK per batch.
    auto rsd=skycraft::ModelRasterizer(false);
    ComPtr<ID3D11RasterizerState> noCull, backCull, shadowNoCull, shadowBackCull, fluidCull;
    check(dev->CreateRasterizerState(&rsd,&noCull));
    rsd=skycraft::ModelRasterizer(true);
    check(dev->CreateRasterizerState(&rsd,&backCull));
    rsd=skycraft::ModelRasterizer(false,true);
    check(dev->CreateRasterizerState(&rsd,&shadowNoCull));
    rsd=skycraft::ModelRasterizer(true,true);
    check(dev->CreateRasterizerState(&rsd,&shadowBackCull));
    rsd=skycraft::FluidRasterizer();
    check(dev->CreateRasterizerState(&rsd,&fluidCull));
    auto read = [&](ID3D11Texture2D* tex) {
        D3D11_TEXTURE2D_DESC td{}; tex->GetDesc(&td); td.Usage=D3D11_USAGE_STAGING; td.BindFlags=0; td.CPUAccessFlags=D3D11_CPU_ACCESS_READ;
        ComPtr<ID3D11Texture2D> stage; check(dev->CreateTexture2D(&td,nullptr,&stage)); ctx->CopyResource(stage.Get(),tex);
        D3D11_MAPPED_SUBRESOURCE mapped{}; check(ctx->Map(stage.Get(),0,D3D11_MAP_READ,0,&mapped));
        float value=*(float*)mapped.pData; ctx->Unmap(stage.Get(),0); return value;
    };
    auto run = [&](const char* name, float mode, float viewZ, float cameraUnder, float relZ, bool visible, float avatarTag=0, float surfaceValue=0.5f, bool invisibleDepthWrite=false, unsigned flags=4, float vertexAlpha=1, unsigned textureAlpha=255, float effectZ=0, bool coverageOnly=false, bool reversedWinding=false, bool rasterCulling=false, bool shadowPass=false, bool fluidPass=false) {
        ctx->OMSetRenderTargets(0,nullptr,nullptr); ctx->CopyResource(depth.Get(),opaque.Get());
        ctx->UpdateSubresource(surface.Get(),0,nullptr,&surfaceValue,4,0);
        set("depthParams",1,100,0,1); set("screen",1,1,coverageOnly?1.0f:0.0f,0); set("renderMode",1,0,mode,cameraUnder); set("waterPlane",1,1,0,0);
        ctx->UpdateSubresource(cb.Get(),0,nullptr,constants.data(),0,0);
        // The real Object b1 and test VS b1 need distinct constants.
        ComPtr<ID3D11Buffer> vertexTest; check(dev->CreateBuffer(&bd,nullptr,&vertexTest));
        float tv[40]{}; tv[0]=viewZ; tv[1]=relZ; tv[2]=vertexAlpha; tv[3]=float(flags);
        tv[4]=reversedWinding?1.0f:0.0f;
        ctx->UpdateSubresource(vertexTest.Get(),0,nullptr,tv,0,0);
        tv[3]=avatarTag; ctx->UpdateSubresource(test.Get(),0,nullptr,tv,0,0);
        skinPixel=(textureAlpha<<24)|0x00ff00; ctx->UpdateSubresource(skin.Get(),0,nullptr,&skinPixel,4,0);
        float bg[]{0.2f,0.2f,0.2f,1}; ctx->ClearRenderTargetView(rtv.Get(),bg);
        auto out=rtv.Get(); ctx->OMSetRenderTargets(1,&out,dsv.Get()); ctx->OMSetDepthStencilState(ds.Get(),0); ctx->OMSetBlendState(blend.Get(),nullptr,~0u);
        D3D11_VIEWPORT vp{0,0,1,1,0,1}; ctx->RSSetViewports(1,&vp); ctx->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        ctx->RSSetState(fluidPass?fluidCull.Get():shadowPass?(rasterCulling?shadowBackCull.Get():shadowNoCull.Get()):(rasterCulling?backCull.Get():noCull.Get()));
        ctx->VSSetShader(shadowPass?shadowVs.Get():vs.Get(),nullptr,0); ctx->PSSetShader(shadowPass?shadowPs.Get():ps.Get(),nullptr,0); auto t=test.Get(), v=vertexTest.Get(); ctx->VSSetConstantBuffers(1,1,&v);
        auto c=cb.Get(); ctx->PSSetConstantBuffers(0,1,&c); auto o=opaqueSrv.Get(), s=surfaceSrv.Get(); ctx->PSSetShaderResources(1,1,&o); ctx->PSSetShaderResources(6,1,&s);
        ctx->PSSetConstantBuffers(1,1,&t);
        auto sam=sampler.Get(); auto sk=skinSrv.Get(); ctx->PSSetSamplers(0,1,&sam); ctx->PSSetSamplers(1,1,&sam); ctx->PSSetShaderResources(0,1,&sk);
        ctx->Draw(3,0); ctx->OMSetRenderTargets(0,nullptr,nullptr);
        float colour=read(output.Get()), copiedDepth=read(depth.Get());
        if (!std::isfinite(colour) || !std::isfinite(copiedDepth) || std::fabs(colour-(visible?0:0.2f))>0.001f || std::fabs(copiedDepth-((visible||invisibleDepthWrite)?0.7f:0.9f))>0.001f || std::fabs(read(opaque.Get())-0.9f)>0.001f || std::fabs(read(surface.Get())-surfaceValue)>0.001f)
            throw std::runtime_error(std::string(name)+" failed colour/depth/isolation");
        std::cout << name << " passed\n";
    };
    run("Submerged foreground rejected",1,4,0,0,false);
    run("Submerged refraction opaque + private depth",2,4,0,0,true);
    run("Above water foreground retained",1,1.5f,0,0,true);
    run("Above water refraction rejected",2,1.5f,0,0,false);
    run("Dry flying avatar survives mismatched surface depth",1,4,0,2,true,1);
    run("Dry flying avatar never enters water refraction",2,4,0,2,false,1);
    run("Behind terrain rejected",2,12,0,0,false);
    run("Underwater camera opaque refraction",2,1.5f,1,0,true);
    run("Underwater camera opaque blocks retained",1,1.5f,1,0,true);
    run("Underwater camera deep opaque blocks retained",1,4,1,0,true);
    run("Underwater camera terrain occludes blocks",1,12,1,0,false);
    run("Underwater camera above plane rejected",2,1.5f,1,2,false);
    run("Dry avatar occludes distant native mist",1,1.5f,0,0,true,1,0.9f);
    run("Above-water camera submerged avatar retains DLL54 rejection",1,4,0,0,false,1);
    run("Underwater camera avatar stays opaque",1,1.5f,1,0,true,1,0.5f,false,121);
    run("Underwater camera deep avatar stays opaque",1,4,1,0,true,1,0.5f,false,121);
    run("Underwater camera terrain still occludes avatar",1,12,1,0,false,1,0.5f,false,121);
    run("Underwater camera solid mob stays opaque",1,4,1,0,true,1,0.5f,false,121);
    run("Above-water camera submerged mob retains water clipping",1,4,0,0,false,1,0.5f,false,121);
    run("Underwater camera above-surface head retains water compositing",1,4,1,2,false,1,0.5f,false,121);
    run("Dry blocks remain opaque after native fog",1,1.5f,0,0,true,0,0.9f);
    run("Dry mob remains opaque near native smoke",1,1.5f,0,0,true,1,0.9f,false,121);
    run("Dry block is opaque despite vertex fade",0,1.5f,0,0,true,0,0.9f,false,121,0.15f);
    run("Exported skin flags121 opaque before transparency despite vertex fade",0,1.5f,0,0,true,1,0.9f,false,121,0.15f);
    run("Skin transparent background remains cutout",0,1.5f,0,0,false,1,0.9f,false,121,1,0);
    run("Skin partial texture alpha remains opaque",0,1.5f,0,0,true,1,0.9f,false,121,1,192);
    run("Ordinary low-alpha texture remains cutout",0,1.5f,0,0,false,1,0.9f,false,121,1,32);
    run("Glowing solid staff core keeps low-alpha source coverage",0,1.5f,0,0,true,1,0.9f,false,121|4096|16384,1,32);
    run("Glowing staff material remains occluded by Skyrim",0,12,0,0,false,1,0.9f,false,121|4096|16384,1,32);
    run("Glowing staff material retains underwater clipping",1,4,0,0,false,1,0.5f,false,121|4096|16384,1,32);
    run("Textured submerged body enters refraction",2,4,0,0,true,1,0.5f,false,121);
    run("Above-water camera textured body cannot overlay water",1,4,0,0,false,1,0.5f,false,121);
    run("Opaque mob remains opaque before isolated smoke composition",1,1.5f,0,10,true,1,0.9f,false,121,1,255,3);
    run("Native effect behind mob cannot make it transparent",1,1.5f,0,10,true,1,0.9f,false,121,1,255,30);
    // The new dry-body depth merge must not change native water's stencil3.
    auto nativeDepth=texture(DXGI_FORMAT_R24G8_TYPELESS,D3D11_BIND_DEPTH_STENCIL,nullptr,4);
    dvd.Format=DXGI_FORMAT_D24_UNORM_S8_UINT;
    ComPtr<ID3D11DepthStencilView> nativeDsv; check(dev->CreateDepthStencilView(nativeDepth.Get(),&dvd,&nativeDsv));
    auto stencilMerge = [&](bool submerged) {
        ctx->OMSetRenderTargets(0,nullptr,nullptr);
        float surfaceValue=submerged?0.5f:0.9f;
        ctx->UpdateSubresource(surface.Get(),0,nullptr,&surfaceValue,4,0);
        set("renderMode",1,0,1,0); ctx->UpdateSubresource(cb.Get(),0,nullptr,constants.data(),0,0);
        float tv[40]{}; tv[0]=submerged?4.0f:1.5f; tv[2]=1; tv[3]=121;
        ComPtr<ID3D11Buffer> vertexTest; check(dev->CreateBuffer(&bd,nullptr,&vertexTest));
        ctx->UpdateSubresource(vertexTest.Get(),0,nullptr,tv,0,0);
        tv[3]=1; ctx->UpdateSubresource(test.Get(),0,nullptr,tv,0,0);
        auto v=vertexTest.Get(); ctx->VSSetConstantBuffers(1,1,&v);
        ctx->ClearDepthStencilView(nativeDsv.Get(),D3D11_CLEAR_DEPTH|D3D11_CLEAR_STENCIL,surfaceValue,3);
        ctx->OMSetRenderTargets(0,nullptr,nativeDsv.Get()); ctx->Draw(3,0);
        ctx->OMSetRenderTargets(0,nullptr,nullptr);
        D3D11_TEXTURE2D_DESC td{}; nativeDepth->GetDesc(&td); td.Usage=D3D11_USAGE_STAGING;
        td.BindFlags=0; td.CPUAccessFlags=D3D11_CPU_ACCESS_READ;
        ComPtr<ID3D11Texture2D> stage; check(dev->CreateTexture2D(&td,nullptr,&stage)); ctx->CopyResource(stage.Get(),nativeDepth.Get());
        D3D11_MAPPED_SUBRESOURCE mapped{}; check(ctx->Map(stage.Get(),0,D3D11_MAP_READ,0,&mapped));
        const unsigned value=*(unsigned*)mapped.pData; ctx->Unmap(stage.Get(),0);
        const float d=float(value&0xffffff)/16777215.0f;
        if ((value>>24)!=3 || std::fabs(d-(submerged?0.5f:0.7f))>0.00001f)
            throw std::runtime_error("Native dry avatar depth / submerged rejection / stencil preservation failed");
        std::cout<<(submerged?"Native underwater depth/stencil preserved":"Native dry avatar depth merged, stencil preserved")<<" passed\n";
    };
    stencilMerge(false); stencilMerge(true);
    run("Fast coverage-only skin writes depth without shading",1,1.5f,0,2,false,1,0.9f,true,121,1,255,0,true);
    run("Fast coverage-only cutout retains skin holes",1,1.5f,0,2,false,1,0.9f,false,121,1,0,0,true);
    run("Fast coverage-only merge preserves water",1,4,0,0,false,1,0.5f,false,121,1,255,0,true);
    run("Fast coverage-only merge retains opaque glowing staff tip",1,1.5f,0,2,false,1,0.9f,true,121|4096|16384,1,32,0,true);
    run("Staff ring outward face keeps Minecraft winding",0,1.5f,0,0,true,1,0.9f,false,121|32768);
    run("Coincident ring inward face cannot compete for depth",0,1.5f,0,0,false,1,0.9f,false,121|32768,1,255,0,false,true);
    run("Ordinary two-sided materials retain inward faces",0,1.5f,0,0,true,1,0.9f,false,121,1,255,0,false,true);
    run("Staff ring front face survives batch raster culling",0,1.5f,0,0,true,1,0.9f,false,121|32768,1,255,0,false,false,true);
    run("Staff ring back face rejected by batch raster culling",0,1.5f,0,0,false,1,0.9f,false,121|32768,1,255,0,false,true,true);
    run("Depth-only ring front face retains terrain occlusion",1,1.5f,0,2,false,1,0.9f,true,121|32768,1,255,0,true);
    run("Depth-only ring back face cannot occlude native scene",1,1.5f,0,2,false,1,0.9f,false,121|32768,1,255,0,true,true);
    run("Culled glowing solid staff core keeps source coverage",0,1.5f,0,0,true,1,0.9f,false,121|4096|16384|32768,1,32);
    run("Culled glowing solid staff core rejects inward coverage",0,1.5f,0,0,false,1,0.9f,false,121|4096|16384|32768,1,32,0,false,true);
    run("Shadow coverage accepts ring front face",0,1.5f,0,0,false,1,0.9f,true,121|32768,1,255,0,false,false,false,true);
    run("Shadow coverage rejects coincident ring back face",0,1.5f,0,0,false,1,0.9f,false,121|32768,1,255,0,false,true,false,true);
    run("Shadow coverage keeps ordinary two-sided materials",0,1.5f,0,0,false,1,0.9f,true,121,1,255,0,false,true,false,true);
    run("Shadow coverage keeps cutout holes",0,1.5f,0,0,false,1,0.9f,false,121|32768,1,0,0,false,false,false,true);
    run("Shadow coverage retains glowing solid alpha",0,1.5f,0,0,false,1,0.9f,true,121|4096|16384|32768,1,32,0,false,false,false,true);
    run("Fluid pass retains its original clockwise front",0,1.5f,0,0,true,0,0.9f,false,4,1,255,0,false,true,false,false,true);
    run("Fluid pass still rejects its counterclockwise back",0,1.5f,0,0,false,0,0.9f,false,4,1,255,0,false,false,false,false,true);

    // Use the actual model vertex shaders, exported quad triangle order and axis conversion.
    // A fullscreen triangle alone cannot establish Minecraft's outward-facing convention.
    auto modelVertex=compile(shader,"VSMain","vs_5_0");
    auto modelShadowVertex=compile(shader,"ShadowVS","vs_5_0");
    ComPtr<ID3D11VertexShader> modelVs,modelShadowVs;
    check(dev->CreateVertexShader(modelVertex->GetBufferPointer(),modelVertex->GetBufferSize(),nullptr,&modelVs));
    check(dev->CreateVertexShader(modelShadowVertex->GetBufferPointer(),modelShadowVertex->GetBufferSize(),nullptr,&modelShadowVs));
    const D3D11_INPUT_ELEMENT_DESC elements[]{
        {"POSITION",0,DXGI_FORMAT_R32G32B32_FLOAT,0,0,D3D11_INPUT_PER_VERTEX_DATA,0},
        {"TEXCOORD",0,DXGI_FORMAT_R32G32_FLOAT,0,12,D3D11_INPUT_PER_VERTEX_DATA,0},
        {"COLOR",0,DXGI_FORMAT_R8G8B8A8_UNORM,0,20,D3D11_INPUT_PER_VERTEX_DATA,0},
        {"TEXCOORD",1,DXGI_FORMAT_R32_UINT,0,24,D3D11_INPUT_PER_VERTEX_DATA,0},
        {"TEXCOORD",2,DXGI_FORMAT_R32_UINT,0,28,D3D11_INPUT_PER_VERTEX_DATA,0}
    };
    ComPtr<ID3D11InputLayout> modelLayout;
    check(dev->CreateInputLayout(elements,5,modelVertex->GetBufferPointer(),modelVertex->GetBufferSize(),&modelLayout));
    auto legacyDesc=skycraft::ModelRasterizer(false); legacyDesc.FrontCounterClockwise=FALSE;
    ComPtr<ID3D11RasterizerState> legacyNoCull;
    check(dev->CreateRasterizerState(&legacyDesc,&legacyNoCull));
    using V3=std::array<float,3>;
    const V3 normals[]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    auto sky=[](V3 v){return V3{v[0],-v[2],v[1]};};
    auto cross=[](V3 a,V3 b){return V3{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};};
    auto matrix=[&](const char* name,const float* values){
        D3D11_SHADER_VARIABLE_DESC vd{}; check(frame->GetVariableByName(name)->GetDesc(&vd));
        std::memcpy(constants.data()+vd.StartOffset,values,64);
    };
    struct ModelVertex { float x,y,z,u,v; unsigned color,light,flags; };
    static_assert(sizeof(ModelVertex)==32);
    D3D11_BUFFER_DESC modelBd{}; modelBd.ByteWidth=6*sizeof(ModelVertex);
    modelBd.Usage=D3D11_USAGE_DEFAULT; modelBd.BindFlags=D3D11_BIND_VERTEX_BUFFER;
    ComPtr<ID3D11Buffer> modelBuffer; check(dev->CreateBuffer(&modelBd,nullptr,&modelBuffer));
    unsigned cases=0;
    for(const auto n:normals) for(int mode=0;mode<12;mode++) {
        const bool shadow=mode>=7 && mode<=9;
        const bool reverse=mode==1 || mode==3 || mode==4 || mode==6 || mode==8 || mode==9 || mode==11;
        const bool twoSided=mode==4 || mode==9;
        const bool coverage=mode==5 || mode==6;
        const bool culled=mode==2 || mode==3 || mode==7 || mode==8;
        const bool visible=mode==0 || mode==2 || mode==4 || mode==5 || mode==7 || mode==9 || mode==11;
        const V3 u=n[0]!=0?V3{0,1,0}:V3{1,0,0}, v=cross(n,u);
        const auto right=sky(u), up=sky(v), outward=sky(n);
        float projection[16]{};
        for(int k=0;k<3;k++) {
            projection[k]=right[k]/70; projection[4+k]=up[k]/70;
            projection[8+k]=-outward[k]/70*100/99; projection[12+k]=-outward[k]/70;
        }
        projection[11]=-100.0f/99;
        matrix("viewProj",projection); matrix("prevViewProj",projection); matrix("lightViewProj",projection);
        set("depthParams",1,100,0,0); set("screen",1,1,coverage?1.0f:0.0f,0);
        set("renderMode",1,0,0,0); set("waterPlane",0,0,0,0);
        ctx->UpdateSubresource(cb.Get(),0,nullptr,constants.data(),0,0);
        float object[40]{};
        for(int k=0;k<3;k++) object[k]=object[4+k]=-outward[k]*210;
        for(int k=0;k<4;k++) object[8+k*5]=object[24+k*5]=1;
        ctx->UpdateSubresource(test.Get(),0,nullptr,object,0,0);
        const int forward[]{0,1,2,0,2,3}, backward[]{0,2,1,0,3,2};
        const float qu[]{-.5f,.5f,.5f,-.5f}, qv[]{-.5f,-.5f,.5f,.5f};
        ModelVertex verts[6]{};
        for(int k=0;k<6;k++) {
            int q=(reverse?backward:forward)[k];
            verts[k]={n[0]*.5f+u[0]*qu[q]+v[0]*qv[q],n[1]*.5f+u[1]*qu[q]+v[1]*qv[q],
                n[2]*.5f+u[2]*qu[q]+v[2]*qv[q],.5f,.5f,0xff00ff00,0,121u|(twoSided?0u:32768u)};
        }
        ctx->UpdateSubresource(modelBuffer.Get(),0,nullptr,verts,0,0);
        ctx->OMSetRenderTargets(0,nullptr,nullptr); ctx->CopyResource(depth.Get(),opaque.Get());
        float bg[]{.2f,.2f,.2f,1}; ctx->ClearRenderTargetView(rtv.Get(),bg);
        auto target=rtv.Get(); ctx->OMSetRenderTargets(1,&target,dsv.Get());
        ctx->IASetInputLayout(modelLayout.Get()); auto buffer=modelBuffer.Get(); UINT stride=sizeof(ModelVertex),zero=0;
        ctx->IASetVertexBuffers(0,1,&buffer,&stride,&zero);
        ctx->VSSetShader(shadow?modelShadowVs.Get():modelVs.Get(),nullptr,0);
        ctx->PSSetShader(shadow?shadowPs.Get():ps.Get(),nullptr,0);
        auto c=cb.Get(),o=test.Get(); ctx->VSSetConstantBuffers(0,1,&c); ctx->VSSetConstantBuffers(1,1,&o);
        ctx->PSSetConstantBuffers(0,1,&c); ctx->PSSetConstantBuffers(1,1,&o);
        ctx->RSSetState(mode>=10?legacyNoCull.Get():shadow?(culled?shadowBackCull.Get():shadowNoCull.Get()):(culled?backCull.Get():noCull.Get()));
        ctx->Draw(6,0); ctx->OMSetRenderTargets(0,nullptr,nullptr);
        const float expectedDepth=visible?100.0f/99*(1-1/2.5f):.9f;
        const float expectedColor=visible&&!coverage&&!shadow?0:.2f;
        const auto actualDepth=read(depth.Get()),actualColor=read(output.Get());
        if(!std::isfinite(actualDepth) || !std::isfinite(actualColor) ||
            std::fabs(actualDepth-expectedDepth)>.001f || std::fabs(actualColor-expectedColor)>.001f)
            throw std::runtime_error("Actual model face/projection case "+std::to_string(cases)+" failed: depth="+
                std::to_string(actualDepth)+", colour="+std::to_string(actualColor));
        cases++;
    }
    std::cout<<cases<<" actual model-VS cardinal-face colour/depth/shadow and legacy-inversion cases passed\n";
    return 0;
} catch(const std::exception& e) { std::cerr << e.what() << '\n'; return 1; } }
