package com.hoptimization.ui.font;

import com.hoptimization.HoptimizationMod;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 基于行货架装箱（Shelf Packing）的多页 OpenGL 字形图集纹理管理器。
 * <p>
 * 仅在首次实际绘制某个字符时按需分配纹理页并上传字形位图，空白区域填充透明白
 * {@code 0x00FFFFFF} 以防止 {@code GL_LINEAR} 线性过滤在字形边缘产生黑边伪影。
 */
public final class GlyphAtlas {

    private static final int DEFAULT_PAGE_SIZE = 512;
    /** 字形槽位之间的安全间隔（偶数），确保 level 0 与 1/2 分辨率 mipmap 都不发生邻居串色。 */
    private static final int ATLAS_GAP = 4;

    private final List<Page> pages = new ArrayList<Page>(2);
    private int pageSize = 0;
    private IntBuffer uploadBuffer = BufferUtils.createIntBuffer(64 * 64);
    private IntBuffer mipBuffer = BufferUtils.createIntBuffer(32 * 32);

    /**
     * 描述字形在图集纹理页中的分配位置与 UV 坐标。
     */
    public static final class Region {
        public final int textureId;
        public final float u0;
        public final float v0;
        public final float u1;
        public final float v1;

        Region(int textureId, float u0, float v0, float u1, float v1) {
            this.textureId = textureId;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
        }
    }

    private static final class Page {
        final int textureId;
        final int size;
        int cursorX;
        int cursorY;
        int shelfHeight;

        Page(int textureId, int size) {
            this.textureId = textureId;
            this.size = size;
            this.cursorX = ATLAS_GAP;
            this.cursorY = ATLAS_GAP;
            this.shelfHeight = 0;
        }
    }

    /**
     * 将单字形位图上传到图集纹理中（必须在渲染线程调用）。
     *
     * @param argbPixels 行优先像素数组（长度至少 {@code width * height}）
     * @param width 位图宽度（像素）
     * @param height 位图高度（像素）
     * @return 上传后的纹理页与 UV 区域；若尺寸非法则返回 {@code null}
     */
    public Region upload(int[] argbPixels, int width, int height) {
        if (width <= 0 || height <= 0 || argbPixels == null || argbPixels.length < width * height) {
            return null;
        }
        int resolvedPageSize = resolvePageSize();
        if (width + ATLAS_GAP * 2 > resolvedPageSize || height + ATLAS_GAP * 2 > resolvedPageSize) {
            HoptimizationMod.LOGGER.warn("Glyph bitmap {}x{} exceeds atlas page size {}; skipping upload.",
                    Integer.valueOf(width), Integer.valueOf(height), Integer.valueOf(resolvedPageSize));
            return null;
        }

        Page targetPage = null;
        for (int i = 0; i < pages.size(); i++) {
            Page candidate = pages.get(i);
            if (canFit(candidate, width, height)) {
                targetPage = candidate;
                break;
            }
        }
        if (targetPage == null) {
            targetPage = createPage(resolvedPageSize);
            pages.add(targetPage);
        }

        if (targetPage.cursorX + width + ATLAS_GAP > targetPage.size) {
            targetPage.cursorX = ATLAS_GAP;
            targetPage.cursorY += alignEven(targetPage.shelfHeight) + ATLAS_GAP;
            targetPage.shelfHeight = 0;
        }

        int slotX = targetPage.cursorX;
        int slotY = targetPage.cursorY;
        targetPage.cursorX += alignEven(width) + ATLAS_GAP;
        int alignedHeight = alignEven(height);
        if (alignedHeight > targetPage.shelfHeight) {
            targetPage.shelfHeight = alignedHeight;
        }

        uploadSubImage(targetPage.textureId, slotX, slotY, width, height, argbPixels);

        float invSize = 1.0f / (float) targetPage.size;
        return new Region(
                targetPage.textureId,
                slotX * invSize,
                slotY * invSize,
                (slotX + width) * invSize,
                (slotY + height) * invSize
        );
    }

    /**
     * 释放图集所持有的全部 OpenGL 纹理资源。
     */
    public void dispose() {
        for (int i = 0; i < pages.size(); i++) {
            int texId = pages.get(i).textureId;
            if (texId != 0) {
                GlStateManager.deleteTexture(texId);
            }
        }
        pages.clear();
    }

    /**
     * 获取当前已分配的纹理页数量。
     *
     * @return 图集页数
     */
    public int getPageCount() {
        return pages.size();
    }

    private boolean canFit(Page page, int width, int height) {
        int alignedW = alignEven(width);
        int alignedH = alignEven(height);
        if (page.cursorX + alignedW + ATLAS_GAP <= page.size) {
            return page.cursorY + Math.max(page.shelfHeight, alignedH) + ATLAS_GAP <= page.size;
        }
        int nextY = page.cursorY + alignEven(page.shelfHeight) + ATLAS_GAP;
        return nextY + alignedH + ATLAS_GAP <= page.size && ATLAS_GAP + alignedW + ATLAS_GAP <= page.size;
    }

    private static int alignEven(int value) {
        return (value + 1) & ~1;
    }

    private int resolvePageSize() {
        if (pageSize > 0) {
            return pageSize;
        }
        int maxTex = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (maxTex <= 0) {
            maxTex = DEFAULT_PAGE_SIZE;
        }
        pageSize = Math.min(DEFAULT_PAGE_SIZE, maxTex);
        return pageSize;
    }

    private Page createPage(int size) {
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        int prevUnpackRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int prevUnpackSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int prevUnpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int prevUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);

        int texId = GlStateManager.generateTexture();
        try {
            GlStateManager.bindTexture(texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

            // 使用透明白 0x00FFFFFF 清空整页（level 0 与 level 1），防止线性过滤与 mipmap 在字形边缘混入黑边
            int pixelCount = size * size;
            IntBuffer clearBuf = ensureBufferCapacity(pixelCount);
            int[] row = new int[size];
            Arrays.fill(row, 0x00FFFFFF);
            clearBuf.clear();
            for (int y = 0; y < size; y++) {
                clearBuf.put(row);
            }
            clearBuf.flip();

            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0,
                    GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, clearBuf);

            int halfSize = size >> 1;
            clearBuf.position(0);
            clearBuf.limit(halfSize * halfSize);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, halfSize, halfSize, 0,
                    GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, clearBuf);
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevUnpackRowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, prevUnpackSkipPixels);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, prevUnpackSkipRows);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpackAlignment);
            GlStateManager.bindTexture(prevTex);
            GlStateManager.setActiveTexture(prevActiveTex);
        }
        return new Page(texId, size);
    }

    private void uploadSubImage(int texId, int x, int y, int width, int height, int[] pixels) {
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        int prevUnpackRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int prevUnpackSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int prevUnpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int prevUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);

        try {
            GlStateManager.bindTexture(texId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

            IntBuffer buf = ensureBufferCapacity(width * height);
            buf.clear();
            buf.put(pixels, 0, width * height);
            buf.flip();

            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height,
                    GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);

            int halfW = (width + 1) >> 1;
            int halfH = (height + 1) >> 1;
            IntBuffer mip = ensureMipBufferCapacity(halfW * halfH);
            mip.clear();
            for (int my = 0; my < halfH; my++) {
                int sy0 = my << 1;
                int sy1 = sy0 + 1;
                for (int mx = 0; mx < halfW; mx++) {
                    int sx0 = mx << 1;
                    int sx1 = sx0 + 1;
                    int a00 = sampleAlpha(pixels, width, height, sx0, sy0);
                    int a10 = sampleAlpha(pixels, width, height, sx1, sy0);
                    int a01 = sampleAlpha(pixels, width, height, sx0, sy1);
                    int a11 = sampleAlpha(pixels, width, height, sx1, sy1);
                    int avgA = (a00 + a10 + a01 + a11 + 2) >> 2;
                    mip.put((avgA << 24) | 0x00FFFFFF);
                }
            }
            mip.flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 1, x >> 1, y >> 1, halfW, halfH,
                    GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, mip);
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevUnpackRowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, prevUnpackSkipPixels);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, prevUnpackSkipRows);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpackAlignment);
            GlStateManager.bindTexture(prevTex);
            GlStateManager.setActiveTexture(prevActiveTex);
        }
    }

    private IntBuffer ensureBufferCapacity(int requiredInts) {
        if (uploadBuffer.capacity() < requiredInts) {
            int nextCapacity = Math.max(requiredInts, uploadBuffer.capacity() * 2);
            uploadBuffer = BufferUtils.createIntBuffer(nextCapacity);
        }
        return uploadBuffer;
    }

    private IntBuffer ensureMipBufferCapacity(int requiredInts) {
        if (mipBuffer.capacity() < requiredInts) {
            int nextCapacity = Math.max(requiredInts, mipBuffer.capacity() * 2);
            mipBuffer = BufferUtils.createIntBuffer(nextCapacity);
        }
        return mipBuffer;
    }

    private static int sampleAlpha(int[] pixels, int width, int height, int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return 0;
        }
        return (pixels[y * width + x] >>> 24) & 0xFF;
    }
}
