/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.mixin.AbstractContainerScreenAccessor;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.bazaar.logic.BazaarSearch;
import sbs.modid.client.economy.itemvalue.ItemAppraisal;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.skills.garden.logic.VisitorMenu;
import sbs.modid.client.skills.garden.logic.VisitorMenu.Required;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Beside a Garden visitor's menu: one button per item the visitor wants - {@code Bazaar: Enchanted Hay
 * Bale ×2  ≈ 1.2M} - that opens that item on the Bazaar.
 *
 * <p><b>One click, one command.</b> A click sends {@code /bz <item>} because the player clicked it,
 * and that is all: nothing is clicked inside the Bazaar that opens (the rule and its history are in
 * {@code docs/features/bazaar-order-message-links.md}). The search text is {@link BazaarSearch}'s,
 * the same rule every other {@code /bz} link uses.
 *
 * <p><b>Placement.</b> A column beside the menu (right, else left) when either side has room, otherwise
 * a block below it, otherwise above it - never over the menu's own slots. Labels are measured: the
 * price is dropped first, then the "Bazaar: " prefix on a narrow column, and the item name gives way
 * ({@link RowText#fit}), never the amount.
 *
 * <p>The menu is read (via {@link VisitorMenu}, the one visitor detector) only when its contents
 * change, and what it read is logged once per visitor under {@code [SBS][Visitor]} - the menu's
 * wording has never been captured, so that line is how the first visit checks the parser.
 *
 * <p><b>The Cookie Buff.</b> {@code /bz} can be refused without it. The button is not hidden on a
 * guess about the buff; instead, when the refusal line (wording confirmed from real logs) arrives
 * within {@link #REFUSAL_WINDOW_MS} of one of these clicks, a one-line hint says why.
 */
public final class VisitorBazaarButtons {

    private static final VisitorBazaarButtons INSTANCE = new VisitorBazaarButtons();

    private static final int ROW_H = 14;
    private static final int GAP = 2;
    private static final int MARGIN = 4;
    /** Narrowest column worth drawing: an ellipsised name and its amount. */
    private static final int MIN_W = 44;
    /** Below this width a button drops its "Bazaar: " prefix; the tooltip still says what it does. */
    private static final int COMPACT_W = 90;
    private static final int MAX_W = 200;
    private static final long REFUSAL_WINDOW_MS = 5000L;
    /** Both variants are in the maintainer's own logs (2026-07-02, 2026-07-04). */
    private static final Pattern COOKIE_REFUSAL =
            Pattern.compile("^You need the Cookie Buff to use this (?:command|feature)!$");

    private record Entry(Required item, String query, String itemId) {
    }

    private record Placed(Entry entry, int x, int y, int w) {
    }

    private AbstractContainerScreen<?> readScreen;
    private int readState = -1;
    private List<Entry> entries = List.of();
    private String lastLogged = "";
    private long lastClickAt;
    private boolean hintShown;
    private AbstractContainerScreen<?> noRoomLogged;

    private VisitorBazaarButtons() {
    }

    public static VisitorBazaarButtons getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.GardenHelpersSettings cfg() {
        return ConfigManager.getInstance().get().gardenHelpers;
    }

    // ------------------------------------------------------------------ reading the menu

    /** Whether the open screen is a visitor menu with something to offer; re-read on change only. */
    public boolean active(AbstractContainerScreen<?> screen) {
        if (!cfg().visitorBazaarButtons || screen == null) {
            return false;
        }
        int state = screen.getMenu().getStateId();
        if (screen != readScreen || state != readState) {
            readScreen = screen;
            readState = state;
            entries = read(screen);
        }
        return !entries.isEmpty();
    }

    private List<Entry> read(AbstractContainerScreen<?> screen) {
        if (!VisitorMenu.isVisitorMenu(screen.getMenu())) {
            return List.of();
        }
        List<Required> required = VisitorMenu.requiredItems(screen.getMenu());
        String summary = required.toString();
        if (!summary.equals(lastLogged)) {
            lastLogged = summary;
            if (required.isEmpty()) {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Visitor] visitor menu with an accept and a refuse "
                        + "button, but no 'Items Required:' block was read - the offer wording differs");
            } else {
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Visitor] offer: {}", summary);
            }
        }
        List<Entry> out = new ArrayList<>();
        for (Required item : required) {
            SkyBlockItemCatalog.Entry catalog = SkyBlockItemCatalog.getInstance().byName(item.name());
            if (catalog == null) {
                catalog = SkyBlockItemCatalog.getInstance().byNormalizedName(item.name());
            }
            String itemId = catalog == null ? null : catalog.id;
            String query = BazaarSearch.query(itemId, item.name());
            if (BazaarSearch.hasQuery(query)) {
                out.add(new Entry(item, query, itemId));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ layout

    private static String label(Entry entry, int width) {
        return width < COMPACT_W ? entry.item().name() : "Bazaar: " + entry.item().name();
    }

    private static String amount(Entry entry) {
        return " ×" + entry.item().amount();
    }

    /** The insta-buy cost for the amount, or {@code null} when nothing prices it (never "0"). */
    private static String price(Entry entry) {
        if (!cfg().visitorBazaarPrice || entry.itemId() == null) {
            return null;
        }
        Long unit = ItemAppraisal.price(entry.itemId(), ItemAppraisal.Side.BUY);
        return unit == null ? null : "≈ " + NumberDisplay.shorten((double) unit * entry.item().amount());
    }

    private List<Placed> layout(AbstractContainerScreen<?> screen, Font font) {
        AbstractContainerScreenAccessor bounds = (AbstractContainerScreenAccessor) screen;
        int left = bounds.skyblockSimplified$leftPos();
        int top = bounds.skyblockSimplified$topPos();
        int menuW = bounds.skyblockSimplified$imageWidth();
        int menuH = bounds.skyblockSimplified$imageHeight();

        int wanted = MIN_W;
        for (Entry entry : entries) {
            String price = price(entry);
            int w = font.width(label(entry, MAX_W) + amount(entry)) + 12
                    + (price == null ? 0 : font.width(price) + 8);
            wanted = Math.max(wanted, Math.min(MAX_W, w));
        }
        int columnH = entries.size() * (ROW_H + GAP) - GAP;
        List<Placed> out = new ArrayList<>();

        // Beside the menu first - right, then left - because a tall menu (six rows is 222 px) can
        // leave no room above or below it at all: 1280x720 on GUI scale 4 is 320x180, where the
        // menu already overflows vertically and each side has 64 px.
        int rightSpace = screen.width - (left + menuW) - MARGIN * 2;
        int leftSpace = left - MARGIN * 2;
        if (rightSpace >= MIN_W || leftSpace >= MIN_W) {
            boolean right = rightSpace >= Math.min(wanted, MIN_W * 2) || rightSpace >= leftSpace;
            int w = Math.min(wanted, right ? rightSpace : leftSpace);
            int x = right ? left + menuW + MARGIN : left - MARGIN - w;
            int y = Math.max(MARGIN, Math.min(top, screen.height - MARGIN - columnH));
            for (Entry entry : entries) {
                out.add(new Placed(entry, x, y, w));
                y += ROW_H + GAP;
            }
            return out;
        }
        int w = Math.min(wanted, Math.max(MIN_W, menuW));
        int below = top + menuH + MARGIN;
        int above = top - MARGIN - columnH;
        int y;
        if (below + columnH <= screen.height - MARGIN) {
            y = below;
        } else if (above >= MARGIN) {
            y = above;
        } else {
            // No room anywhere without covering the menu: draw nothing rather than overlap - and say
            // so once per menu, since a feature that silently draws nothing looks exactly like a
            // broken one.
            if (screen != noRoomLogged) {
                noRoomLogged = screen;
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Visitor] no room beside, below or above the "
                        + "menu for {} button(s) at {}x{} - not drawn", entries.size(), screen.width,
                        screen.height);
            }
            return out;
        }
        int x = Math.max(MARGIN, Math.min(left, screen.width - MARGIN - w));
        for (Entry entry : entries) {
            out.add(new Placed(entry, x, y, w));
            y += ROW_H + GAP;
        }
        return out;
    }

    // ------------------------------------------------------------------ drawing

    /** From {@code OverlayRenderMixin}, in absolute screen coordinates. */
    public void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!active(screen)) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Placed hovered = null;
        for (Placed placed : layout(screen, font)) {
            boolean over = contains(placed, mouseX, mouseY);
            if (over) {
                hovered = placed;
            }
            SciFiRender.roundedRectWithBorder(g, placed.x(), placed.y(), placed.w(), ROW_H,
                    SBSTheme.CORNER_RADIUS, over ? SBSTheme.CARD_BG_HOVER : SBSTheme.CARD_BG,
                    over ? SBSTheme.ACCENT_BRIGHT : SBSTheme.CARD_BORDER);
            int textY = placed.y() + (ROW_H - font.lineHeight) / 2 + 1;
            String amount = amount(placed.entry());
            String price = price(placed.entry());
            int inner = placed.w() - 8;
            int priceW = price == null ? 0 : font.width(price) + 6;
            int labelRoom = inner - font.width(amount) - priceW;
            if (price != null && labelRoom < font.width("Enchanted...")) {
                price = null;   // the price gives way before the name does
                priceW = 0;
                labelRoom = inner - font.width(amount);
            }
            String text = RowText.fit(font, label(placed.entry(), placed.w()), Math.max(0, labelRoom))
                    + amount;
            g.text(font, Component.literal(text), placed.x() + 4, textY, SBSTheme.TEXT);
            if (price != null) {
                g.text(font, Component.literal(price), placed.x() + placed.w() - 4 - font.width(price),
                        textY, 0xFFFFD166);
            }
        }
        if (hovered != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal("Opens " + hovered.entry().item().name() + " on the Bazaar"));
            String price = price(hovered.entry());
            tip.add(Component.literal(price == null
                            ? "No cached price for this item"
                            : "Buying " + hovered.entry().item().amount() + " now: " + price)
                    .withColor(0xAAAAAA));
            g.setTooltipForNextFrame(font, tip, Optional.empty(), mouseX, mouseY, SBSTheme.tooltipStyle());
        }
    }

    private static boolean contains(Placed placed, double x, double y) {
        return x >= placed.x() && x < placed.x() + placed.w() && y >= placed.y() && y < placed.y() + ROW_H;
    }

    // ------------------------------------------------------------------ input

    /** From {@code InventoryButtonsInputMixin}: {@code true} when a button took the click. */
    public boolean handleClick(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (event.button() != 0 || !active(screen)) {
            return false;
        }
        for (Placed placed : layout(screen, Minecraft.getInstance().font)) {
            if (contains(placed, event.x(), event.y())) {
                var player = Minecraft.getInstance().player;
                if (player != null && player.connection != null) {
                    player.connection.sendCommand(BazaarSearch.command(placed.entry().query()));
                    lastClickAt = System.currentTimeMillis();
                    hintShown = false;
                }
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the refusal hint

    /** Every chat line: explains a Cookie Buff refusal that follows one of our clicks. */
    public void onChat(String text) {
        if (hintShown || text == null || System.currentTimeMillis() - lastClickAt > REFUSAL_WINDOW_MS) {
            return;
        }
        if (COOKIE_REFUSAL.matcher(text.trim()).matches()) {
            hintShown = true;
            // No claim about where the buff is needed: the logs show the refusal, not its rule.
            SBSChat.send("§7The Bazaar needs the Cookie Buff here - that is why the visitor "
                    + "button did not open it.");
        }
    }
}
