/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the chat reward block Hypixel prints when a chest is claimed - shared by the Dungeon and the
 * Kuudra profit trackers, which see the same shape with different header words. Pure: colour-stripped
 * text in, blocks out.
 *
 * <pre>
 *   BEDROCK CHEST REWARDS          header, two spaces (Kuudra: PAID / FREE)
 *     Wither Essence x88           item, four spaces
 *     RARE REWARD! Recombobulator 3000
 * </pre>
 *
 * A block is held open while four-space item lines follow and is emitted on the first line that is
 * not one, or by {@link #flush()}. A line repeated in a row may carry the client's stacking counter,
 * {@code " (2)"}; callers strip it with {@link #stripCounter} before feeding the line.
 */
public final class RewardBlockReader {

    /** One item line: {@code "Wither Essence x88"} -> ("Wither Essence", 88). */
    public record Item(String name, int count, boolean rare) {
    }

    /** A claimed chest's whole contents; {@code header} is the upper-case word before "CHEST REWARDS". */
    public record Block(String header, List<Item> items) {
    }

    /** Which header words this reader opens a block for. */
    public interface HeaderFilter {
        boolean accepts(String word);
    }

    private static final Pattern HEADER = Pattern.compile("^\\s*([A-Z]+) CHEST REWARDS\\s*$");
    private static final Pattern COUNT_SUFFIX = Pattern.compile("^(.*?)\\s+x\\s*([0-9][0-9,]*)$");
    private static final Pattern STACK_COUNTER = Pattern.compile("\\s+\\(\\d+\\)$");
    private static final String RARE_ITEM = "RARE REWARD! ";
    private static final String ITEM_INDENT = "    ";

    private final HeaderFilter filter;
    private String openHeader;
    private List<Item> openItems;

    public RewardBlockReader(HeaderFilter filter) {
        this.filter = filter;
    }

    /**
     * Feeds one counter-stripped, untrimmed line. A block this line closes is added to {@code closed}.
     *
     * @return whether the line was part of a block (a header or an item) and needs no further reading
     */
    public boolean accept(String line, List<Block> closed) {
        if (openHeader != null) {
            if (line.startsWith(ITEM_INDENT) && !line.isBlank()) {
                openItems.add(item(line.trim()));
                return true;
            }
            emit(closed);
        }
        Matcher header = HEADER.matcher(line);
        if (header.matches()) {
            if (filter.accepts(header.group(1))) {
                openHeader = header.group(1);
                openItems = new ArrayList<>();
            }
            return true;
        }
        return false;
    }

    /** Ends an open block (an idle timeout or a world change). */
    public void flush(List<Block> closed) {
        emit(closed);
    }

    public boolean open() {
        return openHeader != null;
    }

    private void emit(List<Block> closed) {
        if (openHeader != null && !openItems.isEmpty()) {
            closed.add(new Block(openHeader, List.copyOf(openItems)));
        }
        openHeader = null;
        openItems = null;
    }

    /** {@code "Wither Essence x88"} -> ("Wither Essence", 88); {@code "RARE REWARD! X"} is rare. */
    public static Item item(String text) {
        boolean rare = text.startsWith(RARE_ITEM);
        String rest = rare ? text.substring(RARE_ITEM.length()).trim() : text;
        Matcher counted = COUNT_SUFFIX.matcher(rest);
        if (counted.matches()) {
            return new Item(counted.group(1).trim(), (int) Math.max(1, number(counted.group(2))), rare);
        }
        return new Item(rest, 1, rare);
    }

    /** Drops the client's {@code " (2)"} stacking counter. */
    public static String stripCounter(String line) {
        if (line.isEmpty() || line.charAt(line.length() - 1) != ')') {
            return line;
        }
        return STACK_COUNTER.matcher(line).replaceFirst("");
    }

    /** The digits of {@code "1,200"} as a number, capped at {@link Integer#MAX_VALUE}. */
    public static long number(String digits) {
        long value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                if (value > Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return value;
    }
}
