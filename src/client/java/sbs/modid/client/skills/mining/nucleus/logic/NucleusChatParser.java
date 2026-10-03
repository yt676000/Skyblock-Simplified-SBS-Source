/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.nucleus.logic;

import sbs.modid.client.skills.mining.nucleus.model.Crystal;
import sbs.modid.client.skills.mining.nucleus.model.NucleusCostRules;
import sbs.modid.client.skills.mining.nucleus.model.NucleusSignals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;

/**
 * Turns chat lines into Nucleus run events. Pure - text and a clock in, events out.
 *
 * <p>Hypixel sends a reward block as one line per message; a message that does carry several lines
 * is split here, so both shapes read the same. Matching is regex over colour-stripped, trimmed text.
 * A line this parser does not recognise produces nothing - it never changes what the player sees.
 *
 * <p>Inside an open reward block every line is an item, a HotM XP line or a powder line, except the
 * {@code REWARDS} sub-header, blank lines and lines starting with {@code [} (another mod's output can
 * land inside the block, and Hypixel's item lines never start with a bracket). The block closes on a
 * separator rule once it has content, on the next header, or {@link NucleusSignals#BLOCK_IDLE_MS}
 * after its last line.
 */
public final class NucleusChatParser {

    /** Which reward block a loot line came from. */
    public enum Source {
        /** The Crystal Nucleus loot bundle - the run's own reward. */
        BUNDLE,
        /** A Crystal Hollows treasure chest - run loot. */
        CHEST
    }

    /** What a line meant. */
    public sealed interface Event permits CrystalFound, CrystalPlaced, BlockOpened, Loot, NonCoin,
            BlockClosed, Cost, StashPickup {
    }

    /** {@code CRYSTAL FOUND (count/5)} followed by the crystal's name. */
    public record CrystalFound(Crystal crystal, int count) implements Event {
    }

    public record CrystalPlaced(Crystal crystal) implements Event {
    }

    public record BlockOpened(Source source) implements Event {
    }

    /** One item line; {@code name} has its leading glyph removed. */
    public record Loot(Source source, String name, long qty) implements Event {
    }

    /** HotM XP or powder: {@code kind} is {@code "HotM XP"} or {@code "<Type> Powder"}. */
    public record NonCoin(Source source, String kind, long amount) implements Event {
    }

    public record BlockClosed(Source source) implements Event {
    }

    /** A cost line; {@code itemName} is set when the rule reads the item from the line. */
    public record Cost(NucleusCostRules.Rule rule, String itemName) implements Event {
    }

    /** Items pulled from the stash - the {@code [Sacks]} gain that follows is not loot. */
    public record StashPickup() implements Event {
    }

    private Source open;
    private long lastBlockLineAt;
    private boolean hasContent;

    private int foundCount = -1;
    private long foundAt;

    /** One chat message, as displayed. */
    public List<Event> onLine(String raw, long now) {
        List<Event> events = new ArrayList<>();
        poll(now, events);
        if (raw == null) {
            return events;
        }
        for (String part : raw.split("\n", -1)) {
            line(strip(part), now, events);
        }
        return events;
    }

    /** Closes a block that has gone idle. Called every tick as well as before each line. */
    public List<Event> poll(long now) {
        List<Event> events = new ArrayList<>();
        poll(now, events);
        return events;
    }

    /** World change: an open block or found header does not carry across. */
    public void clear() {
        open = null;
        hasContent = false;
        foundCount = -1;
    }

    /** Whether a reward block is open - the capture log's bundle window keys off this too. */
    public Source openBlock() {
        return open;
    }

    private void poll(long now, List<Event> events) {
        if (open != null && now - lastBlockLineAt > NucleusSignals.BLOCK_IDLE_MS) {
            close(events);
        }
    }

    private void line(String line, long now, List<Event> events) {
        String unglyphed = withoutGlyph(line);

        // Lines that mean the same inside a block or out of one.
        if (NucleusSignals.BUNDLE_HEADER.matcher(line).matches()) {
            openBlock(Source.BUNDLE, now, events);
            return;
        }
        if (NucleusSignals.CHEST_HEADER.matcher(line).matches()) {
            openBlock(Source.CHEST, now, events);
            return;
        }
        Matcher placed = NucleusSignals.CRYSTAL_PLACED.matcher(unglyphed);
        if (placed.matches()) {
            events.add(new CrystalPlaced(Crystal.byName(placed.group(1))));
            return;
        }
        Matcher found = NucleusSignals.CRYSTAL_FOUND.matcher(unglyphed);
        if (found.matches()) {
            foundCount = Integer.parseInt(found.group(1));
            foundAt = now;
            return;
        }
        if (foundCount > 0 && now - foundAt <= NucleusSignals.FOUND_NAME_WINDOW_MS) {
            Matcher name = NucleusSignals.CRYSTAL_NAME.matcher(line);
            if (name.matches()) {
                events.add(new CrystalFound(Crystal.byName(name.group(1)), foundCount));
                foundCount = -1;
                return;
            }
        }
        for (NucleusCostRules.Rule rule : NucleusCostRules.RULES) {
            Matcher cost = rule.line().matcher(line);
            if (cost.matches()) {
                events.add(new Cost(rule, rule.nameFromLine() ? withoutGlyph(cost.group(1).trim()) : null));
                return;
            }
        }
        if (NucleusSignals.STASH_PICKUP.matcher(line).matches()) {
            events.add(new StashPickup());
            return;
        }

        if (open == null) {
            return;
        }
        if (line.isEmpty() || NucleusSignals.REWARDS.matcher(line).matches()) {
            lastBlockLineAt = now;
            return;
        }
        if (NucleusSignals.SEPARATOR.matcher(line).matches()) {
            if (hasContent) {
                close(events);
            }
            return;
        }
        if (line.startsWith("[")) {
            return;
        }
        lastBlockLineAt = now;
        Event content = content(open, unglyphed);
        if (content != null) {
            hasContent = true;
            events.add(content);
        }
    }

    private void openBlock(Source source, long now, List<Event> events) {
        if (open != null) {
            close(events);
        }
        open = source;
        hasContent = false;
        lastBlockLineAt = now;
        events.add(new BlockOpened(source));
    }

    private void close(List<Event> events) {
        Source closing = open;
        open = null;
        hasContent = false;
        events.add(new BlockClosed(closing));
    }

    /** One line inside a block: HotM XP, powder, or an item. */
    static Event content(Source source, String text) {
        Matcher xp = NucleusSignals.HOTM_XP.matcher(text);
        if (xp.matches()) {
            return new NonCoin(source, "HotM XP", amount(xp.group(1)));
        }
        Matcher powder = NucleusSignals.POWDER_NAME_FIRST.matcher(text);
        if (powder.matches()) {
            return new NonCoin(source, powder.group(1) + " Powder",
                    powder.group(2) == null ? 1L : amount(powder.group(2)));
        }
        Matcher powderAmount = NucleusSignals.POWDER_AMOUNT_FIRST.matcher(text);
        if (powderAmount.matches()) {
            return new NonCoin(source, powderAmount.group(2) + " Powder", amount(powderAmount.group(1)));
        }
        Matcher item = NucleusSignals.ITEM.matcher(text);
        if (!item.matches() || item.group(1).isBlank()) {
            return null;
        }
        long qty = item.group(2) == null ? 1L : amount(item.group(2));
        return qty > 0 ? new Loot(source, item.group(1).trim(), qty) : null;
    }

    /** {@code 1,315} / {@code 19.1k} / {@code 2M} to a whole number; {@code 0} when unreadable. */
    static long amount(String raw) {
        String text = raw.replace(",", "").trim().toLowerCase(Locale.ROOT);
        double scale = 1.0;
        if (text.endsWith("k")) {
            scale = 1_000.0;
            text = text.substring(0, text.length() - 1);
        } else if (text.endsWith("m")) {
            scale = 1_000_000.0;
            text = text.substring(0, text.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(text) * scale);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Drops legacy {@code §x} codes and the spaces Hypixel centres and indents its blocks with. */
    public static String strip(String raw) {
        return raw.replaceAll("§.", "").trim();
    }

    /** {@code ❈ Fine Jade Gemstone} to {@code Fine Jade Gemstone}; anything else unchanged. */
    public static String withoutGlyph(String text) {
        return NucleusSignals.GLYPH_PREFIX.matcher(text).replaceFirst("");
    }
}
