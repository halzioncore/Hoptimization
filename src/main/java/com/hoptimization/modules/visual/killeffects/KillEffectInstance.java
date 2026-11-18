package com.hoptimization.modules.visual.killeffects;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

/**
 * Mutable pooled state for a single active client-side kill visual effect.
 */
public final class KillEffectInstance {
    public static final int MODE_SOUL_SEVER = 0;
    public static final int MODE_ANNIHILATION = 1;
    public static final int MODE_PHANTOM_SWORDS = 2;
    public static final int MODE_ABYSS_VORTEX = 3;

    public boolean active;
    public int mode;
    public int entityId;
    public EntityLivingBase entity;
    public long startNanos;
    public long durationNanos;
    public double x;
    public double y;
    public double z;
    public float entityWidth;
    public float entityHeight;
    public float yaw;
    public float behindDirX;
    public float behindDirZ;
    public ItemStack heldItem;
    public float weaponScale;
    public int soulArgb;
    public boolean particlesEnabled;
    public boolean soundEnabled;
    public boolean cleaveTriggered;
    public boolean burstSpawned;
    public int savedDeathTime;
    public int savedHurtTime;

    /**
     * Creates a cleared effect slot ready for pooling.
     */
    public KillEffectInstance() {
        clear();
    }

    /**
     * Computes normalized animation progress within {@code [0.0, 1.0]} for the given timestamp.
     *
     * @param nowNanos current monotonic timestamp from {@link System#nanoTime()}
     * @return progress in {@code [0.0, 1.0]}
     */
    public float getProgress(long nowNanos) {
        if (!this.active || this.durationNanos <= 0L) {
            return 1.0F;
        }
        long elapsed = nowNanos - this.startNanos;
        if (elapsed <= 0L) {
            return 0.0F;
        }
        if (elapsed >= this.durationNanos) {
            return 1.0F;
        }
        return (float) ((double) elapsed / (double) this.durationNanos);
    }

    /**
     * Resets all fields and releases entity/item references back to the pool.
     */
    public void clear() {
        this.active = false;
        this.mode = MODE_SOUL_SEVER;
        this.entityId = -1;
        this.entity = null;
        this.startNanos = 0L;
        this.durationNanos = 0L;
        this.x = 0.0D;
        this.y = 0.0D;
        this.z = 0.0D;
        this.entityWidth = 0.0F;
        this.entityHeight = 0.0F;
        this.yaw = 0.0F;
        this.behindDirX = 0.0F;
        this.behindDirZ = 0.0F;
        this.heldItem = null;
        this.weaponScale = 1.0F;
        this.soulArgb = 0;
        this.particlesEnabled = false;
        this.soundEnabled = false;
        this.cleaveTriggered = false;
        this.burstSpawned = false;
        this.savedDeathTime = 0;
        this.savedHurtTime = 0;
    }
}
