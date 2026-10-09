package dev.skycraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.link.SharedMemory;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.*;

/** Full-resolution overlay readback: publish completed transfers without waiting for the GPU. */
public final class FrameExporter {
    private static final int[] BUFFERS = new int[3];
    private static final long[] FENCES = new long[3], FRAMES = new long[3];
    private static int width, height, generation = -1;
    private static long frameId = 1, published;
    private FrameExporter() {}

    private static void reset() {
        for (int i = 0; i < BUFFERS.length; i++) {
            if (FENCES[i] != 0) GL32.glDeleteSync(FENCES[i]);
            if (BUFFERS[i] != 0) GL15.glDeleteBuffers(BUFFERS[i]);
            BUFFERS[i] = 0;
            FENCES[i] = FRAMES[i] = 0;
        }
    }

    public static void capture(Minecraft minecraft) {
        RenderSystem.assertOnRenderThread();
        RenderTarget target = minecraft.getMainRenderTarget();
        SharedMemory.Segment shared = SkyLink.segment();
        if (shared == null || target.width <= 0 || target.height <= 0
                || target.width > Proto.MAX_OVERLAY_W || target.height > Proto.MAX_OVERLAY_H) return;
        int oldBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int oldFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        try {
            if (width != target.width || height != target.height || generation != SkyLink.generation()) {
                reset();
                width = target.width;
                height = target.height;
                generation = SkyLink.generation();
                SkyCraft.LOG.info("SkyCraft: asynchronous overlay capture {}x{} (3 pixel buffers)", width, height);
            }
            long bytes = (long) width * height * 4;
            int newest = -1;
            for (int i = 0; i < BUFFERS.length; i++) {
                if (FENCES[i] == 0) continue;
                int status = GL32.glClientWaitSync(FENCES[i], 0, 0);
                if (status == GL32.GL_WAIT_FAILED) { reset(); return; }
                if (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED) {
                    GL32.glDeleteSync(FENCES[i]);
                    FENCES[i] = 0;
                    if (FRAMES[i] > published && (newest < 0 || FRAMES[i] > FRAMES[newest])) newest = i;
                }
            }
            if (newest >= 0) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, BUFFERS[newest]);
                ByteBuffer pixels = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, bytes, GL30.GL_MAP_READ_BIT);
                if (pixels != null) {
                    boolean copied = false;
                    try {
                        SharedMemory.copy(SharedMemory.Segment.ofBuffer(pixels), 0, shared, SkyLink.overlayBackSlotOffset(), bytes);
                        copied = true;
                    } finally {
                        boolean valid = GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                        if (copied && valid) {
                            published = FRAMES[newest];
                            SkyLink.publishOverlay(width, height, true, published);
                        }
                    }
                }
            }
            int free = -1;
            for (int i = 0; i < BUFFERS.length; i++) if (FENCES[i] == 0) { free = i; break; }
            if (free < 0) return;
            if (BUFFERS[free] == 0) {
                BUFFERS[free] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, BUFFERS[free]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ);
            } else GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, BUFFERS[free]);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            FENCES[free] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            FRAMES[free] = frameId++;
            GL11.glFlush();
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldBuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldFramebuffer);
            GL11.glReadBuffer(oldReadBuffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
        }
    }
}
