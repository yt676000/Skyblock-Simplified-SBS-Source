/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.inventory.logic;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which slots of Hypixel's Stats &amp; Equipment menu hold the four equipment pieces. Pure: it sees
 * plain strings, never a stack, so the mapping is unit-tested without a game.
 *
 * <p><b>CONFIRMED layout</b> (layout scans 2026-09-25, {@code menus/stats-equipment}; see
 * {@code docs/skyblock-ui/menus.md}, "Stats &amp; Equipment"). Title {@code "Stats & Equipment"}
 * (legacy {@code "Your Equipment and Stats"}, last seen 2026-07-06). The worn gear sits in two
 * columns: 10 necklace, 19 cloak, 28 belt, 37 gloves/bracelet, with the armor beside them at
 * 11 / 20 / 29 / 38 and the pet at 47. The captured rarity lines read
 * {@code "a MYTHIC NECKLACE a"} - the {@code a} on each side is the recombobulator's obfuscated
 * glyph.
 *
 * <p>A piece is still found first by what it <i>is</i> - the type word on its rarity line - because
 * that survives Hypixel moving things; the confirmed slots are the fallback. An empty slot is
 * expected to be a placeholder named after the piece ({@code "Empty Necklace Slot"}) or a glass pane
 * at its slot. <b>Not captured yet:</b> every scan so far is of a fully equipped player, so the empty
 * placeholder and a bracelet in slot 37 are still the expected shapes, not observed ones.
 *
 * <p><b>All four or nothing.</b> A read that could not place every piece is refused as a whole
 * rather than half-written: a stale necklace is better than a confident empty one.
 */
public final class EquipmentMenu {

    /** Display order, top to bottom - the order the menu lists them in. */
    public enum Piece {
        NECKLACE("Necklace"), CLOAK("Cloak"), BELT("Belt"), GLOVES("Gloves");

        private final String label;

        Piece(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Slot per piece, in {@link Piece} order. CONFIRMED - see the class note. */
    static final int[] FALLBACK_SLOTS = {10, 19, 28, 37};

    /** The item type word of a rarity line. Bracelets share the gloves slot. */
    private static final Pattern TYPE_WORD =
            Pattern.compile("\\b(NECKLACE|CLOAK|BELT|GLOVES|GAUNTLET|BRACELET)\\b");
    private static final Pattern RARITY_LINE = Pattern.compile(
            "^(?:a )?(?:VERY SPECIAL|COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|"
                    + "ULTIMATE|ADMIN)\\b.*");
    private static final Pattern EMPTY_NAME =
            Pattern.compile("^empty (necklace|cloak|belt|gloves|bracelet)\\b.*");

    /** One menu slot, as plain text: colour codes already stripped. */
    public record SlotView(int index, String itemId, String name, List<String> lore) {
    }

    /**
     * The read: per piece, the slot index holding it, or {@code -1} when the piece is empty. A
     * {@code null} result means the menu could not be read with confidence.
     */
    public record Mapping(int[] slots) {

        public boolean empty(Piece piece) {
            return slots[piece.ordinal()] < 0;
        }

        public int slot(Piece piece) {
            return slots[piece.ordinal()];
        }
    }

    private EquipmentMenu() {
    }

    /** The menu's two real titles, normalised: modern, and legacy (last seen 2026-07-06). */
    private static final java.util.Set<String> TITLES =
            java.util.Set.of("stats & equipment", "your equipment and stats");

    /**
     * Whether a normalised (stripped, lowercase) title is the Stats &amp; Equipment menu.
     *
     * <p>Exact, not {@code contains("equipment")}: {@code "(1/N) Equipment Sets"} contains the word
     * and holds other gear, so a loose match let opening it overwrite the stored equipment.
     */
    public static boolean isEquipmentMenu(String normalisedTitle) {
        return normalisedTitle != null && TITLES.contains(normalisedTitle.trim());
    }

    /** Places the four pieces, or {@code null} when any of them cannot be placed. */
    public static Mapping map(List<SlotView> slots) {
        int[] found = new int[4];
        boolean[] resolved = new boolean[4];
        for (SlotView slot : slots) {
            Piece piece = pieceOf(slot);
            if (piece != null && !resolved[piece.ordinal()]) {
                found[piece.ordinal()] = slot.index();
                resolved[piece.ordinal()] = true;
                continue;
            }
            Piece empty = emptyPlaceholder(slot.name());
            if (empty != null && !resolved[empty.ordinal()]) {
                found[empty.ordinal()] = -1;
                resolved[empty.ordinal()] = true;
            }
        }
        // Fallback by position, for a piece neither its lore nor a placeholder named.
        for (Piece piece : Piece.values()) {
            if (resolved[piece.ordinal()]) {
                continue;
            }
            SlotView at = at(slots, FALLBACK_SLOTS[piece.ordinal()]);
            if (at != null && isGlassPane(at)) {
                found[piece.ordinal()] = -1;
                resolved[piece.ordinal()] = true;
            }
        }
        for (boolean ok : resolved) {
            if (!ok) {
                return null;
            }
        }
        return new Mapping(found);
    }

    /** The piece an item is, from the type word on its rarity line; {@code null} if none. */
    static Piece pieceOf(SlotView slot) {
        List<String> lore = slot.lore();
        for (int i = lore.size() - 1; i >= 0; i--) {
            String line = lore.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!RARITY_LINE.matcher(line).matches()) {
                continue;   // the rarity line is near the end, but a hint may follow it
            }
            Matcher type = TYPE_WORD.matcher(line);
            if (!type.find()) {
                return null;   // a rarity line that names another type: not a piece
            }
            return switch (type.group(1)) {
                case "NECKLACE" -> Piece.NECKLACE;
                case "CLOAK" -> Piece.CLOAK;
                case "BELT" -> Piece.BELT;
                default -> Piece.GLOVES;
            };
        }
        return null;
    }

    static Piece emptyPlaceholder(String name) {
        Matcher m = EMPTY_NAME.matcher(name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        return switch (m.group(1)) {
            case "necklace" -> Piece.NECKLACE;
            case "cloak" -> Piece.CLOAK;
            case "belt" -> Piece.BELT;
            default -> Piece.GLOVES;
        };
    }

    private static boolean isGlassPane(SlotView slot) {
        return slot.itemId() != null && slot.itemId().endsWith("glass_pane");
    }

    private static SlotView at(List<SlotView> slots, int index) {
        for (SlotView slot : slots) {
            if (slot.index() == index) {
                return slot;
            }
        }
        return null;
    }
}
