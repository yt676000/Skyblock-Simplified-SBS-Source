/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.itemprotection.logic;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.itemprotection.model.ProtectionCategory;

/**
 * One {@code [SBS][Protect]} line per container click, behind
 * {@code itemProtection.debugLog}. Off by default and inert when off - the guard asks the flag before
 * calling in here, so a switched-off log costs one boolean read per click.
 *
 * <h2>Why this exists as its own class</h2>
 * Item Protection can fail to fire for four quite different reasons, and from inside the game they
 * all look identical: nothing happens. The screen was not recognised; the clicked slot was never
 * examined; the stack's identity did not resolve; or the category is switched off. Guessing between
 * them is how the NPC-shop bug survived - the sell path was reached, classified as an unrecognised
 * menu, and quietly downgraded to a confirm that the player-inventory click never even reached. One
 * line naming all four at once replaces that guessing with reading.
 *
 * <p><b>It reports, it never decides.</b> Everything printed is recomputed here from the same helpers
 * the gate uses, and the verdict is passed in rather than re-derived, so the log cannot disagree with
 * what actually happened. Nothing in this class may change the outcome of a click.
 *
 * <h2>The line</h2>
 * <pre>
 * [SBS][Protect] click input=PICKUP btn=0 slot=54 half=PLAYER menu=ChestMenu#7 type=generic_9x6
 *                title.raw='&amp;8Adventurer' title.plain='adventurer' screen=SELL(structural)
 *                control=- stack='Hyperion' id=HYPERION uuid=8f2c... protected=ITEM blocked=true
 * </pre>
 * {@code half} is the half of the screen the slot belongs to, which is the field the shop bug turned
 * on; {@code screen} says how the classifier answered and whether structure or the title decided it;
 * {@code control} is the field the Bazaar bug turned on - in a Bazaar sale nothing moves through the
 * click, so it is the only one of the three that can ever be the reason a press is refused.
 */
public final class ProtectionDebug {

    /** A stack's identity, so an unprotected item and an unidentifiable one read differently. */
    private static final String NONE = "-";

    /** One warning if the logger itself throws; a broken log must not spam a broken log. */
    private static boolean loggerFailed;

    private ProtectionDebug() {
    }

    /**
     * Writes the line for one container click.
     *
     * @param blocked what {@code ItemProtection.blocksContainerInput} actually returned - passed in,
     *                never recomputed, so the log always agrees with the click
     */
    public static void logClick(Player player, int slotId, int button, ContainerInput input,
                                boolean blocked) {
        try {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Protect] {}", describe(player, slotId, button,
                    input, blocked));
        } catch (RuntimeException e) {
            // Narrow on purpose: a debug line is never worth taking a click down with it, but a
            // logger that silently stopped logging would send the next reader hunting the wrong bug.
            if (!loggerFailed) {
                loggerFailed = true;
                SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Protect] debug log failed and is now quiet for this session: {}",
                        e.toString());
            }
        }
    }

    private static String describe(Player player, int slotId, int button, ContainerInput input,
                                   boolean blocked) {
        StringBuilder line = new StringBuilder("click");
        line.append(" input=").append(input == null ? NONE : input.name());
        line.append(" btn=").append(button);
        line.append(" slot=").append(slotId);

        AbstractContainerMenu menu = player == null ? null : player.containerMenu;
        if (menu == null) {
            return line.append(" menu=none").toString();
        }

        Slot slot = slotId >= 0 && slotId < menu.slots.size() ? menu.getSlot(slotId) : null;
        line.append(" half=").append(half(slot, slotId));
        line.append(" menu=").append(menu.getClass().getSimpleName())
                .append('#').append(menu.containerId);
        line.append(" type=").append(menuType(menu));

        String rawTitle = ItemProtection.screenTitle();
        line.append(" title.raw='").append(PlainText.strip(rawTitle)).append('\'');
        line.append(" title.plain='").append(DestructiveScreens.normalize(rawTitle)).append('\'');
        line.append(" screen=").append(screen(menu, rawTitle));
        line.append(" control=").append(control(menu, slotId, input, rawTitle));

        // Every stack the click could put at risk, so "we never looked at it" is distinguishable
        // from "we looked and it was not protected" - the two failures that look the same in game.
        appendStack(line, "clicked", slot == null ? null : slot.getItem());
        appendStack(line, "carried", menu.getCarried());
        if (input == ContainerInput.SWAP) {
            appendStack(line, "swap", swapDestination(player, button));
        }
        return line.append(" blocked=").append(blocked).toString();
    }

    /** Which half of the screen a slot sits in - the field the NPC-shop bug turned on. */
    private static String half(Slot slot, int slotId) {
        if (slotId == -999) {
            return "OUTSIDE";
        }
        if (slot == null) {
            return NONE;
        }
        return slot.container instanceof Inventory ? "PLAYER" : "CONTAINER";
    }

    /**
     * The menu's registered type, or why there is none. {@code getType()} throws for a menu that
     * cannot be built by type - the player's own inventory is one - so it is asked inside a guard
     * rather than tested for.
     */
    private static String menuType(AbstractContainerMenu menu) {
        try {
            return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.MENU
                    .getKey(menu.getType()));
        } catch (UnsupportedOperationException e) {
            return "untyped";
        }
    }

    /** How the classifier answers for this screen, and which signal decided it. */
    private static String screen(AbstractContainerMenu menu, String rawTitle) {
        SBSConfig.ItemProtectionSettings cfg = ConfigManager.getInstance().get().itemProtection;
        ProtectionCategory category = ItemProtection.screenCategory(menu, rawTitle, cfg);
        String name = category == null ? "SAFE" : category.name();
        String source = DestructiveScreens.classify(rawTitle) != null ? "title"
                : DestructiveScreens.sellCapable(menu) ? "structural"
                : "fallback";
        return name + "(" + source + ")"
                + " actsOnInventory=" + (category != null)
                + " categoryOn=" + (category == null || enabledFor(cfg, category));
    }

    /**
     * Whether the clicked slot reads as a control that consumes the inventory, and as what.
     *
     * <p>The field that distinguishes a Bazaar sale from every other click: there the movement rule
     * is silent by construction - nothing moves - and this is the only thing that can refuse. A
     * {@code control=-} on a press of "Sell Instantly" says the wording list in
     * {@code DestructiveScreens} has gone stale, which is otherwise indistinguishable from the
     * feature simply being off.
     */
    private static String control(AbstractContainerMenu menu, int slotId, ContainerInput input,
                                  String rawTitle) {
        ProtectionCategory category = ItemProtection.consumingControl(menu, slotId, input, rawTitle);
        return category == null ? NONE : category.name();
    }

    /** Mirrors the gate's per-category toggle, so a switched-off category is visible as such. */
    private static boolean enabledFor(SBSConfig.ItemProtectionSettings cfg,
                                      ProtectionCategory category) {
        return switch (category) {
            case DROP -> cfg.blockDrop;
            case SELL -> cfg.blockSell;
            case SALVAGE -> cfg.blockSalvage;
            case SACK -> cfg.blockSack;
            case AUCTION -> cfg.blockAuction;
            case CONSUME -> cfg.blockConsume;
            case UNKNOWN -> cfg.confirmUnknownMenus;
        };
    }

    /**
     * A stack's resolved SkyBlock identity. Both keys are printed even when neither is protected:
     * a {@code uuid=-} on an item the player believes is protected is cause 5 of the bug report -
     * identity resolution failing - and it is invisible any other way.
     */
    private static void appendStack(StringBuilder line, String label, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String id = SkyblockItem.id(stack);
        String uuid = SkyblockItem.uuid(stack);
        ItemProtection.Protection protection = ItemProtection.protectionOf(stack);
        line.append(' ').append(label).append("='")
                .append(ItemProtection.displayName(stack)).append('\'')
                .append(" id=").append(id == null ? NONE : id)
                .append(" uuid=").append(uuid == null ? NONE : uuid)
                .append(" protected=").append(protection == null ? NONE : protection.kind().name());
    }

    /** The swap destination, mirroring the gate's own resolution including the offhand. */
    private static ItemStack swapDestination(Player player, int button) {
        if (player == null) {
            return null;
        }
        if (button >= 0 && button < Inventory.getSelectionSize()) {
            return player.getInventory().getItem(button);
        }
        return button == Inventory.SLOT_OFFHAND
                ? player.getInventory().getItem(Inventory.SLOT_OFFHAND)
                : null;
    }
}
