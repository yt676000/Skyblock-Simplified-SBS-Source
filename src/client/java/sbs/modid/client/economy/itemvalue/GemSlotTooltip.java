/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.economy.itemvalue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.skills.mining.logic.GemstoneCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * The Gemstone slot summary tooltip line:
 * {@code Gems: ◆ Perfect Jasper · ◇ Combat (empty) · ✖ Combat (locked) ≈ 4.1M}.
 *
 * <p>Filled gems are named the way the game names the item ("Perfect Jasper"), not grade-last.
 * The value is the Bazaar <b>sell</b> side from the warm caches through {@link ItemAppraisal#price},
 * the same number the Est. Value line counts for the gems, with {@code +} when a gem has no price.
 * Wraps onto a second line when long; nothing is ever dropped from it.
 */
public final class GemSlotTooltip {

    private static final int GOLD = 0xFFAA00;
    private static final int YELLOW = 0xFFFF55;
    private static final int GRAY = 0xAAAAAA;
    private static final int DARK_GRAY = 0x555555;
    private static final int WHITE = 0xFFFFFF;

    /** Past this many characters the line breaks at a slot boundary. */
    static final int WRAP_AT = 56;

    /** One coloured run of text. */
    public record Segment(String text, int color) {
    }

    private GemSlotTooltip() {
    }

    /** Adds the line to {@code lines} when the item has gem slots; else returns {@code lines}. */
    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        SBSConfig.ItemOverlaySettings cfg = ConfigManager.getInstance().get().itemOverlay;
        if (!cfg.showGemSlots || stack == null || stack.isEmpty()) {
            return lines;
        }
        CompoundTag extra = SkyblockItem.extraAttributes(stack);
        String id = SkyblockItem.id(stack);
        SkyBlockItemCatalog.Entry entry = id == null ? null : SkyBlockItemCatalog.getInstance().byId(id);
        List<GemSlots.SlotDef> defs = entry == null ? List.of() : entry.gemSlots;
        GemSlots.Applied applied = ItemModifiers.readGems(extra);
        if (defs.isEmpty() && applied.filled().isEmpty() && applied.unlocked().isEmpty()) {
            return lines;
        }
        List<GemSlots.Slot> slots = GemSlots.resolve(defs, applied, extra.getIntOr("levelable_lvl", 0));
        if (slots.isEmpty()) {
            return lines;
        }
        Value value = cfg.showGemSlotValue ? value(slots) : null;
        List<Component> out = new ArrayList<>(lines);
        for (List<Segment> row : rows(slots, value)) {
            MutableComponent line = Component.empty();
            for (Segment segment : row) {
                line.append(Component.literal(segment.text()).withColor(segment.color()));
            }
            out.add(line);
        }
        return out;
    }

    /** What the filled gems sell for; {@code partial} when one of them has no price. */
    public record Value(long coins, boolean partial) {
    }

    private static Value value(List<GemSlots.Slot> slots) {
        long sum = 0;
        int priced = 0;
        boolean partial = false;
        for (GemSlots.Slot slot : slots) {
            if (slot.gem() == null) {
                continue;
            }
            Long price = ItemAppraisal.price(bazaarId(slot.gem()), ItemAppraisal.Side.SELL);
            if (price == null) {
                partial = true;
            } else {
                sum += price;
                priced++;
            }
        }
        return priced == 0 ? null : new Value(sum, partial);
    }

    /** The gem's Bazaar id via {@link GemstoneCatalog}, falling back to the raw NBT-derived id. */
    static String bazaarId(GemSlots.Filled gem) {
        GemstoneCatalog.Gem known = GemstoneCatalog.byItemId(gem.itemId());
        return known != null ? known.bazaarId() : gem.itemId();
    }

    /**
     * The line(s) as coloured segments - pure, so it is tested without a client.
     *
     * @param value {@code null} to show no value
     */
    public static List<List<Segment>> rows(List<GemSlots.Slot> slots, Value value) {
        List<List<Segment>> labels = new ArrayList<>();
        for (GemSlots.Slot slot : slots) {
            labels.add(label(slot));
        }
        List<Segment> tail = value == null ? List.of() : List.of(new Segment(" ≈ "
                + sbs.modid.client.core.util.NumberDisplay.format(value.coins())
                + (value.partial() ? "+" : ""), YELLOW));

        int total = "Gems: ".length() + length(tail);
        for (int i = 0; i < labels.size(); i++) {
            total += length(labels.get(i)) + (i > 0 ? 3 : 0);
        }
        // Break at the slot boundary nearest the middle; one line when it fits.
        int breakBefore = -1;
        if (total > WRAP_AT && labels.size() > 1) {
            int running = "Gems: ".length();
            int best = Integer.MAX_VALUE;
            for (int i = 1; i < labels.size(); i++) {
                running += length(labels.get(i - 1)) + (i > 1 ? 3 : 0);
                int distance = Math.abs(total / 2 - running);
                if (distance < best) {
                    best = distance;
                    breakBefore = i;
                }
            }
        }
        List<List<Segment>> rows = new ArrayList<>();
        List<Segment> row = new ArrayList<>();
        row.add(new Segment("Gems: ", GOLD));
        for (int i = 0; i < labels.size(); i++) {
            if (i == breakBefore) {
                rows.add(row);
                row = new ArrayList<>();
                row.add(new Segment("  ", GRAY));
            } else if (i > 0) {
                row.add(new Segment(" · ", DARK_GRAY));
            }
            row.addAll(labels.get(i));
        }
        row.addAll(tail);
        rows.add(row);
        return rows;
    }

    private static List<Segment> label(GemSlots.Slot slot) {
        String type = ItemModifiers.prettify(slot.type());
        return switch (slot.state()) {
            case FILLED -> {
                GemstoneCatalog.Gem gem = GemstoneCatalog.byItemId(slot.gem().itemId());
                yield gem != null
                        ? List.of(new Segment("◆ " + gem.shortName(), gem.type().color() & 0xFFFFFF))
                        : List.of(new Segment("◆ " + ItemModifiers.prettify(
                                slot.gem().quality() + "_" + slot.gem().gemType()), WHITE));
            }
            case EMPTY -> List.of(new Segment("◇ " + type + " (empty)", GRAY));
            case LOCKED -> List.of(new Segment("✖ " + type + " (locked)", DARK_GRAY));
        };
    }

    private static int length(List<Segment> segments) {
        int n = 0;
        for (Segment segment : segments) {
            n += segment.text().length();
        }
        return n;
    }

    /** The plain text of rows, for tests and logs. */
    public static List<String> plain(List<List<Segment>> rows) {
        List<String> out = new ArrayList<>();
        for (List<Segment> row : rows) {
            StringBuilder sb = new StringBuilder();
            row.forEach(s -> sb.append(s.text()));
            out.add(sb.toString());
        }
        return out;
    }
}
