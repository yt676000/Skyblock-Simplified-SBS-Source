/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.prices;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.economy.pricehistory.logic.PriceLookup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place a stack is turned into <b>the keys a market is asked about, and how many of it there
 * are</b>. Every value display in the mod goes through here, so a stack that prices in one screen
 * prices in all of them.
 *
 * <p><b>Why it exists.</b> Three screens asked the same question three ways - the Hunting Box read
 * {@code ExtraAttributes.id} and nothing else, the Attribute Menu read the id and then a learned
 * name catalogue, and the tooltip read {@link SkyblockItem#priceLookupCandidates}. Any item whose id
 * one of those paths could not produce was priced by some of them and not by the others, and there
 * was no single place to fix that. There is now.
 *
 * <p><b>The chain is: id, then name, then nothing.</b> {@code ExtraAttributes.id} is the answer
 * whenever it exists. A menu entry that is a display icon rather than the item itself carries no id
 * at all, so the formatting-stripped display name normalised to id convention is tried next. An item
 * neither answers for resolves to <b>no key</b> - never to zero, and never to the vanilla base item,
 * which would price a custom item as its ingredient.
 *
 * <p><b>Attribute shards are the exception, and they are why {@link #shardId} exists separately.</b>
 * All 320 of them carry one id - {@code ATTRIBUTE_SHARD} - so for those the id is a statement of the
 * item's <i>type</i> and the display name is the only thing that says <i>which</i> shard. An
 * id-first resolver answers the same wrong thing for every one of them; see {@link #GENERIC_SHARD_ID}.
 *
 * <p><b>Both shard spellings are produced, and that is the bug this class was written for.</b> The
 * Bazaar keys attribute shards {@code SHARD_<NAME>} while their display name normalises to
 * {@code <NAME>_SHARD} - so the name path produced a key no market has ever heard of, and any shard
 * whose id could not be read priced as unknown. {@code BazaarSyncService} had already found this and
 * flipped the two spellings for orders only; doing it here does it for everything.
 * ({@code PRISMARINE_SHARD} and friends are keyed the other way round and are unaffected: their own
 * spelling is tried first and hits, and the flipped one is a lookup that misses.)
 *
 * <p><b>Producing keys never consults a cache</b>, so {@link #keysFor} answers identically before the
 * first Bazaar pull and after it, and deciding which of them a market knows is the caller's job (see
 * {@code ItemAppraisal.firstPrice} and {@link BazaarPriceCache#keyFor}). The one method that
 * <i>does</i> ask is {@link #shardId}, which has to: only the product map can say which of the two
 * spellings a given shard is really traded under. It degrades to the id's own spelling rather than to
 * nothing while no snapshot has arrived.
 */
public final class ItemPriceKey {

    /** The Bazaar's spelling for an attribute shard. */
    private static final String SHARD_PREFIX = "SHARD_";

    /** What a shard's display name normalises to, and what the Bazaar does <b>not</b> key it under. */
    private static final String SHARD_SUFFIX = "_SHARD";

    /**
     * The id Hypixel puts on <b>every</b> attribute shard item.
     *
     * <p>CONFIRMED from a live capture, 2026-08-19: a Zealot Shard in the inventory carries
     * {@code {attributes:{ender_resistance:1},id:"ATTRIBUTE_SHARD"}}. The id names the <i>item type</i>
     * and never which of the 320 shards it is - the {@code attributes} compound names the attribute
     * the shard grants, which several shards share, so it cannot identify one either. <b>The display
     * name is the only per-shard identity an attribute shard carries.</b>
     *
     * <p>This one constant is the whole reason the feature was dark: {@code ATTRIBUTE_SHARD} ends in
     * {@code _SHARD}, so it passed {@link #isShard}, was returned as a perfectly good shard id, and
     * short-circuited the name path that would have produced the right one. Every shard in the game
     * therefore resolved to the same id, which no market trades.
     */
    private static final String GENERIC_SHARD_ID = "ATTRIBUTE_SHARD";

    /**
     * A trailing "Shard" / "Shards" on a display name - the whole word, never the end of a longer
     * one, so a shard actually named "Sunshard" keeps its name. Anchored at {@code ^} as well, so a
     * name that is only the word normalises to nothing rather than to {@code SHARD_SHARD}.
     */
    private static final Pattern NAME_SHARD_SUFFIX = Pattern.compile("(?i)(?:^|\\s+)shards?$");

    /** Leading glyphs and bullets a menu entry may be decorated with. */
    private static final Pattern NAME_LEADING_JUNK = Pattern.compile("^[^\\p{L}\\p{N}]+");

    /**
     * An amount written into a lore line: {@code "x1,234"}, {@code "1,234x"}, {@code "Amount: 1,234"}.
     *
     * <p>Only ever consulted for {@link Quantity#MENU_ENTRY} - see that constant for why a real
     * inventory stack must never be measured this way.
     */
    private static final Pattern LORE_AMOUNT = Pattern.compile(
            "(?i)(?:amount|owned|stored|quantity)\\s*:?\\s*([0-9][0-9,]*)|\\bx\\s*([0-9][0-9,]*)\\b"
                    + "|\\b([0-9][0-9,]*)\\s*x\\b");

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    /** Which reader produced the key, so a diagnostic can say why a price was or was not found. */
    public enum Origin {

        /** {@code ExtraAttributes.id} - the reliable one. */
        ITEM_ID,

        /** The display name, normalised. What a menu's display icons leave you with. */
        DISPLAY_NAME,

        /** The vanilla registry path, for a plain item that carries no SkyBlock id at all. */
        VANILLA,

        /** Nothing answered. The item has no price key, which is not the same as a price of zero. */
        NONE
    }

    /**
     * How many of the item a caller is holding, which is <b>not</b> one question but two.
     *
     * <p>A vanilla stack stops at 64, so a menu that has to say "you own 1,234 of these" cannot be
     * saying it through the stack size and writes it in the lore instead. Reading that line is
     * therefore right for a menu entry and <b>wrong for an inventory stack</b>, where an "x2" in an
     * item's description is describing something else entirely and would silently multiply that
     * item's value. The two cases do not get one heuristic; they get one implementation and an
     * explicit choice of which question is being asked.
     */
    public enum Quantity {

        /** The stack size, full stop. Inventories, chests, anything the player is really holding. */
        STACK,

        /** The larger of the stack size and an amount stated in the lore. Menu display entries. */
        MENU_ENTRY
    }

    /**
     * What a stack resolves to.
     *
     * @param keys   every key worth asking a market about, most specific first, de-duplicated.
     *               Empty when nothing answered.
     * @param amount how many, under the {@link Quantity} the caller asked for
     * @param origin which reader produced the first key
     */
    public record Resolved(List<String> keys, int amount, Origin origin) {

        public static final Resolved NONE = new Resolved(List.of(), 0, Origin.NONE);

        /** The key a lookup should try first, or {@code null} when the item has none. */
        public String key() {
            return keys.isEmpty() ? null : keys.get(0);
        }

        /** Whether the item resolved to any key at all. */
        public boolean known() {
            return !keys.isEmpty();
        }
    }

    private ItemPriceKey() {
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /** Resolves a stack the player is really holding: keys plus the stack size. */
    public static Resolved of(ItemStack stack) {
        return of(stack, Quantity.STACK);
    }

    /** Resolves a stack, counting it as {@code quantity} says to. */
    public static Resolved of(ItemStack stack, Quantity quantity) {
        if (stack == null || stack.isEmpty()) {
            return Resolved.NONE;
        }
        List<String> keys = keysFor(stack);
        return new Resolved(keys, amount(stack, quantity), originOf(stack, keys));
    }

    /**
     * Every key worth asking a market about, most specific first.
     *
     * <p>Built on {@link PriceLookup#candidatesFor(ItemStack)}, which already composes the pet, book and rune
     * forms and then falls through to id, name and registry path. The one thing added here is the
     * other shard spelling, inserted directly after the key it is an alias of so an exact hit always
     * beats an alias.
     */
    public static List<String> keysFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String candidate : PriceLookup.candidatesFor(stack)) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String upper = candidate.toUpperCase(Locale.ROOT);
            keys.add(upper);
            String alias = shardAlias(upper);
            if (alias != null) {
                keys.add(alias);
            }
        }
        return List.copyOf(keys);
    }

    /**
     * The shard id a stack stands for, in the spelling the Bazaar actually trades it under, or
     * {@code null} when it is not a shard at all.
     *
     * <p>What the Hunting Box and the Attribute Menu both want: they are not pricing an arbitrary
     * item, they are asking "which shard is this". The id path answers it when the entry is the shard
     * item itself; the <b>name path answers it when the entry is a display icon</b>, which carries no
     * {@code ExtraAttributes.id} at all and which neither screen could read before.
     *
     * <p><b>One id per shard, whichever path found it</b>, or the two screens would key the same
     * shard two ways and every cross-reference between them would miss. That is what the Bazaar check
     * in {@link #shardIdOf} is for.
     *
     * <p><b>The id path is not preferred blindly, and that was the bug.</b> Every attribute shard
     * carries {@link #GENERIC_SHARD_ID} - one id for all 320 - so an id-first resolver answered
     * {@code ATTRIBUTE_SHARD} for every shard in the game, never reached the name, and collapsed a
     * whole Hunting Box into one row that no market prices. A generic id is therefore treated as
     * <i>no answer</i>: the name is the only per-shard identity such a stack has. Where both paths
     * answer, the one the Bazaar actually trades wins, so a genuine {@code PRISMARINE_SHARD} keeps its
     * own id and is not renamed into a shard it is not.
     */
    public static String shardId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String rawId = SkyblockItem.id(stack);
        String fromName = shardIdFromName(stack.getHoverName().getString());
        if (isGenericShardId(rawId)) {
            return fromName;   // the id says "an attribute shard"; only the name says which one
        }
        String fromId = shardIdOf(rawId);
        if (fromId == null) {
            return fromName;
        }
        if (fromName != null && !fromName.equals(fromId)
                && BazaarPriceCache.getInstance().get(fromId) == null
                && BazaarPriceCache.getInstance().get(fromName) != null) {
            return fromName;   // the id names no product and the name names one: believe the name
        }
        return fromId;
    }

    /** Whether an id is Hypixel's shared attribute-shard id, which identifies no particular shard. */
    public static boolean isGenericShardId(String id) {
        return id != null && id.equalsIgnoreCase(GENERIC_SHARD_ID);
    }

    /**
     * The shard id a <b>display name</b> stands for, or {@code null} when the name does not name one.
     *
     * <p>Two ways for a name to qualify, and neither is a hardcoded shard list:
     *
     * <ul>
     *   <li><b>The live Bazaar product map trades the derived id.</b> This is the general test, and
     *       it is what makes a shard added by Hypixel next week work with no update here.</li>
     *   <li><b>The name ends in the word "Shard".</b> A self-declaring name is accepted even when no
     *       product matches, so a fusion-only shard the Bazaar does not trade still gets a stable id
     *       rather than vanishing from the box that is holding it.</li>
     * </ul>
     *
     * <p>Without the first test every menu button would become a shard - "Close" would derive
     * {@code SHARD_CLOSE} - and without the second, an untraded shard would be unidentifiable.
     */
    public static String shardIdFromName(String displayName) {
        String plain = PlainText.strip(displayName == null ? "" : displayName).trim();
        if (plain.isEmpty()) {
            return null;
        }
        boolean saysShard = NAME_SHARD_SUFFIX.matcher(plain).find();
        String candidate = shardKeyFromName(plain);
        if (candidate == null) {
            return null;
        }
        if (BazaarPriceCache.getInstance().get(candidate) != null) {
            return candidate;
        }
        return saysShard ? candidate : null;
    }

    /**
     * A display name normalised to the Bazaar's {@code SHARD_<NAME>} spelling, or {@code null} when
     * nothing is left of it. Pure string work: no cache is consulted, so it is testable on its own.
     *
     * <p>Deliberately <b>not</b> {@link SkyblockItem#normalizeName}, which exists for auction
     * matching and does two things that are wrong here: it strips a leading <i>reforge</i> word, so a
     * shard whose name begins "Fine", "Clean", "Heavy" or any of forty others would silently lose it,
     * and it produces the {@code <NAME>_SHARD} spelling the Bazaar does not key.
     */
    public static String shardKeyFromName(String displayName) {
        String text = PlainText.strip(displayName == null ? "" : displayName).trim();
        text = NAME_LEADING_JUNK.matcher(text).replaceFirst("");
        text = NAME_SHARD_SUFFIX.matcher(text).replaceFirst("");
        String key = text.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return key.isEmpty() ? null : SHARD_PREFIX + key;
    }

    /**
     * One candidate read as a shard id, or {@code null} when it does not name a shard.
     *
     * <p><b>{@code SHARD_<NAME>} is unambiguous</b> and is returned as it stands - that spelling is
     * the attribute shards' and nothing else's.
     *
     * <p><b>{@code <NAME>_SHARD} is not.</b> {@code PRISMARINE_SHARD} and {@code GLACITE_SHARD} are
     * genuinely keyed that way and must keep their own spelling, while an attribute shard's
     * <i>display name</i> normalises to exactly the same shape and needs flipping to reach the
     * Bazaar. Nothing about the string separates the two, so the product map is asked which spelling
     * it trades. With no snapshot yet the id keeps its own spelling - the answer it had before this
     * class existed - and the next scan, a second later, gets the flipped one.
     */
    private static String shardIdOf(String candidate) {
        if (candidate == null || !isShard(candidate)) {
            return null;
        }
        String upper = candidate.toUpperCase(Locale.ROOT);
        if (upper.startsWith(SHARD_PREFIX)) {
            return upper;
        }
        String canonical = canonicalShardId(upper);
        return BazaarPriceCache.getInstance().get(canonical) != null ? canonical : upper;
    }

    // ------------------------------------------------------------------
    // Shard spellings
    // ------------------------------------------------------------------

    /**
     * Whether an id names an attribute shard, in either spelling.
     *
     * <p>{@code SHARD_*} is the Bazaar's convention for all of them; {@code *_SHARD} is what a
     * display name normalises to, and is also the genuine id of the older non-attribute shards
     * ({@code PRISMARINE_SHARD}, {@code GLACITE_SHARD}), which is why a caller that needs to tell
     * those apart asks {@link BazaarPriceCache} which spelling it actually trades.
     */
    public static boolean isShard(String id) {
        if (id == null) {
            return false;
        }
        String upper = id.toUpperCase(Locale.ROOT);
        return upper.startsWith(SHARD_PREFIX) || upper.endsWith(SHARD_SUFFIX);
    }

    /** The {@code SHARD_<NAME>} spelling of a shard id, or {@code null} when the id is not one. */
    public static String canonicalShardId(String id) {
        if (id == null) {
            return null;
        }
        String upper = id.toUpperCase(Locale.ROOT);
        if (upper.startsWith(SHARD_PREFIX)) {
            return upper;
        }
        if (upper.endsWith(SHARD_SUFFIX)) {
            return SHARD_PREFIX + upper.substring(0, upper.length() - SHARD_SUFFIX.length());
        }
        return null;
    }

    /**
     * The other spelling of a shard id, or {@code null} when the id is not a shard.
     *
     * <p>Both directions, because both are wrong somewhere: a name-derived {@code TIDE_SHARD} needs
     * {@code SHARD_TIDE} to reach the Bazaar, and an id-derived {@code SHARD_TIDE} would need
     * {@code TIDE_SHARD} if Hypixel ever keys one the other way. Trying a key that does not exist
     * costs one map lookup and returns nothing.
     */
    public static String shardAlias(String id) {
        if (id == null) {
            return null;
        }
        String upper = id.toUpperCase(Locale.ROOT);
        if (upper.startsWith(SHARD_PREFIX)) {
            return upper.substring(SHARD_PREFIX.length()) + SHARD_SUFFIX;
        }
        if (upper.endsWith(SHARD_SUFFIX)) {
            return SHARD_PREFIX + upper.substring(0, upper.length() - SHARD_SUFFIX.length());
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Amounts
    // ------------------------------------------------------------------

    /** How many the stack represents under the given reading. Never below zero. */
    public static int amount(ItemStack stack, Quantity quantity) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        int count = Math.max(0, stack.getCount());
        if (quantity != Quantity.MENU_ENTRY) {
            return count;
        }
        return Math.max(count, loreAmount(stack));
    }

    /**
     * The largest amount any lore line states, or {@code 0} when none does.
     *
     * <p>Largest rather than first: a shard entry's lore may carry both a total and a per-something
     * figure, and the total is the one that cannot be smaller. A number too long for an {@code int}
     * is not an amount and is ignored rather than guessed at.
     */
    public static int loreAmount(ItemStack stack) {
        return amountIn(lore(stack));
    }

    /**
     * The same rule over already-stripped lines. Split out so it can be tested without a game: the
     * Hunting Box states its real total as {@code "Owned: 1,234 Shards"} - a stack caps at 64, so
     * that line is the only place a box holding thousands can be saying so.
     */
    public static int amountIn(List<String> lines) {
        int best = 0;
        for (String line : lines) {
            Matcher matcher = LORE_AMOUNT.matcher(line);
            while (matcher.find()) {
                for (int group = 1; group <= matcher.groupCount(); group++) {
                    String text = matcher.group(group);
                    if (text == null) {
                        continue;
                    }
                    try {
                        best = Math.max(best, Integer.parseInt(text.replace(",", "")));
                    } catch (NumberFormatException tooBig) {
                        // Longer than an int: not an amount. Ignoring it beats inventing one.
                    }
                }
            }
        }
        return best;
    }

    /** The stack's lore as colour-stripped plain lines, empty when it has none. */
    public static List<String> lore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        ItemLore itemLore = stack.get(DataComponents.LORE);
        if (itemLore == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(itemLore.lines().size());
        for (Component line : itemLore.lines()) {
            out.add(strip(line.getString()));
        }
        return out;
    }

    // ------------------------------------------------------------------

    /** Which reader the first key came from, worked out by re-asking the readers themselves. */
    private static Origin originOf(ItemStack stack, List<String> keys) {
        if (keys.isEmpty()) {
            return Origin.NONE;
        }
        String skyblockId = SkyblockItem.id(stack);
        if (skyblockId != null && !skyblockId.isBlank()) {
            return Origin.ITEM_ID;
        }
        return SkyblockItem.normalizeName(stack.getHoverName().getString()).isEmpty()
                ? Origin.VANILLA : Origin.DISPLAY_NAME;
    }

    private static String strip(String text) {
        return text == null ? "" : text.replaceAll(SECTION_SIGN + ".", "").trim();
    }
}
