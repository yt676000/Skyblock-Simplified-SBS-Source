/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.storage;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which menu titles are storage - by the <b>whole</b> title, never a substring of it.
 *
 * <p><b>Why whole titles.</b> The first version asked {@code title.contains("backpack")}, and an
 * Auction House search is titled {@code Auctions: "backpack"}: the search results were saved as
 * "Backpack 1" and shown as that backpack's contents. Every family had the same hole - an AH or Bazaar
 * search for "sack", "museum" or "ender chest" would have been storage too, and the Sack Overlay,
 * which shares {@link #isSackTitle}, would have opened over the results. Each pattern below is
 * anchored at both ends.
 *
 * <p><b>Where each title comes from</b> ({@code docs/skyblock-ui/menus.md}, and the play instance's
 * {@code Container opened: title='…'} log lines, 2026-07 → 2026-09):
 * <ul>
 *   <li>Backpack: {@code Jumbo / Large / Greater Backpack (Slot #N)} - CONFIRMED. One size word;
 *       the number comes from the title and there is no default, so no number means no backpack.</li>
 *   <li>Ender Chest: {@code Ender Chest (N/M)} - CONFIRMED; the unpaged {@code Ender Chest} is
 *       accepted as page 1.</li>
 *   <li>Museum: {@code Your Museum} and {@code (N/M) Museum ➜ <Category>} - CONFIRMED.
 *       {@code Museum ➜ Search Results} is deliberately not storage: it is a filtered view of pages
 *       already indexed, and saving it would double every item it shows.</li>
 *   <li>Sacks: {@code Sack of Sacks} - CONFIRMED. A single sack's own menu has never been opened in
 *       the logs, so {@code <words> Sack} is ESTIMATED; it is anchored and letters-only, which is what
 *       keeps {@code Auctions: "sack"} and {@code Bazaar ➜ "sack"} out.</li>
 * </ul>
 * Titles are colour-stripped and trimmed by the caller. Pure - no Minecraft types - so each rule is
 * unit-tested with the real titles.
 */
public final class StorageTitles {

    private static final Pattern BACKPACK = Pattern.compile("^(?:[A-Za-z]+ )?Backpack \\(Slot #(\\d+)\\)$");
    private static final Pattern ENDER_CHEST = Pattern.compile("^Ender Chest(?: \\((\\d+)/\\d+\\))?$");
    private static final Pattern MUSEUM = Pattern.compile(
            "^(?:Your Museum|\\(\\d+/\\d+\\) Museum ➜ [A-Za-z ]+|Museum ➜ (?!Search Results$)[A-Za-z ]+)$");
    private static final Pattern SACK = Pattern.compile("^(?:Sack of Sacks|(?:[A-Za-z']+ )+Sack)$");

    /** What a title is, when it is storage. {@code number} is the backpack slot or chest page, else 0. */
    public record Match(Kind kind, int number) {
    }

    public enum Kind {
        BACKPACK, ENDER_CHEST, MUSEUM, SACK, VAULT, CHEST
    }

    private StorageTitles() {
    }

    /** The storage a stripped, trimmed title names, or {@code null} when it is not storage. */
    public static Match classify(String title) {
        if (title == null) {
            return null;
        }
        String t = title.trim();
        Matcher m = BACKPACK.matcher(t);
        if (m.matches()) {
            return new Match(Kind.BACKPACK, Integer.parseInt(m.group(1)));
        }
        m = ENDER_CHEST.matcher(t);
        if (m.matches()) {
            return new Match(Kind.ENDER_CHEST, m.group(1) == null ? 1 : Integer.parseInt(m.group(1)));
        }
        if (isSackTitle(t)) {
            return new Match(Kind.SACK, 0);
        }
        if (isMuseumTitle(t)) {
            return new Match(Kind.MUSEUM, 0);
        }
        if (t.equals("Personal Vault")) {
            return new Match(Kind.VAULT, 0);
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("chest") || lower.equals("large chest")) {
            return new Match(Kind.CHEST, 0);
        }
        return null;
    }

    /** Whether a title is a sack's menu (including {@code Sack of Sacks}). The Sack Overlay asks this too. */
    public static boolean isSackTitle(String title) {
        return title != null && SACK.matcher(title.trim()).matches();
    }

    /** Whether a title is a museum page that counts as storage (not the search results). */
    public static boolean isMuseumTitle(String title) {
        return title != null && MUSEUM.matcher(title.trim()).matches();
    }

    // ------------------------------------------------------------------ cache clean-up

    /**
     * Whether a persisted entry's id still fits its kind under the whole-title rules. Sack and museum
     * ids embed the lower-cased title they were captured under, so those are re-checked; numbered
     * kinds carry no title and are checked by content instead ({@link #looksLikeAuctionListing}).
     */
    public static boolean idStillValid(String id, String kind) {
        if (id == null) {
            return false;
        }
        if (kind.equals("SACKS") && id.startsWith("sack:")) {
            return SACK_LOWER.matcher(id.substring(5)).matches();
        }
        if (kind.equals("MUSEUM") && id.startsWith("museum:")) {
            return MUSEUM_LOWER.matcher(id.substring(7)).matches();
        }
        return true;
    }

    private static final Pattern SACK_LOWER = Pattern.compile(SACK.pattern(), Pattern.CASE_INSENSITIVE);
    private static final Pattern MUSEUM_LOWER = Pattern.compile(MUSEUM.pattern(), Pattern.CASE_INSENSITIVE);

    /**
     * Whether a persisted page's stacks are Auction House listings rather than stored items: at least
     * half of the non-empty ones carry a listing's lore ({@code Buy it now}, {@code Starting bid},
     * {@code Seller:}). Read from the stored SNBT text, so nothing has to be decoded. A real backpack
     * holding one auctioned-item copy stays; a page of search results does not.
     *
     * <p><b>ESTIMATED wording:</b> those three phrases are Hypixel's well-known listing lore, but no
     * listing stack has been captured in this repository's logs or probes. This check only cleans
     * caches written before the whole-title rules; the title rules are what keep new ones out.
     *
     * @param snbts each stored stack's SNBT ({@code null} or empty for an empty slot)
     */
    public static boolean looksLikeAuctionListing(List<String> snbts) {
        int items = 0;
        int listings = 0;
        for (String snbt : snbts) {
            if (snbt == null || snbt.isEmpty()) {
                continue;
            }
            items++;
            if (snbt.contains("Buy it now") || snbt.contains("Starting bid") || snbt.contains("Seller:")) {
                listings++;
            }
        }
        return items > 0 && listings * 2 >= items;
    }
}
