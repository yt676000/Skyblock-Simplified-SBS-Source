/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.profit.logic;

import sbs.modid.client.core.loot.RewardBlockReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the dungeon loot chat lines into {@link Event}s. Pure: colour-stripped text in, events out,
 * no Minecraft classes, so every line shape is unit tested on the strings Hypixel prints.
 *
 * <p>The shapes, colour-stripped (1.8.9 logs; the reward block, the essence lines and the Kismet line
 * are also in the 26.2 client logs of 2026-06-18/19):
 * <pre>
 *   BEDROCK CHEST REWARDS                         header, two spaces
 *     Wither Essence x88                          item, four spaces
 *     RARE REWARD! Recombobulator 3000            a rare item inside the block
 * [MVP+] &lt;name&gt; unlocked Wither Essence x104!     a party broadcast of a chest item
 * ESSENCE! You found x10 Diamond Essence!         ("You" or a player name)
 * &lt;name&gt; found a Wither Essence! Everyone gains an extra essence!
 * RARE REWARD! &lt;name&gt; found a Recombobulator 3000 in their Bedrock Chest!
 * You used a Kismet Feather!
 * You bought Dungeon VII Potion for 33,950 Coins!      (1.8.9 logs, 307x)
 * You can no longer consume or splash any potions during the remainder of this Dungeon run!
 *                            Team Score: 305 (S+)
 * </pre>
 * A line repeated in a row may carry the client's stacking counter, {@code " (2)"}, which is
 * stripped before matching.
 *
 * <p><b>Privacy.</b> A player name is only ever compared against the player's own name and then
 * dropped: no event carries one, so nothing downstream can store a teammate's name.
 *
 * <p>The reward block spans lines - separate chat lines, or one message whose lines are joined by
 * {@code \n} (one component reaches the chat hook whole, however many lines it displays as). It is held open while four-space item lines follow and is
 * emitted on the first line that is not one, or by {@link #flush()} (the 26.2 client ends the block
 * with an empty line, and an idle timeout covers a block whose last line never arrives).
 */
public final class DungeonLoot {

    /** The chest a reward block came out of. Wood is free (CONFIRMED, reward chest preview menu). */
    public enum ChestKind {
        WOOD, GOLD, DIAMOND, EMERALD, OBSIDIAN, BEDROCK;

        /** {@code "Bedrock"} -> {@link #BEDROCK}; {@code null} for anything else. */
        public static ChestKind of(String word) {
            if (word == null) {
                return null;
            }
            try {
                return valueOf(word.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException notAChest) {
                return null;
            }
        }
    }

    /** One item line: {@code "Wither Essence x88"} -> ("Wither Essence", 88). */
    public record Item(String name, int count, boolean rare) {
    }

    /** Everything this parser reports. */
    public sealed interface Event permits RewardBlock, Unlocked, EssenceFound, RareReward, KismetUsed, TeamScore,
            PotionBought, PotionsLocked {
    }

    /** A claimed chest's whole contents, as the block listed them. */
    public record RewardBlock(ChestKind kind, List<Item> items) implements Event {
    }

    /** {@code "<name> unlocked <item>!"}; {@code own} says whether the name was the player's. */
    public record Unlocked(boolean own, String item, int count) implements Event {
    }

    /**
     * An essence line. {@code everyone} is the Wither Essence that every party member gains;
     * {@code own} is whether the player found it ({@code "You"} or their own name).
     */
    public record EssenceFound(String type, int count, boolean everyone, boolean own) implements Event {
    }

    /** {@code "RARE REWARD! <name> found a <item> in their <kind> Chest!"}. */
    public record RareReward(boolean own, String item, ChestKind kind) implements Event {
    }

    /** {@code "You used a Kismet Feather!"}. */
    public record KismetUsed() implements Event {
    }

    /**
     * {@code "You bought Dungeon VII Potion for 33,950 Coins!"}: {@code id} is the potion's
     * {@link PotionWatch#idOf id}, {@code coins} what the whole purchase cost.
     */
    public record PotionBought(String id, int count, long coins) implements Event {
    }

    /** {@code "You can no longer consume or splash any potions ..."}: the run has started. */
    public record PotionsLocked() implements Event {
    }

    /** {@code "Team Score: 305 (S+)"} from the run summary. */
    public record TeamScore(int score, String grade) implements Event {
    }

    private static final Pattern UNLOCKED =
            Pattern.compile("^(?:\\[[A-Z+]+\\] )?([A-Za-z0-9_]{1,16}) unlocked (.+)!$");
    private static final Pattern ESSENCE =
            Pattern.compile("^ESSENCE! ([A-Za-z0-9_]{1,16}) found x([0-9,]+) (\\w+) Essence!$");
    private static final Pattern EVERYONE =
            Pattern.compile("^([A-Za-z0-9_]{1,16}) found a (\\w+) Essence! Everyone gains an extra essence!$");
    private static final Pattern RARE =
            Pattern.compile("^RARE REWARD! ([A-Za-z0-9_]{1,16}) found an? (.+) in their (\\w+) Chest!$");
    private static final Pattern SCORE = Pattern.compile("^Team Score: ([0-9]+) \\(([A-Z+]+)\\)$");
    private static final Pattern POTION_BOUGHT =
            Pattern.compile("^You bought (Dungeon [IVX]+ Potion)(?: x([0-9,]+))? for ([0-9,]+) Coins!$");
    private static final String POTIONS_LOCKED = "You can no longer consume or splash any potions";
    private static final String KISMET = "You used a Kismet Feather!";

    /** PAID / FREE are Kuudra's, not a dungeon chest: those blocks are skipped whole. */
    private final RewardBlockReader blocks = new RewardBlockReader(word -> ChestKind.of(word) != null);

    /**
     * Feeds one colour-stripped line.
     *
     * @param plain   the line without colour codes, untrimmed (the indentation is the signal)
     * @param ownName the player's own name; {@code null} or empty matches only {@code "You"}
     * @return the events this line completes, usually none or one
     */
    public List<Event> accept(String plain, String ownName) {
        if (plain == null) {
            return List.of();
        }
        List<Event> out = new ArrayList<>(1);
        if (plain.indexOf('\n') < 0) {
            acceptLine(plain, ownName, out);
            return out;
        }
        // The whole reward block can arrive as ONE message whose lines are joined by newlines: read
        // it line by line and end the block with the message, since no further item line follows.
        for (String part : plain.split("\r?\n", -1)) {
            acceptLine(part, ownName, out);
        }
        out.addAll(flush());
        return out;
    }

    private void acceptLine(String plain, String ownName, List<Event> out) {
        String line = stripCounter(plain);
        List<RewardBlockReader.Block> closed = new ArrayList<>(1);
        boolean consumed = blocks.accept(line, closed);
        convert(closed, out);
        if (consumed) {
            return;
        }
        Event single = single(line.trim(), ownName);
        if (single != null) {
            out.add(single);
        }
    }

    /** Ends an open reward block (an idle timeout or a world change); empty when none is open. */
    public List<Event> flush() {
        List<Event> out = new ArrayList<>(1);
        List<RewardBlockReader.Block> closed = new ArrayList<>(1);
        blocks.flush(closed);
        convert(closed, out);
        return out;
    }

    /** Whether a reward block is being read. */
    public boolean blockOpen() {
        return blocks.open();
    }

    private static void convert(List<RewardBlockReader.Block> closed, List<Event> out) {
        for (RewardBlockReader.Block block : closed) {
            List<Item> items = new ArrayList<>(block.items().size());
            for (RewardBlockReader.Item item : block.items()) {
                items.add(new Item(item.name(), item.count(), item.rare()));
            }
            out.add(new RewardBlock(ChestKind.of(block.header()), List.copyOf(items)));
        }
    }

    private static Event single(String line, String ownName) {
        if (line.equals(KISMET)) {
            return new KismetUsed();
        }
        if (line.startsWith(POTIONS_LOCKED)) {
            return new PotionsLocked();
        }
        Matcher bought = POTION_BOUGHT.matcher(line);
        if (bought.matches()) {
            int count = bought.group(2) == null ? 1 : (int) Math.max(1, number(bought.group(2)));
            return new PotionBought(PotionWatch.idOf(bought.group(1)), count, number(bought.group(3)));
        }
        Matcher m = ESSENCE.matcher(line);
        if (m.matches()) {
            return new EssenceFound(m.group(3), (int) number(m.group(2)), false, isOwn(m.group(1), ownName));
        }
        m = EVERYONE.matcher(line);
        if (m.matches()) {
            return new EssenceFound(m.group(2), 1, true, isOwn(m.group(1), ownName));
        }
        m = RARE.matcher(line);
        if (m.matches()) {
            return new RareReward(isOwn(m.group(1), ownName), m.group(2).trim(), ChestKind.of(m.group(3)));
        }
        m = SCORE.matcher(line);
        if (m.matches()) {
            return new TeamScore((int) number(m.group(1)), m.group(2));
        }
        m = UNLOCKED.matcher(line);
        if (m.matches()) {
            Item item = item(m.group(2).trim());
            return new Unlocked(isOwn(m.group(1), ownName), item.name(), item.count());
        }
        return null;
    }

    /**
     * Whether a line can mean anything to this parser - the chat prefilter's own test, kept here so
     * the dispatcher test can check the prefilter against it.
     */
    public static boolean candidate(String plain) {
        if (plain == null) {
            return false;
        }
        return plain.isEmpty() || plain.charAt(0) == ' ' || plain.contains("unlocked ")
                || plain.contains("ESSENCE!") || plain.contains("Everyone gains") || plain.contains("RARE REWARD!")
                || plain.contains("Kismet") || plain.contains("Team Score:") || plain.contains("otion");
    }

    /** {@code "Wither Essence x88"} -> ("Wither Essence", 88); {@code "RARE REWARD! X"} is rare. */
    static Item item(String text) {
        RewardBlockReader.Item item = RewardBlockReader.item(text);
        return new Item(item.name(), item.count(), item.rare());
    }

    private static boolean isOwn(String who, String ownName) {
        return "You".equals(who) || (ownName != null && !ownName.isEmpty() && ownName.equalsIgnoreCase(who));
    }

    static String stripCounter(String line) {
        return RewardBlockReader.stripCounter(line);
    }

    private static long number(String digits) {
        return RewardBlockReader.number(digits);
    }
}
