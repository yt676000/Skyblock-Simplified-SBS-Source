/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.ChatPriceCache;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.recipe.logic.PriceEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * Appends price lines to the very bottom of an item's tooltip for the Item Overlay module: "Lowest
 * BIN" (auctions) and/or "Lowest Bazaar Price" (bazaar), each independently toggleable.
 *
 * <p>Hooks {@code ItemStack#getTooltipLines} – the single choke point every tooltip goes through, so
 * the lines always sit below enchantments and existing lore. Matching works for <b>every</b> item via
 * {@link sbs.modid.client.economy.prices.ItemPriceKey#keysFor} (SkyBlock {@code ExtraAttributes.id},
 * normalised name, either attribute-shard spelling, and the registry path for plain items like a
 * Stick). A market that has no price for the item is skipped as long as the
 * other one answered - an item lives on one market, so "Not Found" next to a real price says nothing.
 * When neither knows it, the "Not Found" line does appear, so a switched-on toggle is never silent and
 * the reader can tell a missing price from a missing feature. Prices come from the lock-free
 * {@link LbinCache} / {@link BazaarPriceCache} snapshots, with the persistent {@link ChatPriceCache} as
 * a bazaar fallback.
 */
@Mixin(ItemStack.class)
public abstract class PriceTooltipMixin {

    // Vanilla formatting colors: §6 gold, §e yellow, §a green, §c red, §7 gray.
    private static final int GOLD = 0xFFAA00;
    private static final int YELLOW = 0xFFFF55;
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;
    private static final int GRAY = 0xAAAAAA;

    @Inject(method = "getTooltipLines", at = @At("RETURN"), cancellable = true)
    private void skyblockSimplified$appendPrices(Item.TooltipContext context, Player player, TooltipFlag flag,
                                                 CallbackInfoReturnable<List<Component>> cir) {
        List<Component> current = cir.getReturnValue();
        if (current == null) {
            return;
        }
        // The chest value goes first, and inside THIS handler rather than one of its own. Two
        // cancellable @At("RETURN") injections on the same method do not both run: the first one to
        // call setReturnValue makes the method return there, so a second handler is simply never
        // reached - which is why the chest line was missing while LBIN and BZ showed up fine.
        List<Component> withChest = skyblockSimplified$chestLines(current);
        if (withChest != current) {
            current = withChest;
            cir.setReturnValue(current);   // stands on its own if the price lines below are switched off
        }

        // Bits Shop coins-per-bit, for the same reason as the chest line above: it has to share this
        // handler rather than take its own RETURN injection, or whichever ran second would never run.
        // Self-gating - it does nothing unless a bits shop page is open and the stack is an offer.
        List<Component> withBits = sbs.modid.client.economy.bitsshop.BitsShopTooltip.decorate(
                current, (ItemStack) (Object) this);
        if (withBits != current) {
            current = withBits;
            cir.setReturnValue(current);
        }

        // Hunting Box shard breakdown, sharing this handler for the same reason as the two above.
        // Self-gating: it does nothing unless the Hunting Box is open and the stack is a shard.
        List<Component> withShard = sbs.modid.client.skills.hunting.render.HuntingBoxTooltip.decorate(
                current, (ItemStack) (Object) this);
        if (withShard != current) {
            current = withShard;
            cir.setReturnValue(current);
        }

        // Item Protection's "Protected by SBS" line, sharing this handler for the same reason as
        // every line above it. Self-gating: it does nothing unless the item is actually protected.
        List<Component> withProtection =
                sbs.modid.client.helper.itemprotection.render.ProtectedItemTooltip.decorate(
                        current, (ItemStack) (Object) this);
        if (withProtection != current) {
            current = withProtection;
            cir.setReturnValue(current);
        }

        // Museum Helper's "Museum: not donated · +N XP", sharing this handler for the same reason.
        // Self-gating: nothing unless the item's museum category has been fully seen and it is missing.
        List<Component> withMuseum = sbs.modid.client.helper.museum.render.MuseumTooltip.decorate(
                current, (ItemStack) (Object) this);
        if (withMuseum != current) {
            current = withMuseum;
            cir.setReturnValue(current);
        }

        // Gemstone slot summary, sharing this handler for the same reason. Self-gating: nothing
        // unless the item has gemstone slots. Placed above Est. Value, which counts the same gems.
        List<Component> withGems = sbs.modid.client.economy.itemvalue.GemSlotTooltip.decorate(
                current, (ItemStack) (Object) this);
        if (withGems != current) {
            current = withGems;
            cir.setReturnValue(current);
        }

        // NPC flips: the profit on an offer inside the shop that sells it, sharing this handler for
        // the same reason as every line above. Self-gating on the open menu being a learned shop.
        List<Component> withNpcFlip = sbs.modid.client.economy.npcshop.render.NpcFlipTooltip.decorate(
                current, (ItemStack) (Object) this);
        if (withNpcFlip != current) {
            current = withNpcFlip;
            cir.setReturnValue(current);
        }

        SBSConfig.ItemOverlaySettings settings = ConfigManager.getInstance().get().itemOverlay;

        // With Shift held, every price is shown for the WHOLE stack (unit x count) instead of one
        // item; without it, a hint advertises the feature when the stack is bigger than one.
        int count = ((ItemStack) (Object) this).getCount();
        var window = Minecraft.getInstance().getWindow();
        boolean shift = InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
        boolean stackMode = shift && count > 1;

        // The appraised value stands on its own, like the chest line above: it is what THIS item is
        // worth (base price plus every applied star, book, gem and stone), which is a different
        // question from the market price of a clean one that the two toggles below answer.
        List<Component> withValue = skyblockSimplified$valueLines(current, settings, count, stackMode);
        if (withValue != current) {
            current = withValue;
            cir.setReturnValue(current);
        }

        if (!settings.showLbin && !settings.showBazaarPrice) {
            return;
        }
        // Multiple lookup keys per item (SkyBlock id, normalised display name, either shard
        // spelling, registry path): custom Hypixel items whose ExtraAttributes id is unreadable or
        // differs from the API key still match via their display name, instead of silently showing
        // "Not Found". One resolver for every value display in the mod - see ItemPriceKey.
        List<String> candidates = sbs.modid.client.economy.prices.ItemPriceKey
                .keysFor((ItemStack) (Object) this);
        if (candidates.isEmpty()) {
            return;
        }

        List<Component> lines = new ArrayList<>(current);

        // Both markets are looked up before either is drawn, because what one of them found decides
        // whether the other's miss is worth a line - see the "Not Found" rule below.

        // AH / LBIN – matched by item NAME against the auctions crawl ("§6LBIN: §e<value> coins").
        Long lbin = settings.showLbin
                ? firstHit(candidates, id -> LbinCache.getInstance().getLbin(id))
                : null;

        // Bazaar – matched by product ID; both quick_status figures shown separately.
        BazaarPriceCache.BzPrice bz = null;
        Long chat = null;
        if (settings.showBazaarPrice) {
            for (String candidate : candidates) {
                bz = BazaarPriceCache.getInstance().get(candidate);
                if (bz != null) {
                    break;
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
            if (lbin != null) {
                lines.add(Component.literal("LBIN: ").withColor(GOLD).append(coins(lbin, count, stackMode)));
            } else if (!priced) {
                lines.add(Component.literal("LBIN: Not Found").withColor(GRAY));
            }
        }

        if (settings.showBazaarPrice) {
            if (bz != null) {
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
        if ((settings.showLbin || settings.showBazaarPrice) && count > 1 && !stackMode) {
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

        cir.setReturnValue(lines);
    }

    /**
     * The appraised value of the item in front of you: its own market price plus everything applied
     * onto it, priced from the caches the mod keeps warm (see
     * {@link sbs.modid.client.economy.itemvalue.ItemAppraisal}).
     *
     * <p>Silent when nothing about the item can be priced - an item with no market is not worth a
     * "Not Found" line the way an explicitly requested LBIN lookup is, because this line is meant to
     * be on all the time.
     *
     * @return {@code current} untouched when there is nothing to say, else a new list with the line
     */
    @org.spongepowered.asm.mixin.Unique
    private List<Component> skyblockSimplified$valueLines(List<Component> current,
                                                          SBSConfig.ItemOverlaySettings settings,
                                                          int count, boolean stackMode) {
        if (!settings.showItemValue) {
            return current;
        }
        var value = sbs.modid.client.economy.itemvalue.ItemAppraisal.of((ItemStack) (Object) this);
        if (!value.priced() || value.unit() <= 0) {
            return current;
        }
        // "+" = at least this much: some component of the item has no market price, so the number
        // is a floor rather than a figure.
        String suffix = value.complete() ? "" : "+";
        String text = stackMode
                ? format(value.unit()) + suffix + " x" + count + " = " + format(value.total()) + suffix + " coins"
                : format(value.unit()) + suffix + " coins";
        List<Component> lines = new ArrayList<>(current);
        lines.add(Component.literal("Est. Value: ").withColor(GOLD)
                .append(Component.literal(text).withColor(YELLOW)));
        // The Shift hint normally rides along with the LBIN / Bazaar lines; add it here when this
        // line is the only one on, so the stack price is not a secret in that configuration.
        if (count > 1 && !stackMode && !settings.showLbin && !settings.showBazaarPrice) {
            lines.add(Component.literal("Hold Shift for x" + count + " stack price").withColor(GRAY));
        }
        return lines;
    }

    /**
     * Dungeon reward chests: what the loot is worth against what opening it costs.
     *
     * <p>Independent of the LBIN / Bazaar toggles - those decide whether to show an item's own market
     * price, and a chest has none: it is a container whose worth is the sum of its contents.
     *
     * @return {@code current} untouched when this is not a chest, else a new list with the value line
     */
    @org.spongepowered.asm.mixin.Unique
    private List<Component> skyblockSimplified$chestLines(List<Component> current) {
        if (!ConfigManager.getInstance().get().dungeons.chestValue) {
            return current;
        }
        var chest = sbs.modid.client.dungeons.chest.DungeonChestValue.of((ItemStack) (Object) this);
        if (chest == null) {
            return current;
        }
        // One line, the way the other mods word it: "Chest Value" is already the NET - contents minus
        // what opening costs - so a negative number means the chest is not worth opening.
        long profit = chest.profit();
        List<Component> lines = new ArrayList<>(current);
        lines.add(Component.literal("[SBS] ").withColor(GOLD)
                .append(Component.literal("Chest Value: " + (profit >= 0 ? "+" : "") + format(profit))
                        .withColor(profit >= 0 ? GREEN : RED)));
        // Naming what could not be priced keeps the total honest: a missing lookup is a gap in the
        // number, not a zero-value item, and a chest carrying an unpriceable drop may well be worth
        // opening anyway.
        var unpriced = chest.unpriced();
        if (!unpriced.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (var item : unpriced) {
                names.append(names.isEmpty() ? "" : ", ").append(item.name());
            }
            lines.add(Component.literal("No price for: " + names).withColor(GRAY));
        }
        // What a reroll costs, stated as a price and nothing more. Deliberately not "is a reroll
        // worth it": that needs the floor's loot table and the reroll's odds, neither of which this
        // mod has - and a made-up expectation beside a real chest value would be believed.
        if (ConfigManager.getInstance().get().dungeons.kismetHint) {
            Long feather = sbs.modid.client.dungeons.chest.KismetPrice.cost();
            if (feather != null) {
                lines.add(Component.literal("Reroll costs " + format(feather)
                        + " coins (Kismet Feather)").withColor(GRAY));
            }
        }
        return lines;
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
