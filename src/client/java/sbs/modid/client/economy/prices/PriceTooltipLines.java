/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.prices;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.TooltipLines;
import sbs.modid.client.economy.recipe.logic.PriceEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * The Item Overlay price lines at the very bottom of a tooltip: "Est. Value", "LBIN" and/or the
 * Bazaar prices, the Shift stack hint and the crafting margin - built once per stack, price
 * {@link PriceVersion}, config revision, count and Shift state, not once per frame (see
 * {@link sbs.modid.client.core.mixin.PriceTooltipMixin} for where they are appended).
 *
 * <p>Matching works for <b>every</b> item via {@link ItemPriceKey#keysFor} (SkyBlock
 * {@code ExtraAttributes.id}, normalised name, either attribute-shard spelling, and the registry
 * path for plain items like a Stick). A market that has no price for the item is skipped as long as
 * the other one answered - an item lives on one market, so "Not Found" next to a real price says
 * nothing. When neither knows it, the "Not Found" line does appear, so a switched-on toggle is never
 * silent and the reader can tell a missing price from a missing feature.
 */
public final class PriceTooltipLines {

    // Vanilla formatting colors: gold, yellow, green, red, gray.
    private static final int GOLD = 0xFFAA00;
    private static final int YELLOW = 0xFFFF55;
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;
    private static final int GRAY = 0xAAAAAA;

    private static final TooltipLines CACHE = new TooltipLines(PriceTooltipLines::build);

    private PriceTooltipLines() {
    }

    /** {@code current} with the price lines appended, or {@code current} when there are none. */
    public static List<Component> decorate(List<Component> current, ItemStack stack) {
        int count = stack.getCount();
        boolean stackMode = count > 1 && shiftDown();
        long key = TooltipLines.key(PriceVersion.get(), ConfigManager.getInstance().revision(),
                (count << 1) | (stackMode ? 1 : 0));
        return TooltipLines.append(current, CACHE.get(stack, key));
    }

    private static boolean shiftDown() {
        var window = Minecraft.getInstance().getWindow();
        return InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
    }

    private static List<Component> build(ItemStack stack) {
        SBSConfig.ItemOverlaySettings settings = ConfigManager.getInstance().get().itemOverlay;
        // With Shift held, every price is shown for the WHOLE stack (unit x count) instead of one
        // item; without it, a hint advertises the feature when the stack is bigger than one.
        int count = stack.getCount();
        boolean stackMode = count > 1 && shiftDown();
        List<Component> lines = new ArrayList<>();

        // The appraised value stands on its own: it is what THIS item is worth (base price plus
        // every applied star, book, gem and stone), which is a different question from the market
        // price of a clean one that the two toggles below answer.
        valueLines(lines, stack, settings, count, stackMode);

        if (!settings.showLbin && !settings.showBazaarPrice) {
            return List.copyOf(lines);
        }
        // Multiple lookup keys per item (SkyBlock id, normalised display name, either shard
        // spelling, registry path): custom Hypixel items whose ExtraAttributes id is unreadable or
        // differs from the API key still match via their display name, instead of silently showing
        // "Not Found". One resolver for every value display in the mod - see ItemPriceKey.
        List<String> candidates = ItemPriceKey.keysFor(stack);
        if (candidates.isEmpty()) {
            return List.copyOf(lines);
        }

        // Both markets are looked up before either is drawn, because what one of them found decides
        // whether the other's miss is worth a line - see the "Not Found" rule below.

        // AH / LBIN – matched by item NAME against the auctions crawl ("LBIN: <value> coins").
        // Every spelling live first; only then the last known prices (see BackupPrices).
        Long lbin = settings.showLbin
                ? firstHit(candidates, id -> LbinCache.getInstance().getLiveLbin(id))
                : null;
        BackupPrices.Series lbinBackup = null;
        if (settings.showLbin && lbin == null) {
            for (String candidate : candidates) {
                Long fallback = BackupPrices.getInstance().ahFallback(candidate);
                if (fallback != null) {
                    lbin = fallback;
                    lbinBackup = BackupPrices.getInstance().ahSeries(candidate);
                    break;
                }
            }
        }

        // Bazaar – matched by product ID; both quick_status figures shown separately.
        BazaarPriceCache.BzPrice bz = null;
        boolean bzBackup = false;
        Long chat = null;
        if (settings.showBazaarPrice) {
            String liveKey = null;
            for (String candidate : candidates) {
                bz = BazaarPriceCache.getInstance().getLive(candidate);
                if (bz != null) {
                    liveKey = candidate;
                    break;
                }
            }
            // A product gone from the Bazaar, or a side with no orders: the last known prices.
            // get() answers live-or-backup, so a different object back means a backup filled it.
            if (liveKey != null) {
                BazaarPriceCache.BzPrice filled = BazaarPriceCache.getInstance().get(liveKey);
                bzBackup = filled != null && filled != bz;
                bz = filled;
            } else {
                for (String candidate : candidates) {
                    bz = BazaarPriceCache.getInstance().get(candidate);
                    if (bz != null) {
                        bzBackup = true;
                        break;
                    }
                }
            }
            if (bz == null) {
                chat = firstHit(candidates, id -> ChatPriceCache.getInstance().getUnitPrice(id));
            }
        }

        // An item trades on ONE market: what the bazaar sells is not on the auction house and the
        // other way round, so once either lookup has answered, the other's "Not Found" is stating the
        // obvious in the middle of a tooltip. It survives only when NEITHER knows the item, where it
        // is the sole evidence the lookup ran at all rather than the price line being lost.
        boolean priced = lbin != null || bz != null || chat != null;

        if (settings.showLbin) {
            if (lbin != null && lbinBackup != null && BackupPriceMarker.enabled()) {
                // A last known price, never styled like a live one: grey, prefixed, dated.
                lines.add(Component.literal("LBIN " + BackupPriceMarker.PREFIX + " ").withColor(GRAY)
                        .append(Component.literal(stackMode
                                ? format(lbin) + " x" + count + " = " + format(lbin * (long) count) + " coins"
                                : format(lbin) + " coins").withColor(GRAY))
                        .append(Component.literal(" (last seen "
                                + BackupPriceMarker.age(lbinBackup.seenAt(), System.currentTimeMillis()) + ")")
                                .withColor(GRAY)));
            } else if (lbin != null) {
                lines.add(Component.literal("LBIN: ").withColor(GOLD).append(coins(lbin, count, stackMode)));
            } else if (!priced) {
                lines.add(Component.literal("LBIN: Not Found").withColor(GRAY));
            }
        }

        if (settings.showBazaarPrice) {
            if (bz != null && bzBackup && BackupPriceMarker.enabled()) {
                lines.add(Component.literal("BZ " + BackupPriceMarker.PREFIX + " (last known)").withColor(GRAY));
                lines.add(Component.literal("BZ Buy: ").withColor(GRAY)
                        .append(Component.literal(format(bz.buy()) + " coins").withColor(GRAY)));
                lines.add(Component.literal("BZ Sell: ").withColor(GRAY)
                        .append(Component.literal(format(bz.sell()) + " coins").withColor(GRAY)));
            } else if (bz != null) {
                lines.add(Component.literal("BZ Buy: ").withColor(GREEN)
                        .append(coins((long) bz.buy(), count, stackMode)));
                lines.add(Component.literal("BZ Sell: ").withColor(RED)
                        .append(coins((long) bz.sell(), count, stackMode)));
            } else if (chat != null) {
                lines.add(Component.literal("BZ Buy (order): ").withColor(GREEN)
                        .append(coins(chat, count, stackMode)));
            } else if (!priced) {
                lines.add(Component.literal("BZ: Not Found").withColor(GRAY));
            }
        }

        // Discoverability: only when a stack of more than one is hovered without Shift.
        if (count > 1 && !stackMode) {
            lines.add(Component.literal("Hold Shift for x" + count + " stack price").withColor(GRAY));
        }

        // Crafting margin: raw-material cost vs. direct buy price, green when crafting is cheaper.
        Long craftingCost = firstHit(candidates, id -> PriceEstimator.getInstance().craftingCost(id));
        if (craftingCost != null) {
            lines.add(Component.literal("Crafting Cost: " + format(craftingCost) + " coins").withColor(GRAY));
            Long buyPrice = firstHit(candidates, id -> PriceEstimator.getInstance().buyPrice(id));
            if (buyPrice != null) {
                long profit = buyPrice - craftingCost;
                lines.add(Component.literal("Profit: " + (profit >= 0 ? "+" : "") + format(profit) + " coins")
                        .withColor(profit >= 0 ? GREEN : RED));
            }
        }
        return List.copyOf(lines);
    }

    /**
     * The appraised value of the item in front of you: its own market price plus everything applied
     * onto it, priced from the caches the mod keeps warm (see
     * {@link sbs.modid.client.economy.itemvalue.ItemAppraisal}).
     *
     * <p>Silent when nothing about the item can be priced - an item with no market is not worth a
     * "Not Found" line the way an explicitly requested LBIN lookup is, because this line is meant to
     * be on all the time.
     */
    private static void valueLines(List<Component> lines, ItemStack stack,
                                   SBSConfig.ItemOverlaySettings settings, int count, boolean stackMode) {
        if (!settings.showItemValue) {
            return;
        }
        var value = sbs.modid.client.economy.itemvalue.ItemAppraisal.of(stack);
        if (!value.priced() || value.unit() <= 0) {
            return;
        }
        // "+" = at least this much: some component of the item has no market price, so the number
        // is a floor rather than a figure.
        String suffix = value.complete() ? "" : "+";
        // "≈" = part of it is a last known price rather than a live one.
        String prefix = value.usesBackup() && BackupPriceMarker.enabled() ? BackupPriceMarker.PREFIX : "";
        String text = stackMode
                ? prefix + format(value.unit()) + suffix + " x" + count + " = " + format(value.total()) + suffix + " coins"
                : prefix + format(value.unit()) + suffix + " coins";
        lines.add(Component.literal("Est. Value: ").withColor(GOLD)
                .append(Component.literal(text).withColor(YELLOW)));
        // The Shift hint normally rides along with the LBIN / Bazaar lines; add it here when this
        // line is the only one on, so the stack price is not a secret in that configuration.
        if (count > 1 && !stackMode && !settings.showLbin && !settings.showBazaarPrice) {
            lines.add(Component.literal("Hold Shift for x" + count + " stack price").withColor(GRAY));
        }
    }

    /** The first non-null lookup result across the candidate keys, or {@code null}. */
    private static Long firstHit(List<String> candidates, java.util.function.Function<String, Long> lookup) {
        for (String candidate : candidates) {
            Long value = lookup.apply(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * A coins value in yellow. In stack mode it reads "{@code <unit> x<count> = <total> coins}", so
     * both the unit price and what the whole stack is worth are visible at once.
     */
    private static Component coins(long unit, int count, boolean stackMode) {
        String text = stackMode
                ? format(unit) + " x" + count + " = " + format(unit * (long) count) + " coins"
                : format(unit) + " coins";
        return Component.literal(text).withColor(YELLOW);
    }

    /** "1,500,000", or "1.5M" when the player has asked for shortened numbers. */
    private static String format(long value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
