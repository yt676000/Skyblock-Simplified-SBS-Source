/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.cooldowns;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an axe's ability blocks from its lore. Pure, so it is tested on captured lore.
 *
 * <p>Two real axes from the layout scans (menus {@code foraging-recipes}, {@code hunting-recipes},
 * 2026-09-25) set the shapes:
 * <pre>
 * Fig Hew:  "Ability: Frenzy"                (no trigger, no cooldown - passive)
 * Huntaxe:  "Ability: Absorptio  RIGHT CLICK" (a trigger, but no "Cooldown:" line)
 *           "Ability: Vis Temperata"          (passive)
 * type line "UNCOMMON AXE", "LEGENDARY AXE"
 * </pre>
 * Neither has a {@code Cooldown: Ns} line, so neither gets a timer. The cooldown shape is the one
 * {@link AbilityCooldowns} already reads from other items.
 */
public final class AxeAbilityLore {

    /** "Ability: <name>" with an optional upper-case trigger after two or more spaces. */
    private static final Pattern ABILITY = Pattern.compile(
            "^Ability: (.+?)(?:\\s{2,}([A-Z][A-Z ]*[A-Z]))?$");
    private static final Pattern COOLDOWN = Pattern.compile("(?i)^Cooldown:\\s*([0-9]+)s");
    /** The rarity + type line: "UNCOMMON AXE", "LEGENDARY AXE", "RARE DUNGEON AXE"... */
    private static final Pattern TYPE = Pattern.compile("^(?:[A-Z]+ )+([A-Z]+)$");

    /** One ability block. {@code trigger} is empty for a passive one; {@code cooldown} is -1 when none. */
    public record Ability(String name, String trigger, int cooldownSeconds) {

        /** Whether this is an ability that can be ready again: triggered, with a lore cooldown. */
        public boolean timed() {
            return !trigger.isEmpty() && cooldownSeconds > 0;
        }
    }

    private AxeAbilityLore() {
    }

    /** Whether the lore's type line ends in {@code AXE} (a pickaxe's ends in {@code PICKAXE}). */
    public static boolean isAxe(List<String> lore) {
        for (int i = lore.size() - 1; i >= 0; i--) {
            Matcher type = TYPE.matcher(strip(lore.get(i)));
            if (type.matches()) {
                return type.group(1).equals("AXE");
            }
        }
        return false;
    }

    /** Every ability block, in lore order. */
    public static List<Ability> abilities(List<String> lore) {
        List<Ability> out = new ArrayList<>();
        for (int i = 0; i < lore.size(); i++) {
            Matcher ability = ABILITY.matcher(strip(lore.get(i)));
            if (!ability.matches()) {
                continue;
            }
            int cooldown = -1;
            for (int j = i + 1; j < lore.size() && !strip(lore.get(j)).isEmpty(); j++) {
                Matcher cd = COOLDOWN.matcher(strip(lore.get(j)));
                if (cd.find()) {
                    cooldown = Integer.parseInt(cd.group(1));
                    break;
                }
            }
            String trigger = ability.group(2) == null ? "" : ability.group(2).toUpperCase(Locale.ROOT);
            out.add(new Ability(ability.group(1).strip(), trigger, cooldown));
        }
        return out;
    }

    private static String strip(String line) {
        return line == null ? "" : line.replaceAll("§.", "").strip();
    }
}
