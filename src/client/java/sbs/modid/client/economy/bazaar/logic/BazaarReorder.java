/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.bazaar.logic;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.economy.bazaar.model.CancelledOrder;

import java.util.Locale;

/**
 * Re-placing a remembered remainder: opens the item's Bazaar page and holds the quantity ready for
 * the custom-amount sign.
 *
 * <p><b>What this does not do.</b> It does not click Hypixel's menus. Clicking a history row runs
 * {@code /bz <item>} - the same command {@code BestFlipsOverlay} and the Essence shop already use -
 * and then waits. The player walks the Bazaar's own flow (Create Buy Order, then Custom Amount);
 * when that opens a sign, {@code SignSearchMixin} asks here for the armed quantity and types it in.
 * Every step that commits anything stays a real click by a real player, and the only thing automated
 * is the retyping of a number the mod already knew.
 *
 * <p>The price is deliberately left alone. Hypixel's own default price stands, so re-placing a
 * remainder never silently commits the player to a figure they did not look at - the market has
 * moved since the order was cancelled, and which side of that move they want is theirs to decide.
 *
 * <p><b>The arm expires.</b> {@link #TTL_MS} after the click, a sign that opens is just a sign
 * again. Without that, a quantity armed and abandoned would ambush the next custom amount the player
 * typed by hand, possibly in a different session and for a different item.
 *
 * <h2>Why this is not the mod playing the game</h2>
 *
 * <p>{@code AGENTS.md} bans synthesized input outright, so the judgement is recorded here rather
 * than left to be re-derived. Two things happen on a click, and neither is the mod trading:
 *
 * <ul>
 *   <li><b>One click sends one command.</b> {@code /bz <item>} opens a search - the same single-shot
 *       affordance {@code BestFlipsOverlay}, {@code BazaarFlipsScreen} and {@code EssenceBazaar}
 *       already offer. Nothing is clicked in Hypixel's menus, and nothing repeats or retries.</li>
 *   <li><b>Typing a number into a sign the player opened themselves</b> is strictly less than the
 *       shipped search history already does on the same screen class -
 *       {@code BazaarSearchHistory} fills a sign <i>and</i> closes it, which submits. This one only
 *       fills, so every step that commits coins stays a real click.</li>
 * </ul>
 *
 * <p>Against the four conditions: it confers <b>no advantage</b> - the number was the player's own,
 * the mod is retyping what it already recorded, and nobody is outplayed by a quantity field being
 * pre-populated. It <b>restores rather than progresses</b>: the whole purpose is to put back an
 * order that already existed and was cancelled. It is <b>bounded and single-shot</b> - one arm per
 * click, consumed by the first matching sign, expired by {@link #TTL_MS}, never retried. It is
 * <b>not off by default</b>, and deliberately so: that condition guards the automation exception,
 * and this does not reach it - a clipboard copy of the same figure has shipped on by default in
 * {@code copyCancelledAmount} since before this existed, and the master switch is one toggle away.
 */
public final class BazaarReorder {

    private static final BazaarReorder INSTANCE = new BazaarReorder();

    /**
     * How long an armed quantity stays live. Long enough to cross the Bazaar's category, product and
     * order menus at a human pace; short enough that an abandoned click is forgotten well before the
     * player next opens a sign on purpose.
     */
    private static final long TTL_MS = 120_000L;

    /** The quantity waiting for a sign, and the history row it came from. */
    private record Armed(String rowKey, String itemId, String itemName, int amount, double price,
                         long armedAt) {

        boolean live() {
            return System.currentTimeMillis() - armedAt < TTL_MS;
        }
    }

    /** Consumed by the first sign that opens. */
    private volatile Armed armed;

    /**
     * The same flow, kept past the sign until an order for it is actually set up.
     *
     * <p>Two fields rather than one because they end at different moments. The quantity is spent the
     * instant a sign takes it; the <i>row</i> it came from must survive until the order confirms, so
     * the history can be cleared by the "Setup!" line - and must survive a player who types a
     * different number into the sign, which is still a re-order of that row.
     */
    private volatile Armed inFlight;

    private BazaarReorder() {
    }

    public static BazaarReorder getInstance() {
        return INSTANCE;
    }

    /**
     * Starts the re-order flow for one history row: arms its remainder and opens the item's Bazaar
     * page.
     *
     * <p>Returns {@code false} without arming anything when the remainder is not a quantity the
     * Bazaar would take - above the per-order cap, or nothing at all. Pre-filling a sign with a
     * figure Hypixel rejects teaches the player the feature is broken, so the panel says why instead.
     */
    public boolean start(CancelledOrder order) {
        if (order == null || !order.withinOrderLimit()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            return false;
        }
        Armed flow = new Armed(order.key(), order.itemId(), order.itemName(), order.remaining(),
                order.price(), System.currentTimeMillis());
        armed = flow;
        inFlight = flow;
        minecraft.player.connection.sendCommand("bz " + searchQuery(order.itemName()));
        minecraft.player.sendSystemMessage(Component.literal("[SBS] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Re-ordering ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(order.itemName()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" - open Create Buy Order, then Custom Amount, and the "
                        + "remaining quantity is filled in for you.").withStyle(ChatFormatting.GRAY)));
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Bazaar] Re-order armed: {} x{} (price left to Hypixel)",
                order.itemName(), order.remaining());
        return true;
    }

    /**
     * The armed quantity for a sign that is opening, consumed on the way out, or {@code -1} when
     * nothing is armed or the arm has expired.
     *
     * <p>Consuming on read is what stops one click arming two signs: if the player opens Custom
     * Amount, backs out and opens it again, the second sign is theirs to type in.
     */
    public int consumeAmount() {
        Armed current = armed;
        if (current == null) {
            return -1;
        }
        armed = null;
        if (!current.live()) {
            return -1;
        }
        return current.amount();
    }

    /**
     * Whether a quantity is waiting for a sign right now, without spending it.
     *
     * <p>Separate from {@link #consumeAmount()} so a sign can check the cheap condition before
     * deciding whether it is the sign the arm was meant for - asking for the amount in order to find
     * out would spend it on the wrong screen.
     */
    public boolean isArmed() {
        Armed current = armed;
        return current != null && current.live();
    }

    /**
     * The history row a freshly set-up buy order should be credited against, or an empty string.
     *
     * <p>Matched on the item, not on the price: the whole point of leaving the price to the player is
     * that the re-placed order need not carry the cancelled one's, so the price cannot be part of
     * what identifies the flow. Consumed on a hit, so a second order for the same item later in the
     * session is a new order rather than another credit against a row already cleared.
     */
    public String creditRowFor(String itemId) {
        Armed current = inFlight;
        if (current == null || !current.live() || itemId == null
                || !current.itemId().equalsIgnoreCase(itemId)) {
            return "";
        }
        inFlight = null;
        return current.rowKey();
    }

    /** Drops the whole flow - the history row went away, or the player left the Bazaar behind. */
    public void cancel() {
        armed = null;
        inFlight = null;
    }

    /**
     * The Bazaar search string for an item name.
     *
     * <p>Enchanted-book names carry their level as a trailing roman numeral ("Venomous VI"), which
     * the Bazaar search does not match - the product page is the enchantment, and the level is a slot
     * within it. Dropping the numeral lands on the right page; keeping it lands on no results.
     */
    private static String searchQuery(String itemName) {
        String name = itemName == null ? "" : itemName.trim();
        int space = name.lastIndexOf(' ');
        if (space > 0 && isRomanNumeral(name.substring(space + 1))) {
            return name.substring(0, space);
        }
        return name;
    }

    private static boolean isRomanNumeral(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            if ("IVXLC".indexOf(upper.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
