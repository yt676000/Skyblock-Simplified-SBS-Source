/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.museum.render;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.helper.museum.logic.MuseumStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The tooltip line {@code Museum: not donated · +5 XP}, appended from {@code PriceTooltipMixin}'s one
 * handler. Returns the list it was given, untouched, whenever it has nothing to say - which is always,
 * until the item's museum category has been fully seen for this profile.
 */
public final class MuseumTooltip {

    private static final int COLOR = 0x55FFFF;

    private MuseumTooltip() {
    }

    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        SBSConfig.MuseumHelperSettings cfg = ConfigManager.getInstance().get().museumHelper;
        if (!cfg.enabled || !cfg.tooltipLine || stack == null || stack.isEmpty()) {
            return lines;
        }
        String text = MuseumStatus.tooltipLine(SkyblockItem.id(stack), System.currentTimeMillis());
        if (text == null) {
            return lines;
        }
        List<Component> out = new ArrayList<>(lines);
        out.add(Component.literal(text).withColor(COLOR));
        return out;
    }
}
