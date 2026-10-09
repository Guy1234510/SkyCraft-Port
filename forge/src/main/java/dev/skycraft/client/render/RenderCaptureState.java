package dev.skycraft.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** CPU model capture still invokes mod renderers, which may alter Minecraft's GL state. */
public final class RenderCaptureState implements AutoCloseable {
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
    private final int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int[] viewport = new int[4], scissorBox = new int[4];
    private final float[] color = RenderSystem.getShaderColor().clone();

    public RenderCaptureState() {
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
    }

    public void close() {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        else RenderSystem.disableScissor();
        RenderSystem.depthMask(depthMask);
        RenderSystem.depthFunc(depthFunc);
        RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
        RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
    }
}
