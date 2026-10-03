/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.component;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.keybind.CommandKeybind;
import sbs.modid.client.core.keybind.Keys;

/**
 * A themed key-capture button used in the Command Keybinds table.
 *
 * <p>Built exactly like {@link SciFiButton} (extends {@link AbstractButton}, overrides
 * the non-final {@code extractContents} draw hook), so it inherits all focus / click
 * handling and shares the dark-blue card look. Clicking it enters "listening" mode; the
 * next input is captured and handed to the {@link KeyAssigner} – a key press via
 * {@link #keyPressed(KeyEvent)} (the button is focused after the click, so the screen routes key
 * events to it), any of the eight mouse buttons via {@link #mouseClicked}, or a wheel direction via
 * {@link #captureScroll}. {@code Escape} unbinds; every other key, {@code Backspace} included, is
 * bound as-is. To back out without changing anything, left-click the button again or click anywhere
 * else (which drops focus).
 *
 * <p>The button always renders the live name from its {@link CommandKeybind} model, so a successful
 * re-bind needs no rebuild; a rejected key briefly shows a warning tint until the next interaction.
 */
public class SciFiKeybindButton extends AbstractButton implements KeyCaptureWidget {

    /** Captures the chosen input. Returns {@code true} if accepted, {@code false} if rejected. */
    @FunctionalInterface
    public interface KeyAssigner {
        boolean assign(int keyCode);
    }

    /** GLFW {@code GLFW_KEY_ESCAPE} – unbinds the key while listening. */
    private static final int KEY_ESCAPE = 256;

    private final CommandKeybind keybind;
    private final KeyAssigner assigner;

    private boolean listening;
    private boolean denied;

    public SciFiKeybindButton(int x, int y, int width, int height,
                              CommandKeybind keybind, KeyAssigner assigner) {
        super(x, y, width, height, Component.literal("Keybind"));
        this.keybind = keybind;
        this.assigner = assigner;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        // Click → start listening for the next key. Clear any previous rejection state.
        this.listening = true;
        this.denied = false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.listening) {
            int key = event.key();
            // Escape = unbind; every other key, Backspace included, binds as itself.
            assign(key == KEY_ESCAPE ? CommandKeybind.UNBOUND : key);
            return true; // consume (also stops Escape from closing the screen)
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        boolean over = event.x() >= getX() && event.x() < getX() + getWidth()
                && event.y() >= getY() && event.y() < getY() + getHeight();
        if (this.listening && over && event.button() == 0) {
            stopListening();   // left-click the button again = cancel, binding unchanged
            return true;
        }
        if (this.listening && over) {
            // Bind a mouse button - any of the eight, not only the three the hand rests on.
            assign(Keys.ofMouseButton(event.button()));
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.listening && scrollY != 0) {
            captureScroll(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isListening() {
        return this.listening;
    }

    @Override
    public boolean captureScroll(double scrollY) {
        assign(Keys.ofWheel(scrollY));
        return true;
    }

    /**
     * Hands a code to the assigner and leaves listening mode, remembering a rejection.
     *
     * <p>A {@code 0} out of {@link Keys} means "nothing bindable" (a mouse button past the eight
     * this build stores), and here that is the same thing as {@link CommandKeybind#UNBOUND} – the
     * model's own sentinel, so the row reads "None" rather than pretending to hold key zero.
     */
    private void assign(int code) {
        this.denied = !this.assigner.assign(code == 0 ? CommandKeybind.UNBOUND : code);
        this.listening = false;
        setFocused(false);
    }

    private void stopListening() {
        this.listening = false;
        setFocused(false);
    }

    /**
     * Clicking anything else ends the capture, so a field the player walked away from does not sit
     * on "..." and swallow the next key typed on the screen.
     */
    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused) {
            this.listening = false;
        }
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        int textY = y + (h - font.lineHeight) / 2;

        boolean hovered = isHoveredOrFocused() && this.active;
        int bg = hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG;
        int border = this.listening
                ? SBSTheme.ACCENT
                : (this.denied ? SBSTheme.WARN : (hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER));

        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS, bg, border);

        // Same color language as SciFiKeyCaptureButton (the standard settings-row keybind field):
        // bound key = accent blue, unbound = muted, listening = bright.
        Component label = this.listening
                ? KeyCaptureWidget.listeningHint(font, w - 8) : keybind.keyDisplayName();
        int color = this.listening
                ? SBSTheme.ACCENT_BRIGHT
                : (this.denied ? SBSTheme.WARN : (keybind.isBound() ? SBSTheme.ACCENT : SBSTheme.TEXT_MUTED));

        g.centeredText(font, label, x + w / 2, textY, color);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
