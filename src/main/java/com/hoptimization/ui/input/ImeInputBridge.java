package com.hoptimization.ui.input;

import com.hoptimization.HoptimizationMod;
import com.hoptimization.modules.misc.ChineseInput;
import com.hoptimization.ui.clickgui.ClickGuiScreen;
import com.hoptimization.ui.font.FontManager;
import com.hoptimization.ui.font.TtfFontRenderer;
import com.hoptimization.ui.hud.HudRenderHelper;
import com.hoptimization.ui.render.RenderUtil;
import com.hoptimization.ui.render.ScissorStack;
import com.hoptimization.ui.render.UiScale;
import com.hoptimization.ui.theme.ThemeData;
import com.hoptimization.ui.theme.ThemeManager;
import com.hoptimization.ui.theme.UiTokens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiCommandBlock;
import net.minecraft.client.gui.GuiRepair;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import javax.swing.JDialog;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.AttributedCharacterIterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges Windows system IME composition and committed CJK text from an off-display Swing host
 * into LWJGL 2 / Minecraft 1.8.9 input screens and the ClickGUI search bar without blocking gameplay.
 */
public final class ImeInputBridge {
    private static final long COMPOSITION_CLEAR_GRACE_NANOS = 25_000_000L;
    private static final long FOCUS_RESTORE_TIMEOUT_NANOS = 1_000_000_000L;
    private static final int HOST_MOVE_THRESHOLD_PX = 8;
    private static final int HOST_WIDTH_PX = 1;
    private static final int HOST_HEIGHT_PX = 16;
    private static final int WINDOW_TITLE_BAR_OFFSET_PX = 26;
    private static final int WIN_VK_PROCESSKEY = 0x00E5;

    private static final Method GUI_KEY_TYPED_METHOD = resolveGuiKeyTypedMethod();
    private static final Field BOOK_IS_UNSIGNED_FIELD = resolveField(
        GuiScreenBook.class,
        "field_146475_i",
        "bookIsUnsigned"
    );
    private static final Field CHAT_INPUT_FIELD = resolveField(
        GuiChat.class,
        "field_146415_a",
        "inputField"
    );
    private static final Field CMD_INPUT_FIELD = resolveField(
        GuiCommandBlock.class,
        "field_146485_f",
        "commandTextField"
    );
    private static final Field REPAIR_NAME_FIELD = resolveField(
        GuiRepair.class,
        "field_147091_w",
        "nameField"
    );
    private static final Field CREATIVE_SEARCH_FIELD = resolveField(
        GuiContainerCreative.class,
        "field_147062_A",
        "searchField"
    );

    private static final ImeInputBridge INSTANCE = new ImeInputBridge();

    private static final ConcurrentLinkedQueue<PendingAction> PENDING_ACTIONS =
        new ConcurrentLinkedQueue<PendingAction>();
    private static final AtomicReference<String> COMPOSITION_TEXT = new AtomicReference<String>("");

    private static volatile boolean hostActiveRequested = false;
    private static volatile boolean hostVisibleOnEdt = false;
    private static volatile TargetType activeTargetType = TargetType.NONE;
    private static volatile long lastCompositionClearedNanos = 0L;

    // Accessed strictly on the AWT Event Dispatch Thread.
    private static JDialog hostDialog;
    private static JTextField hostTextField;
    private static boolean hostDisposed = false;

    private final float[] boundsScratch = new float[6];
    private boolean registered = false;
    private TargetType lastTargetType = TargetType.NONE;
    private boolean lastDisplayActive = false;
    private int lastHostScreenX = Integer.MIN_VALUE;
    private int lastHostScreenY = Integer.MIN_VALUE;
    private boolean restoreIngameFocusPending = false;
    private long deactivatedAtNanos = 0L;
    private boolean loggedReflectFailure = false;

    private ImeInputBridge() {
    }

    /**
     * Returns the shared {@link ImeInputBridge} singleton.
     *
     * @return bridge singleton
     */
    public static ImeInputBridge getInstance() {
        return INSTANCE;
    }

    /**
     * Idempotently registers the IME bridge on {@link MinecraftForge#EVENT_BUS}.
     */
    public static synchronized void ensureRegistered() {
        INSTANCE.registerIfNeeded();
    }

    /**
     * Immediately hides the IME host and restores LWJGL focus when {@link ChineseInput} is disabled.
     */
    public static void onBridgeDisabled() {
        PENDING_ACTIONS.clear();
        COMPOSITION_TEXT.set("");
        activeTargetType = TargetType.NONE;
        INSTANCE.lastTargetType = TargetType.NONE;
        INSTANCE.deactivateHost(true);
    }

    private synchronized void registerIfNeeded() {
        if (this.registered) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            MinecraftForge.EVENT_BUS.register(this);
            this.registered = true;
        } catch (RuntimeException e) {
            HoptimizationMod.LOGGER.error("Failed to register ImeInputBridge on Forge EVENT_BUS", e);
        } catch (LinkageError ignored) {
            // Safe no-op in pure unit-test environments without Forge/LWJGL native initialization.
        }
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        PENDING_ACTIONS.clear();
        COMPOSITION_TEXT.set("");
        if (event.gui == null) {
            this.lastTargetType = TargetType.NONE;
            activeTargetType = TargetType.NONE;
            deactivateHost(true);
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (this.restoreIngameFocusPending) {
            tryRestoreIngameFocus(mc);
        }
        if (!ChineseInput.isBridgeEnabled()) {
            if (hostActiveRequested || hostVisibleOnEdt || this.lastTargetType != TargetType.NONE) {
                this.lastTargetType = TargetType.NONE;
                activeTargetType = TargetType.NONE;
                deactivateHost(mc.currentScreen == null);
            }
            return;
        }

        if (!PENDING_ACTIONS.isEmpty()) {
            drainPendingActions(mc);
        }
        syncHostState(mc);
        if (this.restoreIngameFocusPending) {
            tryRestoreIngameFocus(mc);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (this.restoreIngameFocusPending) {
            tryRestoreIngameFocus(mc);
        }
        if (ChineseInput.isBridgeEnabled()) {
            syncHostState(mc);
        }
    }

    @SubscribeEvent
    public void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!ChineseInput.isBridgeEnabled() || !ChineseInput.isShowCompositionBar()) {
            return;
        }
        String preedit = COMPOSITION_TEXT.get();
        if (preedit == null || preedit.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || event.gui == null || event.gui != mc.currentScreen) {
            return;
        }
        TargetType target = resolveActiveTarget(mc, this.boundsScratch);
        if (target == TargetType.NONE) {
            return;
        }
        renderCompositionBar(mc, target, preedit, this.boundsScratch);
    }

    private void drainPendingActions(Minecraft mc) {
        PendingAction action;
        while ((action = PENDING_ACTIONS.poll()) != null) {
            if (mc.currentScreen == null) {
                continue;
            }
            TargetType target = resolveActiveTarget(mc, null);
            if (target == TargetType.NONE) {
                continue;
            }
            if (action.text != null) {
                dispatchCommittedText(mc.currentScreen, target, action.text);
            } else {
                dispatchSpecialKey(mc, mc.currentScreen, target, action);
            }
        }
        if (mc.currentScreen == null) {
            this.lastTargetType = TargetType.NONE;
            activeTargetType = TargetType.NONE;
            deactivateHost(true);
        }
    }

    private void dispatchCommittedText(GuiScreen screen, TargetType target, String text) {
        if (text.isEmpty()) {
            return;
        }
        if (target == TargetType.CLICK_GUI && screen instanceof ClickGuiScreen) {
            ((ClickGuiScreen) screen).commitSearchText(text);
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ChatAllowedCharacters.isAllowedCharacter(ch)) {
                invokeGuiKeyTyped(screen, ch, Keyboard.KEY_NONE);
            }
        }
    }

    private void dispatchSpecialKey(Minecraft mc, GuiScreen screen, TargetType target, PendingAction action) {
        if (target == TargetType.CLICK_GUI && screen instanceof ClickGuiScreen) {
            ((ClickGuiScreen) screen).handleImeKeyTyped(action.typedChar, action.keyCode, action.ctrl, action.shift);
            return;
        }
        if (action.ctrl && !action.shift) {
            if (handleVanillaCtrlShortcut(screen, target, action.keyCode)) {
                return;
            }
        } else if (action.shift && handleVanillaShiftNavigation(screen, target, action.keyCode)) {
            return;
        }
        invokeGuiKeyTyped(screen, action.typedChar, action.keyCode);
        if ((action.keyCode == Keyboard.KEY_ESCAPE || action.keyCode == Keyboard.KEY_RETURN)
            && mc.currentScreen == null) {
            deactivateHost(true);
        }
    }

    private boolean handleVanillaCtrlShortcut(GuiScreen screen, TargetType target, int keyCode) {
        GuiTextField field = resolveVanillaTextField(screen, target);
        if (keyCode == Keyboard.KEY_V) {
            String clip = GuiScreen.getClipboardString();
            if (clip != null && !clip.isEmpty()) {
                if (field != null && !field.getSelectedText().isEmpty()) {
                    invokeGuiKeyTyped(screen, '\0', Keyboard.KEY_BACK);
                }
                String filtered = ChatAllowedCharacters.filterAllowedCharacters(clip);
                dispatchCommittedText(screen, target, filtered);
            }
            return true;
        }
        if (field == null) {
            return false;
        }
        if (keyCode == Keyboard.KEY_A) {
            field.setCursorPositionEnd();
            field.setSelectionPos(0);
            return true;
        }
        if (keyCode == Keyboard.KEY_C) {
            GuiScreen.setClipboardString(field.getSelectedText());
            return true;
        }
        if (keyCode == Keyboard.KEY_X) {
            String selected = field.getSelectedText();
            if (!selected.isEmpty()) {
                GuiScreen.setClipboardString(selected);
                invokeGuiKeyTyped(screen, '\0', Keyboard.KEY_BACK);
            }
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (!field.getSelectedText().isEmpty()) {
                invokeGuiKeyTyped(screen, '\0', Keyboard.KEY_BACK);
            } else {
                int targetPos = field.getNthWordFromCursor(-1);
                field.setSelectionPos(targetPos);
                invokeGuiKeyTyped(screen, '\0', Keyboard.KEY_BACK);
            }
            return true;
        }
        if (keyCode == Keyboard.KEY_DELETE) {
            if (!field.getSelectedText().isEmpty()) {
                invokeGuiKeyTyped(screen, '\0', Keyboard.KEY_DELETE);
            } else {
                int targetPos = field.getNthWordFromCursor(1);
                field.setSelectionPos(targetPos);
                invokeGuiKeyTyped(screen, '\0', Keyboard.KEY_DELETE);
            }
            return true;
        }
        if (keyCode == Keyboard.KEY_LEFT) {
            field.setCursorPosition(field.getNthWordFromCursor(-1));
            return true;
        }
        if (keyCode == Keyboard.KEY_RIGHT) {
            field.setCursorPosition(field.getNthWordFromCursor(1));
            return true;
        }
        return false;
    }

    private boolean handleVanillaShiftNavigation(GuiScreen screen, TargetType target, int keyCode) {
        GuiTextField field = resolveVanillaTextField(screen, target);
        if (field == null) {
            return false;
        }
        if (keyCode == Keyboard.KEY_LEFT) {
            field.setSelectionPos(field.getSelectionEnd() - 1);
            return true;
        }
        if (keyCode == Keyboard.KEY_RIGHT) {
            field.setSelectionPos(field.getSelectionEnd() + 1);
            return true;
        }
        if (keyCode == Keyboard.KEY_HOME) {
            field.setSelectionPos(0);
            return true;
        }
        if (keyCode == Keyboard.KEY_END) {
            field.setSelectionPos(field.getText().length());
            return true;
        }
        return false;
    }

    private void invokeGuiKeyTyped(GuiScreen screen, char typedChar, int keyCode) {
        if (GUI_KEY_TYPED_METHOD == null || screen == null) {
            return;
        }
        try {
            GUI_KEY_TYPED_METHOD.invoke(screen, Character.valueOf(typedChar), Integer.valueOf(keyCode));
        } catch (IllegalAccessException e) {
            logReflectErrorOnce("Failed to access GuiScreen.keyTyped", e);
        } catch (InvocationTargetException e) {
            HoptimizationMod.LOGGER.error("Exception while invoking GuiScreen.keyTyped via IME bridge", e.getCause());
        }
    }

    private void logReflectErrorOnce(String message, Throwable error) {
        if (!this.loggedReflectFailure) {
            this.loggedReflectFailure = true;
            HoptimizationMod.LOGGER.error(message, error);
        }
    }

    private void syncHostState(Minecraft mc) {
        boolean displayCreated = Display.isCreated();
        if (!displayCreated) {
            return;
        }
        if (Display.isCloseRequested()) {
            disposeHostAsync();
            return;
        }
        boolean displayActive = Display.isActive();
        boolean fullscreen = Display.isFullscreen();
        TargetType target = !fullscreen ? resolveActiveTarget(mc, this.boundsScratch) : TargetType.NONE;
        activeTargetType = target;

        if (target == TargetType.NONE) {
            if (this.lastTargetType != TargetType.NONE || hostActiveRequested || hostVisibleOnEdt) {
                deactivateHost(mc.currentScreen == null);
            }
            this.lastTargetType = TargetType.NONE;
            this.lastDisplayActive = displayActive;
            return;
        }

        int screenX = computeHostScreenX(mc, target, this.boundsScratch);
        int screenY = computeHostScreenY(mc, target, this.boundsScratch);

        boolean targetBecameActive = this.lastTargetType == TargetType.NONE;
        boolean displayBecameActive = !this.lastDisplayActive && displayActive;
        boolean shouldActivate = displayActive
            && (targetBecameActive || displayBecameActive || !hostActiveRequested);

        if (shouldActivate) {
            activateHost(screenX, screenY);
        } else if (hostActiveRequested && hostVisibleOnEdt
            && (Math.abs(screenX - this.lastHostScreenX) > HOST_MOVE_THRESHOLD_PX
                || Math.abs(screenY - this.lastHostScreenY) > HOST_MOVE_THRESHOLD_PX)) {
            moveHost(screenX, screenY);
        }

        this.lastTargetType = target;
        this.lastDisplayActive = displayActive;
    }

    private void activateHost(final int screenX, final int screenY) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        this.restoreIngameFocusPending = false;
        this.lastHostScreenX = screenX;
        this.lastHostScreenY = screenY;
        hostActiveRequested = true;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!hostActiveRequested || hostDisposed) {
                    return;
                }
                ensureSwingHostCreated();
                if (hostDialog == null || hostTextField == null) {
                    return;
                }
                Rectangle clamped = clampToMonitorBounds(screenX, screenY, HOST_WIDTH_PX, HOST_HEIGHT_PX);
                hostDialog.setBounds(clamped.x, clamped.y, HOST_WIDTH_PX, HOST_HEIGHT_PX);
                if (!hostDialog.isVisible()) {
                    hostDialog.setVisible(true);
                }
                hostVisibleOnEdt = true;
                hostDialog.toFront();
                hostDialog.requestFocus();
                hostTextField.requestFocusInWindow();
            }
        });
    }

    private void moveHost(final int screenX, final int screenY) {
        this.lastHostScreenX = screenX;
        this.lastHostScreenY = screenY;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                if (!hostActiveRequested || hostDialog == null || !hostDialog.isVisible()) {
                    return;
                }
                Rectangle clamped = clampToMonitorBounds(screenX, screenY, HOST_WIDTH_PX, HOST_HEIGHT_PX);
                hostDialog.setBounds(clamped.x, clamped.y, HOST_WIDTH_PX, HOST_HEIGHT_PX);
            }
        });
    }

    private void deactivateHost(boolean requestIngameFocusRestore) {
        COMPOSITION_TEXT.set("");
        hostActiveRequested = false;
        if (requestIngameFocusRestore) {
            this.restoreIngameFocusPending = true;
            this.deactivatedAtNanos = System.nanoTime();
        }
        if (!hostVisibleOnEdt && hostDialog == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                hideHostOnEdt();
            }
        });
    }

    private void disposeHostAsync() {
        hostActiveRequested = false;
        COMPOSITION_TEXT.set("");
        if (hostDialog == null) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                hostDisposed = true;
                hostVisibleOnEdt = false;
                if (hostDialog != null) {
                    hostDialog.setVisible(false);
                    hostDialog.dispose();
                    hostDialog = null;
                    hostTextField = null;
                }
            }
        });
    }

    private void tryRestoreIngameFocus(Minecraft mc) {
        if (mc.currentScreen != null) {
            this.restoreIngameFocusPending = false;
            return;
        }
        if ((System.nanoTime() - this.deactivatedAtNanos) > FOCUS_RESTORE_TIMEOUT_NANOS) {
            this.restoreIngameFocusPending = false;
            return;
        }
        if (!Display.isCreated()) {
            this.restoreIngameFocusPending = false;
            return;
        }
        // Pump pending Win32 WM_ACTIVATE/WM_SETFOCUS messages before checking Display.isActive().
        Display.processMessages();
        try {
            if (!Display.isCurrent()) {
                Display.makeCurrent();
            }
        } catch (LWJGLException e) {
            HoptimizationMod.LOGGER.warn("Failed to rebind LWJGL Display context after IME deactivation", e);
        }
        if (Display.isActive()) {
            if (!mc.inGameHasFocus) {
                KeyBinding.unPressAllKeys();
                mc.setIngameFocus();
            }
            if (Mouse.isCreated()) {
                Mouse.poll();
            }
            if (Keyboard.isCreated()) {
                Keyboard.poll();
            }
            this.restoreIngameFocusPending = false;
        }
    }

    private static void ensureSwingHostCreated() {
        if (hostDialog != null || hostDisposed) {
            return;
        }
        JDialog dialog = new JDialog((Frame) null);
        dialog.setUndecorated(true);
        dialog.setModal(false);
        dialog.setType(Window.Type.UTILITY);
        dialog.setFocusableWindowState(true);
        dialog.setAlwaysOnTop(true);
        dialog.setAutoRequestFocus(true);
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT)) {
                dialog.setOpacity(0.01f);
            }
        } catch (Throwable ignored) {
            // Fall back to opaque 1x16 window if translucency is unsupported on the host GPU/WM.
        }

        JTextField field = new JTextField();
        field.setBorder(null);
        field.enableInputMethods(true);
        field.setFocusTraversalKeysEnabled(false);

        field.addInputMethodListener(new InputMethodListener() {
            @Override
            public void inputMethodTextChanged(InputMethodEvent event) {
                handleInputMethodTextChanged(event);
            }

            @Override
            public void caretPositionChanged(InputMethodEvent event) {
                event.consume();
            }
        });

        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleHostKeyPressed(e);
            }

            @Override
            public void keyTyped(KeyEvent e) {
                handleHostKeyTyped(e);
            }
        });

        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                if (!COMPOSITION_TEXT.get().isEmpty()) {
                    lastCompositionClearedNanos = System.nanoTime();
                }
                COMPOSITION_TEXT.set("");
                hostActiveRequested = false;
                hideHostOnEdt();
            }
        });

        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(field, BorderLayout.CENTER);
        dialog.setBounds(0, 0, HOST_WIDTH_PX, HOST_HEIGHT_PX);

        hostDialog = dialog;
        hostTextField = field;
    }

    private static void hideHostOnEdt() {
        hostVisibleOnEdt = false;
        hostActiveRequested = false;
        if (hostDialog != null && hostDialog.isVisible()) {
            hostDialog.setVisible(false);
        }
    }

    private static void handleInputMethodTextChanged(InputMethodEvent event) {
        AttributedCharacterIterator text = event.getText();
        boolean wasComposing = !COMPOSITION_TEXT.get().isEmpty();
        if (text == null) {
            if (wasComposing) {
                lastCompositionClearedNanos = System.nanoTime();
            }
            COMPOSITION_TEXT.set("");
            event.consume();
            return;
        }

        int committedCount = event.getCommittedCharacterCount();
        StringBuilder committed = committedCount > 0 ? new StringBuilder(committedCount) : null;
        StringBuilder composed = null;

        char ch = text.first();
        int idx = 0;
        while (ch != AttributedCharacterIterator.DONE) {
            if (idx < committedCount) {
                committed.append(ch);
            } else {
                if (composed == null) {
                    composed = new StringBuilder(16);
                }
                composed.append(ch);
            }
            idx++;
            ch = text.next();
        }

        String composedStr = composed != null ? composed.toString() : "";
        if (wasComposing && composedStr.isEmpty()) {
            lastCompositionClearedNanos = System.nanoTime();
        }
        COMPOSITION_TEXT.set(composedStr);

        if (committed != null && committed.length() > 0) {
            PENDING_ACTIONS.offer(PendingAction.text(committed.toString()));
        }
        // Consuming here prevents JTextComponent from synthesizing duplicate KEY_TYPED events.
        event.consume();
    }

    private static void handleHostKeyPressed(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_UNDEFINED
            || code == WIN_VK_PROCESSKEY
            || code == KeyEvent.VK_INPUT_METHOD_ON_OFF
            || code == KeyEvent.VK_CONVERT
            || code == KeyEvent.VK_NONCONVERT
            || code == KeyEvent.VK_CODE_INPUT) {
            return;
        }
        if (!COMPOSITION_TEXT.get().isEmpty()) {
            return;
        }
        boolean recentlyCleared = (System.nanoTime() - lastCompositionClearedNanos) < COMPOSITION_CLEAR_GRACE_NANOS;
        boolean ctrl = Minecraft.isRunningOnMac
            ? e.isMetaDown()
            : (e.isControlDown() && !e.isAltDown());
        boolean shift = e.isShiftDown();

        if (code == KeyEvent.VK_ESCAPE) {
            if (recentlyCleared) {
                e.consume();
                return;
            }
            hideHostOnEdt();
            PENDING_ACTIONS.offer(PendingAction.key('\0', Keyboard.KEY_ESCAPE, false, false));
            e.consume();
            return;
        }
        if (code == KeyEvent.VK_ENTER) {
            if (recentlyCleared) {
                e.consume();
                return;
            }
            TargetType target = activeTargetType;
            if (target == TargetType.VANILLA_CHAT || target == TargetType.VANILLA_COMMAND) {
                hideHostOnEdt();
            }
            PENDING_ACTIONS.offer(PendingAction.key('\r', Keyboard.KEY_RETURN, false, false));
            e.consume();
            return;
        }
        if (code == KeyEvent.VK_BACK_SPACE) {
            if (recentlyCleared) {
                e.consume();
                return;
            }
            PENDING_ACTIONS.offer(PendingAction.key('\0', Keyboard.KEY_BACK, ctrl, shift));
            e.consume();
            return;
        }

        int mapped = mapSpecialKeyCode(code, ctrl);
        if (mapped != Keyboard.KEY_NONE) {
            char ch = mapped == Keyboard.KEY_TAB ? '\t' : '\0';
            PENDING_ACTIONS.offer(PendingAction.key(ch, mapped, ctrl, shift));
            e.consume();
        }
    }

    private static void handleHostKeyTyped(KeyEvent e) {
        if (e.isConsumed() || !COMPOSITION_TEXT.get().isEmpty()) {
            return;
        }
        boolean ctrl = Minecraft.isRunningOnMac
            ? e.isMetaDown()
            : (e.isControlDown() && !e.isAltDown());
        if (ctrl) {
            e.consume();
            return;
        }
        char ch = e.getKeyChar();
        if (ChatAllowedCharacters.isAllowedCharacter(ch)) {
            PENDING_ACTIONS.offer(PendingAction.text(String.valueOf(ch)));
            e.consume();
        }
    }

    private static int mapSpecialKeyCode(int awtCode, boolean ctrl) {
        switch (awtCode) {
            case KeyEvent.VK_DELETE:
                return Keyboard.KEY_DELETE;
            case KeyEvent.VK_TAB:
                return Keyboard.KEY_TAB;
            case KeyEvent.VK_UP:
                return Keyboard.KEY_UP;
            case KeyEvent.VK_DOWN:
                return Keyboard.KEY_DOWN;
            case KeyEvent.VK_LEFT:
                return Keyboard.KEY_LEFT;
            case KeyEvent.VK_RIGHT:
                return Keyboard.KEY_RIGHT;
            case KeyEvent.VK_HOME:
                return Keyboard.KEY_HOME;
            case KeyEvent.VK_END:
                return Keyboard.KEY_END;
            case KeyEvent.VK_PAGE_UP:
                return Keyboard.KEY_PRIOR;
            case KeyEvent.VK_PAGE_DOWN:
                return Keyboard.KEY_NEXT;
            default:
                break;
        }
        if (ctrl) {
            switch (awtCode) {
                case KeyEvent.VK_A:
                    return Keyboard.KEY_A;
                case KeyEvent.VK_C:
                    return Keyboard.KEY_C;
                case KeyEvent.VK_V:
                    return Keyboard.KEY_V;
                case KeyEvent.VK_X:
                    return Keyboard.KEY_X;
                default:
                    break;
            }
        }
        return Keyboard.KEY_NONE;
    }

    private static Rectangle clampToMonitorBounds(int x, int y, int w, int h) {
        Rectangle bestBounds = null;
        try {
            GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            for (int i = 0; i < devices.length; i++) {
                GraphicsConfiguration gc = devices[i].getDefaultConfiguration();
                Rectangle bounds = gc.getBounds();
                if (bounds.contains(x, y)) {
                    bestBounds = bounds;
                    break;
                }
                if (bestBounds == null) {
                    bestBounds = bounds;
                }
            }
        } catch (Throwable ignored) {
            // Ignore headless or restricted environment issues.
        }
        if (bestBounds == null) {
            return new Rectangle(Math.max(0, x), Math.max(0, y), w, h);
        }
        int maxX = Math.max(bestBounds.x, bestBounds.x + bestBounds.width - Math.max(16, w));
        int maxY = Math.max(bestBounds.y, bestBounds.y + bestBounds.height - Math.max(24, h));
        int clampedX = Math.min(Math.max(bestBounds.x, x), maxX);
        int clampedY = Math.min(Math.max(bestBounds.y, y), maxY);
        return new Rectangle(clampedX, clampedY, w, h);
    }

    private void renderCompositionBar(Minecraft mc, TargetType target, String preedit, float[] bounds) {
        int scaleFactor = computeMcScaleFactor(mc);
        float uiScale = target == TargetType.CLICK_GUI ? bounds[5] : UiScale.getScale();
        if (uiScale <= 0.0f) {
            uiScale = UiScale.MIN_SCALE;
        }

        float mcToLogical = (float) scaleFactor / uiScale;
        float boxX;
        float boxY;
        float boxW;
        float boxH;
        float caretX;
        if (target == TargetType.CLICK_GUI) {
            boxX = bounds[0];
            boxY = bounds[1];
            boxW = bounds[2];
            boxH = bounds[3];
            caretX = bounds[4];
        } else {
            boxX = bounds[0] * mcToLogical;
            boxY = bounds[1] * mcToLogical;
            boxW = bounds[2] * mcToLogical;
            boxH = bounds[3] * mcToLogical;
            caretX = bounds[4] * mcToLogical;
        }

        float logicalW = UiScale.getLogicalWidth(uiScale);
        float logicalH = UiScale.getLogicalHeight(uiScale);
        float scaleRatio = uiScale / (float) scaleFactor;
        ThemeData theme = ThemeManager.current();

        HudRenderHelper.GlStateSnapshot glSnapshot = HudRenderHelper.acquireSnapshot();
        glSnapshot.capture();
        GlStateManager.pushMatrix();
        float prevFontScale = FontManager.getUiScale();
        try {
            FontManager.setUiScale(uiScale);
            GlStateManager.scale(scaleRatio, scaleRatio, 1.0f);

            TtfFontRenderer font = FontManager.body();
            float padH = UiTokens.SPACE_8;
            float padV = UiTokens.SPACE_4;
            float accentBarW = UiTokens.SPACE_2;
            float innerGap = UiTokens.SPACE_6;

            float maxBarW = Math.max(64.0f, logicalW - UiTokens.SPACE_16);
            float maxTextW = Math.max(24.0f, maxBarW - padH * 2.0f - accentBarW - innerGap);
            String displayText = font.trimToWidth(preedit, maxTextW);
            float textW = font.getWidth(displayText);

            float barW = Math.min(maxBarW, Math.max(40.0f, textW + padH * 2.0f + accentBarW + innerGap));
            float barH = font.getHeight() + padV * 2.0f;

            float anchorX = target == TargetType.CLICK_GUI ? boxX : Math.max(boxX, caretX - UiTokens.SPACE_8);
            float maxBarX = Math.max(UiTokens.SPACE_4, logicalW - barW - UiTokens.SPACE_4);
            float barX = clampFloat(anchorX, UiTokens.SPACE_4, maxBarX);
            float barY = boxY - barH - UiTokens.SPACE_4;
            if (barY < UiTokens.SPACE_4) {
                barY = Math.min(logicalH - barH - UiTokens.SPACE_4, boxY + boxH + UiTokens.SPACE_4);
            }
            if (barY < UiTokens.SPACE_4) {
                barY = UiTokens.SPACE_4;
            }

            RenderUtil.drawRoundedRectWithShadow(
                barX,
                barY,
                barW,
                barH,
                theme.radiusControl(),
                theme.surface3(),
                theme.borderStrong(),
                UiTokens.BORDER_WIDTH,
                0.0f,
                UiTokens.SHADOW_POPUP_OFFSET_Y * 0.5f,
                UiTokens.SHADOW_POPUP_BLUR * 0.5f,
                theme.popupShadowColor()
            );

            float pillH = Math.max(4.0f, barH - padV * 2.0f);
            RenderUtil.drawRoundedRect(
                barX + padH,
                barY + padV,
                accentBarW,
                pillH,
                theme.radiusPill(accentBarW),
                theme.accent()
            );

            float textX = barX + padH + accentBarW + innerGap;
            float textY = barY + (barH - font.getHeight()) * 0.5f;
            font.drawString(displayText, textX, textY, theme.textPrimary());
        } finally {
            ScissorStack.clear();
            GlStateManager.popMatrix();
            FontManager.setUiScale(prevFontScale);
            glSnapshot.restore();
            HudRenderHelper.releaseSnapshot();
        }
    }

    private TargetType resolveActiveTarget(Minecraft mc, float[] outBounds) {
        if (mc == null || mc.currentScreen == null || !ChineseInput.isBridgeEnabled()) {
            return TargetType.NONE;
        }
        GuiScreen screen = mc.currentScreen;
        if (screen instanceof ClickGuiScreen) {
            if (!ChineseInput.isEnableInClickGui()) {
                return TargetType.NONE;
            }
            ClickGuiScreen clickGui = (ClickGuiScreen) screen;
            if (!clickGui.isSearchFocused()) {
                return TargetType.NONE;
            }
            if (outBounds != null) {
                clickGui.getSearchBoxLogicalBounds(outBounds);
            }
            return TargetType.CLICK_GUI;
        }

        if (!ChineseInput.isEnableInChat()) {
            return TargetType.NONE;
        }

        if (screen instanceof GuiChat) {
            if (outBounds != null) {
                resolveChatBounds(mc, (GuiChat) screen, outBounds);
            }
            return TargetType.VANILLA_CHAT;
        }
        if (screen instanceof GuiEditSign) {
            if (outBounds != null) {
                float srW = computeMcScaledWidth(mc);
                float srH = computeMcScaledHeight(mc);
                writeBounds(outBounds, srW * 0.5f - 45.0f, srH * 0.25f + 44.0f, 90.0f, 12.0f, srW * 0.5f - 45.0f, 1.0f);
            }
            return TargetType.VANILLA_SIGN;
        }
        if (screen instanceof GuiCommandBlock) {
            if (outBounds != null) {
                GuiTextField field = getTextField(CMD_INPUT_FIELD, screen);
                if (field != null) {
                    writeTextFieldBounds(field, outBounds);
                } else {
                    float srW = computeMcScaledWidth(mc);
                    writeBounds(outBounds, srW * 0.5f - 150.0f, 50.0f, 300.0f, 20.0f, srW * 0.5f - 150.0f, 1.0f);
                }
            }
            return TargetType.VANILLA_COMMAND;
        }
        if (screen instanceof GuiScreenBook) {
            if (!isBookEditable((GuiScreenBook) screen)) {
                return TargetType.NONE;
            }
            if (outBounds != null) {
                float srW = computeMcScaledWidth(mc);
                float bookLeft = (srW - 192.0f) * 0.5f;
                writeBounds(outBounds, bookLeft + 36.0f, 34.0f, 116.0f, 12.0f, bookLeft + 36.0f, 1.0f);
            }
            return TargetType.VANILLA_BOOK;
        }
        if (screen instanceof GuiRepair) {
            GuiTextField field = getTextField(REPAIR_NAME_FIELD, screen);
            if (field == null || !field.isFocused() || !field.getVisible()) {
                return TargetType.NONE;
            }
            if (outBounds != null) {
                writeTextFieldBounds(field, outBounds);
            }
            return TargetType.VANILLA_REPAIR;
        }
        if (screen instanceof GuiContainerCreative) {
            GuiContainerCreative creative = (GuiContainerCreative) screen;
            if (!isCreativeSearchTabActive(creative)) {
                return TargetType.NONE;
            }
            if (outBounds != null) {
                GuiTextField field = getTextField(CREATIVE_SEARCH_FIELD, screen);
                if (field != null) {
                    writeTextFieldBounds(field, outBounds);
                } else {
                    float srW = computeMcScaledWidth(mc);
                    float srH = computeMcScaledHeight(mc);
                    float guiLeft = (srW - 195.0f) * 0.5f;
                    float guiTop = (srH - 136.0f) * 0.5f;
                    writeBounds(outBounds, guiLeft + 82.0f, guiTop + 6.0f, 89.0f, 9.0f, guiLeft + 82.0f, 1.0f);
                }
            }
            return TargetType.VANILLA_CREATIVE;
        }

        return TargetType.NONE;
    }

    private void resolveChatBounds(Minecraft mc, GuiChat chat, float[] outBounds) {
        GuiTextField field = getTextField(CHAT_INPUT_FIELD, chat);
        if (field != null) {
            writeTextFieldBounds(field, outBounds);
            return;
        }
        float srW = computeMcScaledWidth(mc);
        float srH = computeMcScaledHeight(mc);
        writeBounds(outBounds, 4.0f, srH - 14.0f, Math.max(80.0f, srW - 8.0f), 12.0f, 4.0f, 1.0f);
    }

    private static void writeTextFieldBounds(GuiTextField field, float[] outBounds) {
        float x = field.xPosition;
        float y = field.yPosition;
        float w = field.width;
        float h = field.height;
        writeBounds(outBounds, x, y, w, h, x, 1.0f);
    }

    private static void writeBounds(
        float[] outBounds,
        float x,
        float y,
        float w,
        float h,
        float caretX,
        float scale
    ) {
        outBounds[0] = x;
        outBounds[1] = y;
        outBounds[2] = w;
        outBounds[3] = h;
        outBounds[4] = caretX;
        outBounds[5] = scale;
    }

    private GuiTextField resolveVanillaTextField(GuiScreen screen, TargetType target) {
        switch (target) {
            case VANILLA_CHAT:
                return getTextField(CHAT_INPUT_FIELD, screen);
            case VANILLA_COMMAND:
                return getTextField(CMD_INPUT_FIELD, screen);
            case VANILLA_REPAIR:
                return getTextField(REPAIR_NAME_FIELD, screen);
            case VANILLA_CREATIVE:
                return getTextField(CREATIVE_SEARCH_FIELD, screen);
            default:
                return null;
        }
    }

    private GuiTextField getTextField(Field field, GuiScreen screen) {
        if (field == null || screen == null) {
            return null;
        }
        try {
            Object val = field.get(screen);
            return val instanceof GuiTextField ? (GuiTextField) val : null;
        } catch (IllegalAccessException e) {
            logReflectErrorOnce("Failed to read GuiTextField via reflection", e);
            return null;
        }
    }

    private boolean isBookEditable(GuiScreenBook book) {
        if (BOOK_IS_UNSIGNED_FIELD == null || book == null) {
            return false;
        }
        try {
            return BOOK_IS_UNSIGNED_FIELD.getBoolean(book);
        } catch (IllegalAccessException e) {
            logReflectErrorOnce("Failed to read GuiScreenBook.bookIsUnsigned", e);
            return false;
        }
    }

    private static boolean isCreativeSearchTabActive(GuiContainerCreative creative) {
        int tabIndex = creative.getSelectedTabIndex();
        if (tabIndex < 0 || tabIndex >= CreativeTabs.creativeTabArray.length) {
            return false;
        }
        CreativeTabs tab = CreativeTabs.creativeTabArray[tabIndex];
        return tab != null && tab.hasSearchBar();
    }

    private static int computeHostScreenX(Minecraft mc, TargetType target, float[] bounds) {
        float scale = target == TargetType.CLICK_GUI ? bounds[5] : (float) computeMcScaleFactor(mc);
        int physX = Math.round(bounds[4] * scale);
        return Display.getX() + physX;
    }

    private static int computeHostScreenY(Minecraft mc, TargetType target, float[] bounds) {
        float scale = target == TargetType.CLICK_GUI ? bounds[5] : (float) computeMcScaleFactor(mc);
        int physY = Math.round((bounds[1] + bounds[3]) * scale);
        return Display.getY() + physY + WINDOW_TITLE_BAR_OFFSET_PX;
    }

    private static int computeMcScaleFactor(Minecraft mc) {
        int displayW = Math.max(1, mc.displayWidth);
        int displayH = Math.max(1, mc.displayHeight);
        int scaleFactor = 1;
        int guiScale = mc.gameSettings != null ? mc.gameSettings.guiScale : 0;
        if (guiScale == 0) {
            guiScale = 1000;
        }
        while (scaleFactor < guiScale && displayW / (scaleFactor + 1) >= 320 && displayH / (scaleFactor + 1) >= 240) {
            scaleFactor++;
        }
        if (mc.isUnicode() && (scaleFactor & 1) != 0 && scaleFactor != 1) {
            scaleFactor--;
        }
        return Math.max(1, scaleFactor);
    }

    private static float computeMcScaledWidth(Minecraft mc) {
        return (float) Math.ceil((double) Math.max(1, mc.displayWidth) / (double) computeMcScaleFactor(mc));
    }

    private static float computeMcScaledHeight(Minecraft mc) {
        return (float) Math.ceil((double) Math.max(1, mc.displayHeight) / (double) computeMcScaleFactor(mc));
    }

    private static float clampFloat(float value, float min, float max) {
        if (value <= min) {
            return min;
        }
        if (value >= max) {
            return max;
        }
        return value;
    }

    private static Method resolveGuiKeyTypedMethod() {
        String[] candidates = { "func_73869_a", "keyTyped" };
        for (int i = 0; i < candidates.length; i++) {
            try {
                Method m = GuiScreen.class.getDeclaredMethod(candidates[i], char.class, int.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // Try next SRG/MCP name.
            } catch (SecurityException e) {
                HoptimizationMod.LOGGER.error("Security restriction caching GuiScreen.keyTyped", e);
                return null;
            } catch (LinkageError ignored) {
                return null;
            }
        }
        HoptimizationMod.LOGGER.error("Unable to resolve GuiScreen.keyTyped / func_73869_a method");
        return null;
    }

    private static Field resolveField(Class<?> owner, String srgName, String mcpName) {
        String[] candidates = { srgName, mcpName };
        for (int i = 0; i < candidates.length; i++) {
            try {
                Field f = owner.getDeclaredField(candidates[i]);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // Try next SRG/MCP name.
            } catch (SecurityException e) {
                HoptimizationMod.LOGGER.error(
                    "Security restriction caching field {}.{}",
                    owner.getSimpleName(),
                    mcpName,
                    e
                );
                return null;
            } catch (LinkageError ignored) {
                return null;
            }
        }
        return null;
    }

    private enum TargetType {
        NONE,
        CLICK_GUI,
        VANILLA_CHAT,
        VANILLA_SIGN,
        VANILLA_COMMAND,
        VANILLA_BOOK,
        VANILLA_REPAIR,
        VANILLA_CREATIVE
    }

    private static final class PendingAction {
        final String text;
        final char typedChar;
        final int keyCode;
        final boolean ctrl;
        final boolean shift;

        private PendingAction(String text, char typedChar, int keyCode, boolean ctrl, boolean shift) {
            this.text = text;
            this.typedChar = typedChar;
            this.keyCode = keyCode;
            this.ctrl = ctrl;
            this.shift = shift;
        }

        static PendingAction text(String committed) {
            return new PendingAction(committed, '\0', Keyboard.KEY_NONE, false, false);
        }

        static PendingAction key(char typedChar, int keyCode, boolean ctrl, boolean shift) {
            return new PendingAction(null, typedChar, keyCode, ctrl, shift);
        }
    }
}
