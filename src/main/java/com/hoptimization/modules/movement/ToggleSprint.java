package com.hoptimization.modules.movement;

import com.hoptimization.core.module.Category;
import com.hoptimization.core.module.Module;
import com.hoptimization.core.setting.BooleanSetting;
import com.hoptimization.core.setting.ModeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Movement module providing toggleable/always sprint and optional in-world toggle sneak.
 */
public class ToggleSprint extends Module {
    public static final String ID = "toggle_sprint";
    public static final String MODE_TOGGLE = "toggle";
    public static final String MODE_ALWAYS = "always";

    private final ModeSetting mode;
    private final BooleanSetting toggleSneak;

    private boolean sprintToggled;
    private boolean sneakToggled;
    private boolean forcingSprint;
    private boolean forcingSneak;
    private int lastSprintKeyCode;
    private int lastSneakKeyCode;

    /**
     * Creates the Toggle Sprint module and registers its settings.
     */
    public ToggleSprint() {
        super(ID, Category.MOVEMENT);
        this.mode = addSetting(
            new ModeSetting(
                "mode",
                "setting.hoptimization.toggle_sprint.mode.name",
                MODE_TOGGLE,
                MODE_TOGGLE,
                MODE_ALWAYS
            )
        );
        this.toggleSneak = addSetting(
            new BooleanSetting("toggle_sneak", "setting.hoptimization.toggle_sprint.toggle_sneak.name", false)
        );

        this.toggleSneak.addChangeListener(new Runnable() {
            @Override
            public void run() {
                if (!toggleSneak.isEnabled()) {
                    sneakToggled = false;
                    releaseSneakKeybind();
                }
            }
        });
    }

    /**
     * Returns the sprint mode setting ({@code toggle} or {@code always}).
     *
     * @return sprint mode setting
     */
    public ModeSetting getModeSetting() {
        return this.mode;
    }

    /**
     * Returns the toggle sneak setting.
     *
     * @return toggle sneak setting
     */
    public BooleanSetting getToggleSneakSetting() {
        return this.toggleSneak;
    }

    /**
     * Returns whether sprint is currently latched by this module.
     *
     * @return true if enabled and either in {@code always} mode or toggled on
     */
    public boolean isSprintToggled() {
        if (!isEnabled()) {
            return false;
        }
        return this.mode.is(MODE_ALWAYS) || this.sprintToggled;
    }

    /**
     * Returns whether in-world sneak is currently toggled on.
     * Always returns false when disabled, when {@code toggle_sneak} is off, or while a GUI screen is open.
     *
     * @return true if toggle sneak is actively sneaking in-world
     */
    public boolean isSneakToggled() {
        if (!isEnabled() || !this.toggleSneak.isEnabled() || !this.sneakToggled) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && mc.theWorld != null && mc.currentScreen == null;
    }

    @Override
    protected void onEnable() {
        this.sprintToggled = true;
        this.sneakToggled = false;
        this.forcingSprint = false;
        this.forcingSneak = false;
        this.lastSprintKeyCode = 0;
        this.lastSneakKeyCode = 0;
    }

    @Override
    protected void onDisable() {
        this.sprintToggled = false;
        this.sneakToggled = false;
        releaseSprintKeybind();
        releaseSneakKeybind();
    }

    /**
     * Toggles sprint/sneak latch state on in-world keyboard presses and keeps key state synced.
     *
     * @param event Forge key input event
     */
    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!isEnabled() || !Keyboard.isCreated() || Keyboard.isRepeatEvent()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isInWorldActive(mc)) {
            return;
        }
        int keyCode = Keyboard.getEventKey() == 0 ? Keyboard.getEventCharacter() + 256 : Keyboard.getEventKey();
        if (keyCode <= Keyboard.KEY_NONE) {
            return;
        }
        if (Keyboard.getEventKeyState()) {
            handleKeyToggle(mc, keyCode);
        }
        syncKeyStates(mc);
    }

    /**
     * Supports sprint/sneak keybindings mapped to mouse buttons without interfering with GUI input.
     *
     * @param event Forge mouse input event
     */
    @SubscribeEvent
    public void onMouseInput(InputEvent.MouseInputEvent event) {
        if (!isEnabled() || !Mouse.isCreated()) {
            return;
        }
        int button = Mouse.getEventButton();
        if (button < 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isInWorldActive(mc)) {
            return;
        }
        int keyCode = button - 100;
        if (Mouse.getEventButtonState()) {
            handleKeyToggle(mc, keyCode);
        }
        syncKeyStates(mc);
    }

    /**
     * Maintains sprint/sneak keybinding states strictly in-world and releases them when any GUI opens.
     *
     * @param event Forge client tick event
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        if (!isInWorldActive(mc)) {
            if (mc.thePlayer == null || mc.theWorld == null) {
                // Reset latched sneak when leaving a world so rejoining never starts crouched.
                this.sneakToggled = false;
            }
            // Never hold sprint or sneak keys while a screen is open or outside a world.
            releaseKeyIfForcing(mc.gameSettings.keyBindSprint.getKeyCode(), true, false);
            releaseKeyIfForcing(mc.gameSettings.keyBindSneak.getKeyCode(), false, false);
            return;
        }
        syncKeyStates(mc);
    }

    private void handleKeyToggle(Minecraft mc, int keyCode) {
        int sprintKey = mc.gameSettings.keyBindSprint.getKeyCode();
        if (sprintKey != 0 && keyCode == sprintKey && this.mode.is(MODE_TOGGLE)) {
            this.sprintToggled = !this.sprintToggled;
            if (!this.sprintToggled) {
                KeyBinding.setKeyBindState(sprintKey, false);
                this.forcingSprint = false;
                this.lastSprintKeyCode = 0;
            }
        }
        if (this.toggleSneak.isEnabled()) {
            int sneakKey = mc.gameSettings.keyBindSneak.getKeyCode();
            if (sneakKey != 0 && keyCode == sneakKey) {
                this.sneakToggled = !this.sneakToggled;
                if (!this.sneakToggled) {
                    KeyBinding.setKeyBindState(sneakKey, false);
                    this.forcingSneak = false;
                    this.lastSneakKeyCode = 0;
                }
            }
        }
    }

    private void syncKeyStates(Minecraft mc) {
        int sprintKey = mc.gameSettings.keyBindSprint.getKeyCode();
        if (this.forcingSprint && this.lastSprintKeyCode != 0 && this.lastSprintKeyCode != sprintKey) {
            KeyBinding.setKeyBindState(this.lastSprintKeyCode, isPhysicalKeyDown(this.lastSprintKeyCode));
            this.forcingSprint = false;
            this.lastSprintKeyCode = 0;
        }
        if (isSprintToggled()) {
            if (sprintKey != 0) {
                KeyBinding.setKeyBindState(sprintKey, true);
                this.forcingSprint = true;
                this.lastSprintKeyCode = sprintKey;
            }
        } else {
            releaseKeyIfForcing(sprintKey, true, true);
        }

        int sneakKey = mc.gameSettings.keyBindSneak.getKeyCode();
        if (this.forcingSneak && this.lastSneakKeyCode != 0 && this.lastSneakKeyCode != sneakKey) {
            KeyBinding.setKeyBindState(this.lastSneakKeyCode, isPhysicalKeyDown(this.lastSneakKeyCode));
            this.forcingSneak = false;
            this.lastSneakKeyCode = 0;
        }
        if (isSneakToggled()) {
            if (sneakKey != 0) {
                KeyBinding.setKeyBindState(sneakKey, true);
                this.forcingSneak = true;
                this.lastSneakKeyCode = sneakKey;
            }
        } else {
            releaseKeyIfForcing(sneakKey, false, true);
        }
    }

    private void releaseSprintKeybind() {
        Minecraft mc = Minecraft.getMinecraft();
        int currentKey = (mc != null && mc.gameSettings != null) ? mc.gameSettings.keyBindSprint.getKeyCode() : 0;
        boolean restorePhysical = mc != null && mc.currentScreen == null;
        releaseKeyIfForcing(currentKey, true, restorePhysical);
    }

    private void releaseSneakKeybind() {
        Minecraft mc = Minecraft.getMinecraft();
        int currentKey = (mc != null && mc.gameSettings != null) ? mc.gameSettings.keyBindSneak.getKeyCode() : 0;
        boolean restorePhysical = mc != null && mc.currentScreen == null;
        releaseKeyIfForcing(currentKey, false, restorePhysical);
    }

    private void releaseKeyIfForcing(int currentKeyCode, boolean isSprint, boolean restorePhysical) {
        boolean wasForcing = isSprint ? this.forcingSprint : this.forcingSneak;
        int lastKeyCode = isSprint ? this.lastSprintKeyCode : this.lastSneakKeyCode;
        if (!wasForcing) {
            return;
        }
        if (lastKeyCode != 0 && lastKeyCode != currentKeyCode) {
            KeyBinding.setKeyBindState(lastKeyCode, restorePhysical && isPhysicalKeyDown(lastKeyCode));
        }
        if (currentKeyCode != 0) {
            KeyBinding.setKeyBindState(currentKeyCode, restorePhysical && isPhysicalKeyDown(currentKeyCode));
        }
        if (isSprint) {
            this.forcingSprint = false;
            this.lastSprintKeyCode = 0;
        } else {
            this.forcingSneak = false;
            this.lastSneakKeyCode = 0;
        }
    }

    private static boolean isInWorldActive(Minecraft mc) {
        return mc != null && mc.gameSettings != null && mc.thePlayer != null
            && mc.theWorld != null && mc.currentScreen == null;
    }

    private static boolean isPhysicalKeyDown(int keyCode) {
        if (keyCode > 0 && keyCode < Keyboard.KEYBOARD_SIZE) {
            return Keyboard.isCreated() && Keyboard.isKeyDown(keyCode);
        }
        if (keyCode < 0) {
            int mouseButton = keyCode + 100;
            return mouseButton >= 0 && Mouse.isCreated() && Mouse.isButtonDown(mouseButton);
        }
        return false;
    }
}
