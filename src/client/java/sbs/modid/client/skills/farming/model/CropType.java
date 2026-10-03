/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.model;

import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.item.SkyblockItem;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The Garden crops, with everything the farming features need to talk about one: the name Hypixel
 * shows, the Bazaar id its drop is priced under, and the farming tools that are specific to it.
 *
 * <p><b>Why a crop is identified by the tool, not the block.</b> A farming feature always wants to
 * know "which crop is this session about", and the honest answer on the Garden is the tool in your
 * hand: you switch tools to switch crops, the tool carries the counter the milestone tracker reads,
 * and it answers correctly while standing still (a block lookup only answers while you are actually
 * breaking something). {@link #forHeldTool} is therefore the single entry point every feature uses.
 *
 * <p>Tools are matched by SkyBlock id fragment rather than an exhaustive list, so the tiers Hypixel
 * keeps adding ({@code MELON_DICER_3}, {@code THEORETICAL_HOE_WHEAT_3}, ...) are picked up without a
 * code change – the same reasoning as {@link FarmingItems}.
 */
public enum CropType {

    WHEAT("Wheat", "WHEAT", new String[]{"THEORETICAL_HOE_WHEAT", "WHEAT_HOE"}),
    CARROT("Carrot", "CARROT_ITEM", new String[]{"THEORETICAL_HOE_CARROT", "CARROT_HOE"}),
    POTATO("Potato", "POTATO_ITEM", new String[]{"THEORETICAL_HOE_POTATO", "POTATO_HOE"}),
    NETHER_WART("Nether Wart", "NETHER_STALK",
            new String[]{"THEORETICAL_HOE_WARTS", "NETHER_WARTS_HOE"}),
    SUGAR_CANE("Sugar Cane", "SUGAR_CANE",
            new String[]{"THEORETICAL_HOE_CANE", "SUGAR_CANE_HOE"}),
    MELON("Melon", "MELON", new String[]{"MELON_DICER"}),
    PUMPKIN("Pumpkin", "PUMPKIN", new String[]{"PUMPKIN_DICER"}),
    COCOA_BEANS("Cocoa Beans", "INK_SACK:3", new String[]{"COCO_CHOPPER"}),
    CACTUS("Cactus", "CACTUS", new String[]{"CACTUS_KNIFE"}),
    MUSHROOM("Mushroom", "MUSHROOM_COLLECTION", new String[]{"FUNGI_CUTTER"}),
    SUNFLOWER("Sunflower", "DOUBLE_PLANT", new String[]{"THEORETICAL_HOE_SUNFLOWER"}),
    /**
     * Cut by the same Eclipse Sickle as {@link #SUNFLOWER} – Hypixel ships no Moonflower-specific
     * tool – so it deliberately carries no tool fragment. It exists so the milestone menu, which
     * lists it by name, has a crop to file its progress under; anything that asks "which crop is
     * this tool for" correctly answers Sunflower for both.
     */
    MOONFLOWER("Moonflower", "MOONFLOWER", new String[0]),
    WILD_ROSE("Wild Rose", "WILD_ROSE", new String[]{"THEORETICAL_HOE_WILD_ROSE"});

    /** Built once: {@link #withTools()} is read every frame the crop-angle panel is up. */
    private static final List<CropType> TOOL_CROPS =
            Arrays.stream(values()).filter(CropType::hasTool).toList();

    private final String displayName;
    private final String bazaarId;
    private final String[] toolFragments;

    CropType(String displayName, String bazaarId, String[] toolFragments) {
        this.displayName = displayName;
        this.bazaarId = bazaarId;
        this.toolFragments = toolFragments;
    }

    /** The name Hypixel shows for the crop ("Nether Wart"). */
    public String displayName() {
        return displayName;
    }

    /** The Bazaar product id the crop's drop is priced under. */
    public String bazaarId() {
        return bazaarId;
    }

    /** Whether a tool of its own points at this crop, i.e. whether {@link #forTool} can return it. */
    public boolean hasTool() {
        return toolFragments.length > 0;
    }

    /**
     * The crops a tool can single out, in enum order. Anything keyed on "the crop you are farming"
     * has to iterate this rather than {@link #values()}: a crop with no tool of its own can never be
     * the answer to that question, so listing it would offer a choice that cannot be reached.
     */
    public static List<CropType> withTools() {
        return TOOL_CROPS;
    }

    /**
     * The crop the given stack is the dedicated tool for, or {@code null} when it is not one.
     *
     * <p>A plain hoe (Rookie Hoe, Euclid's-less {@code *_HOE}) belongs to no single crop, so it
     * deliberately returns {@code null}: the features that need a crop would rather show nothing
     * than the wrong crop.
     */
    public static CropType forTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String id = SkyblockItem.extraAttributes(stack).getStringOr("id", "");
        if (id.isEmpty()) {
            return null;
        }
        String upper = id.toUpperCase(Locale.ROOT);
        for (CropType crop : values()) {
            for (String fragment : crop.toolFragments) {
                if (upper.contains(fragment)) {
                    return crop;
                }
            }
        }
        return null;
    }

    /** The crop of the tool in the player's main hand, or {@code null} when none is held. */
    public static CropType forHeldTool() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return player == null ? null : forTool(player.getMainHandItem());
    }

    /**
     * The crop a menu / widget line names, or {@code null}. Matched longest-name-first so
     * "Nether Wart" never loses to a shorter crop that happens to be a substring of the same line.
     */
    public static CropType forText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        CropType best = null;
        for (CropType crop : values()) {
            if (lower.contains(crop.displayName.toLowerCase(Locale.ROOT))
                    && (best == null || crop.displayName.length() > best.displayName.length())) {
                best = crop;
            }
        }
        return best;
    }
}
