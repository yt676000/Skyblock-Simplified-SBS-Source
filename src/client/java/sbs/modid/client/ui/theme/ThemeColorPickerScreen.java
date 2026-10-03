/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.render.OverlayColor;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * The Theme module's colour picker: a saturation/value square for the current hue, a hue bar, a
 * brightness slider, and a hex field - pick on the palette OR type the exact code, both stay in
 * sync. Saving writes the hex through the supplied setter and re-derives the whole SBS theme
 * immediately, so the picker itself repaints in the new colours as living preview.
 */
public final class ThemeColorPickerScreen extends Screen {

    private static final int SV_SIZE = 96;
    private static final int STEP = 3;
    private static final int HUE_W = 12;

    private final Screen parent;
    private final Consumer<String> setter;

    /** The working colour in HSB (0..1 each). */
    private float hue;
    private float sat;
    private float bri;

    private EditBox hexField;
    private boolean draggingSv;
    private boolean draggingHue;
    private boolean draggingBri;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int svX;
    private int svY;
    private int hueX;
    private int briY;
    private int briW;

    public ThemeColorPickerScreen(String title, String currentHex, Consumer<String> setter, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
        this.setter = setter;
        Integer rgb = OverlayColor.parseHex(currentHex);
        float[] hsb = SBSTheme.rgbToHsb(rgb == null ? SBSTheme.DEFAULT_ACCENT : rgb);
        this.hue = hsb[0];
        this.sat = hsb[1];
        this.bri = hsb[2];
    }

    @Override
    protected void init() {
        panelW = SV_SIZE + HUE_W + 140;
        panelH = SV_SIZE + 96;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        svX = panelX + 12;
        svY = panelY + SBSTheme.HEADER_HEIGHT + 8;
        hueX = svX + SV_SIZE + 8;
        briY = svY + SV_SIZE + 10;
        briW = SV_SIZE + HUE_W + 8;

        addRenderableOnly(new PanelRenderable());

        hexField = new EditBox(this.font, hueX + HUE_W + 16, svY + 12, 96, 12,
                Component.literal("Hex"));
        hexField.setBordered(false);
        hexField.setMaxLength(7);
        hexField.setTextColor(SBSTheme.TEXT);
        hexField.setValue(hex());
        hexField.setResponder(text -> {
            Integer rgb = OverlayColor.parseHex(text);
            if (rgb != null) {
                float[] hsb = SBSTheme.rgbToHsb(rgb);
                hue = hsb[0];
                sat = hsb[1];
                bri = hsb[2];
            }
        });
        addRenderableWidget(hexField);

        addRenderableWidget(new SciFiButton(hueX + HUE_W + 16, svY + SV_SIZE - 40, 96,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Save"), this::saveAndClose));
        addRenderableWidget(new SciFiButton(hueX + HUE_W + 16, svY + SV_SIZE - 16, 96,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Cancel"), this::onClose));
    }

    private String hex() {
        return String.format(Locale.ROOT, "%06X", SBSTheme.hsbToRgb(hue, sat, bri));
    }

    private void saveAndClose() {
        setter.accept(hex());
        ConfigManager.getInstance().save();
        SBSTheme.refreshFromConfig();   // live: the whole SBS UI re-derives from the new base
        onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Input: click-drag on the SV square, the hue bar and the brightness slider
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (pick(event.x(), event.y(), true)) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (pick(event.x(), event.y(), false)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingSv = false;
        draggingHue = false;
        draggingBri = false;
        return super.mouseReleased(event);
    }

    /** Routes a click/drag to whichever control it started on; keeps dragging that one control. */
    private boolean pick(double mx, double my, boolean fresh) {
        boolean inSv = mx >= svX && mx <= svX + SV_SIZE && my >= svY && my <= svY + SV_SIZE;
        boolean inHue = mx >= hueX && mx <= hueX + HUE_W && my >= svY && my <= svY + SV_SIZE;
        boolean inBri = mx >= svX && mx <= svX + briW && my >= briY - 3 && my <= briY + 9;
        if (fresh) {
            draggingSv = inSv;
            draggingHue = inHue;
            draggingBri = inBri && !inSv && !inHue;
        }
        if (draggingSv || (fresh && inSv)) {
            sat = clamp01((float) (mx - svX) / SV_SIZE);
            bri = clamp01(1f - (float) (my - svY) / SV_SIZE);
        } else if (draggingHue || (fresh && inHue)) {
            hue = clamp01((float) (my - svY) / SV_SIZE);
        } else if (draggingBri || (fresh && inBri)) {
            bri = clamp01((float) (mx - svX) / briW);
        } else {
            return false;
        }
        hexField.setValue(hex());
        return true;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = ThemeColorPickerScreen.this.font;
            g.fill(0, 0, width, height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
            g.centeredText(font, getTitle(), panelX + panelW / 2,
                    panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2, SBSTheme.ACCENT_BRIGHT);

            // Saturation/value square for the current hue (coarse steps keep it cheap).
            for (int px = 0; px < SV_SIZE; px += STEP) {
                for (int py = 0; py < SV_SIZE; py += STEP) {
                    float s = (float) px / SV_SIZE;
                    float b = 1f - (float) py / SV_SIZE;
                    g.fill(svX + px, svY + py, svX + px + STEP, svY + py + STEP,
                            0xFF000000 | SBSTheme.hsbToRgb(hue, s, b));
                }
            }
            // Selection marker.
            int selX = svX + Math.round(sat * SV_SIZE);
            int selY = svY + Math.round((1f - bri) * SV_SIZE);
            g.fill(selX - 2, selY - 2, selX + 2, selY + 2, 0xFF000000);
            g.fill(selX - 1, selY - 1, selX + 1, selY + 1, 0xFFFFFFFF);

            // Hue bar.
            for (int py = 0; py < SV_SIZE; py += STEP) {
                float h = (float) py / SV_SIZE;
                g.fill(hueX, svY + py, hueX + HUE_W, svY + py + STEP,
                        0xFF000000 | SBSTheme.hsbToRgb(h, 1f, 1f));
            }
            int hueY = svY + Math.round(hue * SV_SIZE);
            g.fill(hueX - 1, hueY - 1, hueX + HUE_W + 1, hueY + 1, 0xFFFFFFFF);

            // Brightness slider (the picked hue/sat swept dark -> bright).
            for (int px = 0; px < briW; px += STEP) {
                float b = (float) px / briW;
                g.fill(svX + px, briY, svX + px + STEP, briY + 6,
                        0xFF000000 | SBSTheme.hsbToRgb(hue, sat, b));
            }
            int briX = svX + Math.round(bri * briW);
            g.fill(briX - 1, briY - 3, briX + 1, briY + 9, 0xFFFFFFFF);
            g.text(font, Component.literal("Brightness"), svX, briY + 12, SBSTheme.TEXT_MUTED);

            // Preview swatch + hex chrome.
            int swX = hueX + HUE_W + 16;
            SciFiRender.roundedRectWithBorder(g, swX, svY + 30, 96, 24, SBSTheme.CORNER_RADIUS,
                    0xFF000000 | SBSTheme.hsbToRgb(hue, sat, bri), SBSTheme.CARD_BORDER);
            SciFiRender.roundedRectWithBorder(g, swX - 4, svY + 8, 104, 18, SBSTheme.CORNER_RADIUS,
                    SBSTheme.SEARCH_FILL, hexField != null && hexField.isFocused()
                            ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        }
    }
}
