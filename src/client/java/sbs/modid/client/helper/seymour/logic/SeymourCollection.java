/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.seymour.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.helper.seymour.logic.SeymourPieces.Piece;
import sbs.modid.client.helper.storage.StorageIndex;
import sbs.modid.client.helper.storage.StorageSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Every Seymour piece the player owns, as far as SBS has seen: a <b>view</b> over the storage index
 * plus the live inventory and worn armor. Nothing is stored here - the storage index already keeps
 * every captured container per profile - so the collection can never disagree with it.
 *
 * <p>One piece seen in two places (moved from a backpack into the Ender Chest, with the backpack's
 * snapshot not yet refreshed) is the same uuid twice; the most recently captured place wins, and the
 * live inventory counts as captured now. Pieces without a uuid cannot be told apart and are all kept.
 */
public final class SeymourCollection {

    /**
     * One piece, as plain data (no item stack), so sorting, searching and the CSV are testable.
     *
     * @param analysis {@code null} when the piece carries a dye item and its own hex is unreadable
     * @param seenAt   when its location was captured (epoch ms)
     */
    public record Line(Piece piece, String name, int rgb, String dyeItem, String uuid,
                       SeymourColour.Analysis analysis, String location, long seenAt) {

        /** Everything the search field matches against, lower case. */
        public String searchText() {
            StringBuilder out = new StringBuilder();
            out.append(piece.displayName()).append(' ').append(name).append(' ').append(location);
            if (!dyeItem.isEmpty()) {
                out.append(' ').append(dyeItem);
            }
            if (analysis != null) {
                out.append(' ').append(SeymourColour.hex(rgb));
                if (analysis.best() != null) {
                    out.append(' ').append(analysis.best().target().name());   // the runners-up are noise here
                }
                for (String tag : analysis.tagLabels()) {
                    out.append(' ').append(tag);
                }
                out.append(' ').append(analysis.tier().label());
            }
            return out.toString().toLowerCase(Locale.ROOT);
        }

        public double bestDeltaE() {
            SeymourColour.Match best = analysis == null ? null : analysis.best();
            return best == null ? Double.MAX_VALUE : best.deltaE();
        }

        /** The hex to sort by; a piece whose own hex is unknown sorts after every real one. */
        int hexKey() {
            return analysis == null ? Integer.MAX_VALUE : rgb;
        }

        /** Tier rank for sorting: the four tiers, then dyed pieces (unknown colour) last. */
        int tierRank() {
            return analysis == null ? SeymourColour.Tier.values().length : analysis.tier().ordinal();
        }
    }

    /** A line and the stack it was read from, for the icon. */
    public record Row(Line line, ItemStack icon) {
    }

    public enum Sort {
        TIER("Tier"),
        DELTA_E("ΔE"),
        HEX("Hex"),
        PIECE("Piece");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Sort at(int index) {
            Sort[] values = values();
            return index < 0 || index >= values.length ? TIER : values[index];
        }
    }

    private SeymourCollection() {
    }

    // ------------------------------------------------------------------ pure

    /**
     * Keeps one entry per uuid - the one with the latest {@code seenAt} - and every entry without a
     * uuid. First-seen order is kept otherwise.
     */
    public static <T> List<T> dedupe(List<T> items, Function<T, String> uuid, ToLongFunction<T> seenAt) {
        Map<String, T> byUuid = new LinkedHashMap<>();
        List<T> out = new ArrayList<>(items.size());
        for (T item : items) {
            String key = uuid.apply(item);
            if (key == null || key.isEmpty()) {
                out.add(item);
                continue;
            }
            T previous = byUuid.get(key);
            if (previous == null || seenAt.applyAsLong(item) > seenAt.applyAsLong(previous)) {
                byUuid.put(key, item);
            }
        }
        out.addAll(byUuid.values());
        return out;
    }

    public static Comparator<Line> comparator(Sort sort) {
        Comparator<Line> byHex = Comparator.comparingInt(Line::hexKey);
        Comparator<Line> byTier = Comparator.comparingInt(Line::tierRank)
                .thenComparingDouble(Line::bestDeltaE).thenComparing(byHex);
        return switch (sort) {
            case TIER -> byTier;
            case DELTA_E -> Comparator.comparingDouble(Line::bestDeltaE).thenComparing(byHex);
            case HEX -> byHex;
            case PIECE -> Comparator.comparingInt((Line l) -> l.piece().ordinal()).thenComparing(byTier);
        };
    }

    /**
     * Whether a line passes the piece filter ({@code null} = all) and the search: every word of the
     * query must occur in {@link Line#searchText()}; a leading {@code #} is optional for hex.
     */
    public static boolean matches(Line line, Piece filter, String query) {
        if (filter != null && line.piece() != filter) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String text = line.searchText();
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            String bare = word.startsWith("#") ? word.substring(1) : word;
            if (!text.contains(word) && (bare.isEmpty() || !text.contains(bare))) {
                return false;
            }
        }
        return true;
    }

    /** The lines as CSV (RFC 4180 quoting), header first, one piece per row. */
    public static String toCsv(List<Line> lines) {
        StringBuilder out = new StringBuilder(
                "piece,name,hex,best_match,match_type,delta_e,tier,tags,location,uuid\n");
        for (Line line : lines) {
            SeymourColour.Match best = line.analysis() == null ? null : line.analysis().best();
            String tier = line.analysis() != null ? line.analysis().tier().label()
                    : line.dyeItem().isEmpty() ? "no colour" : "dyed";
            String tags = line.analysis() == null ? "" : String.join("; ", line.analysis().tagLabels());
            out.append(csv(line.piece().displayName())).append(',')
                    .append(csv(line.name())).append(',')
                    .append(csv(line.rgb() < 0 || line.analysis() == null ? "" : SeymourColour.hex(line.rgb())))
                    .append(',')
                    .append(csv(best == null ? line.dyeItem() : best.target().name())).append(',')
                    .append(csv(best == null ? "" : best.target().kind().name().toLowerCase(Locale.ROOT)))
                    .append(',')
                    .append(csv(best == null ? "" : SeymourColour.formatDeltaE(best.deltaE()))).append(',')
                    .append(csv(tier)).append(',')
                    .append(csv(tags)).append(',')
                    .append(csv(line.location())).append(',')
                    .append(csv(line.uuid() == null ? "" : line.uuid())).append('\n');
        }
        return out.toString();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // ------------------------------------------------------------------ live

    /**
     * Every Seymour piece in the live inventory, the worn armor and every captured storage, deduped.
     * Client thread only: it reads live stacks.
     */
    public static List<Row> collect() {
        long now = System.currentTimeMillis();
        List<Row> rows = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            Inventory inventory = minecraft.player.getInventory();
            for (int i = 0; i < Math.min(36, inventory.getContainerSize()); i++) {
                add(rows, inventory.getItem(i), "Inventory", now);
            }
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                add(rows, minecraft.player.getItemBySlot(slot), "Worn", now);
            }
        }
        for (StorageIndex.Snapshot snapshot : StorageIndex.getInstance().snapshotsOf(StorageSource.Kind.values())) {
            if (snapshot.source().kind() == StorageSource.Kind.INVENTORY) {
                continue;   // the live inventory above is newer than any snapshot of it
            }
            for (ItemStack stack : snapshot.items()) {
                add(rows, stack, snapshot.source().displayName(), snapshot.capturedAt());
            }
        }
        return dedupe(rows, row -> row.line().uuid(), row -> row.line().seenAt());
    }

    private static void add(List<Row> rows, ItemStack stack, String location, long seenAt) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        SeymourPieces.Facts facts = SeymourPieces.read(stack);
        if (facts.piece() == null) {
            return;
        }
        SeymourColour.Analysis analysis = facts.ownColour() ? ColourAnalyzer.analyse(facts.rgb(), null) : null;
        String plain = net.minecraft.ChatFormatting.stripFormatting(stack.getHoverName().getString());
        String name = plain == null ? "" : plain.trim();
        rows.add(new Row(new Line(facts.piece(), name, facts.rgb(), facts.dyeItem(), facts.uuid(), analysis,
                location, seenAt), stack));
    }
}
