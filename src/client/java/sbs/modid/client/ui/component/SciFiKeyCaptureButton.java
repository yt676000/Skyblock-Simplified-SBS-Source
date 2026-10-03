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
import sbs.modid.client.core.keybind.Keys;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * A themed key-capture settings row: shows "{label}: {key}", click → "..." listening mode, and the
 * next input is stored through the supplied setter. A generic sibling of {@link SciFiKeybindButton}
 * that binds a plain int config field instead of a {@code CommandKeybind}.
 *
 * <p>Anything the player can press is bindable: a key, any of the eight mouse buttons, or a wheel
 * direction – all one {@link Keys} code, so the field itself does not change shape.
 *
 * <p>{@link #holdOnly} rows are the exception. A feature that asks "is this held right now"
 * (Zoom, Slot Lock, the Far Terrain toggles) cannot be driven by the wheel, which is a press with no
 * held state – so those refuse it and say <i>Hold only</i> in the field rather than storing a bind
 * that would never fire.
 *
 * <p>While listening, {@code Escape} unbinds and every other key – {@code Backspace} included – is
 * bound. Right-clicking a bound field also unbinds it; clicking elsewhere cancels.
 */
public class SciFiKeyCaptureButton extends AbstractButton implements KeyCaptureWidget {

    /** GLFW {@code GLFW_KEY_ESCAPE} – unbinds while listening. */
    private static final int KEY_ESCAPE = 256;

    private final String label;
    private final IntSupplier getter;
    private final IntConsumer setter;
    /** Whether the bound feature polls "is it held", which rules the wheel out. See the class doc. */
    private final boolean holdOnly;

    private boolean listening;
    /** Set when a wheel bind was refused; shown as text until the next interaction. */
    private boolean refusedWheel;

    public SciFiKeyCaptureButton(int x, int y, int width, int height,
                                 String label, IntSupplier getter, IntConsumer setter) {
        this(x, y, width, height, label, getter, setter, false);
    }

    public SciFiKeyCaptureButton(int x, int y, int width, int height,
                                 String label, IntSupplier getter, IntConsumer setter,
                                 boolean holdOnly) {
        super(x, y, width, height, Component.literal(label));
        this.label = label;
        this.getter = getter;
        this.setter = setter;
        this.holdOnly = holdOnly;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        this.listening = true;
        this.refusedWheel = false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (this.listening) {
            int key = event.key();
            // Escape = unbind; every other key, Backspace included, binds as itself.
            bind(key == KEY_ESCAPE ? 0 : key);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        boolean over = event.x() >= getX() && event.x() < getX() + getWidth()
                && event.y() >= getY() && event.y() < getY() + getHeight();
        if (this.listening && over) {
            // Bind a mouse button - any of the eight, not only the three the hand rests on.
            bind(Keys.ofMouseButton(event.button()));
            return true;
        }
        // Right-click a bound field to unbind it (a discoverable "clear", no listening needed).
        if (!this.listening && over && event.button() == 1) {
            this.setter.accept(0);
            this.refusedWheel = false;
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
        if (this.holdOnly) {
            this.listening = false;
            this.refusedWheel = true;
            setFocused(false);
            return false;
        }
        bind(Keys.ofWheel(scrollY));
        return true;
    }

    /** Stores a code and leaves listening mode – the one exit every capture path goes through. */
    private void bind(int code) {
        this.setter.accept(code);
        this.listening = false;
        this.refusedWheel = false;
        setFocused(false);
    }

    /**
     * Clicking anything else ends the capture. Without this the field keeps showing "..." forever
     * after the player changes their mind, and the next key typed anywhere would land in it.
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
        int border = this.listening ? SBSTheme.ACCENT
                : (this.refusedWheel ? SBSTheme.WARN
                : (hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER));
        SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS, bg, border);

        g.text(font, Component.literal(label), x + 8, textY, SBSTheme.TEXT);
        // The refusal is spelled out, not only tinted: a colour alone does not say why the wheel
        // did not take (see ui/AGENTS.md).
        Component key = this.listening
                ? KeyCaptureWidget.listeningHint(font, w - 24 - font.width(label))
                : (this.refusedWheel ? Component.literal("Hold only") : Keys.displayName(getter.getAsInt()));
        int color = this.listening ? SBSTheme.ACCENT_BRIGHT
                : (this.refusedWheel ? SBSTheme.WARN
                : (getter.getAsInt() != 0 ? SBSTheme.ACCENT : SBSTheme.TEXT_MUTED));
        g.text(font, key, x + w - 8 - font.width(key), textY, color);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
