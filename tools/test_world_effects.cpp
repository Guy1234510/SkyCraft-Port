// D3D11 WARP regressions for the actual native potion/compositor shaders.
#include <d3d11.h>
#include <d3dcompiler.h>
#include <d3d11shader.h>
#include <wrl/client.h>
#include <fstream>
#include <iostream>
#include <vector>
#include <array>
#include <cstring>
#include <cmath>
#include <stdexcept>
#include <string>
using Microsoft::WRL::ComPtr;
void check(HRESULT hr) { if (FAILED(hr)) throw std::runtime_error("D3D failure " + std::to_string(hr)); }
std::string shader(const char* path) {
    std::ifstream file(path); std::string source((std::istreambuf_iterator<char>(file)), {});
    auto start = source.find("constexpr char kShader[]"), end = source.find(")\";", start);
    if (start == std::string::npos || end == std::string::npos) throw std::runtime_error("Missing shader");
    std::string result;
    for (auto p=source.find("R\"(",start);p<end;p=source.find("R\"(",p+1)) {
        auto q=source.find(")\"",p+3); result+=source.substr(p+3,q-p-3); p=q;
    }
    return result;
}
ComPtr<ID3DBlob> compile(const std::string& source, const char* entry, const char* target) {
    ComPtr<ID3DBlob> code, error;
    auto hr = D3DCompile(source.data(), source.size(), nullptr, nullptr, nullptr, entry, target, D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, &code, &error);
    if (FAILED(hr) && error) std::cerr << (char*)error->GetBufferPointer(); check(hr); return code;
}
int main(int argc, char** argv) { try {
    if (argc != 3) throw std::runtime_error("Pass Overlay.cpp and WorldRender.cpp");
    ComPtr<ID3D11Device> device; ComPtr<ID3D11DeviceContext> context;
    check(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION, &device, nullptr, &context));
    auto texture = [&](DXGI_FORMAT format, UINT bind, const void* data, UINT pitch) {
        D3D11_TEXTURE2D_DESC d{}; d.Width=d.Height=d.MipLevels=d.ArraySize=d.SampleDesc.Count=1;
        d.Format=format; d.Usage=D3D11_USAGE_DEFAULT; d.BindFlags=bind;
        D3D11_SUBRESOURCE_DATA init{data,pitch,0}; ComPtr<ID3D11Texture2D> t;
        check(device->CreateTexture2D(&d,data?&init:nullptr,&t)); return t;
    };
    auto read = [&](ID3D11Texture2D* t) {
        D3D11_TEXTURE2D_DESC d{}; t->GetDesc(&d); d.Usage=D3D11_USAGE_STAGING; d.BindFlags=0; d.CPUAccessFlags=D3D11_CPU_ACCESS_READ;
        ComPtr<ID3D11Texture2D> stage; check(device->CreateTexture2D(&d,nullptr,&stage)); context->CopyResource(stage.Get(),t);
        D3D11_MAPPED_SUBRESOURCE map{}; check(context->Map(stage.Get(),0,D3D11_MAP_READ,0,&map));
        std::array<float,4> values{}; std::memcpy(values.data(),map.pData,16); context->Unmap(stage.Get(),0); return values;
    };
    auto expect = [&](const char* name, const std::array<float,4>& actual, const std::array<float,4>& expected) {
        for (int i=0;i<3;++i) if (!std::isfinite(actual[i]) || std::fabs(actual[i]-expected[i])>0.003f)
            throw std::runtime_error(std::string(name)+" channel "+std::to_string(i)+": "+std::to_string(actual[i])+" expected "+std::to_string(expected[i]));
        std::cout<<name<<" passed\n";
    };
    auto source=shader(argv[1]); auto vb=compile(source,"VSMain","vs_5_0"), pb=compile(source,"PSWorldEffects","ps_5_0");
    ComPtr<ID3D11VertexShader> vs; ComPtr<ID3D11PixelShader> ps;
    check(device->CreateVertexShader(vb->GetBufferPointer(),vb->GetBufferSize(),nullptr,&vs));
    check(device->CreatePixelShader(pb->GetBufferPointer(),pb->GetBufferSize(),nullptr,&ps));
    float input[]{0.1f,0.2f,0.05f,1}, sceneDepth=1;
    auto colour=texture(DXGI_FORMAT_R32G32B32A32_FLOAT,D3D11_BIND_SHADER_RESOURCE,input,16);
    auto depth=texture(DXGI_FORMAT_R32_FLOAT,D3D11_BIND_SHADER_RESOURCE,&sceneDepth,4);
    auto output=texture(DXGI_FORMAT_R32G32B32A32_FLOAT,D3D11_BIND_RENDER_TARGET,nullptr,16);
    ComPtr<ID3D11ShaderResourceView> colourSrv, depthSrv;
    ComPtr<ID3D11RenderTargetView> rtv;
    check(device->CreateShaderResourceView(colour.Get(),nullptr,&colourSrv)); check(device->CreateShaderResourceView(depth.Get(),nullptr,&depthSrv));
    check(device->CreateRenderTargetView(output.Get(),nullptr,&rtv));
    ComPtr<ID3D11ShaderReflection> reflection; check(D3DReflect(pb->GetBufferPointer(),pb->GetBufferSize(),IID_PPV_ARGS(&reflection)));
    auto buffer=reflection->GetConstantBufferByName("Params"); D3D11_SHADER_BUFFER_DESC cbDesc{}; check(buffer->GetDesc(&cbDesc));
    std::vector<char> constants(cbDesc.Size);
    auto set=[&](const char* name,float a,float b,float c,float d) {
        D3D11_SHADER_VARIABLE_DESC desc{}; check(buffer->GetVariableByName(name)->GetDesc(&desc));
        float values[]{a,b,c,d}; std::memcpy(constants.data()+desc.StartOffset,values,16);
    };
    D3D11_BUFFER_DESC desc{}; desc.ByteWidth=cbDesc.Size; desc.Usage=D3D11_USAGE_DEFAULT; desc.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    ComPtr<ID3D11Buffer> cb; check(device->CreateBuffer(&desc,nullptr,&cb));
    D3D11_SAMPLER_DESC samplerDesc{}; samplerDesc.Filter=D3D11_FILTER_MIN_MAG_MIP_POINT;
    samplerDesc.AddressU=samplerDesc.AddressV=samplerDesc.AddressW=D3D11_TEXTURE_ADDRESS_CLAMP; samplerDesc.MaxLOD=D3D11_FLOAT32_MAX;
    ComPtr<ID3D11SamplerState> sampler; check(device->CreateSamplerState(&samplerDesc,&sampler));
    D3D11_VIEWPORT viewport{0,0,1,1,0,1}; context->RSSetViewports(1,&viewport);
    context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    context->VSSetShader(vs.Get(),nullptr,0); context->PSSetShader(ps.Get(),nullptr,0);
    auto out=rtv.Get(); auto constantsBuffer=cb.Get(); auto sm=sampler.Get();
    context->OMSetRenderTargets(1,&out,nullptr); context->PSSetConstantBuffers(0,1,&constantsBuffer); context->PSSetSamplers(0,1,&sm);
    ID3D11ShaderResourceView* resources[]{colourSrv.Get(),depthSrv.Get()}; context->PSSetShaderResources(0,2,resources);
    auto potion=[&](const char* name,float darkness,float pulse,float vision,float distance,bool reversed,std::array<float,4> expected) {
        const float n=15, f=70000, z=distance*70;
        sceneDepth=distance<0?(reversed?0:1):reversed?(n*f/z-n)/(f-n):(f-n*f/z)/(f-n);
        context->UpdateSubresource(depth.Get(),0,nullptr,&sceneDepth,4,0);
        set("effectDepth",n,f,reversed?1.0f:0.0f,1); set("worldEffects",darkness,pulse,vision,256); set("effectProjection",1,1,0,0);
        context->UpdateSubresource(cb.Get(),0,nullptr,constants.data(),0,0); context->Draw(3,0);
        expect(name,read(output.Get()),expected);
    };
    potion("No potion leaves world unchanged",0,0,0,4,false,{.1f,.2f,.05f,1});
    potion("Night vision brightens night and preserves hue",0,0,1,4,false,{.1972f,.2768f,.1583f,1});
    potion("Night vision fade follows vanilla strength",0,0,.5f,4,false,{.1486f,.2384f,.10415f,1});
    potion("Warden darkness preserves nearby world between pulses",1,0,0,4,false,{.1f,.2f,.05f,1});
    potion("Warden pulse darkens nearby world",1,.45f,0,4,false,{.019f,.038f,.0095f,1});
    potion("Warden hides distant world beyond fifteen blocks",1,0,0,20,false,{0,0,0,1});
    potion("Warden uses same distance for reversed depth",1,0,0,20,true,{0,0,0,1});
    potion("Night vision cannot cancel Warden distance fog",1,0,1,20,false,{0,0,0,1});
    potion("Warden darkens the sky",1,0,0,-1,false,{0,0,0,1});
    potion("Warden sky fades smoothly",.5f,0,0,-1,false,{.05f,.1f,.025f,1});
    // Exercise the real compositor: smoke is premultiplied blue over opaque
    // green, even when Skyrim's background before the foreground redraw is red.
    auto compositor=compile(shader(argv[2]),"EffectCompositePS","ps_5_0");
    ComPtr<ID3D11PixelShader> compositePs; check(device->CreatePixelShader(compositor->GetBufferPointer(),compositor->GetBufferSize(),nullptr,&compositePs));
    context->PSSetShader(compositePs.Get(),nullptr,0);
    D3D11_BLEND_DESC blendDesc{}; auto& b=blendDesc.RenderTarget[0];
    b.BlendEnable=TRUE; b.SrcBlend=D3D11_BLEND_ONE; b.DestBlend=D3D11_BLEND_INV_SRC_ALPHA;
    b.BlendOp=b.BlendOpAlpha=D3D11_BLEND_OP_ADD; b.SrcBlendAlpha=D3D11_BLEND_ZERO; b.DestBlendAlpha=D3D11_BLEND_ONE; b.RenderTargetWriteMask=7;
    ComPtr<ID3D11BlendState> blend; check(device->CreateBlendState(&blendDesc,&blend)); context->OMSetBlendState(blend.Get(),nullptr,~0u);
    auto layer=[&](const char* name,std::array<float,4> smoke,std::array<float,4> expected) {
        float body[]{0,1,0,1}; context->ClearRenderTargetView(rtv.Get(),body);
        context->UpdateSubresource(colour.Get(),0,nullptr,smoke.data(),16,0);
        auto s=colourSrv.Get(); context->PSSetShaderResources(7,1,&s); context->Draw(3,0);
        expect(name,read(output.Get()),expected);
    };
    layer("Real smoke alpha covers opaque skin without terrain leaking",{0,0,.5f,.5f},{0,.5f,.5f,1});
    layer("Empty smoke pixels retain opaque skin",{0,0,0,0},{0,1,0,1});
    layer("Opaque smoke fully covers skin",{0,0,1,1},{0,0,1,1});
    layer("Additive fire light retains opaque skin",{.4f,.2f,0,0},{.4f,1.2f,0,1});
    // Execute the actual material-lighting function with no ambient, sun or
    // block light: a sealed Minecraft room must brighten, not only its image.
    auto worldSource=shader(argv[2]);
    worldSource += "\nfloat4 NightLightingProbe(float4 p:SV_Position):SV_Target { VSOut v=(VSOut)0; return float4(Lighting(v,float3(0,0,1),false,0),1); }\n";
    auto lightingCode=compile(worldSource,"NightLightingProbe","ps_5_0");
    ComPtr<ID3D11PixelShader> lightingPs;
    check(device->CreatePixelShader(lightingCode->GetBufferPointer(),lightingCode->GetBufferSize(),nullptr,&lightingPs));
    ComPtr<ID3D11ShaderReflection> lightReflection;
    check(D3DReflect(lightingCode->GetBufferPointer(),lightingCode->GetBufferSize(),IID_PPV_ARGS(&lightReflection)));
    auto frame=lightReflection->GetConstantBufferByName("Frame");
    D3D11_SHADER_BUFFER_DESC frameDesc{}; check(frame->GetDesc(&frameDesc));
    D3D11_SHADER_VARIABLE_DESC screenDesc{}; check(frame->GetVariableByName("screen")->GetDesc(&screenDesc));
    std::vector<float> frameData((frameDesc.Size+15)/16*4,0);
    D3D11_BUFFER_DESC lightingBufferDesc{}; lightingBufferDesc.ByteWidth=UINT(frameData.size()*4);
    lightingBufferDesc.Usage=D3D11_USAGE_DEFAULT; lightingBufferDesc.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    ComPtr<ID3D11Buffer> lightingBuffer; check(device->CreateBuffer(&lightingBufferDesc,nullptr,&lightingBuffer));
    context->PSSetShader(lightingPs.Get(),nullptr,0); context->OMSetBlendState(nullptr,nullptr,~0u);
    auto lightCb=lightingBuffer.Get(); context->PSSetConstantBuffers(0,1,&lightCb);
    for(float strength : {0.0f,0.5f,1.0f}) {
        frameData[screenDesc.StartOffset/4+3]=strength;
        context->UpdateSubresource(lightingBuffer.Get(),0,nullptr,frameData.data(),0,0);
        context->Draw(3,0);
        expect("Night vision material light in a sealed unlit room",read(output.Get()),{.85f*strength,.85f*strength,.85f*strength,1});
    }
    // Run the production Lighting function with moving/static object uniforms.
    // Moving geometry must track the same daylight and ambient as placed blocks.
    auto movingSource = shader(argv[2]);
    movingSource += "\nfloat4 MovingLightingProbe(float4 p:SV_Position):SV_Target { VSOut v=(VSOut)0; v.light.y=screen.x; v.flags=screen.z>0.5?4096:0; return float4(Lighting(v,float3(0,0,1),true,1),1); }\n";
    auto movingCode=compile(movingSource,"MovingLightingProbe","ps_5_0");
    ComPtr<ID3D11PixelShader> movingPs;
    check(device->CreatePixelShader(movingCode->GetBufferPointer(),movingCode->GetBufferSize(),nullptr,&movingPs));
    ComPtr<ID3D11ShaderReflection> movingReflection;
    check(D3DReflect(movingCode->GetBufferPointer(),movingCode->GetBufferSize(),IID_PPV_ARGS(&movingReflection)));
    auto movingFrame=movingReflection->GetConstantBufferByName("Frame");
    D3D11_SHADER_BUFFER_DESC movingFrameDesc{}; check(movingFrame->GetDesc(&movingFrameDesc));
    std::vector<float> movingData((movingFrameDesc.Size+15)/16*4,0);
    auto setLighting=[&](const char* name,std::array<float,4> value) {
        D3D11_SHADER_VARIABLE_DESC desc{}; check(movingFrame->GetVariableByName(name)->GetDesc(&desc));
        std::memcpy(reinterpret_cast<char*>(movingData.data())+desc.StartOffset,value.data(),16);
    };
    D3D11_SHADER_VARIABLE_DESC ambientDesc{}; check(movingFrame->GetVariableByName("ambient")->GetDesc(&ambientDesc));
    for(int face=0;face<6;++face) for(int channel=0;channel<3;++channel)
        movingData[ambientDesc.StartOffset/4+face*4+channel]=.4f;
    setLighting("renderMode",{1,0,0,0}); setLighting("sunDir",{0,0,1,1});
    lightingBufferDesc.ByteWidth=UINT(movingData.size()*4);
    ComPtr<ID3D11Buffer> movingBuffer; check(device->CreateBuffer(&lightingBufferDesc,nullptr,&movingBuffer));
    auto movingCb=movingBuffer.Get(); context->PSSetConstantBuffers(0,1,&movingCb);
    context->PSSetShader(movingPs.Get(),nullptr,0);
    for(float sky : {0.0f,8.0f/15.0f,1.0f}) {
        setLighting("screen",{sky,0,0,0}); setLighting("sunColor",{.6f,.6f,.6f,0});
        context->UpdateSubresource(movingBuffer.Get(),0,nullptr,movingData.data(),0,0);
        context->Draw(3,0);
        float curve=sky/(4.0f-3.0f*sky);
        float value=.4f*(.3f+.7f*curve)+.6f*curve*curve;
        expect("Moving blocks follow production ambient and daylight without night vision",read(output.Get()),{value,value,value,1});
    }
    setLighting("screen",{1,0,0,0}); setLighting("sunColor",{0,0,0,0});
    context->UpdateSubresource(movingBuffer.Get(),0,nullptr,movingData.data(),0,0); context->Draw(3,0);
    expect("Moving blocks lose sunlight when the Skyrim sun changes",read(output.Get()),{.4f,.4f,.4f,1});
    setLighting("screen",{0,0,0,1});
    context->UpdateSubresource(movingBuffer.Get(),0,nullptr,movingData.data(),0,0); context->Draw(3,0);
    expect("Night vision still brightens roofed moving blocks",read(output.Get()),{.85f,.85f,.85f,1});
    for(float nightVision : {0.0f,1.0f}) {
        setLighting("screen",{0,0,1,nightVision});
        context->UpdateSubresource(movingBuffer.Get(),0,nullptr,movingData.data(),0,0); context->Draw(3,0);
        expect("Emissive eyes retain full color without ambient or night vision",read(output.Get()),{1,1,1,1});
    }
    // Execute PSMain with the same additive eyes flags used by Mobzilla's glow layer.
    auto glowSource=shader(argv[2]);
    glowSource += R"(
float4 GlowProbe(float4 p:SV_Position):SV_Target {
    VSOut v=(VSOut)0; v.pos=p; v.curClip.w=v.prevClip.w=1;
    v.rel=float3(p.xy,1); v.viewZ=1; v.uv=.5; v.color=1;
    v.flags=2|8|4096|8192; return PSMain(v,true).color;
})";
    auto glowCode=compile(glowSource,"GlowProbe","ps_5_0");
    ComPtr<ID3D11PixelShader> glowPs;
    check(device->CreatePixelShader(glowCode->GetBufferPointer(),glowCode->GetBufferSize(),nullptr,&glowPs));
    auto glowTexture=texture(DXGI_FORMAT_R32G32B32A32_FLOAT,D3D11_BIND_SHADER_RESOURCE,input,16);
    ComPtr<ID3D11ShaderResourceView> glowSrv;
    check(device->CreateShaderResourceView(glowTexture.Get(),nullptr,&glowSrv));
    D3D11_BLEND_DESC addDesc{}; auto& add=addDesc.RenderTarget[0];
    add.BlendEnable=TRUE; add.SrcBlend=add.DestBlend=D3D11_BLEND_ONE;
    add.BlendOp=add.BlendOpAlpha=D3D11_BLEND_OP_ADD;
    add.SrcBlendAlpha=D3D11_BLEND_ZERO; add.DestBlendAlpha=D3D11_BLEND_ONE; add.RenderTargetWriteMask=7;
    ComPtr<ID3D11BlendState> additive; check(device->CreateBlendState(&addDesc,&additive));
    context->PSSetShader(glowPs.Get(),nullptr,0); context->OMSetBlendState(additive.Get(),nullptr,~0u);
    setLighting("screen",{1,1,0,0}); setLighting("renderMode",{1,2,0,0});
    auto glowCheck=[&](const char* name,std::array<float,4> texel,std::array<float,4> effect,float fog) {
        std::array<float,4> body{.1f,.2f,.3f,1}, expected=body;
        for(int i=0;i<3;++i) expected[i]+=texel[i]*(1-fog)*(1-effect[3]);
        context->ClearRenderTargetView(rtv.Get(),body.data());
        context->UpdateSubresource(glowTexture.Get(),0,nullptr,texel.data(),16,0);
        context->UpdateSubresource(colour.Get(),0,nullptr,effect.data(),16,0);
        setLighting("fogNear",{1,1,1,1}); setLighting("fogRange",{0,-fog,1,1});
        context->UpdateSubresource(movingBuffer.Get(),0,nullptr,movingData.data(),0,0);
        auto atlas=glowSrv.Get(), effects=colourSrv.Get();
        context->PSSetShaderResources(0,1,&atlas); context->PSSetShaderResources(7,1,&effects);
        context->Draw(3,0); expect(name,read(output.Get()),expected);
    };
    glowCheck("Empty Mobzilla glow texels cannot whiten smoke",{0,0,0,0},{.4f,.5f,.6f,.5f},0);
    glowCheck("Empty scales cannot whiten mountain mist",{0,0,0,0},{.7f,.7f,.7f,.7f},.8f);
    glowCheck("Additive flame colour is composed only once",{0,0,0,0},{1,.5f,.2f,0},0);
    glowCheck("Foreground smoke attenuates real glow",{.2f,.3f,.4f,1},{.4f,.5f,.6f,.5f},0);
    glowCheck("Opaque smoke hides real glow",{.2f,.3f,.4f,1},{.4f,.5f,.6f,1},0);
    glowCheck("Mountain mist dims glow without white panels",{.2f,.3f,.4f,1},{0,0,0,0},.8f);
    glowCheck("No effect preserves authored glow colour",{.2f,.3f,.4f,1},{0,0,0,0},0);
    return 0;
} catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 1; } }
