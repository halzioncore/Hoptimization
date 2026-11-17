package com.hoptimization.modules.visual;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.module.ModuleManager;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.function.Supplier;

/**
 * Client-side visual world time override supporting presets and custom tick values.
 */
public class TimeChanger extends Module {
    public static final String ID = "time_changer";
    public static final String MODE_DAY = "day";
    public static final String MODE_SUNSET = "sunset";
    public static final String MODE_NIGHT = "night";
    public static final String MODE_MIDNIGHT = "midnight";
    public static final String MODE_CUSTOM = "custom";

    private static final long TIME_DAY = 6000L;
    private static final long TIME_SUNSET = 12500L;
    private static final long TIME_NIGHT = 14000L;
    private static final long TIME_MIDNIGHT = 18000L;

    private final ModeSetting mode;
    private final NumberSetting time;

    private World trackedWorld;
    private boolean hasSavedVanillaTime;
    private long savedVanillaWorldTime;
    private boolean savedDaylightCycle;

    /**
     * Creates the Time Changer module and registers its settings.
     */
    public TimeChanger() {
        super(ID, Category.VISUAL);
        this.mode = addSetting(
            new ModeSetting(
                "mode",
                "setting.hoptimization.time_changer.mode.name",
                MODE_DAY,
                MODE_DAY,
                MODE_SUNSET,
                MODE_NIGHT,
                MODE_MIDNIGHT,
                MODE_CUSTOM
            )
        );
        this.time = addSetting(
            new NumberSetting("time", "setting.hoptimization.time_changer.time.name", 6000.0D, 0.0D, 24000.0D, 200.0D)
                .visibleWhen(new Supplier<Boolean>() {
                    @Override
                    public Boolean get() {
                        return Boolean.valueOf(mode.is(MODE_CUSTOM));
                    }
                })
        );

        Runnable applyIfEnabled = new Runnable() {
            @Override
            public void run() {
                if (isEnabled()) {
                    applyVisualTimeNow();
                }
            }
        };
        this.mode.addChangeListener(applyIfEnabled);
        this.time.addChangeListener(applyIfEnabled);
    }

    /**
     * Returns the time preset mode setting.
     *
     * @return mode setting
     */
    public ModeSetting getModeSetting() {
        return this.mode;
    }

    /**
     * Returns the custom world time tick setting ({@code 0..24000}).
     *
     * @return custom time setting
     */
    public NumberSetting getTimeSetting() {
        return this.time;
    }

    /**
     * Computes the currently selected target world time in ticks.
     *
     * @return target client world time
     */
    public long getTargetTime() {
        String current = this.mode.getValue();
        if (MODE_SUNSET.equalsIgnoreCase(current)) {
            return TIME_SUNSET;
        }
        if (MODE_NIGHT.equalsIgnoreCase(current)) {
            return TIME_NIGHT;
        }
        if (MODE_MIDNIGHT.equalsIgnoreCase(current)) {
            return TIME_MIDNIGHT;
        }
        if (MODE_CUSTOM.equalsIgnoreCase(current)) {
            return Math.round(this.time.getDoubleValue());
        }
        return TIME_DAY;
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.theWorld != null) {
            captureVanillaTime(mc.theWorld);
            applyVisualTimeNow();
        } else {
            this.trackedWorld = null;
            this.hasSavedVanillaTime = false;
        }
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getMinecraft();
        if (this.hasSavedVanillaTime && mc != null && mc.theWorld != null && mc.theWorld == this.trackedWorld) {
            mc.theWorld.getWorldInfo().setWorldTime(this.savedVanillaWorldTime);
            mc.theWorld.getGameRules().setOrCreateGameRule(
                "doDaylightCycle",
                this.savedDaylightCycle ? "true" : "false"
            );
        }
        this.trackedWorld = null;
        this.hasSavedVanillaTime = false;
    }

    /**
     * Keeps client world time overridden after world ticks while advancing cached vanilla time in the background.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            this.trackedWorld = null;
            this.hasSavedVanillaTime = false;
            return;
        }
        if (mc.theWorld != this.trackedWorld || !this.hasSavedVanillaTime) {
            captureVanillaTime(mc.theWorld);
        } else if (this.savedDaylightCycle) {
            this.savedVanillaWorldTime++;
        }
        mc.theWorld.getWorldInfo().setWorldTime(getTargetTime());
    }

    /**
     * Records server time updates received on the client thread and re-applies the visual override.
     *
     * @param packetWorldTime raw world time from {@code S03PacketTimeUpdate}
     */
    public static void onServerTimePacket(long packetWorldTime) {
        ModuleManager manager = HoptimizationMod.getModuleManager();
        TimeChanger module = manager != null ? manager.getModule(TimeChanger.class) : null;
        if (module == null || !module.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return;
        }
        module.trackedWorld = mc.theWorld;
        module.savedDaylightCycle = packetWorldTime >= 0L;
        module.savedVanillaWorldTime = packetWorldTime < 0L ? -packetWorldTime : packetWorldTime;
        module.hasSavedVanillaTime = true;
        mc.theWorld.getWorldInfo().setWorldTime(module.getTargetTime());
    }

    private void captureVanillaTime(World world) {
        this.trackedWorld = world;
        this.savedVanillaWorldTime = world.getWorldTime();
        this.savedDaylightCycle = world.getGameRules().getBoolean("doDaylightCycle");
        this.hasSavedVanillaTime = true;
    }

    private void applyVisualTimeNow() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.theWorld != null) {
            mc.theWorld.getWorldInfo().setWorldTime(getTargetTime());
        }
    }
}
