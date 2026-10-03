/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one visible page of the Heart of the Mountain menu into facts. Pure: slot index, stripped
 * name and stripped lore in, no game types anywhere, so it is tested on the real logged tooltips.
 *
 * <p><b>The shapes below are the menu's own</b> (play-instance log 2026-09-04-3, recorded in
 * {@code docs/skyblock-ui/menus.md}):
 * <ul>
 *   <li>A levelled perk leads with {@code Level N/MAX}, its effect, then - unless maxed - a
 *       {@code =====[ UPGRADE ]=====} block with {@code Level N+1/MAX} and the next effect, then
 *       {@code Cost} on its own line and the price on the next.</li>
 *   <li>A maxed perk prints {@code Level 50} with no {@code /MAX} and no cost.</li>
 *   <li>A <b>locked</b> perk still prints {@code Level 1/20} - a preview of level 1, not its level.
 *       Its cost is {@code 1 Token of the Mountain}, which is what says it is locked. The old reader
 *       took that first level line at face value and booked every locked perk at level 1.</li>
 *   <li>A locked perk that cannot be reached yet adds {@code Requires <perk>}; one that can says
 *       {@code You don't have enough Token of the Mountain!} or nothing.</li>
 *   <li>Column 0 holds {@code Tier N} labels. The view scrolls, so a perk's tier is the label in
 *       <i>its own row</i>, never a fixed slot number.</li>
 *   <li>Slot {@code Heart of the Mountain} (49) lists {@code Token of the Mountain: N} and each
 *       {@code <Kind> Powder: N} - the spendable totals, read straight from the menu.</li>
 * </ul>
 * Price text is only read after the {@code Cost} line: the Daily Powder description contains
 * {@code +5,000 Mithril Powder}, which the old free-floating pattern booked as a cost.
 */
public final class HotmMenuParser {

    private static final Pattern LEVEL = Pattern.compile("^Level (\\d{1,3})(?:/(\\d{1,3}))?$");
    private static final Pattern POWDER_COST =
            Pattern.compile("^([\\d,]+) (Mithril|Gemstone|Glacite) Powder$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN_COST = Pattern.compile("^\\d+ Token of the Mountain$");
    private static final Pattern REQUIRES = Pattern.compile("^Requires (.+)$");
    private static final Pattern TIER_LABEL = Pattern.compile("^Tier (\\d{1,2})$");
    private static final Pattern HEADER_POWDER =
            Pattern.compile("^(Mithril|Gemstone|Glacite) Powder: ([\\d,]+)$");
    private static final Pattern HEADER_TOKENS = Pattern.compile("^Token of the Mountain: (\\d+)$");
    private static final Pattern NUMBER = Pattern.compile("[+-]?[\\d,]*\\.?\\d+");

    private static final String UPGRADE = "=====[ UPGRADE ]=====";

    /** Chrome and info items that are not perks. */
    private static final List<String> NOT_PERKS = List.of(
            "Scroll Up", "Scroll Down", "Close", "Go Back", "Heart of the Mountain",
            "Heart of the Mountain Slot", "Crystal Hollows Crystals", "Crystal Nucleus RNG Meter",
            "Reset Heart of the Mountain");

    private HotmMenuParser() {
    }

    /** One menu slot as the reader saw it. */
    public record SlotText(int index, String name, List<String> lore) {
    }

    /**
     * One perk node on the page.
     *
     * @param level     the real level: 0 when locked, even though the lore shows "Level 1/.."
     * @param maxLevel  from {@code /MAX}; for a maxed perk, its own level; 1 for single-level nodes
     * @param nextCost  powder for the next level as the menu printed it, or 0
     * @param requires  the perk named by a {@code Requires} line, or {@code null} when reachable
     * @param nowValue  the first number of the current effect line, or NaN
     * @param nextValue the first number of the upgrade effect line, or NaN
     */
    public record Node(String name, int slot, int tier, int column, int level, int maxLevel,
                       long nextCost, String nextPowder, String requires,
                       double nowValue, double nextValue) {

        public boolean owned() {
            return level > 0;
        }
    }

    /** Everything one page gave up. */
    public record Page(List<Node> nodes, Map<String, Long> powder, int tokens, int unlockedTier,
                       int highestTierSeen) {

        public static final Page EMPTY = new Page(List.of(), Map.of(), -1, 0, 0);
    }

    public static Page parse(List<SlotText> slots) {
        Map<Integer, Integer> tierByRow = new LinkedHashMap<>();
        int unlockedTier = 0;
        int highestSeen = 0;
        for (SlotText slot : slots) {
            Matcher tier = TIER_LABEL.matcher(slot.name().trim());
            if (slot.index() % 9 == 0 && tier.matches()) {
                int number = Integer.parseInt(tier.group(1));
                tierByRow.put(slot.index() / 9, number);
                highestSeen = Math.max(highestSeen, number);
                if (slot.lore().stream().anyMatch(line -> line.trim().equals("UNLOCKED"))) {
                    unlockedTier = Math.max(unlockedTier, number);
                }
            }
        }

        Map<String, Long> powder = new LinkedHashMap<>();
        int tokens = -1;
        List<Node> nodes = new ArrayList<>();
        for (SlotText slot : slots) {
            String name = slot.name().trim();
            if (name.equals("Heart of the Mountain")) {
                for (String line : slot.lore()) {
                    Matcher p = HEADER_POWDER.matcher(line.trim());
                    if (p.matches()) {
                        powder.put(p.group(1).toUpperCase(Locale.ROOT), amount(p.group(2)));
                    }
                    Matcher t = HEADER_TOKENS.matcher(line.trim());
                    if (t.matches()) {
                        tokens = Integer.parseInt(t.group(1));
                    }
                }
                continue;
            }
            int column = slot.index() % 9;
            Integer tier = tierByRow.get(slot.index() / 9);
            if (name.isEmpty() || column == 0 || column == 8 || tier == null || NOT_PERKS.contains(name)) {
                continue;
            }
            nodes.add(node(name, slot.index(), tier, column, slot.lore()));
        }
        return new Page(List.copyOf(nodes), Map.copyOf(powder), tokens, unlockedTier, highestSeen);
    }

    static Node node(String name, int slot, int tier, int column, List<String> lore) {
        int shownLevel = -1;
        int max = -1;
        boolean afterUpgrade = false;
        boolean afterCost = false;
        boolean tokenCost = false;
        boolean enabledState = false;
        long cost = 0;
        String powder = "";
        String requires = null;
        double now = Double.NaN;
        double next = Double.NaN;

        for (String raw : lore) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equals(UPGRADE)) {
                afterUpgrade = true;
                continue;
            }
            if (line.equals("Cost")) {
                afterCost = true;
                continue;
            }
            Matcher level = LEVEL.matcher(line);
            if (level.matches()) {
                if (!afterUpgrade && shownLevel < 0) {
                    shownLevel = Integer.parseInt(level.group(1));
                    max = level.group(2) == null ? shownLevel : Integer.parseInt(level.group(2));
                }
                continue;
            }
            if (afterCost) {
                Matcher p = POWDER_COST.matcher(line);
                if (p.matches() && cost == 0) {
                    cost = amount(p.group(1));
                    powder = p.group(2).toUpperCase(Locale.ROOT);
                    continue;
                }
                if (TOKEN_COST.matcher(line).matches()) {
                    tokenCost = true;
                    continue;
                }
            }
            Matcher req = REQUIRES.matcher(line);
            if (req.matches()) {
                requires = req.group(1).trim();
                continue;
            }
            if (line.equals("ENABLED") || line.equals("DISABLED") || line.equals("SELECTED")
                    || line.startsWith("Click to select") || line.equals("UNLOCKED")) {
                enabledState = true;
                continue;
            }
            // The first number-bearing line of each block is its effect.
            if (shownLevel >= 0 && !afterCost) {
                Matcher number = NUMBER.matcher(line);
                if (number.find()) {
                    double value = amountDouble(number.group());
                    if (!afterUpgrade && Double.isNaN(now)) {
                        now = value;
                    } else if (afterUpgrade && Double.isNaN(next)) {
                        next = value;
                    }
                }
            }
        }

        int levelNow;
        int maxLevel;
        if (shownLevel >= 0) {
            // A token price means locked: the "Level 1/N" shown is the preview, not the level.
            levelNow = tokenCost ? 0 : shownLevel;
            maxLevel = max;
        } else {
            // Single-level nodes (abilities, Sky Mall, Daily Powder...): owned when they show a state.
            levelNow = tokenCost || !enabledState ? 0 : 1;
            maxLevel = 1;
        }
        if (levelNow == 0) {
            // A locked perk's "now" line describes level 1; it is the value the unlock buys.
            next = now;
            now = Double.NaN;
            cost = 0;
            powder = "";
        }
        return new Node(name, slot, tier, column, levelNow, maxLevel, cost, powder,
                levelNow == 0 ? requires : null, now, next);
    }

    /**
     * Whether the perk's lore offers a powder upgrade right now. The menu prints
     * {@code Left-click to upgrade!} only on a step the player can pay for and
     * {@code You don't have enough <Kind> Powder!} otherwise (CONFIRMED, captures 2026-09-28) - the
     * game's own answer at this moment, which is why the highlight asks it rather than the cache.
     */
    public static boolean offersPowderUpgrade(List<String> lore) {
        for (String line : lore) {
            if (line.trim().equals("Left-click to upgrade!")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a locked perk's lore reads as unlockable now: a {@code 1 Token of the Mountain} price
     * after {@code Cost}, no {@code You don't have enough Token} line and no {@code Requires} line.
     * ESTIMATED: every capture so far was taken with no tokens, so the affirmative wording has never
     * been seen; this is the absence of every refusal that has been.
     */
    public static boolean offersTokenUnlock(List<String> lore) {
        boolean afterCost = false;
        boolean tokenCost = false;
        for (String raw : lore) {
            String line = raw.trim();
            if (line.equals("Cost")) {
                afterCost = true;
            } else if (afterCost && TOKEN_COST.matcher(line).matches()) {
                tokenCost = true;
            } else if (line.startsWith("You don't have enough") || REQUIRES.matcher(line).matches()) {
                return false;
            }
        }
        return tokenCost;
    }

    private static long amount(String raw) {
        return Long.parseLong(raw.replace(",", ""));
    }

    private static double amountDouble(String raw) {
        try {
            return Double.parseDouble(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
