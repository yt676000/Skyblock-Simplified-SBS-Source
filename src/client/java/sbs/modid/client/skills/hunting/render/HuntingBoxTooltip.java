/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.render;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.Rarity;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.skills.hunting.logic.HuntingBoxScanner;
import sbs.modid.client.skills.hunting.logic.ShardResolver;
import sbs.modid.client.skills.hunting.logic.ShardValuation;
import sbs.modid.client.skills.hunting.model.HuntingBoxShard;
import sbs.modid.client.skills.hunting.model.ShardContext;
import sbs.modid.client.skills.hunting.model.ShardRarity;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-shard breakdown, appended to a shard's own tooltip inside the Hunting Box.
 *
 * <p>Three rows, in the order that keeps the reader honest: what is still needed to max an attribute,
 * what is surplus beyond that, and only then what the surplus is worth in coins. Putting the coin
 * figure last is the whole point - a player reading top to bottom meets the reason to keep the shards
 * before the reason to sell them.
 *
 * <p>Called from {@code PriceTooltipMixin}'s single {@code RETURN} handler rather than taking an
 * injection of its own: two cancellable {@code @At("RETURN")} injections on {@code getTooltipLines}
 * do not both run, and whichever set the return value first wins.
 */
public final class HuntingBoxTooltip {

    private static final int HEADER = 0x3FB4FF;
    private static final int NEEDED = 0xFFAA55;
    private static final int SURPLUS = 0x55FF55;
    private static final int WARN = 0xFF5555;
    private static final int MUTED = 0xAAAAAA;

    private HuntingBoxTooltip() {
    }

    /**
     * {@code lines} with the shard rows appended, or the same list untouched when this stack is not
     * a shard in an open Hunting Box.
     */
    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        var cfg = ConfigManager.getInstance().get().hunting;
        if (!cfg.boxValue || !cfg.boxTooltip || lines == null || stack == null || stack.isEmpty()) {
            return lines;
        }
        if (!boxOpen()) {
            return lines;   // a shard looked at anywhere else keeps its own tooltip
        }
        // The same resolver the scanner uses, in the same context, and that is the point: the box
        // names its entries after the shard while the Attribute Menu names them after the attribute,
        // so a tooltip that resolved the stack its own way would disagree with the total beside it.
        ShardResolver.Resolution resolution =
                ShardResolver.resolve(stack, ShardContext.HUNTING_BOX);
        if (!resolution.resolved()) {
            return lines;
        }
        String id = resolution.key();
        // "Owned: N Shards" first: a vanilla stack stops at 64, so for a box holding thousands that
        // line is the only place the real total can be.
        int owned = HuntingBoxScanner.ownedIn(ItemPriceKey.lore(stack));
        int amount = owned >= 0 ? owned : Math.max(0, stack.getCount());

        // Valued from the hovered stack itself, not from the stored snapshot: the box in front of you
        // is newer than anything on disk, and the two disagreeing on a tooltip would be indefensible.
        ShardRarity rarity = ShardRarity.from(Rarity.detect(stack));
        HuntingBoxShard shard = new HuntingBoxShard(id, resolution.displayName(), rarity, amount,
                owned >= 0);
        ShardValuation.BoxValue value = ShardValuation.value(List.of(shard));
        if (value.rows().isEmpty()) {
            return lines;
        }
        ShardValuation.Valued row = value.rows().get(0);

        List<Component> out = new ArrayList<>(lines);
        out.add(Component.empty());
        out.add(text("Hunting Box", HEADER));

        if (row.needed() > 0) {
            out.add(text(" Still needed: " + row.needed() + " to max one attribute", NEEDED));
        } else if (row.needed() == 0) {
            out.add(text(" Enough for one attribute", SURPLUS));
        } else {
            out.add(text(" Needed: unknown rarity, so keep them", MUTED));
        }

        if (row.surplus() > 0) {
            out.add(text(" Surplus: " + row.surplus() + " beyond a full attribute", SURPLUS));
        } else {
            out.add(text(" Surplus: none - selling costs you attribute progress", MUTED));
        }

        if (row.priced()) {
            out.add(text(" Sell (after tax): " + NumberDisplay.format(row.taxedUnit()) + " each"
                    + (row.surplus() > 0
                    ? ", " + NumberDisplay.format(row.surplusValue()) + " for the surplus" : ""),
                    MUTED));
        } else {
            out.add(text(" Sell: the Bazaar has no price for this shard", MUTED));
        }

        if (row.wideSpread()) {
            out.add(text(" Thin book: buying costs far more than selling pays here", WARN));
        }
        if (row.thinForStack()) {
            out.add(text(" That is a big share of this shard's weekly volume", WARN));
        }
        out.add(text(" Fusion value not counted - recipes are not published", MUTED));
        out.add(text(" Syphon counts are " + ShardRarity.COUNT_CERTAINTY.displayName(), MUTED));
        return out;
    }

    /** Whether the screen in front of the player is the Hunting Box. */
    private static boolean boxOpen() {
        var screen = sbs.modid.client.core.api.GuiStateManager.getInstance().getCurrentScreen();
        return screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container
                && HuntingBoxScanner.isBox(container);
    }

    private static Component text(String content, int color) {
        return Component.literal(content).setStyle(
                net.minecraft.network.chat.Style.EMPTY.withColor(TextColor.fromRgb(color)));
    }
}
