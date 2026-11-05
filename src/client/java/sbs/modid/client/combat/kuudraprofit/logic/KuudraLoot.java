/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudraprofit.logic;

import sbs.modid.client.core.loot.RewardBlockReader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Kuudra loot chat lines into {@link Event}s. Pure: colour-stripped text in, events out.
 *
 * <p>The shapes, colour-stripped (CONFIRMED in the maintainer's 1.8.9 logs):
 * <pre>
 *   PAID CHEST REWARDS                header, two spaces (158x in the logs)
 *   FREE CHEST REWARDS                (8x)
 *     Crimson Essence x1,200          item, four spaces - the dungeon block's shape
 * You bought Infernal Kuudra Key!     (432x; "Fiery" 102x; Kuudra / Hot / Burning by the same pattern)
 * KUUDRA DOWN!                        a won run
 * DEFEAT                              a lost run
 * </pre>
 * A purchase line ending in {@code " for N Coins!"} is also read, in case an NPC prints the price;
 * no log has shown one yet. The trailing {@code " (N)"} stacking counter is stripped first.
 */
public final class KuudraLoot {

    /** Everything this parser reports. */
    public sealed interface Event permits RewardBlock, KeyBought, RunEnded {
    }

    /** A claimed chest. {@code paid} is the one opened with a key; the free one costs nothing. */
    public record RewardBlock(boolean paid, List<RewardBlockReader.Item> items) implements Event {
    }

    /** A Kuudra key bought; {@code price} is the coins the line named, or {@code null}. */
    public record KeyBought(int tier, int count, Long price) implements Event {
    }

    /** {@code KUUDRA DOWN!} ({@code won}) or {@code DEFEAT}. */
    public record RunEnded(boolean won) implements Event {
    }

    private static final Pattern KEY = Pattern.compile(
            "^You bought (?:(Hot|Burning|Fiery|Infernal) )?Kuudra Key(?: x([0-9,]+))?(?: for ([0-9,]+) Coins)?!$");

    private final RewardBlockReader blocks =
            new RewardBlockReader(word -> word.equals("PAID") || word.equals("FREE"));

    /**
     * Feeds one colour-stripped, untrimmed line.
     *
     * @return the events this line completes, usually none or one
     */
    public List<Event> accept(String plain) {
        if (plain == null) {
            return List.of();
        }
        String line = RewardBlockReader.stripCounter(plain);
        List<Event> out = new ArrayList<>(1);
        List<RewardBlockReader.Block> closed = new ArrayList<>(1);
        boolean consumed = blocks.accept(line, closed);
        convert(closed, out);
        if (consumed) {
            return out;
        }
        String trimmed = line.trim();
        if (trimmed.equals("KUUDRA DOWN!")) {
            out.add(new RunEnded(true));
        } else if (trimmed.equals("DEFEAT")) {
            out.add(new RunEnded(false));
        } else {
            Matcher key = KEY.matcher(trimmed);
            if (key.matches()) {
                int count = key.group(2) == null ? 1 : (int) Math.max(1, RewardBlockReader.number(key.group(2)));
                Long price = key.group(3) == null ? null : RewardBlockReader.number(key.group(3));
                out.add(new KeyBought(tier(key.group(1)), count, price));
            }
        }
        return out;
    }

    /** Ends an open reward block (an idle timeout or a world change). */
    public List<Event> flush() {
        List<Event> out = new ArrayList<>(1);
        List<RewardBlockReader.Block> closed = new ArrayList<>(1);
        blocks.flush(closed);
        convert(closed, out);
        return out;
    }

    public boolean blockOpen() {
        return blocks.open();
    }

    private static void convert(List<RewardBlockReader.Block> closed, List<Event> out) {
        for (RewardBlockReader.Block block : closed) {
            out.add(new RewardBlock(block.header().equals("PAID"), block.items()));
        }
    }

    /** The key's tier from its prefix word; no prefix is the tier 1 {@code Kuudra Key}. */
    static int tier(String prefix) {
        if (prefix == null) {
            return 1;
        }
        return switch (prefix) {
            case "Hot" -> 2;
            case "Burning" -> 3;
            case "Fiery" -> 4;
            case "Infernal" -> 5;
            default -> 0;
        };
    }

    /** The prefilter's own test, kept here so the dispatcher test can check the prefilter against it. */
    public static boolean candidate(String plain) {
        if (plain == null) {
            return false;
        }
        return plain.isEmpty() || plain.charAt(0) == ' ' || plain.contains("Kuudra Key")
                || plain.contains("KUUDRA DOWN!") || plain.contains("DEFEAT");
    }
}
