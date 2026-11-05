/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.stacktips;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.item.TooltipLines;

import java.util.List;

/**
 * The dungeon-quality tooltip line, {@code Quality: 48/50 (96%) · Floor 7}, in the tip's own
 * colour. Shares the Stack Tip switch: it is the same fact, spelled out. Unlike the tip it ignores
 * the containers/hotbar scope - a hovered item is in neither.
 */
public final class DungeonQualityTooltip {

    private static final TooltipLines CACHE = new TooltipLines(DungeonQualityTooltip::build);

    private DungeonQualityTooltip() {
    }

    /** Adds the line when the item has a quality roll; else returns {@code lines} itself. */
    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        if (!ConfigManager.getInstance().get().itemOverlay.stackTipDungeonQuality
                || stack == null || stack.isEmpty()) {
            return lines;
        }
        // Built once per stack; the line depends on nothing but the stack itself.
        return TooltipLines.append(lines, CACHE.get(stack, 0L));
    }

    private static List<Component> build(ItemStack stack) {
        DungeonQuality quality = DungeonQualityReader.of(stack);
        if (quality == null) {
            return List.of();
        }
        return List.of(Component.literal(quality.tooltipText()).withColor(quality.color() & 0xFFFFFF));
    }
}
