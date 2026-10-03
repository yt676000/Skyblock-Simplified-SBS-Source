/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.stacktips;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an item's plain name, SkyBlock id and (for menu-bound kinds) the open menu's title into the
 * short text drawn on its icon. Pure strings in, so every rule is unit-tested without a game.
 *
 * <p><b>Where each source was checked.</b>
 * <ul>
 *   <li>Pets: the {@code [Lvl 87]} name prefix is the same one {@code PetsOverlay} detects pets by.</li>
 *   <li>Minions and Catacombs passes: read from the <b>id</b>, checked against Hypixel's keyless
 *       items resource on 2026-09-24 - all 719 minions are {@code <TYPE>_GENERATOR_<tier>} with the
 *       id's number equal to {@code generator_tier}, and passes are {@code CATACOMBS_PASS_<n>} /
 *       {@code MASTER_CATACOMBS_PASS_<n>} for 0-10. The id beats the name: the same resource names
 *       {@code BIRCH_GENERATOR_12} "Birch Minion XIi", which a roman-numeral parse would drop.</li>
 *   <li>Skills and collections: the name shapes below are <b>unverified</b> against a live menu, so
 *       both kinds ship switched off. See {@code docs/features/item-stack-tips.md}.</li>
 * </ul>
 */
public final class StackTipParser {

    /** One parsed tip. {@code text} is what is drawn; it is never empty. */
    public record Tip(StackTipKind kind, String text) {
    }

    private static final Pattern PET = Pattern.compile("^\\[Lvl\\s*(\\d{1,3})]");
    private static final Pattern MINION_ID = Pattern.compile("^[A-Z0-9_]+_GENERATOR_(\\d{1,2})$");
    private static final Pattern MINION_NAME = Pattern.compile("^.+ Minion ([IVX]+)$");
    private static final Pattern PASS_ID = Pattern.compile("^(MASTER_)?CATACOMBS_PASS_(\\d{1,2})$");
    private static final Pattern SKILL_NAME = Pattern.compile(
            "^(Farming|Mining|Combat|Foraging|Fishing|Enchanting|Alchemy|Carpentry|Runecrafting"
                    + "|Social|Taming|Hunting) (\\d{1,2}|[IVXL]+)$");
    private static final Pattern COLLECTION_NAME = Pattern.compile("^(.+) ([IVXL]+)$");

    private StackTipParser() {
    }

    /**
     * The tip that does not depend on where the item is - pets, minions, passes - or {@code null}.
     *
     * @param plainName the display name with its colour codes removed
     * @param id        the SkyBlock id, or {@code null}/empty for none
     */
    public static Tip itemTip(String plainName, String id) {
        String name = plainName == null ? "" : plainName.trim();
        Matcher pet = PET.matcher(name);
        if (pet.find()) {
            return new Tip(StackTipKind.PET, String.valueOf(Integer.parseInt(pet.group(1))));
        }
        if (id != null && !id.isEmpty()) {
            Matcher minion = MINION_ID.matcher(id);
            if (minion.matches()) {
                return positive(StackTipKind.MINION, Integer.parseInt(minion.group(1)));
            }
            Matcher pass = PASS_ID.matcher(id);
            if (pass.matches()) {
                return new Tip(StackTipKind.DUNGEON_PASS,
                        (pass.group(1) != null ? "M" : "") + Integer.parseInt(pass.group(2)));
            }
            return null;
        }
        // No id at all (a menu's display copy): fall back to the verified minion name shape.
        Matcher minionName = MINION_NAME.matcher(name);
        if (minionName.matches()) {
            return positive(StackTipKind.MINION, roman(minionName.group(1)));
        }
        return null;
    }

    /**
     * The tip that only means something inside the open menu - a skill level in the Skills menu, a
     * collection tier in a collection menu - or {@code null}.
     *
     * @param menuTitle the open menu's plain title, any case
     */
    public static Tip menuTip(String plainName, String menuTitle) {
        if (plainName == null || menuTitle == null) {
            return null;
        }
        String name = plainName.trim();
        String title = menuTitle.trim().toLowerCase(Locale.ROOT);
        if (isSkillsMenu(title)) {
            Matcher skill = SKILL_NAME.matcher(name);
            return skill.matches() ? positive(StackTipKind.SKILL, level(skill.group(2))) : null;
        }
        if (isCollectionMenu(title)) {
            Matcher tier = COLLECTION_NAME.matcher(name);
            return tier.matches() ? positive(StackTipKind.COLLECTION, roman(tier.group(2))) : null;
        }
        return null;
    }

    static boolean isSkillsMenu(String lowerTitle) {
        return lowerTitle.equals("your skills");
    }

    /** "Farming Collection", "Mining Collections" - not the top "Collections" category picker. */
    static boolean isCollectionMenu(String lowerTitle) {
        return lowerTitle.endsWith(" collection") || lowerTitle.endsWith(" collections");
    }

    private static Tip positive(StackTipKind kind, int value) {
        return value > 0 ? new Tip(kind, String.valueOf(value)) : null;
    }

    private static int level(String raw) {
        return Character.isDigit(raw.charAt(0)) ? Integer.parseInt(raw) : roman(raw);
    }

    /** Standard roman numerals up to L; anything malformed is 0, which draws nothing. */
    static int roman(String numeral) {
        int total = 0;
        int previous = 0;
        for (int i = numeral.length() - 1; i >= 0; i--) {
            int value = switch (numeral.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                default -> -1;
            };
            if (value < 0) {
                return 0;
            }
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }
}
