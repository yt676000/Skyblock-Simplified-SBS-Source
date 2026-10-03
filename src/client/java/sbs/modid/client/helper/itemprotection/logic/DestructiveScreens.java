/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.logic;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.itemprotection.model.ProtectionCategory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <b>The one place a Hypixel menu is classified.</b> Every title pattern and every lore keyword this
 * feature matches on lives here, because they are the part guaranteed to rot: Hypixel rewords its
 * menus and nothing tells us when it has. One file means one place to fix, and one place to read to
 * find out what we currently believe.
 *
 * <h2>Provenance, per block</h2>
 * Each group below says where its strings came from. Two kinds exist and they are not
 * interchangeable:
 * <ul>
 *   <li><b>VERIFIED (in tree)</b> - the same strings another SBS feature already matches on and has
 *       been used against the live game. Reused deliberately rather than re-typed, so a correction
 *       lands in both places at once.</li>
 *   <li><b>UNVERIFIED</b> - derived from the feature request, not from a menu anyone has opened with
 *       this build. These are hypotheses. They are why the module ships off by default and why an
 *       unrecognised screen asks rather than allows.</li>
 * </ul>
 *
 * <h2>Three answers, and the third is the important one</h2>
 * {@link #classify} names a destructive category; {@link #isSafe} names a menu an item may enter
 * freely; anything matching neither is {@link ProtectionCategory#UNKNOWN}, which confirms. A wrong
 * or missing pattern therefore costs one extra click, never an item - which is the only failure
 * mode worth designing for here.
 *
 * <h2>Structure first, title second</h2>
 * <b>A title is never the sole signal any more.</b> A Hypixel NPC shop is titled with the NPC's own
 * name - "Adventurer", "Bea", "Pierre" - so no whitelist of titles can ever match one, and the shop
 * that sold a protected item was reaching the confirm-only unknown fallback rather than the sell
 * category the player had switched on. {@link #sellCapable} answers the same question from the
 * menu's <i>contents</i> instead: a shop is a screen whose container slots carry Hypixel's own price
 * and trade lore, or a dedicated sell control. That test needs no maintenance and works on the first
 * day of an NPC nobody has met.
 *
 * <p>{@link #classify(AbstractContainerMenu, String)} is the combined entry point and the one the
 * guard calls: structure decides, the title only adds what structure cannot see (a salvage menu and
 * a sack hold no priced offers). The structural markers are themselves UNVERIFIED - see
 * {@link #COST_LINE} - and deliberately do not carry the feature's safety on their own: a screen
 * they miss is still {@link ProtectionCategory#UNKNOWN}, which still asks.
 *
 * <h2>And the sale where nothing is clicked</h2>
 * Classifying the screen answers "what would this menu do to an item I put in it", which presumes
 * an item is being put in. <b>A Bazaar sale puts nothing in.</b> The player presses "Sell Instantly"
 * or "Create Sell Offer" and the server takes the stock out of the inventory; no protected stack
 * passes through the click, so the whole screen classification above is consulted on a path the
 * sale never takes. {@link #consumingControl} and {@link #isSaleConfirmation} are the two tests
 * that can see such a click, and they are the reason the Bazaar - whose titles are the best
 * verified in this file - was nonetheless completely unguarded.
 */
public final class DestructiveScreens {

    /** Strips a "(1/3)" page counter, so a paged menu matches the same as its first page. */
    private static final Pattern PAGE_COUNTER = Pattern.compile("\\(\\s*\\d+\\s*/\\s*\\d+\\s*\\)");

    private DestructiveScreens() {
    }

    /**
     * Normalises a raw screen title for matching: formatting codes stripped, page counter removed,
     * whitespace collapsed, lower-cased. Matching happens on colour-stripped text only, never on a
     * formatted string.
     */
    public static String normalize(String rawTitle) {
        if (rawTitle == null || rawTitle.isEmpty()) {
            return "";
        }
        String plain = PlainText.strip(rawTitle);
        plain = PAGE_COUNTER.matcher(plain).replaceAll(" ");
        return plain.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    // ==================================================================
    // SAFE - menus an item may enter freely
    // ==================================================================

    /**
     * VERIFIED (in tree). Storage menus, taken from the same strings
     * {@code helper/storage/StorageIndex#classify} matches on. An item put here is retrievable, so
     * none of them is destructive.
     *
     * <p>Note "sack" is deliberately <b>absent</b>: {@code StorageIndex} counts sacks as storage
     * because for its purpose they are, while for this feature they consume the stack's identity.
     * See {@link #SACK_TITLES}.
     */
    private static final String[] SAFE_STORAGE = {
            "ender chest", "backpack", "personal vault", "storage",
    };

    /**
     * VERIFIED (in tree). Wardrobe / loadouts, from
     * {@code helper/loadouts/LoadoutsOverlay#isLoadoutsMenu}; accessory bag from
     * {@code helper/inventory/logic/AccessoryIndex}. Both move items around without consuming them.
     */
    private static final String[] SAFE_EQUIPMENT = {
            "loadout", "wardrobe", "accessory bag",
    };

    /**
     * Whether a menu is one an item may enter with no question asked.
     *
     * <p>Deliberately short. Everything not listed falls through to
     * {@link ProtectionCategory#UNKNOWN}, and the way to quiet a noisy menu is to add its
     * <i>verified</i> title here - never to widen the fallback.
     */
    public static boolean isSafe(String rawTitle) {
        String title = normalize(rawTitle);
        if (title.isEmpty()) {
            return true;   // no menu title at all: the player's own inventory screen
        }
        // The vanilla inventory screen is identified by its menu type in ItemProtection, not here;
        // this is only the text fallback. "Crafting" is deliberately NOT safe - a Hypixel craft menu
        // consumes what it is given, and the player inventory's own 2x2 grid never reaches this test.
        if (title.equals("inventory")) {
            return true;
        }
        return containsAny(title, SAFE_STORAGE) || containsAny(title, SAFE_EQUIPMENT);
    }

    // ==================================================================
    // DESTRUCTIVE - one block per category, each with its provenance
    // ==================================================================

    /**
     * VERIFIED (in tree). The Bazaar, matched exactly as
     * {@code economy/bazaar/logic/BazaarOrderTracker#isBazaarGui} matches it: the category and
     * search menus ("Bazaar -> ..."), the product pages ("-> Enchanted Diamond"), the order menus,
     * and the confirmation flows. "Sell Instantly" lives inside a product page, so the page's own
     * title is what catches it.
     */
    private static final String[] SELL_BAZAAR_CONTAINS = {"bazaar"};
    private static final String[] SELL_BAZAAR_STARTS = {"➜", "confirm instant"};
    private static final String[] SELL_BAZAAR_EQUALS = {
            "confirm buy order", "confirm sell offer", "order options",
    };

    /**
     * UNVERIFIED. Sell confirmations outside the Bazaar. An NPC shop's title is the NPC's own name
     * and cannot be matched here at all - which is why {@link #sellCapable} exists and why these
     * strings are now only a hint on top of it, never the thing the sell category rests on.
     */
    private static final String[] SELL_TITLES = {"confirm sell", "sell item", "shop"};

    /**
     * UNVERIFIED. Reforge-anvil salvage, dungeon salvage and the bulk variant. One word covers all
     * three if Hypixel keeps titling them with it.
     */
    private static final String[] SALVAGE_TITLES = {"salvage"};

    /**
     * UNVERIFIED. Sacks and the Sack of Sacks. Note the deliberate divergence from
     * {@code StorageIndex}, which treats a sack as storage: an item absorbed by a sack loses its
     * identity, which is exactly what this feature exists to prevent.
     */
    private static final String[] SACK_TITLES = {"sack"};

    /** UNVERIFIED. The Personal Deletor - distinctive enough that one word is safe. */
    private static final String[] DELETOR_TITLES = {"deletor"};

    /**
     * VERIFIED (in tree) for "auction" - the same test
     * {@code economy/auctions/ui/AhFlipsOverlay} uses. The narrower create/confirm strings are
     * UNVERIFIED, and do not need to be right: the category is the same either way and it only ever
     * confirms.
     */
    private static final String[] AUCTION_TITLES = {
            "auction", "create auction", "create bin", "confirm auction",
    };

    /**
     * "forge" is VERIFIED (in tree) - {@code economy/forge/ui/ForgeFlipsOverlay} matches it.
     * Everything else here is UNVERIFIED: Kat's pet upgrade menu, attribute fusion, the anvil, craft
     * menus, and museum donation (irreversible, so it belongs with the consuming menus even though
     * the request did not name it).
     */
    private static final String[] CONSUME_TITLES = {
            "forge", "kat", "attribute", "fusion", "anvil", "craft", "museum",
    };

    /**
     * The destructive category of a menu, or {@code null} when this file recognises it as nothing in
     * particular. A {@code null} is not "safe" - ask {@link #isSafe} for that.
     *
     * <p>Order matters where a title could match two blocks: salvage and sacks are tested before the
     * broad sell and consume words, so "Salvage Item" is a salvage rather than whatever else its
     * wording happens to contain.
     */
    public static ProtectionCategory classify(String rawTitle) {
        String title = normalize(rawTitle);
        if (title.isEmpty()) {
            return null;
        }
        if (containsAny(title, SALVAGE_TITLES)) {
            return ProtectionCategory.SALVAGE;
        }
        if (containsAny(title, SACK_TITLES)) {
            return ProtectionCategory.SACK;
        }
        if (containsAny(title, DELETOR_TITLES)) {
            // Not a category of its own: what a Deletor does to an item is what a sack does, only
            // permanently, and one toggle for "menus that swallow items" is one the player can find.
            return ProtectionCategory.SACK;
        }
        if (containsAny(title, AUCTION_TITLES)) {
            return ProtectionCategory.AUCTION;
        }
        if (isBazaar(title) || containsAny(title, SELL_TITLES)) {
            return ProtectionCategory.SELL;
        }
        if (containsAny(title, CONSUME_TITLES)) {
            return ProtectionCategory.CONSUME;
        }
        return null;
    }

    // ==================================================================
    // STRUCTURE - what the menu contains, which no rewording can hide
    // ==================================================================

    /**
     * UNVERIFIED. Hypixel's own price line on a shop offer: "Cost", then "1,234 Coins". Written over
     * two lines and sometimes inlined, which is why this matches the number and unit rather than the
     * word "Cost".
     *
     * <p>Shaped after {@code economy/bitsshop/BitsShop#bitsCost}, which is the in-tree precedent for
     * detecting a Hypixel shop page by content: that feature matches "{@code … Bits}" for exactly
     * this reason - the sub-menu titles are unlistable and the price on the item is the one thing
     * that is always there and always means what it says.
     */
    private static final Pattern COST_LINE =
            Pattern.compile("[\\d,.]+\\s*coins?\\b", Pattern.CASE_INSENSITIVE);

    /**
     * UNVERIFIED. The action line a shop offer carries under its price. Any one of these on a
     * container item says the slot is a trade rather than a display.
     */
    private static final String[] TRADE_LINES = {
            "click to trade", "click to buy", "click to purchase", "buy it", "buy for",
    };

    /**
     * UNVERIFIED. A dedicated sell control - the slot a shop reserves for "put an item here" or
     * "click an item in your inventory to sell it". One of these is on its own enough to call the
     * screen sell-capable, because a menu with a sell button is a menu that sells.
     *
     * <p>Separate from {@link #BULK_SELL}: that names a button which acts on items the player is not
     * clicking, this names a screen that will take one they are.
     */
    private static final String[] SELL_CONTROLS = {
            "sell item", "sell to", "sell price", "click to sell",
    };

    /** How many priced offers a screen needs before its contents alone make it a shop. */
    private static final int OFFER_THRESHOLD = 2;

    /**
     * Whether this menu's <b>contents</b> say it can sell - independent of what it is titled.
     *
     * <p>This is the answer to the bug that produced this method: an NPC shop is titled with the
     * NPC's name, and a title table cannot ever be made to match one. Two shapes count, both read
     * off the container half of the menu only:
     * <ul>
     *   <li><b>Priced offers.</b> {@link #OFFER_THRESHOLD} or more container items carrying both a
     *       coin price and a trade line. Two, not one, because a single item mentioning coins is an
     *       ordinary thing for a menu to do and two priced offers side by side is a shop.</li>
     *   <li><b>A sell control.</b> One container item whose name or lore offers to sell, which is
     *       enough on its own.</li>
     * </ul>
     *
     * <p>Player-inventory slots are skipped: the player's own items carry lore of their own, and a
     * Hyperion's "Costs 200 Coins" ability line must not make every screen a shop.
     *
     * @param menu the open menu, or {@code null} for no menu at all
     */
    public static boolean sellCapable(AbstractContainerMenu menu) {
        if (menu == null) {
            return false;
        }
        int offers = 0;
        for (Slot slot : menu.slots) {
            if (slot == null || slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (marksSellControl(searchableText(stack))) {
                return true;
            }
            if (marksPricedOffer(searchableText(stack)) && ++offers >= OFFER_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * The same verdict as {@link #sellCapable}, from the container items' text alone.
     *
     * <p>Split out because it is the half that can be tested: no test in this tree builds a live
     * {@code ItemStack}, and the part of this file worth pinning is which wording counts as a shop -
     * not the slot walk around it.
     *
     * @param containerItemTexts each container item's name and lore, one entry per item, exactly as
     *                           {@link #searchableText} would render it
     */
    public static boolean sellCapableText(List<String> containerItemTexts) {
        if (containerItemTexts == null) {
            return false;
        }
        int offers = 0;
        for (String text : containerItemTexts) {
            String haystack = text == null ? "" : text.toLowerCase(Locale.ROOT);
            if (marksSellControl(haystack)) {
                return true;
            }
            if (marksPricedOffer(haystack) && ++offers >= OFFER_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** Whether one item offers to sell - enough on its own to call the screen sell-capable. */
    private static boolean marksSellControl(String text) {
        return containsAny(text, SELL_CONTROLS);
    }

    /**
     * Whether one item is a priced offer: a coin price <b>and</b> a trade line. Both are required
     * because either alone is something an ordinary menu does - a quest reward states coins, a
     * pagination arrow says "click" - and it is the pair that means a transaction.
     */
    private static boolean marksPricedOffer(String text) {
        return containsAny(text, TRADE_LINES) && COST_LINE.matcher(text).find();
    }

    /**
     * The destructive category of the open menu, deciding by contents first and by title second, or
     * {@code null} when neither recognises it.
     *
     * <p><b>Why this order.</b> Structure cannot be reworded and a title can, so where the two
     * disagree the contents win - a menu full of priced offers is a shop whatever it calls itself.
     * The title still has work to do, though, and that is why it is not simply dropped: a salvage
     * menu, a sack and a forge hold no priced offers at all, so nothing in their contents marks them
     * and only their title does. The one case the title must not lose is the Bazaar, whose product
     * pages are also full of priced offers - "Bazaar" reads as SELL either way, so the two agree.
     *
     * @param menu     the open menu; {@code null} falls back to the title alone
     * @param rawTitle the screen's raw title, formatting included
     */
    public static ProtectionCategory classify(AbstractContainerMenu menu, String rawTitle) {
        ProtectionCategory byTitle = classify(rawTitle);
        if (byTitle != null) {
            return byTitle;
        }
        // A verified safe title outranks the guessed lore markers, so a backpack holding an item
        // whose own lore mentions selling stays a backpack. The empty-title guard is the other half
        // of that: no title means the screen is not tracked yet rather than that it is harmless, and
        // skipping the structural test there would blind us exactly where we can see least.
        if (!normalize(rawTitle).isEmpty() && isSafe(rawTitle)) {
            return null;
        }
        return sellCapable(menu) ? ProtectionCategory.SELL : null;
    }

    /** An item's name and lore as one lower-cased, formatting-stripped haystack. */
    private static String searchableText(ItemStack stack) {
        StringBuilder text = new StringBuilder(
                PlainText.strip(stack.getHoverName().getString()).toLowerCase(Locale.ROOT));
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (var line : lore.lines()) {
                text.append('\n').append(PlainText.strip(line.getString()).toLowerCase(Locale.ROOT));
            }
        }
        return text.toString();
    }

    private static boolean isBazaar(String title) {
        if (containsAny(title, SELL_BAZAAR_CONTAINS)) {
            return true;
        }
        for (String equal : SELL_BAZAAR_EQUALS) {
            if (title.equals(equal)) {
                return true;
            }
        }
        for (String start : SELL_BAZAAR_STARTS) {
            if (title.startsWith(start)) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // Inventory-consuming controls - the click that moves nothing
    // ==================================================================

    /**
     * UNVERIFIED. Name / lore keywords of a control that consumes items the player is <b>not</b>
     * clicking - "Sell All", "Sell Instantly", "Create Sell Offer", "Salvage All".
     *
     * <h3>Why this list is the whole Bazaar story</h3>
     * A Bazaar sale never moves the stack through the click. The player presses a button, the
     * server takes the items out of the inventory, and no protected stack passes through any slot -
     * so the movement rule in {@code ItemProtection} has nothing to look at, and the Bazaar's own
     * (verified) titles were being classified correctly on a path a sale never takes. This list and
     * {@link #isSaleConfirmation} are the only two things that can see such a click at all.
     *
     * <p>"sell offer" alone is deliberately <b>absent</b>: a row in the Bazaar orders menu is named
     * "Sell Offer" and clicking it claims or cancels an order, which returns items rather than
     * taking them. Only the narrower "create sell offer" is matched.
     *
     * <p>Deliberately confirm-only, wherever the mode is set: the client cannot know what such a
     * control would actually consume, and a false positive that refuses outright would make a
     * perfectly ordinary "sell all" button unusable. Asking is proportionate to the confidence.
     */
    private static final String[] BULK_SELL = {
            "sell all", "sell inventory", "sell everything",
            "sell instantly", "instantly sell", "create sell offer",
    };
    private static final String[] BULK_SALVAGE = {"salvage all", "bulk salvage", "salvage everything"};

    /**
     * UNVERIFIED. Names of a control that backs out of a menu instead of acting.
     *
     * <p>Matched on the control's <b>name only</b>, never its lore, and that restriction is the
     * point: a sell button whose lore happens to say "this cannot be cancelled" must not be waved
     * through by the word "cancel". Excluding these keeps {@link #isSaleConfirmation} from asking
     * about the one click on a confirmation screen that cannot possibly sell anything.
     */
    private static final String[] BACK_CONTROLS = {"cancel", "go back", "close"};

    /**
     * The consuming action a menu control advertises, or {@code null} when it is an ordinary slot.
     *
     * @param menuStack the stack in the clicked <b>menu</b> slot (never a player-inventory slot)
     */
    public static ProtectionCategory consumingControl(ItemStack menuStack) {
        if (menuStack == null || menuStack.isEmpty()) {
            return null;
        }
        return consumingControlText(searchableText(menuStack));
    }

    /**
     * The same verdict as {@link #consumingControl}, from the control's text alone.
     *
     * <p>Split out for the reason {@link #sellCapableText} was: no test in this tree builds a live
     * {@code ItemStack}, and the half worth pinning is which wording counts as a consuming control.
     *
     * @param controlText the control's name and lore exactly as {@link #searchableText} renders it
     */
    public static ProtectionCategory consumingControlText(String controlText) {
        String haystack = controlText == null ? "" : controlText.toLowerCase(Locale.ROOT);
        if (containsAny(haystack, BULK_SALVAGE)) {
            return ProtectionCategory.SALVAGE;
        }
        if (containsAny(haystack, BULK_SELL)) {
            return ProtectionCategory.SELL;
        }
        return null;
    }

    /**
     * Whether a control backs out of the menu rather than acting on it.
     *
     * @param menuStack the stack in the clicked menu slot
     */
    public static boolean isBackControl(ItemStack menuStack) {
        if (menuStack == null || menuStack.isEmpty()) {
            return false;
        }
        return isBackControlName(menuStack.getHoverName().getString());
    }

    /** {@link #isBackControl} from the control's name alone, formatting included. */
    public static boolean isBackControlName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return containsAny(PlainText.strip(name).toLowerCase(Locale.ROOT), BACK_CONTROLS);
    }

    /**
     * VERIFIED (in tree). The screens where the sale itself is one button press, taken from the
     * titles {@code economy/bazaar/logic/BazaarOrderTracker#isBazaarGui} already matches: "Confirm
     * Instant Sell" and "Confirm Sell Offer".
     *
     * <p><b>The buy side is deliberately excluded.</b> "Confirm Instant Buy" and "Confirm Buy Order"
     * spend coins, not items, and asking about them would refuse a click that risks nothing.
     *
     * <p>This is the backstop under {@link #BULK_SELL}, and the split matters: the button wordings
     * up there are guesses that can rot, while these titles are strings this tree already matches
     * against the live game. If Hypixel renames "Sell Instantly" tomorrow, the sale is still caught
     * one screen later.
     */
    private static final String[] SELL_CONFIRMATION_TITLES = {"confirm instant sell", "confirm sell"};

    /**
     * Whether this screen is a sale confirmation - one where pressing any control that is not a way
     * out completes the sale.
     */
    public static boolean isSaleConfirmation(String rawTitle) {
        return containsAny(normalize(rawTitle), SELL_CONFIRMATION_TITLES);
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
