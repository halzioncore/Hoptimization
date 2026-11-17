package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ColorSetting;
import com.hoptimization.core.setting.NumberSetting;

/**
 * Placeholder module for customizing the targeted block selection box.
 */
public class BlockOutline extends Module {
    private final ColorSetting color;
    private final NumberSetting lineWidth;
    private final BooleanSetting fill;

    /**
     * Creates the Block Outline placeholder module and registers its settings.
     */
    public BlockOutline() {
        super("block_outline", Category.VISUAL);
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

    @Override
    protected void onEnable() {
        // TODO: 占位模块，无功能
    }

    @Override
    protected void onDisable() {
        // TODO: 占位模块，无功能
    }
}
