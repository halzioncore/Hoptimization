package com.hoptimization.modules.visual;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import com.hoptimization.core.setting.NumberSetting;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.function.Supplier;

/**
 * Custom Atmosphere Changer featuring an abstract minimalist 3-stop sky dome with soft aurora glow
 * and geometric starlight, custom 3D wind-slanted rain streaks with ground splash ripples, and
 * an anti-glare soft display filter.
 */
public class Atmosphere extends Module {
    public static final String ID = "atmosphere";

    public static final String SKY_OFF = "off";
    public static final String SKY_MELANCHOLY_DUSK = "melancholy_dusk";
    public static final String SKY_MISTY_SLATE = "misty_slate";
    public static final String SKY_MIDNIGHT_INDIGO = "midnight_indigo";
    public static final String SKY_SOFT_OVERCAST = "soft_overcast";
    public static final String SKY_THEME_ACCENT = "theme_accent";

    public static final String WEATHER_OFF = "off";
    public static final String WEATHER_LIGHT_RAIN = "light_rain";
    public static final String WEATHER_HEAVY_RAIN = "heavy_rain";

    private static volatile Atmosphere instance;

    private final ModeSetting skyPreset;
    private final BooleanSetting showStars;
    private final ModeSetting weatherMode;
    private final BooleanSetting showRipples;
    private final BooleanSetting softDisplay;
    private final NumberSetting softStrength;
    private final BooleanSetting softFog;

    /**
     * Creates the Atmosphere module and registers its custom sky, weather, and soft-display settings.
     */
    public Atmosphere() {
        super(ID, Category.VISUAL);
        this.skyPreset = addSetting(
            new ModeSetting(
                "sky_preset",
                "setting.hoptimization.atmosphere.sky_preset.name",
                SKY_MISTY_SLATE,
                SKY_OFF,
                SKY_MELANCHOLY_DUSK,
                SKY_MISTY_SLATE,
                SKY_MIDNIGHT_INDIGO,
                SKY_SOFT_OVERCAST,
                SKY_THEME_ACCENT
            )
        );
        this.showStars = addSetting(
            new BooleanSetting("show_stars", "setting.hoptimization.atmosphere.show_stars.name", true)
                .visibleWhen(new Supplier<Boolean>() {
                    @Override
                    public Boolean get() {
                        return Boolean.valueOf(!skyPreset.is(SKY_OFF));
                    }
                })
        );
        this.weatherMode = addSetting(
            new ModeSetting(
                "weather",
                "setting.hoptimization.atmosphere.weather.name",
                WEATHER_LIGHT_RAIN,
                WEATHER_OFF,
                WEATHER_LIGHT_RAIN,
                WEATHER_HEAVY_RAIN
            )
        );
        this.showRipples = addSetting(
            new BooleanSetting("show_ripples", "setting.hoptimization.atmosphere.show_ripples.name", true)
                .visibleWhen(new Supplier<Boolean>() {
                    @Override
                    public Boolean get() {
                        return Boolean.valueOf(!weatherMode.is(WEATHER_OFF));
                    }
                })
        );
        this.softDisplay = addSetting(
            new BooleanSetting("soft_display", "setting.hoptimization.atmosphere.soft_display.name", true)
        );
        this.softStrength = addSetting(
            new NumberSetting("soft_strength", "setting.hoptimization.atmosphere.soft_strength.name", 0.20D, 0.05D, 0.50D, 0.05D)
                .visibleWhen(new Supplier<Boolean>() {
                    @Override
                    public Boolean get() {
                        return Boolean.valueOf(softDisplay.isEnabled());
                    }
                })
        );
        this.softFog = addSetting(
            new BooleanSetting("soft_fog", "setting.hoptimization.atmosphere.soft_fog.name", true)
        );
        instance = this;
    }

    public static boolean isActive() {
        Atmosphere inst = instance;
        return inst != null && inst.isEnabled();
    }

    public static boolean isCustomSkyEnabled() {
        Atmosphere inst = instance;
        return inst != null && inst.isEnabled() && !SKY_OFF.equals(inst.skyPreset.getValue());
    }

    public static boolean isStarsEnabled() {
        Atmosphere inst = instance;
        return inst == null || inst.showStars.isEnabled();
    }

    public static String getActiveSkyPreset() {
        Atmosphere inst = instance;
        return inst != null ? inst.skyPreset.getValue() : SKY_MISTY_SLATE;
    }

    public static boolean isCustomRainEnabled() {
        Atmosphere inst = instance;
        return inst != null && inst.isEnabled() && !WEATHER_OFF.equals(inst.weatherMode.getValue());
    }

    public static boolean isHeavyRain() {
        Atmosphere inst = instance;
        return inst != null && WEATHER_HEAVY_RAIN.equals(inst.weatherMode.getValue());
    }

    public static boolean isRipplesEnabled() {
        Atmosphere inst = instance;
        return inst == null || inst.showRipples.isEnabled();
    }

    /**
     * Resolves {@code [zenithRgb, midRgb, horizonRgb, auroraRgb]} for the active abstract sky preset.
     */
    public static void resolveSkyPalette(int[] outPalette) {
        String preset = getActiveSkyPreset();
        if (SKY_MELANCHOLY_DUSK.equals(preset)) {
            outPalette[0] = 0x1A1829;
            outPalette[1] = 0x332A42;
            outPalette[2] = 0x4E4259;
            outPalette[3] = 0x8C6B8E;
        } else if (SKY_MIDNIGHT_INDIGO.equals(preset)) {
            outPalette[0] = 0x0B0E1A;
            outPalette[1] = 0x151C33;
            outPalette[2] = 0x243152;
            outPalette[3] = 0x526FA8;
        } else if (SKY_SOFT_OVERCAST.equals(preset)) {
            outPalette[0] = 0x353E4A;
            outPalette[1] = 0x4E5A68;
            outPalette[2] = 0x6D7B8A;
            outPalette[3] = 0x99AAB8;
        } else if (SKY_THEME_ACCENT.equals(preset)) {
            int accent = ThemeManager.current().accent();
            int ar = (accent >>> 16) & 0xFF;
            int ag = (accent >>> 8) & 0xFF;
            int ab = accent & 0xFF;
            outPalette[0] = scaleRgb(ar, ag, ab, 0.16f, 12);
            outPalette[1] = scaleRgb(ar, ag, ab, 0.30f, 22);
            outPalette[2] = scaleRgb(ar, ag, ab, 0.48f, 36);
            outPalette[3] = accent & 0x00FFFFFF;
        } else {
            // SKY_MISTY_SLATE
            outPalette[0] = 0x18212E;
            outPalette[1] = 0x2B394C;
            outPalette[2] = 0x46586E;
            outPalette[3] = 0x7392B0;
        }
    }

    @SubscribeEvent
    public void onFogColors(EntityViewRenderEvent.FogColors event) {
        if (!isEnabled()) {
            return;
        }
        if (isCustomSkyEnabled()) {
            int[] palette = new int[4];
            resolveSkyPalette(palette);
            int horizon = palette[2];
            event.red = ((horizon >>> 16) & 0xFF) / 255.0f;
            event.green = ((horizon >>> 8) & 0xFF) / 255.0f;
            event.blue = (horizon & 0xFF) / 255.0f;
        } else if (this.softDisplay.isEnabled()) {
            float s = this.softStrength.getFloatValue() * 0.40f;
            event.red = event.red * (1.0f - s) + 0.36f * s;
            event.green = event.green * (1.0f - s) + 0.40f * s;
            event.blue = event.blue * (1.0f - s) + 0.46f * s;
        }
    }

    @SubscribeEvent
    public void onFogDensity(EntityViewRenderEvent.FogDensity event) {
        if (!isEnabled() || !this.softFog.isEnabled()) {
            return;
        }
        if (isCustomRainEnabled()) {
            event.density = isHeavyRain() ? 0.015f : 0.0085f;
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRenderOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (!isEnabled() || !this.softDisplay.isEnabled() || event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return;
        }
        ScaledResolution sr = event.resolution != null ? event.resolution : new ScaledResolution(mc);
        float w = (float) sr.getScaledWidth_double();
        float h = (float) sr.getScaledHeight_double();

        float alpha = this.softStrength.getFloatValue() * 0.25f;
        int tintRgb = SKY_MELANCHOLY_DUSK.equals(this.skyPreset.getValue()) ? 0x1A1624 : 0x10141C;
        RenderUtil.drawRect(0.0f, 0.0f, w, h, RenderUtil.withAlpha(0xFF000000 | tintRgb, alpha));
    }

    private static int scaleRgb(int r, int g, int b, float factor, int base) {
        int rr = Math.min(255, Math.round(r * factor + base));
        int gg = Math.min(255, Math.round(g * factor + base));
        int bb = Math.min(255, Math.round(b * factor + base));
        return (rr << 16) | (gg << 8) | bb;
    }
}
