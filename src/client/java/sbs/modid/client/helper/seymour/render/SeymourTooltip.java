/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.seymour.render;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.helper.seymour.logic.ColourAnalyzer;
import sbs.modid.client.helper.seymour.logic.ColourTargets;
import sbs.modid.client.helper.seymour.logic.SeymourColour;
import sbs.modid.client.helper.seymour.logic.SeymourPieces;
import sbs.modid.client.core.config.SBSConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * At most three lines under a Seymour piece's tooltip (or any dyed leather piece's, with "Also For
 * All Leather Armor"), appended from {@code PriceTooltipMixin}'s one handler:
 *
 * <pre>
 * ■ Hex #F2DF11
 * ≈ Superior Dragon Chestplate · ΔE 0.8 · exact
 * Paired · Web-safe
 * </pre>
 *
 * A piece with a dye applied gets one line saying its own hex cannot be read, because its
 * {@code dyed_color} is then the dye's. The lines are built once per hovered stack: the tooltip is
 * rebuilt every frame, and the stack is replaced whenever the server changes the slot, so the last
 * stack's lines stay valid for as long as it is the one hovered.
 */
public final class SeymourTooltip {

    private static final int GREY = 0xAAAAAA;
    private static final int TAGS = 0xFFAA00;

    private static ItemStack lastStack;
    private static String lastKey;
    private static List<Component> lastLines = List.of();

    private SeymourTooltip() {
    }

    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        SBSConfig.SeymourColourSettings cfg = ColourAnalyzer.cfg();
        if (!cfg.enabled || !cfg.tooltip || stack == null || stack.isEmpty()) {
            return lines;
        }
        List<Component> extra = linesFor(stack, cfg);
        if (extra.isEmpty()) {
            return lines;
        }
        List<Component> out = new ArrayList<>(lines.size() + extra.size());
        out.addAll(lines);
        out.addAll(extra);
        return out;
    }

    private static synchronized List<Component> linesFor(ItemStack stack, SBSConfig.SeymourColourSettings cfg) {
        // Settings and the target list both change what is said, so they are part of the cache key.
        String key = cfg.allLeather + "|" + cfg.exactUpTo + "|" + cfg.nearUpTo + "|" + cfg.closeUpTo + "|"
                + cfg.words + "|" + ColourTargets.current().size();
        if (stack == lastStack && key.equals(lastKey)) {
            return lastLines;
        }
        lastStack = stack;
        lastKey = key;
        lastLines = build(SeymourPieces.read(stack), cfg.allLeather);
        return lastLines;
    }

    private static List<Component> build(SeymourPieces.Facts facts, boolean allLeather) {
        boolean seymour = facts.piece() != null;
        if (!seymour && (!allLeather || (facts.rgb() < 0 && facts.dyeItem().isEmpty()))) {
            return List.of();
        }
        if (!facts.dyeItem().isEmpty()) {
            return List.of(Component.literal("Dyed with " + dyeName(facts.dyeItem())
                    + ": original hex not readable").withColor(GREY));
        }
        if (facts.rgb() < 0) {
            return List.of(Component.literal("No colour on this piece").withColor(GREY));
        }
        SeymourColour.Analysis analysis = ColourAnalyzer.analyse(facts.rgb(), seymour ? null : facts.id());
        List<Component> out = new ArrayList<>(3);
        MutableComponent hex = Component.literal("■ ").withColor(facts.rgb());
        hex.append(Component.literal("Hex " + SeymourColour.hex(facts.rgb())).withColor(0xFFFFFF));
        out.add(hex);
        out.add(matchLine(analysis));
        List<String> tags = analysis.tagLabels();
        if (!tags.isEmpty()) {
            out.add(Component.literal(String.join(" · ", tags)).withColor(TAGS));
        }
        return out;
    }

    private static Component matchLine(SeymourColour.Analysis analysis) {
        SeymourColour.Match best = analysis.best();
        if (best == null) {
            return Component.literal("No colour targets loaded yet").withColor(GREY);
        }
        String deltaE = "ΔE " + SeymourColour.formatDeltaE(best.deltaE());
        if (analysis.tier() == SeymourColour.Tier.NONE) {
            return Component.literal("No close match (nearest: " + best.target().name() + ", " + deltaE + ")")
                    .withColor(GREY);
        }
        return Component.literal("≈ " + best.target().name() + " · " + deltaE + " · " + analysis.tier().label())
                .withColor(tierColor(analysis.tier()));
    }

    /** Tier colour. The tier is always also written out, so the colour is never the only signal. */
    public static int tierColor(SeymourColour.Tier tier) {
        return switch (tier) {
            case EXACT -> 0x55FF55;
            case NEAR -> 0x55FFFF;
            case CLOSE -> 0xFFFF55;
            case NONE -> GREY;
        };
    }

    /** A dye item's display name from the catalogue, or its id. */
    public static String dyeName(String dyeItem) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(dyeItem);
        return entry == null ? dyeItem : entry.name;
    }
}
