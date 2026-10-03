/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.pests;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat shapes the pest profit tracker reads, as pure string parsing.
 *
 * <p><b>Verified</b> from the play-instance logs: "RARE DROP! &lt;Item&gt; (+N ✯ Magic Find)" (combat
 * drops), "[Sacks] +N items. (Last Ns.)" with N from 5 to 30, and the hover lines
 * "+N Item (Some Sack)" the other sack readers already parse.
 *
 * <p><b>ESTIMATED</b>: the pest kill line. No pest was killed on that instance, so {@link #killedPest}
 * accepts any line with a kill word and a pest name, and the tracker logs every line near a kill
 * under {@code [SBS][PestDrops]} so the real wording can replace this.
 *
 * <p><b>Pest Traps.</b> Verified: the Pests widget's rows "Pest Traps: 0/3", "Full Traps: None" and
 * "No Bait: None". ESTIMATED, nothing seen yet: the collection line ({@link #trapCollected}), a line
 * naming a caught pest ({@link #trapCaught}), the trap menu's title and contents
 * ({@link #isTrapMenu}, {@link #menuPests}) and the "Full Traps" value when it is not "None"
 * ({@link #fullTrapCount}). The tracker logs all of it under {@code [SBS][PestTraps]}.
 */
public final class PestChat {

    /** Hypixel's pests. Longest first, so "Field Mouse" wins over "Mouse". */
    public static final List<String> PESTS = List.of("Praying Mantis", "Field Mouse", "Dragonfly",
            "Earthworm", "Mosquito", "Firefly", "Cricket", "Beetle", "Locust", "Mouse", "Mite",
            "Moth", "Slug", "Rat", "Fly");

    private static final Pattern RARE_DROP = Pattern.compile(
            "^(?:CRAZY |VERY )?(?:RARE|INSANE|PET) DROP! (?:\\((\\d+)x\\) )?(.+?)(?: \\(\\+.*\\))?$");
    private static final Pattern SACK_LINE = Pattern.compile("^\\s*([+-][\\d,]+) (.+?) \\((.+)\\)\\s*$");
    private static final Pattern SACK_PERIOD = Pattern.compile("\\(Last (\\d+)s\\.\\)");
    private static final Pattern KILL_WORD = Pattern.compile(
            "(?i)\\b(?:killed|kill|slain|squashed|vacuumed|exterminated)\\b");

    /**
     * Base crops a sack message carries while you farm. A sack batch near a kill also holds the crops
     * you broke in that period, and nothing in the message tells them apart, so these are left out
     * of pest drops. A pest's own drops are expected to be enchanted forms and rare items.
     */
    public static final Set<String> FARMED_ITEMS = Set.of("WHEAT", "SEEDS", "CARROT_ITEM",
            "POTATO_ITEM", "POISONOUS_POTATO", "NETHER_STALK", "SUGAR_CANE", "MELON", "PUMPKIN",
            "INK_SACK:3", "CACTUS", "RED_MUSHROOM", "BROWN_MUSHROOM", "MUSHROOM_COLLECTION",
            "DOUBLE_PLANT", "MOONFLOWER", "WILD_ROSE");

    private static final Pattern TRAP_WORD = Pattern.compile("(?i)\\btraps?\\b");
    private static final Pattern COLLECT_WORD = Pattern.compile(
            "(?i)\\b(?:collect\\w*|claim\\w*|empt\\w*|loot\\w*|harvest\\w*|retriev\\w*)\\b");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private PestChat() {
    }

    /** A pest and how many, as a trap line or menu names it. */
    public record Caught(String pest, int count) {
    }

    /** Player chat or a market line, never a game event about your traps. */
    private static boolean notTrapEvent(String plain) {
        return plain.contains(": ") || plain.startsWith("[Bazaar]") || plain.startsWith("[Auction")
                || plain.startsWith("[Sacks]") || plain.startsWith("[NPC]");
    }

    /** ESTIMATED: a line saying a Pest Trap was emptied. */
    public static boolean trapCollected(String plain) {
        return !notTrapEvent(plain) && TRAP_WORD.matcher(plain).find() && COLLECT_WORD.matcher(plain).find();
    }

    /** ESTIMATED: a trap line naming the pest it caught ("... trap caught 2x Rat"), or null. */
    public static Caught trapCaught(String plain) {
        if (notTrapEvent(plain) || !TRAP_WORD.matcher(plain).find()) {
            return null;
        }
        return pestWithCount(plain);
    }

    /** The first pest a text names, with the number written right before it (1 when none). */
    private static Caught pestWithCount(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String pest : PESTS) {
            Matcher m = Pattern.compile("(?:(\\d+)\\s*x?\\s+)?\\b" + pest.toLowerCase(Locale.ROOT) + "e?s?\\b")
                    .matcher(lower);
            if (m.find()) {
                return new Caught(pest, m.group(1) == null ? 1 : Integer.parseInt(m.group(1)));
            }
        }
        return null;
    }

    /** ESTIMATED: whether a container title is a Pest Trap's menu. */
    public static boolean isTrapMenu(String title) {
        return title != null && TRAP_WORD.matcher(title.replaceAll("§.", "")).find();
    }

    /**
     * ESTIMATED: the pests a trap menu lists, from each item's name and stack count. An item whose
     * name is a pest ("Rat", "Beetle x2") is a caught pest; anything else is not counted.
     */
    public static Map<String, Integer> menuPests(List<Caught> items) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Caught item : items) {
            String name = item.pest() == null ? "" : item.pest().replaceAll("§.", "").trim();
            for (String pest : PESTS) {
                if (name.equalsIgnoreCase(pest) || name.matches("(?i)" + pest + "\\s*x\\d+")) {
                    Matcher n = Pattern.compile("x(\\d+)$").matcher(name);
                    int count = n.find() ? Integer.parseInt(n.group(1)) : Math.max(1, item.count());
                    out.merge(pest, count, Integer::sum);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * The number of full traps in the widget's "Full Traps:" value: 0 for "None" (verified), else
     * ESTIMATED as "N/3" (first number) or a comma list ("#1, #3", one entry each). -1 when unreadable.
     */
    public static int fullTrapCount(String value) {
        if (value == null) {
            return -1;
        }
        String v = value.replaceAll("§.", "").trim();
        if (v.isEmpty()) {
            return -1;
        }
        if (v.equalsIgnoreCase("none")) {
            return 0;
        }
        if (v.contains("/")) {
            Matcher m = DIGITS.matcher(v);
            return m.find() ? Integer.parseInt(m.group()) : -1;
        }
        int n = 0;
        for (String part : v.split(",")) {
            if (!part.isBlank()) {
                n++;
            }
        }
        return n;
    }

    /** A dropped item, by display name as chat shows it. */
    public record Drop(String name, long amount) {
    }

    /** The drop on a RARE DROP line, or null. {@code plain} has no § codes. */
    public static Drop rareDrop(String plain) {
        Matcher m = RARE_DROP.matcher(plain.trim());
        if (!m.find()) {
            return null;
        }
        long amount = m.group(1) == null ? 1 : Long.parseLong(m.group(1));
        return new Drop(m.group(2).trim(), amount);
    }

    /** The seconds a "[Sacks]" line covers, or -1 when it is not one. */
    public static int sackPeriod(String plain) {
        if (!plain.startsWith("[Sacks]")) {
            return -1;
        }
        Matcher m = SACK_PERIOD.matcher(plain);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * The gains in a sack hover text, one per item name. The hover can list an item once per sack and
     * can arrive twice, so each (item, sack) pair is taken once, by its largest amount.
     */
    public static Map<String, Long> sackGains(String hover) {
        Map<String, Long> perItemSack = new LinkedHashMap<>();
        for (String line : hover.split("\n")) {
            Matcher m = SACK_LINE.matcher(line.replaceAll("§.", ""));
            if (!m.find() || m.group(1).startsWith("-")) {
                continue;
            }
            long amount = Long.parseLong(m.group(1).replaceAll("[^0-9]", ""));
            perItemSack.merge(m.group(2) + "|" + m.group(3), amount, Math::max);
        }
        Map<String, Long> perItem = new LinkedHashMap<>();
        perItemSack.forEach((key, amount) -> perItem.merge(key.substring(0, key.indexOf('|')), amount, Long::sum));
        return perItem;
    }

    /** ESTIMATED: the pest a kill line names, or null. */
    public static String killedPest(String plain) {
        // Player chat ("Party > Name: killed a rat") is never a game event.
        if (!KILL_WORD.matcher(plain).find() || plain.startsWith("[Sacks]") || plain.contains(": ")) {
            return null;
        }
        String lower = plain.toLowerCase(Locale.ROOT);
        for (String pest : PESTS) {
            if (Pattern.compile("\\b" + pest.toLowerCase(Locale.ROOT) + "\\b").matcher(lower).find()) {
                return pest;
            }
        }
        return null;
    }
}
