/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config.share.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.share.ConfigShare;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.screen.SBSMainScreen;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * What an imported config would change, before any of it is applied.
 *
 * <p><b>This screen is not optional and has no "don't show again".</b> The payload format is
 * compressed base64, which is honestly opaque - nobody can tell what a blob does by looking at it,
 * and that is deliberate, because a format that <i>looks</i> readable is the one people skip the
 * check on. The consequence, accepted in the design, is that this screen is the only place a player
 * can see what they are accepting. Removing it, or adding a way past it, removes the only control.
 *
 * <p>The line the screen leads with is <b>features being switched on</b>. Almost every hostile
 * payload has to enable something, so that count is the highest-value thing on the page, and it is
 * stated before the list rather than left to be noticed inside it.
 *
 * <p>"View raw" shows the decoded JSON. That is strictly more useful than the same JSON on a
 * clipboard: it is here, after decoding, beside the summary of what actually changes.
 */
public final class ImportPreviewScreen extends Screen {

    private static final int GAP = 4;

    private final ConfigShare.Preview preview;
    private final String rawJson;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int innerX;
    private int contentW;
    private int listTop;
    private int listBottom;
    private int buttonsY;
    private int scroll;
    private boolean showRaw;

    public ImportPreviewScreen(ConfigShare.Preview preview, String rawJson) {
        super(Component.literal("Import Settings"));
        this.preview = preview;
        this.rawJson = rawJson == null ? "" : rawJson;
    }

    @Override
    protected void init() {
        // Sized from the viewport, never up to a minimum - a panel clamped to a minimum hangs off
        // both edges of a small screen at a large GUI scale.
        int availableW = Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2);
        int availableH = Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2);
        panelW = Math.min(availableW, SBSTheme.PANEL_MAX_WIDTH + 120);
        panelH = Math.min(availableH, SBSTheme.PANEL_MAX_HEIGHT + 60);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = Math.max(1, panelW - pad * 2);

        // The header block is measured from the lines that will actually be drawn, not a constant.
        listTop = panelY + SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER
                + this.font.lineHeight * (headerLines().size() + 1);
        buttonsY = panelY + panelH - pad - SBSTheme.SEARCH_HEIGHT;
        listBottom = buttonsY - SBSTheme.GAP_AFTER_SEARCH;

        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        addRenderableOnly(new PanelRenderable());

        int buttons = 3;
        int buttonW = Math.max(40, (contentW - GAP * (buttons - 1)) / buttons);
        addRenderableWidget(new SciFiButton(innerX, buttonsY, buttonW, SBSTheme.SEARCH_HEIGHT,
                Component.literal(showRaw ? "Hide raw" : "View raw"),
                () -> {
                    showRaw = !showRaw;
                    scroll = 0;
                    rebuild();
                }));
        addRenderableWidget(new SciFiButton(innerX + buttonW + GAP, buttonsY, buttonW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Cancel"), this::onClose));
        // Nothing is applied until this is pressed. There is no other path to apply.
        addRenderableWidget(new SciFiButton(innerX + (buttonW + GAP) * 2, buttonsY, buttonW,
                SBSTheme.SEARCH_HEIGHT,
                Component.literal(preview.hasChanges() ? "Apply" : "Nothing to apply"),
                this::apply));
    }

    private void apply() {
        if (!preview.hasChanges()) {
            onClose();
            return;
        }
        int changed = ConfigShare.apply(preview);
        sbs.modid.client.social.chat.logic.SBSChat.send("§bImported §f" + changed
                + "§b setting" + (changed == 1 ? "" : "s") + ". Your previous config was backed up - "
                + "\"Restore Last Backup\" on the SBS Settings page puts it back.");
        Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
    }

    /** The summary above the list, built from what the payload actually contains. */
    private List<String> headerLines() {
        List<String> lines = new ArrayList<>(4);
        long enabling = preview.featuresEnabled();
        lines.add((enabling > 0 ? "§c" : "§7") + enabling + " feature"
                + (enabling == 1 ? "" : "s") + " would be switched ON");
        lines.add("§7" + preview.changes().size() + " setting"
                + (preview.changes().size() == 1 ? "" : "s") + " would change");
        if (!preview.rejected().isEmpty()) {
            lines.add("§e" + preview.rejected().size() + " value(s) refused - see below");
        }
        if (!preview.unknown().isEmpty()) {
            lines.add("§8" + preview.unknown().size() + " key(s) this version does not know, ignored");
        }
        return lines;
    }

    /** Every line of the scrolling body, already coloured. */
    private List<String> bodyLines() {
        if (showRaw) {
            List<String> raw = new ArrayList<>();
            for (String line : rawJson.split("(?<=,)")) {
                raw.add("§7" + line.trim());
            }
            return raw;
        }
        List<String> lines = new ArrayList<>();
        for (ConfigShare.Change change : preview.changes()) {
            lines.add((change.enablesFeature() ? "§c" : "§f") + change.path()
                    + " §8" + change.from() + " §7→ §f" + change.to());
        }
        for (var refused : preview.rejected()) {
            lines.add("§e" + refused.path() + " §8refused: " + refused.reason());
        }
        for (String key : preview.unknown()) {
            lines.add("§8" + key + " - unknown, ignored");
        }
        return lines;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listTop) / this.font.lineHeight);
    }

    private int maxScroll() {
        return Math.max(0, bodyLines().size() - visibleRows());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() <= 0 || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll + (scrollY > 0 ? -1 : 1)));
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(new SBSMainScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private String fit(String text) {
        return this.font.width(text) <= contentW ? text
                : this.font.plainSubstrByWidth(text, Math.max(1, contentW - 8), false) + "...";
    }

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            var font = ImportPreviewScreen.this.font;
            g.fill(0, 0, ImportPreviewScreen.this.width, ImportPreviewScreen.this.height,
                    SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Import Settings"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);

            int y = panelY + SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER;
            g.text(font, Component.literal(fit("§8From a config made by mod version "
                            + preview.sourceVersion())), innerX, y, SBSTheme.TEXT_MUTED);
            y += font.lineHeight;
            for (String line : headerLines()) {
                g.text(font, Component.literal(fit(line)), innerX, y, SBSTheme.TEXT);
                y += font.lineHeight;
            }

            List<String> body = bodyLines();
            if (body.isEmpty()) {
                g.text(font, Component.literal("§7This config matches what you already have."),
                        innerX, listTop, SBSTheme.TEXT_MUTED);
                return;
            }
            int last = Math.min(body.size(), scroll + visibleRows());
            int ly = listTop;
            for (int i = scroll; i < last; i++) {
                g.text(font, Component.literal(fit(body.get(i))), innerX, ly, SBSTheme.TEXT);
                ly += font.lineHeight;
            }
            if (maxScroll() > 0) {
                g.text(font, Component.literal("§8" + (scroll + 1) + "-" + last + " of " + body.size()
                                + "  (scroll)"), innerX, listBottom - font.lineHeight,
                        SBSTheme.TEXT_MUTED);
            }
        }
    }
}
