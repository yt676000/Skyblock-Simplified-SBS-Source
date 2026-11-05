/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import sbs.modid.client.skills.fishing.model.FishingData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the bait store's menu and reads its baits - pure, no game objects, so the matching and
 * the slot reading are unit-tested.
 *
 * <p><b>The store changed on 2026-09-21</b> (the wiki; the Fishing Bag's final removal is listed as
 * 0.27.2 on 2026-10-06): the {@code Fishing Bag} became the <b>Bait Sack</b>, a real sack in three
 * sizes ({@code SMALL_/MEDIUM_/LARGE_BAIT_SACK}, 2,240 / 6,720 / 20,160 per bait), with a
 * "favourite" bait chosen by shift-click that is consumed first. <b>None of the menu is captured
 * yet</b> - titles, layout and the favourite marker below are the wiki's word plus how every other
 * sack menu behaves. {@link BaitSackProbe} logs the real menu; correct this class against that.
 *
 * <ul>
 *   <li><b>Title</b>: exact titles only ({@link #isBaitStoreTitle}). A loose "bait" match also caught
 *       Bazaar / Auction House pages named after a bait, whose display stacks clobbered the reserve.</li>
 *   <li><b>Count</b>: a sack shows its amount in the lore ({@code Stored: 1,234/20,160}), not in the
 *       stack size, so the lore wins and the stack size is only the old bag's fallback.</li>
 *   <li><b>Layout</b>: every menu slot is read and only bait ids are kept, so a size with more rows,
 *       the navigation row and a separate favourite marker all fall out without slot numbers.</li>
 * </ul>
 */
public final class BaitSackReader {

    private static final Pattern COLOUR_CODE = Pattern.compile("§.");

    /**
     * The bait store's menu titles: the three Bait Sack sizes, a bare {@code Bait Sack} (sack menus
     * may drop the size), and the old {@code Fishing Bag} - each with an optional page counter, as a
     * prefix {@code (1/2) } or a suffix {@code  (1/2)} (Hypixel uses both). Anchored on both ends, so
     * {@code Bazaar ➜ Fish Bait}, {@code Fish Bait ➜ Instant Buy}, {@code Small Bait Sack Recipe}
     * and an AH search never match.
     */
    private static final Pattern STORE_TITLE = Pattern.compile(
            "^(?:\\(\\d+/\\d+\\) )?(?:(?:(?:small|medium|large) )?bait sack|fishing bag)(?: \\(\\d+/\\d+\\))?$");

    /** A sack's stored amount: {@code Stored: 1,234/20,160} (the cap is optional). */
    private static final Pattern STORED = Pattern.compile("^Stored:?\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Lore wording that marks the favourite. UNVERIFIED - the wiki says only "Shift+Left Click to be
     * favored". Instruction lines ("Shift-Click to favorite", "Click to unfavorite") carry "click" and
     * are excluded, so the hint every bait shows never marks them all.
     */
    private static final Pattern FAVOURITE_WORDING = Pattern.compile(
            "favou?rite|favou?red|currently (?:using|used|selected)|selected", Pattern.CASE_INSENSITIVE);

    private BaitSackReader() {
    }

    /** One menu slot as the reader sees it: id, name, stack size, lore lines and the glint flag. */
    public record SlotView(int index, String itemId, String name, int count, List<String> lore, boolean glint) {
    }

    /**
     * What one look into the store found: {@code baits} bait id -> stored amount (zero-stock entries
     * dropped), {@code content} a fingerprint of every non-empty slot for the settle check, and the
     * favourite bait id, or {@code null} when the menu marks none this reader recognises.
     */
    public record Reading(Map<String, Integer> baits, Map<String, Integer> content, String favourite) {
    }

    /** Whether {@code title} (raw, colour codes allowed) is the Bait Sack or the old Fishing Bag. */
    public static boolean isBaitStoreTitle(String title) {
        return title != null && STORE_TITLE.matcher(normalise(title)).matches();
    }

    /** Lower-case, colour-free, trimmed - the form titles are compared in. */
    static String normalise(String title) {
        return COLOUR_CODE.matcher(title).replaceAll("").trim().toLowerCase(Locale.ROOT);
    }

    /** Reads the store's menu slots (the player inventory below is the caller's to leave out). */
    public static Reading read(List<SlotView> slots) {
        Map<String, Integer> baits = new LinkedHashMap<>();
        Map<String, Integer> content = new LinkedHashMap<>();
        String byLore = null;
        String byGlint = null;
        int loreMarked = 0;
        int glinted = 0;
        for (SlotView slot : slots) {
            String key = slot.itemId() != null ? slot.itemId() : slot.name();
            int amount = amountOf(slot);
            // The settle fingerprint spans EVERY item (navigation, toggles, empty baits) - a store
            // whose baits stream in last must not read as "settled and bait-free" early.
            content.merge(key, amount, Integer::sum);
            if (!FishingData.isBait(slot.itemId())) {
                continue;
            }
            if (amount > 0) {
                baits.merge(slot.itemId(), amount, Integer::sum);
            }
            if (marksFavourite(slot.lore())) {
                loreMarked++;
                byLore = slot.itemId();
            }
            if (slot.glint()) {
                glinted++;
                byGlint = slot.itemId();
            }
        }
        // Exactly one marked bait is a favourite; several (a wording every bait shares) is none.
        String favourite = loreMarked == 1 ? byLore : glinted == 1 ? byGlint : null;
        return new Reading(baits, content, favourite);
    }

    /** A slot's amount: the sack's {@code Stored:} lore line, else the stack size (the old bag). */
    static int amountOf(SlotView slot) {
        for (String line : slot.lore()) {
            Matcher matcher = STORED.matcher(COLOUR_CODE.matcher(line).replaceAll("").trim());
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1).replace(",", ""));
                } catch (NumberFormatException e) {
                    break;
                }
            }
        }
        return slot.count();
    }

    /** Whether a lore line states (not merely offers) the favourite - see {@link #FAVOURITE_WORDING}. */
    static boolean marksFavourite(List<String> lore) {
        for (String line : lore) {
            String plain = COLOUR_CODE.matcher(line).replaceAll("").toLowerCase(Locale.ROOT);
            if (!plain.contains("click") && FAVOURITE_WORDING.matcher(plain).find()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a lore line is a favourite-marker candidate worth logging (the probe's filter). */
    static boolean mentionsFavourite(String line) {
        return line != null && FAVOURITE_WORDING.matcher(COLOUR_CODE.matcher(line).replaceAll("")).find();
    }
}
