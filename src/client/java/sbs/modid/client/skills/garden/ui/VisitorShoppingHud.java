/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.bazaar.logic.BazaarSearch;
import sbs.modid.client.economy.itemvalue.ItemAppraisal;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.skills.garden.logic.VisitorMenu;
import sbs.modid.client.skills.garden.logic.VisitorOfferStore;
import sbs.modid.client.skills.garden.logic.VisitorShoppingList;
import sbs.modid.client.skills.garden.logic.VisitorShoppingList.Line;
import sbs.modid.client.skills.garden.logic.VisitorShoppingList.Offer;
import sbs.modid.client.skills.garden.logic.VisitorShoppingList.VisitorCost;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.Locale;

/**
 * The visitor shopping list: a HUD card on the Garden, and the same list in chat
 * ({@code /sbs visitors}) with every item a link. A link runs one {@code /bz <query>} because the
 * player clicked it - {@link BazaarSearch}'s rule, no navigation after it.
 *
 * <p>"Have" counts the <b>inventory only</b>: sack amounts are read from lore in a format that has
 * never been verified, and nothing stores them outside an open sack, so crediting them would be a
 * guess. Costs are insta-buy from warm caches; a line or a visitor with any unpriced item shows
 * {@code +} after its cost instead of pretending the sum is complete.
 */
public final class VisitorShoppingHud {

    private static final int MAX_ITEM_ROWS = 8;
    private static final long REFRESH_MS = 500;

    private static List<Line> lines = List.of();
    private static List<VisitorCost> costs = List.of();
    private static List<String> unknown = List.of();
    private static long builtAt;

    private VisitorShoppingHud() {
    }

    private static SBSConfig.GardenHelpersSettings cfg() {
        return ConfigManager.getInstance().get().gardenHelpers;
    }

    // ------------------------------------------------------------------ the numbers

    /** Insta-buy price per item by display name, or {@code null}. Warm caches only. */
    static Long unitPrice(String name) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byName(name);
        if (entry == null) {
            entry = SkyBlockItemCatalog.getInstance().byNormalizedName(name);
        }
        return entry == null ? null : ItemAppraisal.price(entry.id, ItemAppraisal.Side.BUY);
    }

    /** How many of an item (by display name) the inventory holds. Sacks are not counted - see above. */
    static int inInventory(String name) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        int total = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()
                    && VisitorMenu.plain(stack.getHoverName().getString()).trim().toLowerCase(Locale.ROOT)
                    .equals(wanted)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void rebuild() {
        long now = System.currentTimeMillis();
        if (now - builtAt < REFRESH_MS) {
            return;
        }
        builtAt = now;
        VisitorOfferStore store = VisitorOfferStore.getInstance();
        List<Offer> offers = store.offers();
        lines = VisitorShoppingList.lines(offers, VisitorShoppingHud::inInventory, VisitorShoppingHud::unitPrice);
        costs = VisitorShoppingList.visitorCosts(offers, VisitorShoppingHud::unitPrice);
        unknown = store.unknownVisitors();
    }

    private static String cost(Long coins) {
        return coins == null ? "?" : NumberDisplay.shorten((double) coins);
    }

    private static String perCopper(VisitorCost c) {
        Double value = c.coinsPerCopper();
        return value == null ? "no copper" : NumberDisplay.shorten(value) + (c.fullyPriced() ? "" : "+") + "/copper";
    }

    // ------------------------------------------------------------------ HUD

    /** From the HUD hook once per frame; draws only on the Garden, and only with something to show. */
    public static void render(GuiGraphicsExtractor g) {
        if (!cfg().visitorShoppingList || !cfg().visitorShoppingHud
                || HudLayout.isHidden(HudElement.VISITOR_SHOPPING)
                || Minecraft.getInstance().player == null || !SkyBlockLocation.onIsland("Garden")) {
            return;
        }
        rebuild();
        if (lines.isEmpty() && unknown.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        int pad = 5;
        List<Line> shownLines = lines.size() > MAX_ITEM_ROWS ? lines.subList(0, MAX_ITEM_ROWS) : lines;
        int rows = 1 + shownLines.size() + (lines.size() > MAX_ITEM_ROWS ? 1 : 0)
                + (costs.isEmpty() ? 0 : 1 + costs.size()) + (unknown.isEmpty() ? 0 : 1);
        int width = 150;
        for (Line line : shownLines) {
            width = Math.max(width, font.width(itemLeft(line)) + 12 + font.width(itemRight(line)) + pad * 2);
        }
        for (VisitorCost c : costs) {
            width = Math.max(width, font.width(visitorLeft(c)) + 12 + font.width(perCopper(c)) + pad * 2);
        }
        String unknownText = unknown.isEmpty() ? null : "Open once: " + String.join(", ", unknown);
        if (unknownText != null) {
            width = Math.max(width, Math.min(260, font.width(unknownText) + pad * 2));
        }
        int height = pad * 2 + lineH * rows - 2;

        HudElement.Bounds b = HudElement.VISITOR_SHOPPING.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.VISITOR_SHOPPING, x, y, width, height);
        HudLayout.begin(g, HudElement.VISITOR_SHOPPING);
        HudCard.draw(g, x, y, width, height);
        int ix = x + pad;
        int right = x + width - pad;
        int iy = y + pad;
        g.text(font, Component.literal("Visitor Shopping"), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (Line line : shownLines) {
            g.text(font, Component.literal(itemLeft(line)), ix, iy,
                    line.missing() == 0 ? SBSTheme.TEXT_MUTED : SBSTheme.TEXT);
            String r = itemRight(line);
            g.text(font, Component.literal(r), right - font.width(r), iy, SBSTheme.TEXT);
            iy += lineH;
        }
        if (lines.size() > MAX_ITEM_ROWS) {
            g.text(font, Component.literal("+" + (lines.size() - MAX_ITEM_ROWS) + " more - /sbs visitors"),
                    ix, iy, SBSTheme.TEXT_MUTED);
            iy += lineH;
        }
        if (!costs.isEmpty()) {
            g.text(font, Component.literal("Cheapest copper"), ix, iy, SBSTheme.ACCENT_BRIGHT);
            iy += lineH;
            for (VisitorCost c : costs) {
                g.text(font, Component.literal(visitorLeft(c)), ix, iy,
                        c.offer().rareReward() != null ? 0xFFFFAA00 : SBSTheme.TEXT);
                String r = perCopper(c);
                g.text(font, Component.literal(r), right - font.width(r), iy, SBSTheme.TEXT_MUTED);
                iy += lineH;
            }
        }
        if (unknownText != null) {
            g.text(font, Component.literal(font.plainSubstrByWidth(unknownText, width - pad * 2)), ix, iy,
                    SBSTheme.TEXT_MUTED);
        }
        HudLayout.end(g);
    }

    private static String itemLeft(Line line) {
        return line.name() + " " + line.have() + "/" + line.needed();
    }

    private static String itemRight(Line line) {
        return line.missing() == 0 ? "done" : cost(line.cost()) + (line.unitPrice() == null ? "+" : "");
    }

    private static String visitorLeft(VisitorCost c) {
        return (c.offer().rareReward() != null ? "★ " : "") + c.offer().visitor()
                + " " + cost(c.cost()) + (c.fullyPriced() ? "" : "+");
    }

    // ------------------------------------------------------------------ chat

    /** {@code /sbs visitors}: the whole list in chat, each item a {@code /bz} link. */
    public static void printToChat() {
        builtAt = 0;
        rebuild();
        if (lines.isEmpty() && unknown.isEmpty()) {
            SBSChat.send("§7No visitor offers known yet - open a visitor's menu once on the Garden.");
            return;
        }
        SBSChat.send("§bVisitor shopping list §8(inventory only - sacks not counted)");
        for (Line line : lines) {
            MutableComponent row = Component.literal("  ");
            String query = BazaarSearch.query(null, line.name());
            MutableComponent name = Component.literal(line.name()).withStyle(ChatFormatting.YELLOW);
            if (BazaarSearch.hasQuery(query) && line.missing() > 0) {
                name = name.withStyle(style -> style.withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/" + BazaarSearch.command(query)))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Opens " + line.name()
                                + " on the Bazaar"))));
            }
            row.append(name).append(Component.literal(" " + line.have() + "/" + line.needed() + "  "
                    + itemRight(line)).withStyle(ChatFormatting.GRAY));
            SBSChat.send(row);
        }
        for (VisitorCost c : costs) {
            SBSChat.send(Component.literal("  " + visitorLeft(c) + " → "
                    + (c.offer().copper() > 0 ? c.offer().copper() + " copper, " : "")
                    + perCopper(c)
                    + (c.offer().rareReward() != null ? "  [" + c.offer().rareReward() + "]" : ""))
                    .withStyle(c.offer().rareReward() != null ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        }
        if (!unknown.isEmpty()) {
            SBSChat.send("§7Open their menu once: §f" + String.join(", ", unknown));
        }
    }
}
