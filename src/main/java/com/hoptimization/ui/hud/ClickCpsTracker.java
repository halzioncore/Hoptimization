package com.hoptimization.ui.hud;

import com.hoptimization.HoptimizationMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Mouse;

/**
 * Shared zero-allocation LMB/RMB click tracker using fixed-size ring buffers over a 1000 ms sliding window.
 * Registers on {@link MinecraftForge#EVENT_BUS} only while at least one consumer module needs CPS tracking.
 */
public final class ClickCpsTracker {
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final int BUFFER_CAPACITY = 512;
    private static final ClickCpsTracker INSTANCE = new ClickCpsTracker();

    private final TimestampRing leftClicks = new TimestampRing(BUFFER_CAPACITY);
    private final TimestampRing rightClicks = new TimestampRing(BUFFER_CAPACITY);

    private int activeConsumers = 0;
    private boolean registered = false;

    private ClickCpsTracker() {
    }

    /**
     * Returns the shared CPS tracker singleton.
     *
     * @return tracker instance
     */
    public static ClickCpsTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Pre-registers the shared mouse listener during FML initialization while keeping tracking inactive
     * until at least one consumer acquires it.
     */
    public static void ensureRegistered() {
        INSTANCE.registerIfNeeded();
    }

    /**
     * Increments the active consumer reference count and subscribes to mouse input events when needed.
     */
    public synchronized void acquire() {
        this.activeConsumers++;
        if (this.activeConsumers > 0) {
            registerIfNeeded();
        }
    }

    /**
     * Decrements the active consumer reference count and clears buffered timestamps when unused.
     */
    public synchronized void release() {
        if (this.activeConsumers > 0) {
            this.activeConsumers--;
        }
        if (this.activeConsumers == 0) {
            this.leftClicks.clear();
            this.rightClicks.clear();
        }
    }

    /**
     * Returns whether at least one enabled module is currently consuming CPS tracking.
     *
     * @return {@code true} if active consumers exist
     */
    public synchronized boolean hasActiveConsumers() {
        return this.activeConsumers > 0;
    }

    private synchronized void registerIfNeeded() {
        if (this.registered || Minecraft.getMinecraft() == null) {
            return;
        }
        try {
            MinecraftForge.EVENT_BUS.register(this);
            this.registered = true;
        } catch (RuntimeException e) {
            HoptimizationMod.LOGGER.error("Failed to register ClickCpsTracker on Forge EVENT_BUS", e);
        }
    }

    /**
     * Records an in-game left or right mouse button press at the current monotonic timestamp.
     *
     * @param button mouse button index ({@code 0} = LMB, {@code 1} = RMB)
     */
    public void recordClick(int button) {
        recordClickAt(button, System.nanoTime());
    }

    /**
     * Records a mouse button press at an explicit monotonic nanosecond timestamp.
     *
     * @param button   mouse button index ({@code 0} = LMB, {@code 1} = RMB)
     * @param nowNanos monotonic timestamp from {@link System#nanoTime()}
     */
    public void recordClickAt(int button, long nowNanos) {
        if (button == 0) {
            this.leftClicks.push(nowNanos);
        } else if (button == 1) {
            this.rightClicks.push(nowNanos);
        }
    }

    /**
     * Returns the left-click count within the last 1000 ms without allocating.
     *
     * @return current left CPS
     */
    public int getLeftCps() {
        return this.leftClicks.countSince(System.nanoTime() - WINDOW_NANOS);
    }

    /**
     * Returns the right-click count within the last 1000 ms without allocating.
     *
     * @return current right CPS
     */
    public int getRightCps() {
        return this.rightClicks.countSince(System.nanoTime() - WINDOW_NANOS);
    }

    /**
     * Returns the left-click count at an explicit monotonic timestamp (for deterministic tests).
     *
     * @param nowNanos current timestamp in nanoseconds
     * @return left CPS within {@code [nowNanos - 1000ms, nowNanos]}
     */
    public int getLeftCpsAt(long nowNanos) {
        return this.leftClicks.countSince(nowNanos - WINDOW_NANOS);
    }

    /**
     * Returns the right-click count at an explicit monotonic timestamp (for deterministic tests).
     *
     * @param nowNanos current timestamp in nanoseconds
     * @return right CPS within {@code [nowNanos - 1000ms, nowNanos]}
     */
    public int getRightCpsAt(long nowNanos) {
        return this.rightClicks.countSince(nowNanos - WINDOW_NANOS);
    }

    /**
     * Returns whether this tracker is currently subscribed to Forge's event bus.
     *
     * @return {@code true} if registered
     */
    public synchronized boolean isRegistered() {
        return this.registered;
    }

    @SubscribeEvent
    public void onMouseInput(InputEvent.MouseInputEvent event) {
        if (this.activeConsumers <= 0 || !Mouse.getEventButtonState()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null) {
            return;
        }
        int button = Mouse.getEventButton();
        if (button == 0 || button == 1) {
            recordClickAt(button, System.nanoTime());
        }
    }

    /**
     * Fixed-capacity circular timestamp buffer requiring zero per-click allocations.
     */
    private static final class TimestampRing {
        private final long[] timestamps;
        private int head = 0;
        private int tail = 0;
        private int size = 0;

        TimestampRing(int capacity) {
            this.timestamps = new long[capacity];
        }

        void push(long timestampNanos) {
            if (this.size == this.timestamps.length) {
                this.head = (this.head + 1) % this.timestamps.length;
                this.size--;
            }
            this.timestamps[this.tail] = timestampNanos;
            this.tail = (this.tail + 1) % this.timestamps.length;
            this.size++;
        }

        int countSince(long cutoffNanos) {
            while (this.size > 0 && this.timestamps[this.head] < cutoffNanos) {
                this.head = (this.head + 1) % this.timestamps.length;
                this.size--;
            }
            return this.size;
        }

        void clear() {
            this.head = 0;
            this.tail = 0;
            this.size = 0;
        }
    }
}
