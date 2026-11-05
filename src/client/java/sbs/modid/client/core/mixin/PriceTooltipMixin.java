/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

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

import java.util.ArrayList;
import java.util.List;

/**
 * Appends price lines to the very bottom of an item's tooltip for the Item Overlay module: "Lowest
 * BIN" (auctions) and/or "Lowest Bazaar Price" (bazaar), each independently toggleable.
 *
 * <p>Hooks {@code ItemStack#getTooltipLines} – the single choke point every tooltip goes through, so
 * the lines always sit below enchantments and existing lore. The price lines themselves - matching,
 * the "Not Found" rule, the per-stack cache - live in
 * {@link sbs.modid.client.economy.prices.PriceTooltipLines}.
 */
@Mixin(ItemStack.class)
public abstract class PriceTooltipMixin {

    // Vanilla formatting colors: §6 gold, §a green, §c red, §7 gray.
    private static final int GOLD = 0xFFAA00;
    private static final int GREEN = 0x55FF55;
    private static final int RED = 0xFF5555;
    private static final int GRAY = 0xAAAAAA;

    @Inject(method = "getTooltipLines", at = @At("RETURN"), cancellable = true)
    private void skyblockSimplified$appendPrices(Item.TooltipContext context, Player player, TooltipFlag flag,
                                                 CallbackInfoReturnable<List<Component>> cir) {
        try (sbs.modid.client.core.perf.Perf.Section perf = sbs.modid.client.core.perf.Perf.hud("tooltip")) {
            skyblockSimplified$decorate(cir);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private void skyblockSimplified$decorate(CallbackInfoReturnable<List<Component>> cir) {
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

        // Seymour Colours: hex, best colour match and tags, sharing this handler for the same reason.
        // Self-gating: nothing unless the item is a Seymour piece (or dyed leather, when enabled).
        List<Component> withSeymour = sbs.modid.client.helper.seymour.render.SeymourTooltip.decorate(
                current, (ItemStack) (Object) this);
        if (withSeymour != current) {
            current = withSeymour;
            cir.setReturnValue(current);
        }

        // Dungeon drop quality, sharing this handler for the same reason. Self-gating: nothing
        // unless the item carries a quality roll. Cached per stack like the lines around it.
        List<Component> withQuality = sbs.modid.client.helper.stacktips.DungeonQualityTooltip.decorate(
                current, (ItemStack) (Object) this);
        if (withQuality != current) {
            current = withQuality;
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

        // Counter enchant XP (Champion, Toxophilite), sharing this handler for the same reason.
        // Self-gating: nothing unless the option is on and the item carries a counter enchant.
        List<Component> withCounter = sbs.modid.client.helper.enchants.counter.CounterEnchantTooltip.decorate(
                current, (ItemStack) (Object) this);
        if (withCounter != current) {
            current = withCounter;
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

        // Est. Value, LBIN / Bazaar, the Shift hint and the crafting margin: cached per stack and
        // price version, so a hovered item builds them once rather than every frame.
        List<Component> withPrices = sbs.modid.client.economy.prices.PriceTooltipLines.decorate(
                current, (ItemStack) (Object) this);
        if (withPrices != current) {
            cir.setReturnValue(withPrices);
        }
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

    /** "1,500,000", or "1.5M" when the player has asked for shortened numbers. */
    private static String format(long value) {
        return sbs.modid.client.core.util.NumberDisplay.format(value);
    }
}
