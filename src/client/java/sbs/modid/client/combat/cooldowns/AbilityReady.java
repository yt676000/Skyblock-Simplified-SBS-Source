/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.cooldowns;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ability Ready Alert, the decisions only: which chat lines mean "an ability was used" and "it is
 * ready again", and what cooldown that pair teaches. No Minecraft types, so it is unit tested; the
 * alert, the config and the HUD live in {@link AbilityReadyAlert}.
 *
 * <p>Both lines are Hypixel's own and were read in the play instance's logs (2026-09-27):
 * <pre>
 * You used your Pickobulus Pickaxe Ability!
 * Your Pickobulus destroyed 30 blocks!
 * Pickobulus is now available!            (58s after the use line)
 * Guided Sheep is now available!          (71 times, never with a use line)
 * </pre>
 * No cooldown length is ever assumed - they depend on Heart of the Mountain and gear. A cooldown is
 * only known once the gap between a use line and the next "now available" line has been seen.
 */
public final class AbilityReady {

    /**
     * The ready line. Anchored at both ends over colour-stripped text, and the name may not hold a
     * colon or a bracket, so a player or party member typing the phrase ("Name: Pickobulus is now
     * available!", "Party > [MVP+] Name: ...") never matches.
     */
    static final Pattern READY = Pattern.compile("^([A-Z][A-Za-z' -]{1,40}) is now available!$");

    /**
     * The use line. "Pickaxe" is verified; "Axe" is the same shape Hypixel is expected to print for
     * an axe ability and is NOT verified - an unmatched axe use only costs the learned countdown,
     * never the ready alert, which comes from {@link #READY} alone.
     */
    static final Pattern USE = Pattern.compile("^You used your ([A-Z][A-Za-z' -]{1,40}) (Pickaxe|Axe) Ability!$");

    /**
     * Heart of the Mountain pickaxe abilities, on by default even when their first sighting is the
     * ready line rather than a use line. Names from the wiki (WIKI); only Pickobulus is CONFIRMED.
     */
    static final Set<String> MINING_ABILITIES = Set.of("Mining Speed Boost", "Pickobulus",
            "Maniac Miner", "Vein Seeker", "Gemstone Infusion", "Sheer Force", "Anomalous Desire");

    /** A use older than this is not paired with a ready line: the pair would teach nonsense. */
    static final long MAX_PAIR_MS = 10 * 60_000L;

    /** What one chat line meant. */
    public sealed interface Event permits Used, Ready {
    }

    /** "You used your X ... Ability!" - {@code tool} is "pickaxe" or "axe". */
    public record Used(String ability, String tool) implements Event {
    }

    /** "X is now available!" - {@code learnedSeconds} is the cooldown this line taught, or -1. */
    public record Ready(String ability, int learnedSeconds) implements Event {
    }

    private final Map<String, Long> usedAt = new ConcurrentHashMap<>();

    /** Parses one colour-stripped chat line; {@code null} when it is not one of ours. */
    public Event onChat(String line, long nowMs) {
        if (line == null) {
            return null;
        }
        String text = line.replaceAll("§.", "").strip();
        Matcher use = USE.matcher(text);
        if (use.matches()) {
            String ability = use.group(1).strip();
            usedAt.put(ability, nowMs);
            return new Used(ability, use.group(2).toLowerCase(Locale.ROOT));
        }
        Matcher ready = READY.matcher(text);
        if (ready.matches()) {
            String ability = ready.group(1).strip();
            Long used = usedAt.remove(ability);
            int learned = -1;
            if (used != null && nowMs - used > 0 && nowMs - used <= MAX_PAIR_MS) {
                learned = (int) Math.round((nowMs - used) / 1000.0);
            }
            return new Ready(ability, learned);
        }
        return null;
    }

    /** When {@code ability} was last used and has not been ready since, or -1. */
    public long usedAt(String ability) {
        Long t = usedAt.get(ability);
        return t == null ? -1 : t;
    }

    /** Every ability used and still cooling down. */
    public Set<String> cooling() {
        return usedAt.keySet();
    }

    /** Whether a newly seen ability starts switched on: mining and axe abilities yes, others no. */
    public static boolean defaultOn(String ability, String tool) {
        return tool != null || MINING_ABILITIES.contains(ability);
    }

    /**
     * The countdown line: "ready", "in 42s", or "?" while the cooldown has never been learned.
     *
     * @param usedAt  when it was last used, or -1 when it has been ready since
     * @param learned the learned cooldown in seconds, or -1
     */
    public static String status(long usedAt, int learned, long nowMs) {
        if (usedAt < 0 || nowMs - usedAt > MAX_PAIR_MS) {
            return "ready";
        }
        if (learned <= 0) {
            return "?";
        }
        long left = (usedAt + learned * 1000L - nowMs + 999) / 1000;
        return left <= 0 ? "ready" : "in " + left + "s";
    }
}
