/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.render;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects "claimable" Bazaar slots, which {@link BazaarClaimableDecorator} frames with the shared
 * {@link sbs.modid.client.helper.visual.render.ChromaBorder} – overriding the ordinary "Best Offer
 * Matched / outdated" overlay once an order is ready to collect.
 *
 * <p>Detection reads the item's {@link DataComponents#LORE} component directly (the exact tooltip
 * lines Hypixel sends) and looks for the tell-tale <i>"Click to claim!"</i> line, instead of building
 * the full tooltip every frame.
 *
 * <p><b>And then caches the answer per stack</b>, because "cheap" was doing a lot of work in that
 * sentence: {@link #detect} is asked about every slot of the open menu on every frame, and each ask
 * built a String out of every lore line and ran a regex over it - several hundred of each per frame
 * on a full Bazaar page. The cache is keyed by identity ({@link ItemStack} inherits {@code equals},
 * and the server hands a slot a <i>new</i> stack whenever its contents change, so an entry cannot go
 * stale), and is dropped whole past {@link #CACHE_LIMIT} because menus turn their stacks over
 * constantly and none of it is worth keeping.
 *
 * <p>The border colour flows smoothly over time from the top-left corner to the bottom-right corner:
 * hue is a function of a point's diagonal position minus a time phase, so the wave visibly travels
 * down-right. Uses only {@code net.minecraft.*} draw primitives (per-pixel {@code fill}).
 */
public final class BazaarChroma {

    /**
     * The claim state of a Bazaar order slot, derived from its lore:
     * <ul>
     *   <li>{@link #NONE} – nothing to claim (normal status checker only, no chroma).</li>
     *   <li>{@link #PARTIAL} – claimable but not fully done (status checker + chroma).</li>
     *   <li>{@link #FULL} – claimable and the "Filled:" line reads 100% (chroma only, no status).</li>
     * </ul>
     */
    public enum ClaimState {
        NONE,
        PARTIAL,
        FULL;

        /** Whether the animated chroma border should be drawn for this state. */
        public boolean showsChroma() {
            return this != NONE;
        }

        /** Whether the ordinary "Best Offer Matched / outdated" status overlay should still be drawn. */
        public boolean showsStatus() {
            return this != FULL;
        }
    }

    /** How many stacks the detection cache holds before it is dropped whole. */
    private static final int CACHE_LIMIT = 512;

    /** Per-stack detection results. See the class note for why identity keying is safe here. */
    private static final Map<ItemStack, ClaimState> CACHE = new ConcurrentHashMap<>();

    /**
     * Lore markers. EVERY claimable order – partially or fully filled – shows "Click to claim!"
     * plus a "You have ? items/coins to claim!" total, so the markers alone cannot tell the two
     * apart; only the "Filled: 60/160 (37.5%)!" line does.
     */
    private static final String CLAIM_MARKER = "Click to claim!";
    private static final String CLAIM_ITEMS_MARKER = "items to claim!";
    private static final String CLAIM_COINS_MARKER = "coins to claim!";

    /** The "Filled: 60/160 (37.5%)!" lore line; group 1 is the fill percentage. */
    private static final Pattern FILLED_LINE = Pattern.compile("Filled: \\S+ \\(?([0-9.]+)%\\)?!?");

    private BazaarChroma() {
    }

    /** Classifies a slot's stack into a {@link ClaimState} by scanning its lore lines. */
    public static ClaimState detect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ClaimState.NONE;
        }
        ClaimState cached = CACHE.get(stack);
        if (cached != null) {
            return cached;
        }
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        ClaimState state = read(stack);
        CACHE.put(stack, state);
        return state;
    }

    /** The actual lore read, run once per stack. */
    private static ClaimState read(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return ClaimState.NONE;
        }
        boolean claimable = false;
        double filledPct = -1.0;
        for (Component line : lore.lines()) {
            String text = line.getString();
            if (text.contains(CLAIM_MARKER) || text.contains(CLAIM_ITEMS_MARKER)
                    || text.contains(CLAIM_COINS_MARKER)) {
                claimable = true;
                continue;
            }
            Matcher m = FILLED_LINE.matcher(text);
            if (m.find()) {
                try {
                    filledPct = Double.parseDouble(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (!claimable) {
            return ClaimState.NONE;
        }
        // FULL only at a real 100% – misreading a partial fill as FULL would silently switch
        // off the status tracking, so an unreadable "Filled:" line stays PARTIAL.
        return filledPct >= 99.95 ? ClaimState.FULL : ClaimState.PARTIAL;
    }
}
