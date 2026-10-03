/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bitsshop;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.NumberDisplay;

import java.util.ArrayList;
import java.util.List;

/**
 * The Bits Shop lines appended to an offer's tooltip: what it is worth in coins, what that works out
 * to per bit, and where that ranks against every other offer the shop sells.
 *
 * <p>The rank is the point. A coins-per-bit figure on its own means nothing without something to
 * compare it to, and the thing worth comparing against is the rest of the shop - which is why this
 * says "#3 of 41" rather than just quoting a number and leaving the arithmetic to the reader.
 */
public final class BitsShopTooltip {

    private static final int HEADER = 0x3FB4FF;
    private static final int GREEN = 0x55FF55;
    private static final int YELLOW = 0xFFFF55;
    private static final int MUTED = 0xAAAAAA;

    private BitsShopTooltip() {
    }

    private static SBSConfig.BitsShopSettings cfg() {
        return ConfigManager.getInstance().get().bitsShop;
    }

    /**
     * Returns {@code lines} with the bits-shop rows appended, or the same list untouched when this
     * stack is not a shop offer (or a shop page is not even open, so an item that merely mentions
     * bits somewhere in its lore is left alone).
     */
    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        SBSConfig.BitsShopSettings cfg = cfg();
        if (!cfg.enabled || !cfg.showTooltipLine || lines == null || stack == null || stack.isEmpty()) {
            return lines;
        }
        if (!BitsShop.shopOpen()) {
            return lines;
        }
        Long bits = BitsShop.bitsCost(stack);
        String id = SkyblockItem.id(stack);
        if (bits == null || id == null || id.isEmpty()) {
            return lines;
        }

        List<Component> out = new ArrayList<>(lines);
        out.add(Component.empty());
        BitsShop.Ranked ranked = BitsShop.rankOf(id);
        if (ranked == null) {
            // Known offer, unknown price: say which, rather than implying it is a bad deal.
            out.add(text("Bits Shop: no market price for this item", MUTED));
            return out;
        }

        int total = BitsShop.ranking().size();
        int color = ranked.rank() == 1 ? GREEN : ranked.rank() == 2 ? YELLOW : HEADER;
        String rankLabel = switch (ranked.rank()) {
            case 1 -> "BEST deal in the shop";
            case 2 -> "2nd best in the shop";
            default -> "#" + ranked.rank() + " of " + total;
        };

        out.add(text(BitsShop.formatPerBit(ranked.coinsPerBit()) + " coins per Bit  (" + rankLabel + ")",
                color));
        out.add(text("Sells for " + NumberDisplay.format(ranked.coins()) + " coins  ·  "
                + NumberDisplay.format(bits) + " Bits", MUTED));

        // The winner is only useful if you can find it - most offers live on another page.
        List<BitsShop.Ranked> ranking = BitsShop.ranking();
        if (ranked.rank() > 1 && !ranking.isEmpty()) {
            BitsShop.Ranked best = ranking.get(0);
            String where = best.entry().menu == null || best.entry().menu.isBlank()
                    ? "" : "  (" + best.entry().menu + ")";
            out.add(text("Best: " + bestName(best) + " at "
                    + BitsShop.formatPerBit(best.coinsPerBit()) + "/Bit" + where, MUTED));
        }
        int observed = BitsShopCatalog.getInstance().observedCount();
        out.add(text("Comparing " + total + " priced offer(s) from " + observed
                + " seen  ·  open each category once for the full shop", MUTED));
        return out;
    }

    /** A seeded entry has no display name until the page is actually seen; fall back to its id. */
    private static String bestName(BitsShop.Ranked best) {
        String name = best.entry().name;
        return name == null || name.isBlank() ? best.entry().id : name;
    }

    private static Component text(String value, int rgb) {
        return Component.literal(value).withStyle(style -> style.withColor(TextColor.fromRgb(rgb)));
    }
}
