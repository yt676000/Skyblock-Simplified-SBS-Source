/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.skills.hunting.model.ShardContext;
import sbs.modid.client.skills.hunting.model.ShardDefinition;
import sbs.modid.client.skills.hunting.model.ShardId;
import sbs.modid.client.skills.hunting.model.ShardStrategy;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a stack into a canonical shard id, by asking <b>the one strategy that is correct for the
 * screen it came from</b>.
 *
 * <p><b>Why the context has to be passed in.</b> The previous resolver tried every reading in turn
 * and took the first that answered, which is exactly how the feature broke: in the Attribute Menu
 * the display name is the <i>attribute</i> plus a roman tier ("Berry Eater IX"), so the name reader
 * answered confidently with an id that names no shard at all - and because it answered, the reader
 * that would have got it right never ran. A fallback chain is only safe when a wrong answer looks
 * like no answer, and here it does not. Each screen therefore gets one strategy, and a strategy
 * that cannot answer returns {@link ShardStrategy#NONE} rather than deferring to a reading that is
 * known to be wrong in that place.
 *
 * <table>
 *   <caption>Which reader runs where</caption>
 *   <tr><th>Context</th><th>Strategy</th><th>What it reads</th></tr>
 *   <tr><td>{@link ShardContext#ATTRIBUTE_MENU}</td><td>{@link ShardStrategy#SOURCE_LORE}</td>
 *       <td>{@code Source: Toxic Shard (C12)}</td></tr>
 *   <tr><td>{@link ShardContext#HUNTING_BOX}, {@code FUSION_BOX}, {@code SHARD_FUSION}</td>
 *       <td>{@link ShardStrategy#DISPLAY_NAME}</td><td>the name, colour codes stripped</td></tr>
 *   <tr><td>{@link ShardContext#CONFIRM_FUSION}</td><td>{@link ShardStrategy#FIRST_LORE_LINE}</td>
 *       <td>the first lore line</td></tr>
 *   <tr><td>{@link ShardContext#INVENTORY}</td><td>{@link ShardStrategy#ITEM_NBT}</td>
 *       <td>{@code ExtraAttributes} and its {@code attributes} compound</td></tr>
 * </table>
 *
 * <p><b>An id is produced even for a shard the catalogue does not carry.</b> The id is derived from
 * what the screen said, and the catalogue is then consulted to attach a definition to it. Those are
 * two questions and conflating them is what made a rate-limited network pull able to un-identify a
 * shard. A resolution with a {@code null} {@link Resolution#shard()} is a real reading of something
 * the catalogue has not got - it is logged, and it still keys consistently across every screen.
 */
public final class ShardResolver {

    /**
     * The Attribute Menu's own statement of which shard feeds an attribute:
     * {@code "Source: Toxic Shard (C12)"}. The name is the answer; the parenthesised short id is a
     * cross-check.
     *
     * <p>Deliberately looser than the sentence it came from - the keyword, the name and the optional
     * bracket are anchored and nothing else is - so a reworded line of the same shape still reads.
     */
    private static final Pattern SOURCE_LINE = Pattern.compile(
            "(?i)^\\s*source\\s*:\\s*(.+?)\\s*(?:\\(\\s*([A-Za-z]?[0-9]+[A-Za-z0-9]*)\\s*\\))?\\s*$");

    /** A trailing "Shard" / "Shards" as a whole word, so a shard named "Sunshard" keeps its name. */
    private static final Pattern TRAILING_SHARD = Pattern.compile("(?i)(?:^|\\s+)shards?$");

    /** Leading glyphs, bullets and arrows a menu entry may be decorated with. */
    private static final Pattern LEADING_JUNK = Pattern.compile("^[^\\p{L}\\p{N}]+");

    /** Hypixel's shared item id for every attribute shard - it names the type, never which one. */
    private static final String GENERIC_ITEM_ID = "ATTRIBUTE_SHARD";

    /** The NBT compound naming what the shard grants, and at what tier. */
    private static final String ATTRIBUTES = "attributes";

    /**
     * One reading of one stack.
     *
     * @param canonicalId {@code ATTRIBUTE_SHARD_<NAME>;<tier>}, or {@code null} when nothing answered
     * @param tier        the tier the reading stated, or {@link ShardId#DEFAULT_TIER}
     * @param strategy    which reader answered - {@link ShardStrategy#NONE} when none did
     * @param shard       the catalogue entry for {@link #canonicalId}, or {@code null} when the
     *                    catalogue does not carry it. Not the same question as whether it resolved.
     * @param sourceText  the text the id was read out of, for the debug dump and the log
     */
    public record Resolution(String canonicalId, int tier, ShardStrategy strategy,
                             ShardDefinition shard, String sourceText) {

        public static final Resolution NONE =
                new Resolution(null, ShardId.DEFAULT_TIER, ShardStrategy.NONE, null, "");

        /** Whether a reader produced an id at all. */
        public boolean resolved() {
            return canonicalId != null;
        }

        /** Whether the catalogue also knows what that id is. */
        public boolean catalogued() {
            return shard != null;
        }

        /** The identity key every map in this feature is keyed on - the id without its tier. */
        public String key() {
            return ShardId.key(canonicalId);
        }

        /** The display name to show: the catalogue's where it has one, else what was read. */
        public String displayName() {
            if (shard != null) {
                return shard.display();
            }
            String name = ShardId.nameOf(canonicalId);
            return name == null ? sourceText : name;
        }
    }

    private ShardResolver() {
    }

    /** Resolves a stack read out of {@code context}. Never {@code null}; never throws. */
    public static Resolution resolve(ItemStack stack, ShardContext context) {
        if (stack == null || stack.isEmpty() || context == null) {
            return Resolution.NONE;
        }
        return switch (context) {
            case ATTRIBUTE_MENU -> fromSourceLore(stack);
            case HUNTING_BOX, FUSION_BOX, SHARD_FUSION -> fromDisplayName(stack);
            case CONFIRM_FUSION -> fromFirstLoreLine(stack);
            case INVENTORY -> fromItemNbt(stack);
        };
    }

    // ------------------------------------------------------------------
    // The strategies
    // ------------------------------------------------------------------

    /**
     * The Attribute Menu: the {@code Source:} lore line, and nothing else.
     *
     * <p><b>The display name is never consulted here, and that is the whole point of this class.</b>
     * It reads "Berry Eater IX" - the attribute and its tier - so a name-derived id is not a
     * near-miss, it is an id for a shard that does not exist. See {@code docs/issues/skills.md}.
     */
    private static Resolution fromSourceLore(ItemStack stack) {
        for (String line : lore(stack)) {
            Matcher matcher = SOURCE_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String name = stripShardWord(matcher.group(1));
            String shortId = matcher.group(2);
            if (name.isEmpty() && (shortId == null || shortId.isBlank())) {
                continue;
            }
            // The name is the stated answer; the short id in brackets is asked only when the
            // catalogue does not recognise the name, so a renamed shard still lands on its entry.
            ShardDefinition byName = ShardCatalog.byDisplayName(name);
            ShardDefinition shard = byName != null ? byName : ShardCatalog.byShortId(shortId);
            String canonical = canonicalFor(shard, name);
            if (canonical == null) {
                continue;
            }
            return new Resolution(canonical, ShardId.tierOf(canonical), ShardStrategy.SOURCE_LORE,
                    shard, line);
        }
        return Resolution.NONE;
    }

    /** The Hunting Box and the fusion menus: the display name there <i>is</i> the shard's name. */
    private static Resolution fromDisplayName(ItemStack stack) {
        String plain = PlainText.strip(stack.getHoverName().getString()).trim();
        return fromName(plain, plain, ShardStrategy.DISPLAY_NAME);
    }

    /** The Confirm Fusion dialog: the shard is stated in the first lore line, not in the name. */
    private static Resolution fromFirstLoreLine(ItemStack stack) {
        List<String> lore = lore(stack);
        if (lore.isEmpty()) {
            return Resolution.NONE;
        }
        String first = lore.get(0);
        return fromName(first, first, ShardStrategy.FIRST_LORE_LINE);
    }

    /**
     * A real item: {@code ExtraAttributes}, which is the only context that carries usable NBT.
     *
     * <p>Every attribute shard shares one item id, so the id alone says "this is a shard" and never
     * which one; the {@code attributes} compound carries the name and the tier. Where that name is
     * one the catalogue does not carry <b>and</b> the item's own display name is one it does, the
     * display name wins for identity and the compound still supplies the tier - the two disagree
     * only when the compound is naming the attribute rather than the shard, and collapsing every
     * shard that feeds one attribute into a single row is the failure that costs the most.
     */
    private static Resolution fromItemNbt(ItemStack stack) {
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        String itemId = extra.getStringOr("id", "");
        if (itemId.isEmpty()) {
            itemId = extra.getStringOr("ID", "");
        }
        if (!GENERIC_ITEM_ID.equalsIgnoreCase(itemId)) {
            return Resolution.NONE;   // not an attribute shard at all
        }
        CompoundTag attributes = extra.getCompoundOrEmpty(ATTRIBUTES);
        String nbtName = null;
        int tier = ShardId.DEFAULT_TIER;
        for (String key : attributes.keySet()) {
            nbtName = key;
            tier = Math.max(1, attributes.getIntOr(key, ShardId.DEFAULT_TIER));
            break;   // one compound, one shard; a second key would be a shape nobody has seen
        }
        if (nbtName == null) {
            return Resolution.NONE;
        }
        String source = ATTRIBUTES + ":{" + nbtName + ":" + tier + "}";
        ShardDefinition byNbt = ShardCatalog.byDisplayName(nbtName);
        if (byNbt == null) {
            ShardDefinition byName =
                    ShardCatalog.byDisplayName(stripShardWord(
                            PlainText.strip(stack.getHoverName().getString())));
            if (byName != null) {
                return new Resolution(ShardId.of(byName.name, tier), tier, ShardStrategy.ITEM_NBT,
                        byName, source);
            }
        }
        String canonical = ShardId.of(byNbt != null ? byNbt.name : nbtName, tier);
        return canonical == null ? Resolution.NONE
                : new Resolution(canonical, tier, ShardStrategy.ITEM_NBT, byNbt, source);
    }

    // ------------------------------------------------------------------
    // Shared name handling
    // ------------------------------------------------------------------

    /**
     * A display name turned into a resolution: leading decoration off, a trailing "Shard" off,
     * overrides applied by the catalogue, then upper-cased into the canonical id.
     */
    private static Resolution fromName(String rawName, String sourceText, ShardStrategy strategy) {
        String name = stripShardWord(rawName);
        if (name.isEmpty()) {
            return Resolution.NONE;
        }
        ShardDefinition shard = ShardCatalog.byDisplayName(name);
        if (shard == null) {
            // Not in the catalogue. An id is still produced - the reading is real and keys the same
            // way everywhere - but the name is logged, because an entry the catalogue cannot name is
            // either a shard we are missing or a control we should not be reading.
            ShardNameOverrides.noteUnmatched(name, strategy.displayName());
        }
        String canonical = canonicalFor(shard, name);
        return canonical == null ? Resolution.NONE
                : new Resolution(canonical, ShardId.tierOf(canonical), strategy, shard, sourceText);
    }

    /** The canonical id of a catalogue entry, or one derived from the name when there is none. */
    private static String canonicalFor(ShardDefinition shard, String name) {
        return shard != null ? shard.canonical() : ShardId.of(name);
    }

    /**
     * A name with its decoration and a trailing "Shard"/"Shards" removed.
     *
     * <p>The trailing word goes because Hypixel writes it in some places and not others - "Toxic" in
     * the box, "Toxic Shard" in a lore line - and one shard must not become two ids depending on
     * which screen it was read from.
     */
    private static String stripShardWord(String text) {
        String plain = PlainText.strip(text == null ? "" : text).trim();
        plain = LEADING_JUNK.matcher(plain).replaceFirst("");
        return TRAILING_SHARD.matcher(plain).replaceFirst("").trim();
    }


    /** A stack's lore as colour-stripped plain lines; empty when it has none. */
    private static List<String> lore(ItemStack stack) {
        return sbs.modid.client.economy.prices.ItemPriceKey.lore(stack);
    }

}
