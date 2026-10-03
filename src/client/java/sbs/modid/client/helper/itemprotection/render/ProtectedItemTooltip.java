/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.render;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.itemprotection.logic.ItemProtection;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Protected" line at the bottom of a protected item's tooltip.
 *
 * <p><b>Called from {@code PriceTooltipMixin}, never from an injection of its own.</b> Two
 * cancellable {@code @At("RETURN")} injections into {@code ItemStack.getTooltipLines} do not both
 * run - the first to call {@code setReturnValue} ends the method - so every SBS tooltip line shares
 * that one handler. This one follows {@code HuntingBoxTooltip.decorate}: same shape, same contract,
 * returns the list unchanged when it has nothing to add.
 */
public final class ProtectedItemTooltip {

    /** Amber, matching the default slot indicator so the two obviously belong to one feature. */
    private static final int AMBER = 0xFFC83F;

    private ProtectedItemTooltip() {
    }

    /**
     * Appends the protection line.
     *
     * @return the same list when nothing was added, or a new list with the line appended
     */
    public static List<Component> decorate(List<Component> lines, ItemStack stack) {
        SBSConfig.ItemProtectionSettings cfg = ConfigManager.getInstance().get().itemProtection;
        if (!cfg.tooltipLine || lines == null || stack == null || stack.isEmpty()) {
            return lines;
        }
        if (!ItemProtection.enabled()) {
            return lines;
        }
        ItemProtection.Protection protection = ItemProtection.protectionOf(stack);
        if (protection == null) {
            return lines;
        }
        // Which list matched is worth saying: "this one item" and "every item of this kind" are
        // undone in different places, and a player who sees only "Protected" cannot tell which
        // entry to remove.
        String text = protection.kind() == ItemProtection.Kind.ITEM
                ? "Protected by SBS (this item)"
                : "Protected by SBS (this item type)";
        List<Component> out = new ArrayList<>(lines);
        out.add(Component.literal(text).withColor(AMBER));
        return out;
    }
}
