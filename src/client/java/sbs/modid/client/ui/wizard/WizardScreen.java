/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.font.SbsFonts;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.settings.SettingRowList;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.theme.UiStyle;
import sbs.modid.client.ui.wizard.logic.StylePreview;
import sbs.modid.client.ui.wizard.logic.WizardAccount;
import sbs.modid.client.ui.wizard.model.ModVersion;

import java.util.ArrayList;
import java.util.List;

/**
 * The overlay itself: one page at a time, with Back / Next / Skip and a page indicator.
 *
 * <p><b>Escape always closes, from any page.</b> A player is never trapped behind this screen, and
 * closing it is never taken as having agreed to or completed anything - it simply returns next
 * launch. Only reaching the end, or pressing Skip, writes a record.
 *
 * <p><b>One throwing page closes the overlay rather than freezing it.</b> Every page's content is
 * resolved and drawn inside a guard: a page that throws is logged with its id and the overlay shuts,
 * because a screen that cannot draw and cannot be dismissed is the worst outcome available.
 */
public final class WizardScreen extends Screen {

    private static final int PREFERRED_WIDTH = 420;
    private static final int PREFERRED_HEIGHT = 260;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_GAP = 6;

    private final WizardMode mode;
    private final List<WizardPage> pages;
    /** How many were dropped by the showcase cap, for the "and N more" line. Zero when none were. */
    private final int droppedByCap;
    /** The version to record as seen when this run ends. {@code null} for onboarding. */
    private final ModVersion showcaseTarget;

    private int index;
    private WizardPages.Resolved resolved;

    private final SettingRowList rowList = new SettingRowList(new SettingRowList.WidgetSink() {
        @Override
        public void add(AbstractWidget widget) {
            addRenderableWidget(widget);
        }

        @Override
        public void remove(AbstractWidget widget) {
            removeWidget(widget);
        }
    });

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentTop;
    private int contentBottom;
    private int textBottom;

    public WizardScreen(WizardMode mode, List<WizardPage> pages, int droppedByCap,
                        ModVersion showcaseTarget) {
        super(Minecraft.getInstance(), SbsFonts.ui(), Component.literal("Skyblock Simplified"));
        this.mode = mode;
        this.pages = List.copyOf(pages);
        this.droppedByCap = Math.max(0, droppedByCap);
        this.showcaseTarget = showcaseTarget;
    }

    /** Escape closes without recording anything. That is the point, not an oversight. */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    protected void init() {
        // Sized from the viewport, never to a fixed minimum: the available space is the ceiling.
        int availableW = Math.max(1, this.width - SBSTheme.SCREEN_MARGIN * 2);
        int availableH = Math.max(1, this.height - SBSTheme.SCREEN_MARGIN * 2);
        panelW = Math.min(availableW, PREFERRED_WIDTH);
        panelH = Math.min(availableH, PREFERRED_HEIGHT);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int pad = SBSTheme.PANEL_PADDING;
        contentTop = panelY + SBSTheme.HEADER_HEIGHT + SBSTheme.GAP_AFTER_HEADER;
        contentBottom = panelY + panelH - pad - BUTTON_HEIGHT - BUTTON_GAP;

        addRenderableOnly(new PanelRenderable());
        buildPage();
        addRenderableOnly(new OverlayRenderable());
    }

    // ------------------------------------------------------------------
    // Page building
    // ------------------------------------------------------------------

    private WizardPage page() {
        return index >= 0 && index < pages.size() ? pages.get(index) : null;
    }

    private void buildPage() {
        rowList.clear();
        WizardPage page = page();
        if (page == null) {
            finish();
            return;
        }
        try {
            resolved = WizardPages.resolve(page);
        } catch (Throwable t) {
            fail(page, t);
            return;
        }

        int pad = SBSTheme.PANEL_PADDING;
        int contentX = panelX + pad;
        int contentW = panelW - pad * 2;

        // Text first, rows underneath: measure the prose against the real width so the row column
        // starts below it rather than on top of it.
        textBottom = contentTop + measureText(contentW);

        List<SettingRow> rows = new ArrayList<>();
        for (WizardPages.Resolved.Element element : resolved.elements()) {
            if (element.row() != null) {
                rows.add(element.row());
            }
        }
        rowList.setBounds(contentX, Math.min(textBottom + 4, contentBottom), contentW, contentBottom);
        rowList.setRightGutter(0);   // no favourite stars out here
        rowList.rebuild(rows);

        buildButtons();
    }

    private void buildButtons() {
        int pad = SBSTheme.PANEL_PADDING;
        int y = panelY + panelH - pad - BUTTON_HEIGHT;
        int right = panelX + panelW - pad;

        // Skip says plainly what it does. "Skip" alone leaves the player guessing whether the wizard
        // returns, which is the ambiguity this label exists to remove.
        String skipLabel = mode == WizardMode.ONBOARDING
                ? "Skip setup - don't ask again"
                : "Skip - don't show update notices";
        int skipW = Math.min(150, SbsFonts.ui().width(skipLabel) + 14);
        addRenderableWidget(new SciFiButton(panelX + pad, y, skipW, BUTTON_HEIGHT,
                Component.literal(skipLabel), this::skipAll));

        boolean last = index >= pages.size() - 1;
        int nextW = 62;
        addRenderableWidget(new SciFiButton(right - nextW, y, nextW, BUTTON_HEIGHT,
                Component.literal(last ? "Finish" : "Next"), this::next));

        if (index > 0) {
            int backW = 52;
            addRenderableWidget(new SciFiButton(right - nextW - BUTTON_GAP - backW, y, backW,
                    BUTTON_HEIGHT, Component.literal("Back"), this::back));
        }
    }

    private void rebuildPage() {
        clearWidgets();
        init();
    }

    // ------------------------------------------------------------------
    // Navigation. Records are written on finish or skip - never on open.
    // ------------------------------------------------------------------

    private void next() {
        WizardPage page = page();
        if (page != null) {
            // Marked as it is left, not as it is opened: a crash halfway through must not consume
            // pages the player never reached.
            WizardAccount.state().markSeen(page.id());
        }
        if (index >= pages.size() - 1) {
            finish();
            return;
        }
        index++;
        rebuildPage();
    }

    private void back() {
        if (index > 0) {
            index--;
            rebuildPage();
        }
    }

    /** Skip ends the whole run, and says so on the button before it is pressed. */
    private void skipAll() {
        if (mode == WizardMode.SHOWCASE) {
            WizardAccount.state().setShowcaseOptOut(true);
        }
        finish();
    }

    private void finish() {
        if (mode == WizardMode.ONBOARDING) {
            WizardAccount.state().markOnboardingCompleted();
        } else if (showcaseTarget != null) {
            // Recorded even when the cap dropped pages, so the ones that did not fit are not offered
            // again on the next launch as if they had never been considered.
            WizardAccount.state().markShowcaseSeen(showcaseTarget);
        }
        onClose();
    }

    private void fail(WizardPage page, Throwable t) {
        SkyblockSimplifiedSBS.LOGGER.error(
                "[SBS][Wizard] Page '{}' failed to render - closing the overlay so it cannot trap the "
                        + "player.", page == null ? "<none>" : page.id(), t);
        onClose();
    }

    @Override
    public void onClose() {
        rowList.clear();
        Minecraft.getInstance().setScreenAndShow(null);
    }

    // ------------------------------------------------------------------
    // Input - the row list gets first refusal, for its dropdowns
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // The picker is painted by the panel rather than registered as a widget, so it is offered the
        // click by hand - before the row list, whose rows sit below it and never overlap it.
        return clickStylePicker(event.x(), event.y())
                || rowList.mouseClicked(event, doubled)
                || super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return rowList.mouseDragged(event.y()) || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return rowList.mouseReleased() || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return rowList.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        rowList.syncAvailability();
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        rowList.renderDropdownOverlay(g, mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** Height the prose needs at this width. Measured, never assumed from a constant. */
    private int measureText(int contentW) {
        if (resolved == null) {
            return 0;
        }
        var font = SbsFonts.ui();
        int height = 0;
        for (WizardPages.Resolved.Element element : resolved.elements()) {
            switch (element.source()) {
                case PageElement.Heading heading -> height += font.lineHeight + 4;
                case PageElement.Text text ->
                        height += font.split(Component.literal(text.body()), contentW).size()
                                * font.lineHeight + 4;
                case PageElement.Image image -> height += Math.min(image.height(), 80) + 4;
                case PageElement.StylePicker ignored -> height += pickerHeight(contentW) + 4;
                default -> { }
            }
        }
        if (page() != null && page().tier() == Tier.REQUIRES_LICENCE) {
            height += font.lineHeight + 4;
        }
        return height;
    }

    private final class PanelRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = SbsFonts.ui();
            g.fill(0, 0, WizardScreen.this.width, WizardScreen.this.height, SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            WizardPage page = page();
            int pad = SBSTheme.PANEL_PADDING;
            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal(page == null ? "Setup" : page.title()),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);

            // Page indicator, so the player knows how long this is before deciding to sit through it.
            String indicator = (index + 1) + " / " + pages.size();
            g.text(font, Component.literal(indicator),
                    panelX + panelW - pad - font.width(indicator), titleY, SBSTheme.TEXT_MUTED);

            int dividerY = panelY + SBSTheme.HEADER_HEIGHT;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            drawContent(g, font);
        }
    }

    private void drawContent(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font) {
        if (resolved == null) {
            return;
        }
        int pad = SBSTheme.PANEL_PADDING;
        int x = panelX + pad;
        int contentW = panelW - pad * 2;
        int y = contentTop;

        if (page() != null && page().tier() == Tier.REQUIRES_LICENCE) {
            g.text(font, Component.literal("Needs a licence - skippable like any other page"),
                    x, y, 0xFFFFD24B);
            y += font.lineHeight + 4;
        }
        for (WizardPages.Resolved.Element element : resolved.elements()) {
            if (y > contentBottom) {
                break;
            }
            switch (element.source()) {
                case PageElement.Heading heading -> {
                    g.text(font, Component.literal(heading.text()), x, y, SBSTheme.ACCENT_BRIGHT);
                    y += font.lineHeight + 4;
                }
                case PageElement.Text text -> {
                    for (FormattedCharSequence line
                            : font.split(Component.literal(text.body()), contentW)) {
                        if (y > contentBottom) {
                            break;
                        }
                        g.text(font, line, x, y, SBSTheme.TEXT);
                        y += font.lineHeight;
                    }
                    y += 4;
                }
                case PageElement.Image image -> {
                    int drawH = Math.min(image.height(), 80);
                    int drawW = Math.min(image.width(), contentW);
                    g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                            image.texture(), x, y, 0f, 0f, drawW, drawH, drawW, drawH);
                    y += drawH + 4;
                }
                case PageElement.StylePicker ignored -> {
                    drawStylePicker(g, font, x, y, contentW);
                    y += pickerHeight(contentW) + 4;
                }
                default -> { }
            }
        }

        if (droppedByCap > 0) {
            // Only ever drawn when the cap actually dropped something: a player who saw everything is
            // never told there is more.
            String more = "and " + droppedByCap + " more change"
                    + (droppedByCap == 1 ? "" : "s") + " - see the changelog";
            g.text(font, Component.literal(more), x, contentBottom - font.lineHeight,
                    SBSTheme.TEXT_MUTED);
        }
    }

    // ------------------------------------------------------------------
    // The style picker
    // ------------------------------------------------------------------

    /** Tile size and gap. Columns are derived from the content width, never hardcoded. */
    private static final int TILE_W = 74;
    private static final int TILE_H = 46;
    private static final int TILE_GAP = 4;

    private static int columns(int contentW) {
        return Math.max(1, (contentW + TILE_GAP) / (TILE_W + TILE_GAP));
    }

    private static int pickerHeight(int contentW) {
        int rows = (int) Math.ceil(UiStyle.values().length / (double) columns(contentW));
        return rows * (TILE_H + TILE_GAP) - TILE_GAP;
    }

    /** Where a tile sits, or {@code null} when the index is out of range. */
    private int[] tileBounds(int index, int x, int y, int contentW) {
        UiStyle[] all = UiStyle.values();
        if (index < 0 || index >= all.length) {
            return null;
        }
        int cols = columns(contentW);
        return new int[] {x + (index % cols) * (TILE_W + TILE_GAP),
                y + (index / cols) * (TILE_H + TILE_GAP)};
    }

    /**
     * Draws one small panel per style, each in that style.
     *
     * <p>Each tile is drawn with its own style actually applied - see {@code StylePreview} for why
     * that is the only honest way to do it. The tiles are drawn last within this pass so nothing else
     * is caught mid-swap, and the restore runs in a {@code finally} so a throwing tile cannot leave
     * the whole mod wearing a look the player never chose.
     */
    private void drawStylePicker(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font,
                                 int x, int y, int contentW) {
        UiStyle current = SBSTheme.style();
        UiStyle[] all = UiStyle.values();
        try {
            for (int i = 0; i < all.length; i++) {
                int[] at = tileBounds(i, x, y, contentW);
                if (at == null || at[1] + TILE_H > contentBottom) {
                    continue;
                }
                StylePreview.apply(all[i]);

                SciFiRender.roundedRect(g, at[0], at[1], TILE_W, TILE_H,
                        SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
                SciFiRender.roundedRectGradient(g, at[0] + 1, at[1] + 1, TILE_W - 2, TILE_H - 2,
                        Math.max(0, SBSTheme.PANEL_CORNER - 1),
                        SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);
                g.fill(at[0] + 5, at[1] + 13, at[0] + TILE_W - 5, at[1] + 14, SBSTheme.ACCENT);
                SciFiRender.roundedRect(g, at[0] + 5, at[1] + 18, TILE_W - 10, 9,
                        Math.max(0, SBSTheme.CORNER_RADIUS), SBSTheme.CARD_BORDER);
                SciFiRender.roundedRect(g, at[0] + 5, at[1] + 30, TILE_W - 10, 9,
                        Math.max(0, SBSTheme.CORNER_RADIUS), SBSTheme.CARD_BORDER);

                g.text(font, Component.literal(
                                font.plainSubstrByWidth(all[i].displayName(), TILE_W - 10)),
                        at[0] + 5, at[1] + 3, SBSTheme.TEXT);
            }
        } finally {
            StylePreview.restore();
        }

        // Drawn after the restore, in the real theme: which tile is chosen must never render in a
        // style the player has not actually picked.
        for (int i = 0; i < all.length; i++) {
            if (all[i] != current) {
                continue;
            }
            int[] at = tileBounds(i, x, y, contentW);
            if (at == null || at[1] + TILE_H > contentBottom) {
                continue;
            }
            int ring = SBSTheme.ACCENT_BRIGHT;
            g.fill(at[0] - 2, at[1] - 2, at[0] + TILE_W + 2, at[1] - 1, ring);
            g.fill(at[0] - 2, at[1] + TILE_H + 1, at[0] + TILE_W + 2, at[1] + TILE_H + 2, ring);
            g.fill(at[0] - 2, at[1] - 2, at[0] - 1, at[1] + TILE_H + 2, ring);
            g.fill(at[0] + TILE_W + 1, at[1] - 2, at[0] + TILE_W + 2, at[1] + TILE_H + 2, ring);
        }
    }

    /**
     * Picks a style when a tile is clicked.
     *
     * <p>Applied immediately rather than on Finish, so the whole screen repaints into the chosen look
     * and the choice becomes its own preview.
     */
    private boolean clickStylePicker(double mouseX, double mouseY) {
        if (resolved == null) {
            return false;
        }
        int pad = SBSTheme.PANEL_PADDING;
        int contentW = panelW - pad * 2;
        int y = pickerTop(contentW);
        if (y < 0) {
            return false;
        }
        UiStyle[] all = UiStyle.values();
        for (int i = 0; i < all.length; i++) {
            int[] at = tileBounds(i, panelX + pad, y, contentW);
            if (at != null && mouseX >= at[0] && mouseX <= at[0] + TILE_W
                    && mouseY >= at[1] && mouseY <= at[1] + TILE_H) {
                ConfigManager.getInstance().get().theme.style = all[i].name();
                ConfigManager.getInstance().save();
                SBSTheme.refreshFromConfig();
                return true;
            }
        }
        return false;
    }

    /**
     * The y the picker starts at, or {@code -1} when this page has none.
     *
     * <p>Walks the same elements the draw walks, in the same order, so the hit box cannot drift away
     * from what is on screen.
     */
    private int pickerTop(int contentW) {
        var font = SbsFonts.ui();
        int y = contentTop;
        if (page() != null && page().tier() == Tier.REQUIRES_LICENCE) {
            y += font.lineHeight + 4;
        }
        for (WizardPages.Resolved.Element element : resolved.elements()) {
            switch (element.source()) {
                case PageElement.StylePicker ignored -> {
                    return y;
                }
                case PageElement.Heading ignored -> y += font.lineHeight + 4;
                case PageElement.Text text ->
                        y += font.split(Component.literal(text.body()), contentW).size()
                                * font.lineHeight + 4;
                case PageElement.Image image -> y += Math.min(image.height(), 80) + 4;
                default -> {
                }
            }
        }
        return -1;
    }

    /** Scrollbar and tooltips last, so they sit over the rows. */
    private final class OverlayRenderable implements Renderable {
        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            rowList.renderScrollbar(g, mouseX, mouseY);
            rowList.renderTooltip(g, mouseX, mouseY);
        }
    }
}
