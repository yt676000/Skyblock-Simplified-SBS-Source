/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.particles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.particles.ParticleCatalog.ParticleEntry;
import sbs.modid.client.helper.particles.logic.ParticlePresets;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;

/**
 * The Particles module's list: every particle type the game has, searchable, each with a checkbox.
 *
 * <p><b>A ticked box means the particle is on</b> - the list reads as "what is currently allowed to
 * render", which is the way round a player thinks about it. The config stores the inverse (the
 * <i>hidden</i> ids) so an untouched install persists an empty set instead of six hundred entries,
 * and so a game update's new particles default to visible rather than silently hidden.
 *
 * <p>Modelled on the Mob Highlight selection screen: search box on top filtering live, a scrollable
 * checkbox list with its own scrollbar, bulk enable/disable for whatever the filter currently shows,
 * and a footer counter. Every edit saves immediately and invalidates {@link ParticleFilter}, so the
 * effect is visible in the world the moment the screen closes.
 */
public final class ParticleSelectionScreen extends Screen {

    private static final int ROW_H = 18;

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int innerX;
    private int contentW;
    private int listTop;
    private int listBottom;
    private int searchW;

    private EditBox search;
    private List<ParticleEntry> results = List.of();
    private int scroll;
    private int scrollMax;

    /** The list's scrollbar - the shared one, so it can actually be dragged. */
    private final SciFiScrollbar bar = new SciFiScrollbar();

    /** @param parent the screen to return to on close, so the module page is restored as it was. */
    public ParticleSelectionScreen(Screen parent) {
        super(Component.literal("Particles"));
        this.parent = parent;
    }

    private static SBSConfig.ParticleSettings cfg() {
        return ConfigManager.getInstance().get().particles;
    }

    private static void save() {
        ConfigManager.getInstance().save();
        ParticleFilter.invalidate();
    }

    @Override
    protected void init() {
        panelW = clamp(this.width - SBSTheme.SCREEN_MARGIN * 2, 380, 560);
        panelH = clamp(this.height - SBSTheme.SCREEN_MARGIN * 2, 260, 460);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        dividerY = panelY + SBSTheme.HEADER_HEIGHT;
        int pad = SBSTheme.PANEL_PADDING;
        innerX = panelX + pad;
        contentW = panelW - pad * 2;

        int topY = dividerY + SBSTheme.GAP_AFTER_HEADER;
        int btnW = 70;
        int textH = this.font.lineHeight;
        searchW = contentW - btnW * 2 - 12;

        listTop = topY + SBSTheme.SEARCH_HEIGHT + 6;
        listBottom = panelY + panelH - pad - font.lineHeight - 4;

        // The panel renders FIRST so the widgets added after it paint on top - otherwise the
        // search-field fill covers the EditBox text and typing looks like it does nothing.
        addRenderableOnly(new PanelRenderable());

        search = new EditBox(this.font, innerX + 6, topY + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                searchW - 12, textH, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(SBSTheme.TEXT);
        search.setHint(Component.literal("Search particles..."));
        search.setResponder(query -> refresh());
        addRenderableWidget(search);
        setInitialFocus(search);

        // Bulk actions apply to what the filter shows, so a search doubles as a group selector
        // ("dust" then Disable All switches off every dust particle at once).
        addRenderableWidget(new SciFiButton(innerX + contentW - btnW * 2 - 6, topY, btnW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Enable All"), () -> setAllShown(true)));
        addRenderableWidget(new SciFiButton(innerX + contentW - btnW, topY, btnW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Disable All"), () -> setAllShown(false)));

        refresh();
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private void refresh() {
        results = ParticleCatalog.search(search == null ? "" : search.getValue());
        scroll = 0;
    }

    /** A ticked row = the particle renders, i.e. it is NOT in the hidden set. */
    private boolean isEnabled(ParticleEntry entry) {
        var hidden = cfg().hiddenParticles;
        return hidden == null || !hidden.contains(entry.id());
    }

    private void toggle(ParticleEntry entry) {
        var hidden = cfg().hiddenParticles;
        if (!hidden.remove(entry.id())) {
            hidden.add(entry.id());
        }
        // One hand edit and the set no longer is what the preset says it is, so the label stops
        // claiming otherwise - and this edit becomes the selection Custom hands back later.
        ParticlePresets.markCustom();
        save();
    }

    /** Enables or disables every particle the current filter shows, leaving the rest alone. */
    private void setAllShown(boolean enabled) {
        var hidden = cfg().hiddenParticles;
        for (ParticleEntry entry : results) {
            if (enabled) {
                hidden.remove(entry.id());
            } else {
                hidden.add(entry.id());
            }
        }
        ParticlePresets.markCustom();
        save();
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) {
            return true;
        }
        // The bar before the rows: a click on it must not also tick the particle behind it.
        if (bar.handleClick(event.x(), event.y(), scroll, value -> scroll = value)) {
            return true;
        }
        if (event.x() < innerX || event.x() > innerX + contentW
                || event.y() < listTop || event.y() >= listBottom) {
            return false;
        }
        int row = scroll + (int) ((event.y() - listTop) / ROW_H);
        if (row < 0 || row >= results.size()) {
            return false;
        }
        toggle(results.get(row));
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (bar.handleDrag(event.y(), value -> scroll = value)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return bar.release() || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Feeds the shared scrollbar the list's track box; the render pass measured the row count. */
    private void syncBar(int total, int visible) {
        bar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, visible * ROW_H, total, visible);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {   // Escape closes even while the search box has focus
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private final class PanelRenderable implements Renderable {

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            var font = ParticleSelectionScreen.this.font;
            g.fill(0, 0, ParticleSelectionScreen.this.width, ParticleSelectionScreen.this.height,
                    SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER,
                    SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Particles"), panelX + panelW / 2, titleY,
                    SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            int topY = dividerY + SBSTheme.GAP_AFTER_HEADER;
            SciFiRender.roundedRectWithBorder(g, innerX, topY, searchW, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    search.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            drawList(g, mouseX, mouseY);
            drawFooter(g);
        }

        private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = ParticleSelectionScreen.this.font;
            if (results.isEmpty()) {
                g.centeredText(font, Component.literal("§7No matching particles."),
                        panelX + panelW / 2, listTop + 12, SBSTheme.TEXT_MUTED);
                scrollMax = 0;
                return;
            }
            int visible = Math.max(1, (listBottom - listTop) / ROW_H);
            scrollMax = Math.max(0, results.size() - visible);
            scroll = clamp(scroll, 0, scrollMax);

            boolean scrollable = results.size() > visible;
            int rowW = contentW - (scrollable ? SciFiScrollbar.WIDTH + 3 : 0);
            int y = listTop;
            for (int i = scroll; i < results.size() && i < scroll + visible; i++) {
                drawRow(g, results.get(i), innerX, y, rowW, mouseX, mouseY);
                y += ROW_H;
            }
            syncBar(results.size(), visible);
            bar.render(g, scroll, mouseX, mouseY);
        }

        /** One particle row: a checkbox, the display name, and its muted registry id. */
        private void drawRow(GuiGraphicsExtractor g, ParticleEntry entry, int x, int y, int w,
                             int mouseX, int mouseY) {
            var font = ParticleSelectionScreen.this.font;
            int h = ROW_H - 2;
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                    hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);

            int textY = y + (h - font.lineHeight) / 2;
            boolean enabled = isEnabled(entry);

            int boxSize = 9;
            int boxX = x + 4;
            int boxY = y + (h - boxSize) / 2;
            SciFiRender.roundedRectWithBorder(g, boxX, boxY, boxSize, boxSize, 2,
                    enabled ? SBSTheme.ACCENT : SBSTheme.CARD_BG,
                    enabled ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            if (enabled) {
                g.text(font, Component.literal("§f✔"), boxX + 1, boxY - 1, SBSTheme.TEXT);
            }

            int nameX = boxX + boxSize + 5;
            g.text(font, Component.literal((enabled ? "§f" : "§7") + entry.name()), nameX, textY,
                    SBSTheme.TEXT);

            String id = "§8" + entry.id();
            int idW = font.width(id);
            if (nameX + font.width(entry.name()) + 8 + idW < x + w - 4) {
                g.text(font, Component.literal(id), x + w - 4 - idW, textY, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawFooter(GuiGraphicsExtractor g) {
            var font = ParticleSelectionScreen.this.font;
            int y = listBottom + 2;
            int total = ParticleCatalog.all().size();
            var hidden = cfg().hiddenParticles;
            int off = hidden == null ? 0 : hidden.size();
            String status = "§7" + (total - off) + " of " + total + " enabled"
                    + " §8· §7" + cfg().preset.displayName();
            if (!cfg().enabled) {
                status += " §c(module off)";
            }
            g.text(font, Component.literal(status), innerX, y, SBSTheme.TEXT_MUTED);
            String hint = "§8click a row to toggle · Esc to go back";
            g.text(font, Component.literal(hint), innerX + contentW - font.width(hint), y,
                    SBSTheme.TEXT_MUTED);
        }
    }
}
