/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.ui;

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
import sbs.modid.client.skills.fishing.model.CreatureAnnounceMode;
import sbs.modid.client.skills.fishing.model.FishingData;
import sbs.modid.client.skills.fishing.model.SeaCreatureRarity;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The per-creature exception list for the Sea Creature Announcer: every creature this build knows,
 * searchable, each row cycling Auto → Always → Never.
 *
 * <p>Three states rather than a checkbox, because the rarity switches on the settings page are the
 * base rule and the exceptions genuinely run both ways – see {@link CreatureAnnounceMode}. A row
 * left on Auto stores nothing at all, so the config file holds only what the player actually
 * changed and a creature added to the table in a later build inherits its tier's switch instead of
 * a stale "off" written years earlier.
 *
 * <p>Modelled on the mob-highlight selector, which is the mod's established shape for "one long
 * list, filtered, clicked": search box on top, scrollable list with its own bar, footer with the
 * bulk actions' effect and a hint. Each row is painted in its creature's rarity colour, so the list
 * doubles as the table the settings page cannot show.
 */
public final class SeaCreatureAnnounceScreen extends Screen {

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

    private EditBox search;
    private List<String> results = List.of();
    private int scroll;
    private int scrollMax;

    private final SciFiScrollbar bar = new SciFiScrollbar();

    /**
     * @param parent the screen to return to on close (the settings page), so the module page is
     *               restored exactly as the player left it.
     */
    public SeaCreatureAnnounceScreen(Screen parent) {
        super(Component.literal("Sea Creatures"));
        this.parent = parent;
    }

    private static SBSConfig.SeaCreatureAnnouncerSettings cfg() {
        return ConfigManager.getInstance().get().seaCreatureAnnouncer;
    }

    private static void save() {
        ConfigManager.getInstance().save();
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

        listTop = topY + SBSTheme.SEARCH_HEIGHT + 6;
        listBottom = panelY + panelH - pad - font.lineHeight - 4;

        // The panel renders first so the widgets added after it paint on top - otherwise the
        // search field's fill covers the EditBox text and typing looks like it does nothing.
        addRenderableOnly(new PanelRenderable());

        search = new EditBox(this.font, innerX + 6, topY + (SBSTheme.SEARCH_HEIGHT - textH) / 2,
                contentW - btnW * 2 - 24, textH, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(SBSTheme.TEXT);
        search.setHint(Component.literal("Search sea creatures..."));
        search.setResponder(query -> refresh());
        addRenderableWidget(search);
        setInitialFocus(search);

        addRenderableWidget(new SciFiButton(innerX + contentW - btnW * 2 - 6, topY, btnW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Mute Shown"), this::muteShown));
        addRenderableWidget(new SciFiButton(innerX + contentW - btnW, topY, btnW,
                SBSTheme.SEARCH_HEIGHT, Component.literal("Reset"), this::resetAll));

        refresh();
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private void refresh() {
        String needle = (search == null ? "" : search.getValue().trim()).toLowerCase(Locale.ROOT);
        List<String> list = new ArrayList<>();
        for (String creature : FishingData.allSeaCreatures()) {
            if (needle.isEmpty()
                    || creature.toLowerCase(Locale.ROOT).contains(needle)
                    || FishingData.rarityOf(creature).displayName().toLowerCase(Locale.ROOT).contains(needle)) {
                list.add(creature);
            }
        }
        results = list;
        scroll = 0;
    }

    /** One click on a row: Auto → Always → Never → Auto. Auto is stored as absence. */
    private void cycle(String creature) {
        CreatureAnnounceMode next = cfg().modeFor(creature).next();
        if (next == CreatureAnnounceMode.AUTO) {
            cfg().creatureModes.remove(creature);
        } else {
            cfg().creatureModes.put(creature, next);
        }
        save();
    }

    /** Silences every creature the current filter shows, leaving the rest of the list untouched. */
    private void muteShown() {
        for (String creature : results) {
            cfg().creatureModes.put(creature, CreatureAnnounceMode.NEVER);
        }
        save();
    }

    /** Back to "every creature follows its rarity" - the state a fresh config is in. */
    private void resetAll() {
        cfg().creatureModes.clear();
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
        // The bar sits over the rows: a click on it must not also cycle the row behind it.
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
        cycle(results.get(row));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scroll = clamp(scroll - (int) Math.signum(scrollY), 0, scrollMax);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    private void syncBar(int total, int visible) {
        bar.set(innerX + contentW - SciFiScrollbar.WIDTH, listTop, visible * ROW_H, total, visible);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Escape closes back to the settings page even while the search box is focused.
        if (event.key() == 256) {
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
            var font = SeaCreatureAnnounceScreen.this.font;
            g.fill(0, 0, SeaCreatureAnnounceScreen.this.width, SeaCreatureAnnounceScreen.this.height,
                    SBSTheme.BG_TINT);
            SciFiRender.glow(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
            SciFiRender.roundedRect(g, panelX, panelY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
            SciFiRender.roundedRectGradient(g, panelX + 1, panelY + 1, panelW - 2, panelH - 2,
                    SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

            int titleY = panelY + (SBSTheme.HEADER_HEIGHT - font.lineHeight) / 2;
            g.centeredText(font, Component.literal("Announce Which Sea Creatures"),
                    panelX + panelW / 2, titleY, SBSTheme.ACCENT_BRIGHT);
            int pad = SBSTheme.PANEL_PADDING;
            g.fill(panelX + pad, dividerY, panelX + panelW - pad, dividerY + 1, SBSTheme.ACCENT);

            int topY = dividerY + SBSTheme.GAP_AFTER_HEADER;
            SciFiRender.roundedRectWithBorder(g, innerX, topY, contentW - 70 * 2 - 12, SBSTheme.SEARCH_HEIGHT,
                    SBSTheme.CORNER_RADIUS, SBSTheme.SEARCH_FILL,
                    search.isFocused() ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            drawList(g, mouseX, mouseY);
            drawFooter(g);
        }

        private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            var font = SeaCreatureAnnounceScreen.this.font;
            if (results.isEmpty()) {
                g.centeredText(font, Component.literal("§7No matching sea creatures."),
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

        /** One creature: its name in its rarity colour, its tier, and the state of its exception. */
        private void drawRow(GuiGraphicsExtractor g, String creature, int x, int y, int w,
                             int mouseX, int mouseY) {
            var font = SeaCreatureAnnounceScreen.this.font;
            int h = ROW_H - 2;
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            SciFiRender.roundedRectWithBorder(g, x, y, w, h, SBSTheme.CORNER_RADIUS,
                    hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG, SBSTheme.CARD_BORDER);

            int textY = y + (h - font.lineHeight) / 2;
            SeaCreatureRarity rarity = FishingData.rarityOf(creature);
            CreatureAnnounceMode mode = cfg().modeFor(creature);
            boolean announced = mode == CreatureAnnounceMode.ALWAYS
                    || (mode == CreatureAnnounceMode.AUTO && cfg().announces(rarity));

            // The state dot: filled when this creature would actually speak right now, given the
            // rarity switches AND its own exception. That combined answer is what the player came
            // to this screen to check, and neither half of it alone tells them.
            int dot = 7;
            int dotX = x + 5;
            int dotY = y + (h - dot) / 2;
            SciFiRender.roundedRectWithBorder(g, dotX, dotY, dot, dot, 2,
                    announced ? rarity.argb() : SBSTheme.CARD_BG,
                    announced ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);

            int nameX = dotX + dot + 5;
            g.text(font, Component.literal(rarity.code() + creature), nameX, textY, SBSTheme.TEXT);

            String state = mode.code() + mode.displayName() + " §8· " + rarity.code() + rarity.displayName();
            int stateW = font.width(state);
            if (nameX + font.width(creature) + 8 + stateW < x + w - 4) {
                g.text(font, Component.literal(state), x + w - 4 - stateW, textY, SBSTheme.TEXT_MUTED);
            }
        }

        private void drawFooter(GuiGraphicsExtractor g) {
            var font = SeaCreatureAnnounceScreen.this.font;
            int y = listBottom + 2;
            String status = "§7" + cfg().creatureModes.size() + " exceptions §8· "
                    + FishingData.allSeaCreatures().size() + " creatures";
            g.text(font, Component.literal(status), innerX, y, SBSTheme.TEXT_MUTED);
            String hint = "§8click to cycle Auto / Always / Never · Esc to go back";
            g.text(font, Component.literal(hint), innerX + contentW - font.width(hint), y, SBSTheme.TEXT_MUTED);
        }
    }
}
