/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.buffs.consumables.logic;

import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.buffs.BuffDuration;
import sbs.modid.client.helper.buffs.consumables.model.ConsumableKind;
import sbs.modid.client.helper.buffs.consumables.model.ConsumableTimer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the chat lines that start, report on and end a timed consumable. Pure: one line in, one
 * reading (or {@code null}) out, tested on the lines copied from the play logs.
 *
 * <p>Every line below was seen as a server message (colour-stripped):
 * <ul>
 *   <li>{@code GULP! The God Potion grants you powers for 28h 48m!} - also {@code SIP!}, {@code SLURP!}</li>
 *   <li>{@code SCHLURP! The Glowing Mush Mixin grants you effects for 86h 24m! They will pause if your
 *       God Potion expires.}</li>
 *   <li>{@code You consumed a Booster Cookie! Savory!} - no duration in it</li>
 *   <li>{@code You consumed a Refined Dark Cacao Truffle and gained +30☘ Global Fortune for 60m!}</li>
 *   <li>{@code BUFF! You have gained Critical III! Press TAB or type /effects to view your active effects!}</li>
 *   <li>{@code God Potion expires in 30 Minutes}, {@code Booster Cookie expires in 60s}</li>
 *   <li>{@code Pest Repellent MAX has expired!}</li>
 * </ul>
 *
 * <p>Every pattern is anchored at the start: a player quoting a line in chat ({@code [78] [VIP]
 * name: Booster Cookie expires in 60s}) starts with their own prefix and matches nothing.
 */
public final class ConsumableLines {

    /** A reading of one line. */
    public sealed interface Reading permits Consumed, ExpiresIn, Expired, EffectGained {
    }

    /**
     * Something was consumed. {@code durationMs} is {@link ConsumableTimer#UNKNOWN} when neither
     * the line nor a known fixed length gives one; {@code stated} says the line itself printed it.
     */
    public record Consumed(String name, ConsumableKind kind, long durationMs, long precisionMs,
                           boolean stated, boolean stacks, String detail) implements Reading {
    }

    /** The server's own countdown warning: an exact remaining time. */
    public record ExpiresIn(String name, long remainingMs, long precisionMs) implements Reading {
    }

    /** The server says it ran out. */
    public record Expired(String name) implements Reading {
    }

    /** A potion effect was gained; the line never carries its duration. */
    public record EffectGained(String name) implements Reading {
    }

    /**
     * A Booster Cookie's length. The consume line does not print it; Elizabeth's own line in game
     * says "grants you perks ... for 4 days". The footer replaces this within two seconds.
     */
    static final long COOKIE_MS = 4L * 86_400_000L;

    private static final Pattern GOD_POTION =
            Pattern.compile("^[A-Z]+! The God Potion grants you powers for (.+?)!$");
    private static final Pattern MIXIN = Pattern.compile(
            "^[A-Z]+! The (.+?) grants you effects for (.+?)! They will pause if your God Potion expires\\.?$");
    private static final Pattern COOKIE = Pattern.compile("^You consumed a Booster Cookie!.*$");
    private static final Pattern CONSUMED_FOR =
            Pattern.compile("^You consumed an? (.+?) and gained (.+) for (.+?)!$");
    private static final Pattern BUFF = Pattern.compile("^BUFF! You have gained (.+?)! Press TAB\\b.*$");
    private static final Pattern EXPIRES_IN =
            Pattern.compile("^([A-Z][A-Za-z0-9' ]{1,40}?) expires in (.+)$");
    private static final Pattern EXPIRED = Pattern.compile("^([A-Z][A-Za-z0-9' ]{1,40}?) has expired!$");
    /** The repeat counter chat stacking appends: {@code (x2)}. */
    private static final Pattern REPEAT = Pattern.compile("\\s*\\(x\\d+\\)$");

    private ConsumableLines() {
    }

    /** The reading of {@code raw} (colour codes allowed), or {@code null} for any other line. */
    public static Reading parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String line = REPEAT.matcher(PlainText.strip(raw).trim()).replaceAll("");
        if (line.isEmpty()) {
            return null;
        }
        Matcher m = GOD_POTION.matcher(line);
        if (m.matches()) {
            BuffDuration.Parsed d = BuffDuration.parse(m.group(1));
            return d == null ? null
                    : new Consumed("God Potion", ConsumableKind.GOD_POTION, d.millis(), d.precisionMs(),
                            true, true, null);
        }
        m = MIXIN.matcher(line);
        if (m.matches()) {
            BuffDuration.Parsed d = BuffDuration.parse(m.group(2));
            return d == null ? null
                    : new Consumed(m.group(1), ConsumableKind.MIXIN, d.millis(), d.precisionMs(),
                            true, true, null);
        }
        if (COOKIE.matcher(line).matches()) {
            return new Consumed("Booster Cookie", ConsumableKind.BOOSTER_COOKIE, COOKIE_MS, 86_400_000L,
                    false, true, null);
        }
        m = CONSUMED_FOR.matcher(line);
        if (m.matches()) {
            BuffDuration.Parsed d = BuffDuration.parse(m.group(3));
            return d == null ? null
                    : new Consumed(m.group(1), ConsumableKind.OTHER, d.millis(), d.precisionMs(),
                            true, false, m.group(2).trim());
        }
        m = BUFF.matcher(line);
        if (m.matches()) {
            return new EffectGained(m.group(1).trim());
        }
        m = EXPIRES_IN.matcher(line);
        if (m.matches()) {
            BuffDuration.Parsed d = BuffDuration.parse(m.group(2));
            return d == null ? null : new ExpiresIn(m.group(1).trim(), d.millis(), d.precisionMs());
        }
        m = EXPIRED.matcher(line);
        if (m.matches()) {
            return new Expired(m.group(1).trim());
        }
        return null;
    }

    /** "Duration: 24h 0m +12h default ..." - the pairs straight after the label. */
    private static final Pattern LORE_DURATION = Pattern.compile(
            "(?i)\\bduration:\\s*((?:\\d+\\s*(?:years?|months?|weeks?|days?|hours?|hrs?|minutes?|mins?"
                    + "|seconds?|secs?|[ywdhms])\\b\\s*)+|\\d{1,3}:\\d{2}(?::\\d{2})?)");
    /** "Smoldering Polarization I (00:30:00)" - a bracketed clock. */
    private static final Pattern LORE_CLOCK = Pattern.compile("\\((\\d{1,3}:\\d{2}(?::\\d{2})?)\\)");
    /** Any time-looking fragment, for deciding what the use capture logs. */
    private static final Pattern TIME_FRAGMENT =
            Pattern.compile("(?i)duration|\\b\\d+\\s*(?:s|m|h|d|sec|min|mins|hours?|minutes?|days?)\\b|\\d+:\\d{2}");

    /**
     * The duration an item's lore states (bundled lore data: {@code Duration: 12h 0m},
     * {@code Duration: 30m}, {@code Duration: 3 minutes}, {@code (00:30:00)}), or {@code null}.
     * An estimate: modifiers the player has (alchemy level, accessories) are not in it.
     */
    public static BuffDuration.Parsed loreDuration(java.util.List<String> lore) {
        if (lore == null) {
            return null;
        }
        for (String raw : lore) {
            String line = PlainText.strip(raw == null ? "" : raw);
            Matcher m = LORE_DURATION.matcher(line);
            if (m.find()) {
                BuffDuration.Parsed d = BuffDuration.parse(m.group(1).trim());
                if (d != null) {
                    return d;
                }
            }
        }
        for (String raw : lore) {
            Matcher m = LORE_CLOCK.matcher(PlainText.strip(raw == null ? "" : raw));
            if (m.find()) {
                BuffDuration.Parsed d = BuffDuration.parse(m.group(1));
                if (d != null) {
                    return d;
                }
            }
        }
        return null;
    }

    /** Whether a lore line mentions a time or a duration (what the use capture records). */
    public static boolean mentionsTime(String loreLine) {
        return loreLine != null && TIME_FRAGMENT.matcher(PlainText.strip(loreLine)).find();
    }

    /**
     * Whether a line looks like it belongs here but did not parse - a wording change worth logging.
     * Kept narrow on purpose: the God Potion / mixin shape and the expiry warning with an unreadable
     * time.
     */
    public static boolean looksRelated(String raw) {
        if (raw == null) {
            return false;
        }
        String line = PlainText.strip(raw);
        return line.contains("grants you powers for") || line.contains("grants you effects for")
                || (EXPIRES_IN.matcher(line.trim()).matches() && parse(raw) == null);
    }
}
