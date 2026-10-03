/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.essenceshop.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.essenceshop.logic.EssenceBazaar;
import sbs.modid.client.economy.essenceshop.logic.EssenceShopReader;
import sbs.modid.client.economy.essenceshop.model.EssenceType;
import sbs.modid.client.economy.essenceshop.model.LevelSource;
import sbs.modid.client.economy.essenceshop.model.PerkRow;
import sbs.modid.client.economy.essenceshop.model.ShopOverview;
import sbs.modid.client.ui.component.SciFiScrollbar;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.window.FloatingWindows;
import sbs.modid.client.ui.window.WindowMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The overview beside an open essence shop: what every perk still costs to max, what the whole shop
 * costs together, how much of that the player is short, and a button that opens the Bazaar for the
 * essence being spent.
 *
 * <p>It answers the question the menu itself cannot. Hypixel states the price of the next level and
 * only that, so "what will finishing this perk cost" needs the whole cost curve - which comes from
 * the perk table, not from the screen.
 *
 * <p><b>It never touches a slot.</b> The panel is drawn outside the menu and takes clicks only
 * inside its own rectangle, through the same routing every other overlay uses. A click on a perk is
 * still Hypixel's click and reaches the server untouched: repurposing one would either buy something
 * the player did not choose or swallow something they did.
 *
 * <p>A total is never quietly short. Perks whose level could not be read stay out of it and the
 * footer says how many there were.
 */
public final class EssenceShopOverlay {

    private static final EssenceShopOverlay INSTANCE = new EssenceShopOverlay();

    private static final int HEADER_H = 15;
    private static final int PAD = 5;
    private static final int MARGIN = 2;
    private static final int WIDTH = 234;
    private static final int MAX_HEIGHT = 250;
    private static final int MIN_BAR_W = 110;
    private static final int ROW_H = 11;
    private static final int BUTTON_H = 14;

    private int posX = Integer.MIN_VALUE;
    private int posY;
    private int panelW = WIDTH;
    private int panelH = MAX_HEIGHT;

    private boolean minimized;
    private boolean dragging;
    private double grabDX;
    private double grabDY;
    private int scroll;

    private final SciFiScrollbar bar = new SciFiScrollbar();
    private final WindowMemory memory = new WindowMemory(FloatingWindows.Layer.ESSENCE_SHOP);

    /** Collapsed-bar rectangle from the last frame, so clicks hit exactly what was drawn. */
    private int minBarX;
    private int minBarY;
    private int minBarW;
    private int minBarH;

    /** The Bazaar button's rectangle from the last frame, and the essence it was drawn for. */
    private int buttonX;
    private int buttonY;
    private int buttonW;
    private int buttonH;
    private EssenceType buttonType;

    private EssenceShopOverlay() {
    }

    public static EssenceShopOverlay getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.EssenceShopSettings cfg() {
        return ConfigManager.getInstance().get().essenceShop;
    }

    /** The shop on screen right now, or {@code null} - memoised per menu revision by the reader. */
    private EssenceShopReader.Result shop(AbstractContainerScreen<?> container) {
        if (!cfg().enabled) {
            return null;
        }
        return EssenceShopReader.getInstance().read(container);
    }

    private boolean inPanel(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + panelH;
    }

    private boolean inHeader(double mx, double my) {
        return mx >= posX && mx < posX + panelW && my >= posY && my < posY + HEADER_H;
    }

    private int minimizeGlyphX() {
        return posX + panelW - 14;
    }

    private boolean inMinimizeBox(double mx, double my) {
        return mx >= minimizeGlyphX() && mx < minimizeGlyphX() + 12
                && my >= posY + 2 && my < posY + HEADER_H;
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    /** Drawn on the floating-window pass, above the menu's own slots. */
    public void renderTopMost(AbstractContainerScreen<?> container, GuiGraphicsExtractor g,
                              int mouseX, int mouseY) {
        EssenceShopReader.Result result = shop(container);
        if (result == null) {
            return;
        }
        ShopOverview overview = result.overview();
        Font font = Minecraft.getInstance().font;

        memory.restore(state -> {
            posX = state.x;
            posY = state.y;
            minimized = state.minimized;
        });
        // Default spot: left of the centred menu, which is where there is room at every GUI scale
        // this mod supports. Clamped like any other position, so a narrow viewport still fits it.
        if (posX == Integer.MIN_VALUE) {
            posX = MARGIN * 2;
            posY = MARGIN * 2;
        }
        if (minimized) {
            drawMinimizedBar(container, g, font, mouseX, mouseY);
            return;
        }

        List<String[]> notes = notes(overview, result.carriedOver());
        panelW = Math.min(WIDTH, Math.max(120, container.width - MARGIN * 2));
        int chrome = HEADER_H + 4 + font.lineHeight + 4 + footerHeight(font, overview, notes);
        int wanted = chrome + Math.max(ROW_H, overview.rows().size() * ROW_H) + PAD;
        panelH = Math.min(Math.min(MAX_HEIGHT, wanted), Math.max(60, container.height - MARGIN * 2));
        posX = clamp(posX, MARGIN, Math.max(MARGIN, container.width - panelW - MARGIN));
        posY = clamp(posY, MARGIN, Math.max(MARGIN, container.height - panelH - MARGIN));

        SciFiRender.glow(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_GLOW, 2);
        SciFiRender.roundedRect(g, posX, posY, panelW, panelH, SBSTheme.PANEL_CORNER, SBSTheme.PANEL_BORDER);
        SciFiRender.roundedRectGradient(g, posX + 1, posY + 1, panelW - 2, panelH - 2,
                SBSTheme.PANEL_CORNER - 1, SBSTheme.PANEL_FILL_TOP, SBSTheme.PANEL_FILL_BOTTOM);

        drawHeader(g, font, overview, mouseX, mouseY);
        int listTop = listTop(font);
        int listBottom = Math.max(listTop, posY + panelH - PAD - footerHeight(font, overview, notes));
        // The rows are the part that yields when the panel is too short for everything: a total the
        // player cannot read is worth less than one row they can, and overlapping text renders
        // silently. The list is scrollable, so nothing is lost by dropping it at extreme sizes.
        if (listBottom - listTop >= ROW_H) {
            drawRows(g, font, overview, listTop, listBottom, mouseX, mouseY);
        }
        drawFooter(g, font, overview, notes, listBottom, mouseX, mouseY);
    }

    private void drawHeader(GuiGraphicsExtractor g, Font font, ShopOverview overview,
                            int mouseX, int mouseY) {
        int textY = posY + (HEADER_H - font.lineHeight) / 2 + 1;
        String title = RowText.fit(font, overview.type().displayName(), minimizeGlyphX() - 4 - (posX + PAD));
        g.text(font, Component.literal(title), posX + PAD, textY, SBSTheme.ACCENT_BRIGHT);
        boolean minHover = inMinimizeBox(mouseX, mouseY);
        g.text(font, Component.literal("-"), minimizeGlyphX() + 4, textY,
                minHover ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT_MUTED);
        g.fill(posX + PAD, posY + HEADER_H, posX + panelW - PAD, posY + HEADER_H + 1, SBSTheme.ACCENT_SOFT);

        // The balance sits on its own line under the header. It is the number the shortfall is built
        // on, so when the menu did not state it that has to be visible rather than implied by a
        // missing column.
        int balanceY = posY + HEADER_H + 4;
        String label = overview.balance() >= 0 ? "You have" : "Your balance";
        String value = overview.balance() >= 0
                ? NumberDisplay.format(overview.balance()) : "unknown";
        int valueW = font.width(value);
        g.text(font, Component.literal(RowText.fit(font, label, panelW - PAD * 2 - valueW - 6)),
                posX + PAD, balanceY, SBSTheme.TEXT_MUTED);
        g.text(font, Component.literal(value), posX + panelW - PAD - valueW, balanceY,
                overview.balance() >= 0 ? SBSTheme.TEXT : SBSTheme.TEXT_MUTED);
    }

    private int listTop(Font font) {
        return posY + HEADER_H + 4 + font.lineHeight + 4;
    }

    private void drawRows(GuiGraphicsExtractor g, Font font, ShopOverview overview,
                          int top, int bottom, int mouseX, int mouseY) {
        List<PerkRow> rows = overview.rows();
        int visible = Math.max(1, (bottom - top) / ROW_H);
        syncBar(overview, top, bottom);
        scroll = clamp(scroll, 0, Math.max(0, rows.size() - visible));
        int listRight = posX + panelW - PAD - (bar.needed() ? SciFiScrollbar.WIDTH + 3 : 0);

        // Both value columns are reserved from the widest string they can produce, not from the
        // current one, or the name column jitters as the numbers change.
        int levelW = 0;
        int costW = 0;
        for (PerkRow row : rows) {
            levelW = Math.max(levelW, font.width(row.levelText()));
            costW = Math.max(costW, font.width(costText(row)));
        }
        int nameW = Math.max(20, listRight - (posX + PAD) - levelW - costW - 10);

        for (int i = 0; i < visible && i + scroll < rows.size(); i++) {
            PerkRow row = rows.get(i + scroll);
            int y = top + i * ROW_H + (ROW_H - font.lineHeight) / 2;
            int nameColour = row.counted() ? (row.maxed() ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT)
                    : SBSTheme.TEXT_MUTED;
            g.text(font, Component.literal(RowText.fit(font, row.perk().name(), nameW)),
                    posX + PAD, y, nameColour);
            String level = row.levelText();
            g.text(font, Component.literal(level), listRight - costW - 6 - font.width(level), y,
                    row.source() == LevelSource.LORE ? SBSTheme.TEXT_MUTED : SBSTheme.WARN);
            String cost = costText(row);
            g.text(font, Component.literal(cost), listRight - font.width(cost), y, costColour(row));
        }
        bar.render(g, scroll, mouseX, mouseY);
    }

    /** What one perk still owes: a number, {@code done} when there is nothing left, {@code ?} when unread. */
    private static String costText(PerkRow row) {
        if (!row.counted()) {
            return "?";
        }
        return row.maxed() ? "done" : NumberDisplay.format(row.remaining());
    }

    private static int costColour(PerkRow row) {
        if (!row.counted()) {
            return SBSTheme.WARN;
        }
        return row.maxed() ? SBSTheme.TOGGLE_ON : SBSTheme.TEXT;
    }

    /**
     * The footer's lines of caveat, built from what the overview actually found. Each is
     * {@code {text, colour}} with the colour as a string key so the height can be measured from the
     * same list the drawing walks - the height and the content can never disagree.
     */
    private List<String[]> notes(ShopOverview overview, boolean carriedOver) {
        List<String[]> notes = new ArrayList<>();
        if (!overview.complete()) {
            notes.add(new String[]{overview.unknownCount() + " perk(s) unreadable - total incomplete", "warn"});
        }
        if (overview.inferredCount() > 0) {
            notes.add(new String[]{"~ " + overview.inferredCount() + " level(s) read from the perk name", "muted"});
        }
        if (carriedOver) {
            notes.add(new String[]{"from the perk list you just left", "muted"});
        }
        return notes;
    }

    private int footerHeight(Font font, ShopOverview overview, List<String[]> notes) {
        int lines = 1;                                   // the total
        if (overview.balance() >= 0) {
            lines++;                                     // the shortfall
        }
        if (cfg().coinEstimate && EssenceBazaar.coinsFor(overview.type(), overview.total()) != null) {
            lines++;
        }
        lines += notes.size();
        int height = 3 + lines * font.lineHeight + 2;
        if (cfg().bazaarShortcut) {
            height += BUTTON_H + 3;
        }
        return height;
    }

    private void drawFooter(GuiGraphicsExtractor g, Font font, ShopOverview overview,
                            List<String[]> notes, int top, int mouseX, int mouseY) {
        g.fill(posX + PAD, top, posX + panelW - PAD, top + 1, SBSTheme.ACCENT_SOFT);
        int y = top + 3;
        int right = posX + panelW - PAD;

        String total = overview.total() > 0 ? NumberDisplay.format(overview.total()) : "nothing left";
        y = keyValue(g, font, y, "To max", total,
                overview.total() > 0 ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TOGGLE_ON, right);

        if (overview.balance() >= 0) {
            long shortfall = overview.shortfall();
            y = keyValue(g, font, y, shortfall > 0 ? "Still short" : "Covered by your balance",
                    shortfall > 0 ? NumberDisplay.format(shortfall) : "yes",
                    shortfall > 0 ? SBSTheme.WARN : SBSTheme.TOGGLE_ON, right);
        }
        if (cfg().coinEstimate) {
            Long coins = EssenceBazaar.coinsFor(overview.type(), overview.total());
            if (coins != null) {
                y = keyValue(g, font, y, "Bazaar cost (est.)",
                        NumberDisplay.format(coins) + " coins", SBSTheme.TEXT_MUTED, right);
            }
        }
        for (String[] note : notes) {
            g.text(font, Component.literal(RowText.fit(font, note[0], panelW - PAD * 2)),
                    posX + PAD, y, "warn".equals(note[1]) ? SBSTheme.WARN : SBSTheme.TEXT_MUTED);
            y += font.lineHeight;
        }
        if (cfg().bazaarShortcut) {
            drawBazaarButton(g, font, overview.type(), y + 2, mouseX, mouseY);
        } else {
            buttonType = null;
        }
    }

    private int keyValue(GuiGraphicsExtractor g, Font font, int y, String label, String value,
                         int valueColour, int right) {
        int valueW = font.width(value);
        g.text(font, Component.literal(RowText.fit(font, label, right - (posX + PAD) - valueW - 6)),
                posX + PAD, y, SBSTheme.TEXT_MUTED);
        g.text(font, Component.literal(value), right - valueW, y, valueColour);
        return y + font.lineHeight;
    }

    private void drawBazaarButton(GuiGraphicsExtractor g, Font font, EssenceType type, int y,
                                  int mouseX, int mouseY) {
        buttonX = posX + PAD;
        buttonY = y;
        buttonW = panelW - PAD * 2;
        buttonH = BUTTON_H;
        buttonType = type;
        boolean hovered = mouseX >= buttonX && mouseX < buttonX + buttonW
                && mouseY >= buttonY && mouseY < buttonY + buttonH;
        SciFiRender.roundedRectWithBorder(g, buttonX, buttonY, buttonW, buttonH, SBSTheme.CORNER_RADIUS,
                hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        String label = RowText.fit(font, "Bazaar: " + type.displayName(), buttonW - 8);
        g.text(font, Component.literal(label), buttonX + (buttonW - font.width(label)) / 2,
                buttonY + (buttonH - font.lineHeight) / 2 + 1,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.TEXT);
        if (hovered) {
            List<Component> tip = List.of(
                    Component.literal("Opens the Bazaar for " + type.displayName()),
                    Component.literal("§8Runs /bz once, on this click, and closes the menu"));
            g.setTooltipForNextFrame(font, tip, Optional.empty(), mouseX, mouseY, SBSTheme.tooltipStyle());
        }
    }

    private void syncBar(ShopOverview overview, int top, int bottom) {
        bar.set(posX + panelW - PAD - SciFiScrollbar.WIDTH, top, bottom - top,
                overview.rows().size(), Math.max(1, (bottom - top) / ROW_H));
    }

    private void drawMinimizedBar(AbstractContainerScreen<?> container, GuiGraphicsExtractor g,
                                  Font font, int mouseX, int mouseY) {
        minBarW = MIN_BAR_W;
        minBarH = HEADER_H;
        minBarX = clamp(posX, MARGIN, Math.max(MARGIN, container.width - minBarW - MARGIN));
        minBarY = clamp(posY, MARGIN, Math.max(MARGIN, container.height - minBarH - MARGIN));
        boolean hovered = mouseX >= minBarX && mouseX < minBarX + minBarW
                && mouseY >= minBarY && mouseY < minBarY + minBarH;
        SciFiRender.roundedRectWithBorder(g, minBarX, minBarY, minBarW, minBarH,
                SBSTheme.CORNER_RADIUS, hovered ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                hovered ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
        g.text(font, Component.literal("Essence +"), minBarX + PAD,
                minBarY + (minBarH - font.lineHeight) / 2 + 1, SBSTheme.TEXT);
    }

    // ------------------------------------------------------------------
    // Input - forwarded from ContainerSearchBarMixin
    // ------------------------------------------------------------------

    /** @return whether the click was consumed (and must not reach the menu's slots) */
    public boolean handleClick(AbstractContainerScreen<?> container, MouseButtonEvent event) {
        if (shop(container) == null) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (minimized) {
            if (event.button() == 0 && mx >= minBarX && mx < minBarX + minBarW
                    && my >= minBarY && my < minBarY + minBarH) {
                minimized = false;
                rememberWindow();
                FloatingWindows.raise(FloatingWindows.Layer.ESSENCE_SHOP);
                return true;
            }
            return false;   // everything else passes through to the menu
        }
        if (!inPanel(mx, my)) {
            return false;
        }
        if (event.button() == 0 && inMinimizeBox(mx, my)) {
            minimized = true;
            rememberWindow();
            return true;
        }
        if (event.button() == 0 && inHeader(mx, my)) {
            dragging = true;
            grabDX = mx - posX;
            grabDY = my - posY;
            return true;
        }
        // The Bazaar button: the one place in this panel that sends anything, and only ever on a
        // left click the player aimed at the button drawn last frame.
        if (event.button() == 0 && buttonType != null && mx >= buttonX && mx < buttonX + buttonW
                && my >= buttonY && my < buttonY + buttonH) {
            EssenceBazaar.open(buttonType);
            return true;
        }
        if (event.button() == 0 && bar.handleClick(mx, my, scroll, value -> scroll = value)) {
            return true;
        }
        // Swallow every other click inside the panel: it is ours, not the shop's underneath.
        return true;
    }

    public boolean handleDrag(AbstractContainerScreen<?> container, MouseButtonEvent event) {
        if (shop(container) == null || minimized) {
            return false;
        }
        if (bar.handleDrag(event.y(), value -> scroll = value)) {
            return true;
        }
        if (!dragging) {
            return false;
        }
        posX = clamp((int) (event.x() - grabDX), MARGIN,
                Math.max(MARGIN, container.width - panelW - MARGIN));
        posY = clamp((int) (event.y() - grabDY), MARGIN,
                Math.max(MARGIN, container.height - panelH - MARGIN));
        return true;
    }

    public boolean handleRelease(MouseButtonEvent event) {
        if (bar.release()) {
            return true;
        }
        if (!dragging) {
            return false;
        }
        dragging = false;
        rememberWindow();
        return true;
    }

    public boolean handleScroll(AbstractContainerScreen<?> container, double mouseX, double mouseY,
                                double scrollY) {
        if (shop(container) == null || minimized || !inPanel(mouseX, mouseY) || scrollY == 0) {
            return false;
        }
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        return true;
    }

    private void rememberWindow() {
        memory.remember(posX, posY, minimized);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }
}
