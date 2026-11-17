package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Optical zoom module supporting hold/toggle activation, smooth FOV easing, and smooth camera.
 */
public class Zoom extends Module {
    public static final String ID = "zoom";
    public static final String MODE_HOLD = "hold";
    public static final String MODE_TOGGLE = "toggle";

    private final NumberSetting fov;
    private final BooleanSetting smoothZoom;
    private final BooleanSetting smoothCamera;
    private final ModeSetting mode;

    private boolean previousSmoothCamera;
    private boolean hasSavedSmoothCamera;
    private float currentMultiplier = 1.0F;
    private long lastUpdateNanos;

    /**
     * Creates the Zoom module with default keybind {@code C}.
     */
    public Zoom() {
        super(ID, Category.VISUAL, Keyboard.KEY_C);
        this.fov = addSetting(
            new NumberSetting("fov", "setting.hoptimization.zoom.fov.name", 20.0D, 10.0D, 60.0D, 1.0D)
        );
        this.smoothZoom = addSetting(
            new BooleanSetting("smooth_zoom", "setting.hoptimization.zoom.smooth_zoom.name", true)
        );
        this.smoothCamera = addSetting(
            new BooleanSetting("smooth_camera", "setting.hoptimization.zoom.smooth_camera.name", true)
        );
        this.mode = addSetting(
            new ModeSetting(
                "mode",
                "setting.hoptimization.zoom.mode.name",
                MODE_HOLD,
                MODE_HOLD,
                MODE_TOGGLE
            )
        );

        this.smoothCamera.addChangeListener(new Runnable() {
            @Override
            public void run() {
                if (!isEnabled() || !hasSavedSmoothCamera) {
                    return;
                }
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.gameSettings != null) {
                    mc.gameSettings.smoothCamera = smoothCamera.isEnabled() || previousSmoothCamera;
                }
            }
        });
    }

    /**
     * Returns the target zoomed FOV setting.
     *
     * @return target FOV setting
     */
    public NumberSetting getFovSetting() {
        return this.fov;
    }

    /**
     * Returns the smooth zoom transition setting.
     *
     * @return smooth zoom setting
     */
    public BooleanSetting getSmoothZoomSetting() {
        return this.smoothZoom;
    }

    /**
     * Returns the cinematic smooth camera setting.
     *
     * @return smooth camera setting
     */
    public BooleanSetting getSmoothCameraSetting() {
        return this.smoothCamera;
    }

    /**
     * Returns the activation mode setting ({@code hold} or {@code toggle}).
     *
     * @return mode setting
     */
    public ModeSetting getModeSetting() {
        return this.mode;
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.gameSettings != null) {
            this.previousSmoothCamera = mc.gameSettings.smoothCamera;
            this.hasSavedSmoothCamera = true;
            if (this.smoothCamera.isEnabled()) {
                mc.gameSettings.smoothCamera = true;
            }
        }
        this.currentMultiplier = 1.0F;
        this.lastUpdateNanos = System.nanoTime();
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getMinecraft();
        if (this.hasSavedSmoothCamera && mc != null && mc.gameSettings != null) {
            mc.gameSettings.smoothCamera = this.previousSmoothCamera;
        }
        this.hasSavedSmoothCamera = false;
        this.currentMultiplier = 1.0F;
    }

    /**
     * Automatically releases zoom in {@code hold} mode once the bound key is no longer pressed.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled()) {
            return;
        }
        if (this.mode.is(MODE_HOLD)) {
            int key = getKeybind();
            if (key <= Keyboard.KEY_NONE || !Keyboard.isCreated() || !Keyboard.isKeyDown(key)) {
                setEnabled(false);
            }
        }
    }

    /**
     * Applies the configured zoomed FOV multiplier smoothly via Forge's FOV update hook.
     *
     * @param event Forge FOV update event
     */
    @SubscribeEvent
    public void onFovUpdate(FOVUpdateEvent event) {
        if (!isEnabled() || event == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null || event.entity != mc.thePlayer) {
            return;
        }
        float baseFov = mc.gameSettings.fovSetting;
        if (baseFov <= 1.0F) {
            baseFov = 70.0F;
        }
        float target = this.fov.getFloatValue() / baseFov;
        if (target < 0.05F) {
            target = 0.05F;
        } else if (target > 1.0F) {
            target = 1.0F;
        }

        if (!this.smoothZoom.isEnabled()) {
            this.currentMultiplier = target;
            event.newfov = target;
            return;
        }

        long now = System.nanoTime();
        float dtSeconds = (now - this.lastUpdateNanos) / 1.0E9F;
        this.lastUpdateNanos = now;
        if (dtSeconds < 0.0F || dtSeconds > 0.25F) {
            dtSeconds = 0.05F;
        }
        float blend = 1.0F - (float) Math.exp(-16.0D * dtSeconds);
        this.currentMultiplier += (target - this.currentMultiplier) * blend;
        event.newfov = this.currentMultiplier;
    }
}
