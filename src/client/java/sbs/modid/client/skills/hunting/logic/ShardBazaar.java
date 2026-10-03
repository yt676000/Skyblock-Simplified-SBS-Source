/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.skills.hunting.model.ShardId;
import sbs.modid.client.skills.hunting.model.ShardPriceSource;

import java.util.Locale;

/**
 * The two things the missing-shard list does with the Bazaar: price a shard, and open it.
 *
 * <p><b>The id mapping is not identity, and this is the only place that knows it.</b> The game keys
 * a shard {@code ATTRIBUTE_SHARD_<NAME>} while the Bazaar trades all 320 as {@code SHARD_<NAME>}, so
 * {@link #priceOf} translates and nothing upstream carries the market's spelling. A shard with no
 * product row prices as unknown and is drawn as {@code -}, never as zero: a zero sorts first and
 * reads as free, which is the worst possible thing for a list sorted by price to say.
 *
 * <p><b>One deliberate click, one command.</b> {@link #open} runs only from a left click on a row
 * drawn last frame, behind a cooldown so a double click cannot become two commands. Nothing here
 * fires from a hover, a scroll, a screen opening or a recompute, and the player is not sent back to
 * the menu afterwards - returning them would be a second command nobody asked for.
 *
 * <p><b>Why that is allowed at all</b> (the mod-never-plays-the-game rule): it is one command, fired
 * by one deliberate click, that opens a menu the player could have opened by typing. It confers no
 * advantage, contests nothing, restores rather than progresses, is bounded and single-shot, and never
 * retries. It sits inside the same envelope as the essence shop's Bazaar button, which is already
 * accepted, and it has its own config toggle so a player who wants a pure display panel can have one.
 */
public final class ShardBazaar {

    /** Two commands cannot leave closer together than this, whatever the mouse does. */
    private static final long COOLDOWN_MS = 600L;

    private static long lastSentAt;

    private ShardBazaar() {
    }

    /** What one shard costs from the chosen side of the book, or {@code null} when unknown. */
    public static Long unitPrice(String canonicalId, ShardPriceSource source) {
        BazaarPriceCache.BzPrice price = priceOf(canonicalId);
        if (price == null) {
            return null;
        }
        long value = source == ShardPriceSource.BUY_ORDER ? price.sell() : price.buy();
        return value > 0 ? value : null;
    }

    /** Whether this shard's book is wide enough that the quoted price is not what you will pay. */
    public static boolean wideSpread(String canonicalId) {
        return ShardValuation.wideSpread(priceOf(canonicalId));
    }

    /**
     * The book for a shard, translated from our id to the market's.
     *
     * <p><b>The two spellings differ and the translation happens here, once.</b> The game keys a
     * shard {@code ATTRIBUTE_SHARD_<NAME>} and the Bazaar trades it as {@code SHARD_<NAME>}; letting
     * both spellings travel through the feature is what previously produced two ids for one shard
     * and a cross-reference that missed. Everything upstream of this method uses the canonical id
     * only.
     */
    private static BazaarPriceCache.BzPrice priceOf(String canonicalId) {
        String product = ShardId.bazaarId(canonicalId);
        return product == null ? null : BazaarPriceCache.getInstance().priceOf(product);
    }

    /**
     * Opens Hypixel's Bazaar search for this shard.
     *
     * <p>The screen is closed first: the command opens a Hypixel menu, and sending it while a
     * container is still up leaves the client and the server disagreeing about what is open.
     *
     * <p><b>The query is the display name, not the id.</b> {@code /bz} is a search box, and
     * {@code SHARD_FUNGLOOM} finds nothing in it - the same reason {@code RecipeOverlay} and
     * {@code QuestItemActions} both send the lower-cased name.
     *
     * @return whether a command was actually sent (false while the cooldown is running)
     */
    public static boolean open(String displayName) {
        Minecraft minecraft = Minecraft.getInstance();
        if (displayName == null || displayName.isBlank()
                || minecraft.player == null || minecraft.player.connection == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastSentAt < COOLDOWN_MS) {
            return false;
        }
        lastSentAt = now;
        minecraft.setScreenAndShow(null);
        minecraft.player.connection.sendCommand("bz " + displayName.toLowerCase(Locale.ROOT));
        return true;
    }
}
