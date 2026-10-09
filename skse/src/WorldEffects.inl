// World-only potion pass, before the separately composited Minecraft HUD.
ID3D11PixelShader* worldEffectsPs{};
ID3D11Texture2D* worldEffectsColour{};
ID3D11ShaderResourceView* worldEffectsSrv{};
ID3D11BlendState* worldEffectsBlend{};

void DrawWorldEffects(IDXGISwapChain* swapChain) {
    auto& state = State();
    const float darkness = state.darkness, vision = state.nightVision;
    if (!state.renderWorld || state.skyrimMenuOpen || !Link::Get().McAlive() || (darkness < 0.0001f && vision < 0.0001f)) return;
    if (!worldEffectsPs) {
        ID3DBlob* code{};
        if (!Compile("PSWorldEffects", "ps_5_0", &code)) return;
        device->CreatePixelShader(code->GetBufferPointer(), code->GetBufferSize(), nullptr, &worldEffectsPs);
        SafeRelease(code);
        D3D11_BLEND_DESC bd{}; bd.RenderTarget[0].RenderTargetWriteMask = 7;
        device->CreateBlendState(&bd, &worldEffectsBlend);
        if (!worldEffectsPs || !worldEffectsBlend) return;
    }
    ID3D11Texture2D* back{};
    if (FAILED(swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&back)))) return;
    D3D11_TEXTURE2D_DESC desc{}; back->GetDesc(&desc);
    if (worldEffectsColour) {
        D3D11_TEXTURE2D_DESC previous{}; worldEffectsColour->GetDesc(&previous);
        if (previous.Width != desc.Width || previous.Height != desc.Height || previous.Format != desc.Format) {
            SafeRelease(worldEffectsSrv); SafeRelease(worldEffectsColour);
        }
    }
    if (!worldEffectsColour) {
        auto copy = desc;
        copy.Usage = D3D11_USAGE_DEFAULT; copy.CPUAccessFlags = copy.MiscFlags = 0;
        copy.BindFlags = D3D11_BIND_SHADER_RESOURCE;
        if (FAILED(device->CreateTexture2D(&copy, nullptr, &worldEffectsColour)) ||
            FAILED(device->CreateShaderResourceView(worldEffectsColour, nullptr, &worldEffectsSrv))) {
            SafeRelease(back); SafeRelease(worldEffectsColour); SafeRelease(worldEffectsSrv); return;
        }
    }
    ID3D11RenderTargetView* rtv{};
    if (FAILED(device->CreateRenderTargetView(back, nullptr, &rtv))) { SafeRelease(back); return; }
    // Save every stage/resource touched; native Present can keep a geometry shader bound.
    ID3D11RenderTargetView* oldRtvs[8]{};
    ID3D11DepthStencilView* oldDsv{};
    ID3D11BlendState* oldBlend{}; float oldFactor[4]{}; UINT oldMask{};
    ID3D11DepthStencilState* oldDepth{}; UINT oldStencil{};
    ID3D11RasterizerState* oldRaster{};
    D3D11_VIEWPORT oldVps[16]{}; UINT oldVpCount = 16;
    ID3D11InputLayout* oldLayout{}; D3D11_PRIMITIVE_TOPOLOGY oldTopology{};
    ID3D11VertexShader* oldVs{}; ID3D11PixelShader* oldPs{};
    ID3D11GeometryShader* oldGs{}; ID3D11HullShader* oldHs{}; ID3D11DomainShader* oldDs{};
    ID3D11ShaderResourceView* oldSrvs[2]{}; ID3D11SamplerState* oldSampler{}; ID3D11Buffer* oldCb{};
    context->OMGetRenderTargets(8, oldRtvs, &oldDsv);
    context->OMGetBlendState(&oldBlend, oldFactor, &oldMask);
    context->OMGetDepthStencilState(&oldDepth, &oldStencil);
    context->RSGetState(&oldRaster); context->RSGetViewports(&oldVpCount, oldVps);
    context->IAGetInputLayout(&oldLayout); context->IAGetPrimitiveTopology(&oldTopology);
    context->VSGetShader(&oldVs, nullptr, nullptr); context->PSGetShader(&oldPs, nullptr, nullptr);
    context->GSGetShader(&oldGs, nullptr, nullptr); context->HSGetShader(&oldHs, nullptr, nullptr); context->DSGetShader(&oldDs, nullptr, nullptr);
    context->PSGetShaderResources(0, 2, oldSrvs); context->PSGetSamplers(0, 1, &oldSampler); context->PSGetConstantBuffers(0, 1, &oldCb);
    context->OMSetRenderTargets(0, nullptr, nullptr);
    context->CopyResource(worldEffectsColour, back);
    SafeRelease(back);
    Params values{};
    values.viewport[0] = float(desc.Width); values.viewport[1] = float(desc.Height);
    values.worldEffects[0] = darkness; values.worldEffects[1] = state.darknessPulse;
    values.worldEffects[2] = vision; values.worldEffects[3] = 256.0f;
    ID3D11ShaderResourceView* depthSrv{};
    if (auto* camera = RE::Main::WorldRootCamera()) {
        const auto& frustum = camera->GetRuntimeData2().viewFrustum;
        values.effectDepth[0] = frustum.fNear; values.effectDepth[1] = frustum.fFar;
        values.effectProjection[0] = std::max(std::fabs(frustum.fLeft), std::fabs(frustum.fRight));
        values.effectProjection[1] = std::max(std::fabs(frustum.fBottom), std::fabs(frustum.fTop));
        const auto& m = camera->GetRuntimeData().worldToCam;
        const auto& r = camera->world.rotate;
        auto depthAt = [&](float distance) {
            const auto p = camera->world.translate + RE::NiPoint3{r.entry[0][0], r.entry[1][0], r.entry[2][0]} * distance;
            return (m[2][0]*p.x+m[2][1]*p.y+m[2][2]*p.z+m[2][3]) /
                   (m[3][0]*p.x+m[3][1]*p.y+m[3][2]*p.z+m[3][3]);
        };
        values.effectDepth[2] = depthAt(100.0f) > depthAt(10000.0f) ? 1.0f : 0.0f;
        if (auto* renderer = RE::BSGraphics::Renderer::GetSingleton())
            depthSrv = reinterpret_cast<ID3D11ShaderResourceView*>(renderer->GetDepthStencilData().depthStencils[RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN].depthSRV);
        values.effectDepth[3] = depthSrv ? 1.0f : 0.0f;
    }
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (SUCCEEDED(context->Map(params, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
        std::memcpy(mapped.pData, &values, sizeof(values)); context->Unmap(params, 0);
        D3D11_VIEWPORT viewport{0, 0, float(desc.Width), float(desc.Height), 0, 1};
        context->OMSetRenderTargets(1, &rtv, nullptr);
        context->OMSetBlendState(worldEffectsBlend, nullptr, 0xFFFFFFFF);
        context->OMSetDepthStencilState(depth, 0);
        context->RSSetState(raster); context->RSSetViewports(1, &viewport);
        context->IASetInputLayout(nullptr); context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        context->GSSetShader(nullptr, nullptr, 0); context->HSSetShader(nullptr, nullptr, 0); context->DSSetShader(nullptr, nullptr, 0);
        context->VSSetShader(vs, nullptr, 0); context->PSSetShader(worldEffectsPs, nullptr, 0);
        ID3D11ShaderResourceView* resources[]{worldEffectsSrv, depthSrv}; context->PSSetShaderResources(0, 2, resources);
        context->PSSetConstantBuffers(0, 1, &params); context->PSSetSamplers(0, 1, &sampler);
        context->Draw(3, 0);
    }
    ID3D11ShaderResourceView* none[2]{}; context->PSSetShaderResources(0, 2, none);
    context->OMSetRenderTargets(8, oldRtvs, oldDsv);
    context->OMSetBlendState(oldBlend, oldFactor, oldMask); context->OMSetDepthStencilState(oldDepth, oldStencil);
    context->RSSetState(oldRaster); context->RSSetViewports(oldVpCount, oldVps);
    context->IASetInputLayout(oldLayout); context->IASetPrimitiveTopology(oldTopology);
    context->VSSetShader(oldVs, nullptr, 0); context->PSSetShader(oldPs, nullptr, 0);
    context->GSSetShader(oldGs, nullptr, 0); context->HSSetShader(oldHs, nullptr, 0); context->DSSetShader(oldDs, nullptr, 0);
    context->PSSetShaderResources(0, 2, oldSrvs); context->PSSetSamplers(0, 1, &oldSampler); context->PSSetConstantBuffers(0, 1, &oldCb);
    for (auto*& v : oldRtvs) SafeRelease(v);
    for (auto*& v : oldSrvs) SafeRelease(v);
    SafeRelease(oldDsv); SafeRelease(oldBlend); SafeRelease(oldDepth); SafeRelease(oldRaster); SafeRelease(oldLayout);
    SafeRelease(oldVs); SafeRelease(oldPs); SafeRelease(oldGs); SafeRelease(oldHs); SafeRelease(oldDs);
    SafeRelease(oldSampler); SafeRelease(oldCb); SafeRelease(rtv);
}
