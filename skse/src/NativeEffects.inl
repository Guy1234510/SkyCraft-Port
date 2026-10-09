// Included inside WorldRender.cpp's anonymous namespace, after RenderPasses.
// Native particle shaders render into a clean layer, never a terrain-containing
// scene copy. Their real alpha is retained and composited over opaque MC colour.
struct NativeEffectLayer {
    ID3D11Texture2D *colour{}, *depth{}, *sampleDepth{};
    ID3D11RenderTargetView* rtv{};
    ID3D11DepthStencilView* dsv{};
    ID3D11ShaderResourceView *srv{}, *depthSrv{};
    ID3D11PixelShader* composite{};
    ID3D11BlendState* compositeBlend{};
    std::unordered_map<ID3D11BlendState*, ID3D11BlendState*> blends;
    UINT width{}, height{};
    bool ready{}, drawn{}, bound{};
    unsigned candidates{}, captures{}, additionalDrawCaptures{};
    ID3D11RenderTargetView* savedRtvs[8]{};
    ID3D11DepthStencilView* savedDsv{};
    ID3D11BlendState* savedBlend{};
    float savedFactor[4]{};
    UINT savedMask{};
    ID3D11ShaderResourceView* savedSrvs[16]{};
    unsigned depthSlots{};

    void ResetTargets() {
        Release(rtv); Release(dsv); Release(srv); Release(depthSrv);
        Release(colour); Release(depth); Release(sampleDepth);
        ready = drawn = false;
    }
    bool Ensure(ID3D11Texture2D* nativeDepth, ID3D11DepthStencilView* nativeDsv,
                ID3D11ShaderResourceView* nativeSrv) {
        D3D11_TEXTURE2D_DESC dd{}; nativeDepth->GetDesc(&dd);
        if (dd.SampleDesc.Count != 1 || dd.ArraySize != 1) return false;
        if (!composite) {
            ID3DBlob* code{};
            if (!Compile("EffectCompositePS", "ps_5_0", &code)) return false;
            device->CreatePixelShader(code->GetBufferPointer(), code->GetBufferSize(), nullptr, &composite);
            Release(code);
            D3D11_BLEND_DESC bd{};
            auto& b = bd.RenderTarget[0];
            b.BlendEnable = TRUE;
            b.SrcBlend = D3D11_BLEND_ONE;
            b.DestBlend = D3D11_BLEND_INV_SRC_ALPHA;
            b.BlendOp = b.BlendOpAlpha = D3D11_BLEND_OP_ADD;
            b.SrcBlendAlpha = D3D11_BLEND_ZERO;
            b.DestBlendAlpha = D3D11_BLEND_ONE;
            b.RenderTargetWriteMask = 7;
            device->CreateBlendState(&bd, &compositeBlend);
        }
        if (depth) {
            D3D11_TEXTURE2D_DESC previous{}; depth->GetDesc(&previous);
            if (previous.Width != dd.Width || previous.Height != dd.Height || previous.Format != dd.Format) ResetTargets();
        }
        if (!depth) {
            D3D11_DEPTH_STENCIL_VIEW_DESC dv{}; nativeDsv->GetDesc(&dv); dv.Flags = 0;
            D3D11_SHADER_RESOURCE_VIEW_DESC sv{}; nativeSrv->GetDesc(&sv);
            dd.Usage = D3D11_USAGE_DEFAULT; dd.CPUAccessFlags = dd.MiscFlags = 0;
            dd.BindFlags = D3D11_BIND_DEPTH_STENCIL;
            if (FAILED(device->CreateTexture2D(&dd, nullptr, &depth)) ||
                FAILED(device->CreateDepthStencilView(depth, &dv, &dsv))) { ResetTargets(); return false; }
            dd.BindFlags = D3D11_BIND_SHADER_RESOURCE;
            if (FAILED(device->CreateTexture2D(&dd, nullptr, &sampleDepth)) ||
                FAILED(device->CreateShaderResourceView(sampleDepth, &sv, &depthSrv))) { ResetTargets(); return false; }
            dd.Format = DXGI_FORMAT_R16G16B16A16_FLOAT;
            dd.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
            if (FAILED(device->CreateTexture2D(&dd, nullptr, &colour)) ||
                FAILED(device->CreateRenderTargetView(colour, nullptr, &rtv)) ||
                FAILED(device->CreateShaderResourceView(colour, nullptr, &srv))) { ResetTargets(); return false; }
            width = dd.Width; height = dd.Height;
        }
        return composite && compositeBlend;
    }
} nativeEffects;

void EndNativeLocalEffect() {
    auto& e = nativeEffects;
    if (!e.bound) return;
    auto* context = NativeDrawContext();
    context->OMSetRenderTargets(8, e.savedRtvs, e.savedDsv);
    context->OMSetBlendState(e.savedBlend, e.savedFactor, e.savedMask);
    for (unsigned slot = 0; slot < 16; ++slot)
        if (e.depthSlots & (1u << slot)) context->PSSetShaderResources(slot, 1, &e.savedSrvs[slot]);
    for (auto*& v : e.savedRtvs) Release(v);
    for (auto*& v : e.savedSrvs) Release(v);
    Release(e.savedDsv); Release(e.savedBlend);
    e.bound = false;
}

void PrepareNativeLocalEffects(ID3D11DeviceContext* context, RE::NiCamera* camera) {
    auto& e = nativeEffects;
    e.ready = e.drawn = false;
    auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
    if (!inFrameDrawn || !renderer) return;
    const auto& main = renderer->GetDepthStencilData().depthStencils[RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN];
    auto* nativeDepth = reinterpret_cast<ID3D11Texture2D*>(main.texture);
    auto* nativeDsv = reinterpret_cast<ID3D11DepthStencilView*>(main.views[0]);
    auto* nativeSrv = reinterpret_cast<ID3D11ShaderResourceView*>(main.depthSRV);
    if (!nativeDepth || !nativeDsv || !nativeSrv || !e.Ensure(nativeDepth, nativeDsv, nativeSrv)) return;
    StateBackup backup; backup.Save(context);
    context->OMSetRenderTargets(0, nullptr, nullptr);
    context->CopyResource(e.depth, nativeDepth);
    const float clear[4]{};
    context->ClearRenderTargetView(e.rtv, clear);
    Target t;
    t.dsv = e.dsv; t.sceneDepth = depthCopySrv; t.surfaceDepth = surfaceCopySrv;
    t.width = e.width; t.height = e.height;
    t.hdr = t.foreground = t.skyrimDepth = t.depthOnly = true;
    RenderPasses(context, t, camera, nullptr);
    context->OMSetRenderTargets(0, nullptr, nullptr);
    context->CopyResource(e.sampleDepth, e.depth);
    backup.Restore(context);
    e.ready = true;
}

bool BeginNativeLocalEffect(ID3D11DeviceContext* context) {
    auto& e = nativeEffects;
    if (e.bound) return true;
    if (!localEffectGeometryActive || !e.ready) return false;
    ++e.candidates;
    // Geometry eligibility can survive a cached transition to another shader.
    // Never divert a lighting/water/full-screen draw merely on that old flag.
    ID3D11PixelShader* actual{};
    context->PSGetShader(&actual, nullptr, nullptr);
    bool effectShader = false;
    for (auto* family : localEffectShaders) {
        if (!family) continue;
        for (auto* shader : family->pixelShaders) {
            if (shader && reinterpret_cast<ID3D11PixelShader*>(shader->shader) == actual) {
                effectShader = true; break;
            }
        }
        if (effectShader) break;
    }
    Release(actual);
    if (!effectShader) return false;
    context->OMGetRenderTargets(8, e.savedRtvs, &e.savedDsv);
    context->OMGetBlendState(&e.savedBlend, e.savedFactor, &e.savedMask);
    auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
    D3D11_BLEND_DESC desc{};
    if (e.savedBlend) e.savedBlend->GetDesc(&desc);
    const auto& original = desc.RenderTarget[0];
    // Only main-view alpha/additive particles. Opaque effects and reflection
    // targets retain their own path; distant fog is excluded by geometry bounds.
    ID3D11Resource* colourResource{};
    if (e.savedRtvs[0]) e.savedRtvs[0]->GetResource(&colourResource);
    const bool mainColour = renderer && colourResource == reinterpret_cast<ID3D11Resource*>(
        renderer->GetRuntimeData().renderTargets[RE::RENDER_TARGETS::kMAIN].texture);
    Release(colourResource);
    if (!mainColour ||
        !original.BlendEnable || original.BlendOp != D3D11_BLEND_OP_ADD ||
        (original.DestBlend != D3D11_BLEND_INV_SRC_ALPHA && original.DestBlend != D3D11_BLEND_ONE)) {
        for (auto*& v : e.savedRtvs) Release(v);
        Release(e.savedDsv); Release(e.savedBlend);
        return false;
    }
    ID3D11BlendState* layerBlend{};
    if (auto it = e.blends.find(e.savedBlend); it != e.blends.end()) layerBlend = it->second;
    else {
        auto& b = desc.RenderTarget[0];
        b.SrcBlendAlpha = b.DestBlend == D3D11_BLEND_ONE ? D3D11_BLEND_ZERO : D3D11_BLEND_ONE;
        b.DestBlendAlpha = b.DestBlend == D3D11_BLEND_ONE ? D3D11_BLEND_ONE : D3D11_BLEND_INV_SRC_ALPHA;
        b.BlendOpAlpha = D3D11_BLEND_OP_ADD;
        b.RenderTargetWriteMask = 15;
        if (FAILED(device->CreateBlendState(&desc, &layerBlend))) {
            for (auto*& v : e.savedRtvs) Release(v);
            Release(e.savedDsv); Release(e.savedBlend);
            return false;
        }
        e.savedBlend->AddRef(); // keep the cache key valid
        e.blends.emplace(e.savedBlend, layerBlend);
    }
    context->PSGetShaderResources(0, 16, e.savedSrvs);
    e.depthSlots = 0;
    ID3D11ShaderResourceView* resources[16]{};
    std::copy_n(e.savedSrvs, 16, resources);
    const auto& depths = renderer->GetDepthStencilData().depthStencils;
    for (int slot = 0; slot < 16; ++slot) {
        if (!resources[slot]) continue;
        ID3D11Resource* resource{}; resources[slot]->GetResource(&resource);
        for (auto index : { RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN, RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN_COPY,
                           RE::RENDER_TARGETS_DEPTHSTENCIL::kPOST_ZPREPASS_COPY }) {
            if (resource == reinterpret_cast<ID3D11Resource*>(depths[index].texture)) {
                resources[slot] = e.depthSrv;
                e.depthSlots |= 1u << slot;
            }
        }
        Release(resource);
    }
    context->OMSetRenderTargets(1, &e.rtv, e.dsv);
    context->OMSetBlendState(layerBlend, e.savedFactor, e.savedMask);
    for (unsigned slot = 0; slot < 16; ++slot)
        if (e.depthSlots & (1u << slot)) context->PSSetShaderResources(slot, 1, &resources[slot]);
    e.bound = e.drawn = true;
    ++e.captures;
    return true;
}

void CompositeNativeLocalEffects(ID3D11DeviceContext* context) {
    auto& e = nativeEffects;
    static auto nextAudit = std::chrono::steady_clock::time_point{};
    const auto now = std::chrono::steady_clock::now();
    if (e.ready && now >= nextAudit) {
        logger::info("effects76: {} local shader bindings considered, {} isolated smoke/fire bindings captured ({} from additional particle/dynamic draws)",
            e.candidates, e.captures, e.additionalDrawCaptures);
        e.candidates = e.captures = e.additionalDrawCaptures = 0;
        nextAudit = now + std::chrono::seconds(10);
    }
    if (!e.ready || !e.drawn) return;
    auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
    auto* rtv = reinterpret_cast<ID3D11RenderTargetView*>(renderer->GetRuntimeData().renderTargets[RE::RENDER_TARGETS::kMAIN].RTV);
    StateBackup backup; backup.Save(context);
    context->GSSetShader(nullptr, nullptr, 0);
    context->HSSetShader(nullptr, nullptr, 0);
    context->DSSetShader(nullptr, nullptr, 0);
    context->OMSetRenderTargets(1, &rtv, nullptr);
    context->OMSetBlendState(e.compositeBlend, nullptr, 0xFFFFFFFF);
    context->OMSetDepthStencilState(nullptr, 0);
    context->RSSetState(raster);
    D3D11_VIEWPORT vp{0, 0, float(e.width), float(e.height), 0, 1};
    context->RSSetViewports(1, &vp);
    context->IASetInputLayout(nullptr);
    context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    context->VSSetShader(probeVs, nullptr, 0);
    context->PSSetShader(e.composite, nullptr, 0);
    context->PSSetShaderResources(7, 1, &e.srv);
    context->Draw(3, 0);
    backup.Restore(context);
}

// Depth is borrowed for post-processing only. Native empty/sky regions may
// retain copied depth across frames; leaving a flying avatar there produces
// stale silhouettes and invalid temporal reprojection in the following frame.
struct NativePostDepth {
    ID3D11Texture2D* snapshots[3]{};
    ID3D11ShaderResourceView* srvs[3]{};
    ID3D11Texture2D* destinations[3]{};
    bool pending{};
} nativePostDepth;

void SnapshotNativePostDepth(ID3D11DeviceContext* context, Target& target) {
    auto* renderer = RE::BSGraphics::Renderer::GetSingleton();
    if (!renderer) return;
    const auto& depths = renderer->GetDepthStencilData().depthStencils;
    const std::array indices{RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN,
        RE::RENDER_TARGETS_DEPTHSTENCIL::kMAIN_COPY, RE::RENDER_TARGETS_DEPTHSTENCIL::kPOST_ZPREPASS_COPY};
    for (std::size_t i = 0; i < indices.size(); ++i) {
        const auto& native = depths[indices[i]];
        auto* texture = reinterpret_cast<ID3D11Texture2D*>(native.texture);
        auto* srv = reinterpret_cast<ID3D11ShaderResourceView*>(native.depthSRV);
        auto* dsv = reinterpret_cast<ID3D11DepthStencilView*>(native.views[0]);
        if (!texture || !srv || !dsv) continue;
        D3D11_TEXTURE2D_DESC desc{}; texture->GetDesc(&desc);
        if (desc.Width != target.width || desc.Height != target.height || desc.SampleDesc.Count != 1) continue;
        bool duplicate = false;
        for (std::size_t j = 0; j < i; ++j) duplicate |= nativePostDepth.destinations[j] == texture;
        if (duplicate || !EnsureDepthCopy(texture, srv, nativePostDepth.snapshots[i], nativePostDepth.srvs[i])) continue;
        context->CopyResource(nativePostDepth.snapshots[i], texture);
        nativePostDepth.destinations[i] = texture;
        target.avatarDepthCopies[i] = dsv;
        nativePostDepth.pending = true;
    }
}

void RestoreNativePostDepth(ID3D11DeviceContext* context) {
    if (!nativePostDepth.pending) return;
    StateBackup backup; backup.Save(context);
    context->OMSetRenderTargets(0, nullptr, nullptr);
    ID3D11ShaderResourceView* none[8]{};
    context->PSSetShaderResources(0, 8, none);
    for (std::size_t i = 0; i < 3; ++i) {
        if (nativePostDepth.destinations[i])
            context->CopyResource(nativePostDepth.destinations[i], nativePostDepth.snapshots[i]);
        nativePostDepth.destinations[i] = nullptr;
    }
    backup.Restore(context);
    nativePostDepth.pending = false;
}
