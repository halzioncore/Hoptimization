package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.NumberSetting;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

/**
 * Renders a customized targeted block selection outline and optional translucent box fill.
 */
public class BlockOutline extends Module {
    public static final String ID = "block_outline";
    private static final float FILL_ALPHA_SCALE = 0.35F;

    private final ColorSetting color;
    private final NumberSetting lineWidth;
    private final BooleanSetting fill;

    /**
     * Creates the Block Outline module and registers its settings.
     */
    public BlockOutline() {
        super(ID, Category.VISUAL);
        this.color = addSetting(
            new ColorSetting("color", "setting.hoptimization.block_outline.color.name", 0x995B8CFF, true)
        );
        this.lineWidth = addSetting(
            new NumberSetting(
                "line_width",
                "setting.hoptimization.block_outline.line_width.name",
                2.0D,
                1.0D,
                5.0D,
                0.5D
            )
        );
        this.fill = addSetting(
            new BooleanSetting("fill", "setting.hoptimization.block_outline.fill.name", false)
        );
    }

    /**
     * Returns the block outline color setting.
     *
     * @return color setting
     */
    public ColorSetting getColorSetting() {
        return this.color;
    }

    /**
     * Returns the block outline stroke width setting.
     *
     * @return line width setting
     */
    public NumberSetting getLineWidthSetting() {
        return this.lineWidth;
    }

    /**
     * Returns the block fill toggle setting.
     *
     * @return fill setting
     */
    public BooleanSetting getFillSetting() {
        return this.fill;
    }

    /**
     * Replaces vanilla block selection rendering with configured color, line width, and optional fill.
     *
     * @param event Forge block highlight event
     */
    @SubscribeEvent
    public void onDrawBlockHighlight(DrawBlockHighlightEvent event) {
        if (!isEnabled() || event == null || event.subID != 0 || event.target == null
            || event.target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || event.player == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return;
        }
        BlockPos pos = event.target.getBlockPos();
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block.getMaterial() == Material.air || !mc.theWorld.getWorldBorder().contains(pos)) {
            return;
        }

        event.setCanceled(true);

        int argb = this.color.getArgb();
        float alpha = ((argb >>> 24) & 0xFF) / 255.0F;
        if (alpha <= 0.0F) {
            return;
        }
        float red = ((argb >>> 16) & 0xFF) / 255.0F;
        float green = ((argb >>> 8) & 0xFF) / 255.0F;
        float blue = (argb & 0xFF) / 255.0F;

        block.setBlockBoundsBasedOnState(mc.theWorld, pos);
        double px = event.player.lastTickPosX + (event.player.posX - event.player.lastTickPosX) * event.partialTicks;
        double py = event.player.lastTickPosY + (event.player.posY - event.player.lastTickPosY) * event.partialTicks;
        double pz = event.player.lastTickPosZ + (event.player.posZ - event.player.lastTickPosZ) * event.partialTicks;
        AxisAlignedBB box = block.getSelectedBoundingBox(mc.theWorld, pos)
            .expand(0.002D, 0.002D, 0.002D)
            .offset(-px, -py, -pz);

        // Save both GL attribute values and GlStateManager-tracked toggles so state cache stays coherent.
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasTexture2D = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        float prevLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);

        try {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE,
                GL11.GL_ZERO
            );
            GlStateManager.disableTexture2D();
            GlStateManager.depthMask(false);

            if (this.fill.isEnabled()) {
                GlStateManager.disableCull();
                GlStateManager.color(red, green, blue, alpha * FILL_ALPHA_SCALE);
                drawFilledBoundingBox(box);
            }

            GL11.glLineWidth(this.lineWidth.getFloatValue());
            GlStateManager.color(red, green, blue, alpha);
            RenderGlobal.drawSelectionBoundingBox(box);
        } finally {
            GL11.glLineWidth(prevLineWidth);
            GlStateManager.depthMask(prevDepthMask);
            if (wasCull) {
                GlStateManager.enableCull();
            } else {
                GlStateManager.disableCull();
            }
            if (wasTexture2D) {
                GlStateManager.enableTexture2D();
            } else {
                GlStateManager.disableTexture2D();
            }
            if (wasBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static void drawFilledBoundingBox(AxisAlignedBB box) {
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        worldrenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        // Bottom (-Y)
        worldrenderer.pos(box.minX, box.minY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.minY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.minY, box.maxZ).endVertex();
        worldrenderer.pos(box.minX, box.minY, box.maxZ).endVertex();
        // Top (+Y)
        worldrenderer.pos(box.minX, box.maxY, box.maxZ).endVertex();
        worldrenderer.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        worldrenderer.pos(box.maxX, box.maxY, box.minZ).endVertex();
        worldrenderer.pos(box.minX, box.maxY, box.minZ).endVertex();
        // North (-Z)
        worldrenderer.pos(box.minX, box.minY, box.minZ).endVertex();
        worldrenderer.pos(box.minX, box.maxY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.maxY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.minY, box.minZ).endVertex();
        // South (+Z)
        worldrenderer.pos(box.minX, box.minY, box.maxZ).endVertex();
        worldrenderer.pos(box.maxX, box.minY, box.maxZ).endVertex();
        worldrenderer.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        worldrenderer.pos(box.minX, box.maxY, box.maxZ).endVertex();
        // West (-X)
        worldrenderer.pos(box.minX, box.minY, box.maxZ).endVertex();
        worldrenderer.pos(box.minX, box.maxY, box.maxZ).endVertex();
        worldrenderer.pos(box.minX, box.maxY, box.minZ).endVertex();
        worldrenderer.pos(box.minX, box.minY, box.minZ).endVertex();
        // East (+X)
        worldrenderer.pos(box.maxX, box.minY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.maxY, box.minZ).endVertex();
        worldrenderer.pos(box.maxX, box.maxY, box.maxZ).endVertex();
        worldrenderer.pos(box.maxX, box.minY, box.maxZ).endVertex();
        tessellator.draw();
    }
}
