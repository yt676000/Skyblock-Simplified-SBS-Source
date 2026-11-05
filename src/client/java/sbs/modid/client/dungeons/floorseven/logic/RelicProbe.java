/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.dungeons.events.ChatPatternRegistry;
import sbs.modid.client.dungeons.floorseven.model.RelicColour;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Log-only capture of the three facts the relic waypoints are missing: where each relic appears,
 * where it is picked up, and where it is placed. Nothing is drawn, sent or written to a file; every
 * line goes to the game log under {@code [SBS][Relics]}, <b>once per colour per run</b>, so one M7
 * run's {@code latest.log} is the capture.
 *
 * <ul>
 *   <li><b>Appearance.</b> Twice a second in the M7 boss room, every entity within
 *       {@link #SCAN_RADIUS} whose name or any worn/held item mentions "Relic" is logged with its
 *       type, name, equipment and position - the first time a colour is seen. What a relic entity
 *       actually is (armor stand with a head, a dropped item, ...) is UNVERIFIED, which is why the
 *       match is on the word rather than on an entity type.</li>
 *   <li><b>Pickup.</b> On the CONFIRMED chat line: the picker's position (the local player, or the
 *       teammate when their entity is loaded) and every entity within {@link #PICKUP_RADIUS} of it,
 *       because the relic entity may already be gone by the time the line arrives.</li>
 *   <li><b>Placement.</b> After the local player's own pickup: the moment no inventory item mentions
 *       that colour's relic any more is taken as the placement, and the player's position is logged
 *       with the nearest cauldron block within {@link #PLACE_RADIUS}. Whether the relic is an
 *       inventory item at all is UNVERIFIED - the log says so when it never shows up.</li>
 * </ul>
 */
public final class RelicProbe {

    static final String TAG = "[SBS][Relics] ";

    private static final RelicProbe INSTANCE = new RelicProbe();

    private static final double SCAN_RADIUS = 96.0;
    private static final double PICKUP_RADIUS = 4.0;
    private static final int PLACE_RADIUS = 8;
    private static final int SCAN_EVERY_TICKS = 10;

    private final Set<RelicColour> appearedLogged = EnumSet.noneOf(RelicColour.class);
    private final Set<RelicColour> pickedLogged = EnumSet.noneOf(RelicColour.class);
    private final Set<RelicColour> placedLogged = EnumSet.noneOf(RelicColour.class);
    private boolean unnamedLogged;

    /** The colour the local player picked and has not yet been seen placing, or null. */
    private RelicColour carrying;
    /** Whether the carried relic has been seen in the inventory since the pickup. */
    private boolean seenInInventory;
    private int carryTicks;
    private int tick;

    private RelicProbe() {
        ChatPatternRegistry.getInstance().register(java.util.regex.Pattern.compile(RelicPickup.REGEX),
                (matcher, raw) -> onPickup(RelicPickup.from(matcher, selfName())), "m7 relics: probe pickup");
    }

    public static RelicProbe getInstance() {
        return INSTANCE;
    }

    /** World change or run end: a new run logs every colour again. */
    public void reset() {
        appearedLogged.clear();
        pickedLogged.clear();
        placedLogged.clear();
        unnamedLogged = false;
        carrying = null;
        seenInInventory = false;
        carryTicks = 0;
    }

    static String selfName() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? null : player.getName().getString();
    }

    // ---- tick ---------------------------------------------------------------------------------

    public void onClientTick() {
        if (!DragonTracker.inBossRoom()) {
            if (!DragonTracker.onMasterSeven() && (carrying != null || !appearedLogged.isEmpty()
                    || !pickedLogged.isEmpty())) {
                reset();
            }
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || ++tick % SCAN_EVERY_TICKS != 0) {
            return;
        }
        if (appearedLogged.size() < RelicColour.values().length) {
            scanAppearances(level, player);
        }
        if (carrying != null) {
            watchPlacement(level, player);
        }
    }

    private void scanAppearances(ClientLevel level, LocalPlayer player) {
        for (Entity entity : level.getEntities(player, player.getBoundingBox().inflate(SCAN_RADIUS))) {
            String text = describeText(entity);
            if (!text.toLowerCase(Locale.ROOT).contains("relic")) {
                continue;
            }
            RelicColour colour = RelicColour.findIn(text);
            if (colour == null) {
                if (!unnamedLogged) {
                    unnamedLogged = true;
                    SkyblockSimplifiedSBS.LOGGER.info(TAG + "appeared (colour unread): {} phase={}",
                            describe(entity), FloorSevenPhaseTimer.getInstance().current());
                }
                continue;
            }
            if (appearedLogged.add(colour)) {
                SkyblockSimplifiedSBS.LOGGER.info(TAG + "appeared {}: {} phase={}", colour, describe(entity),
                        FloorSevenPhaseTimer.getInstance().current());
            }
        }
    }

    private void watchPlacement(ClientLevel level, LocalPlayer player) {
        carryTicks += SCAN_EVERY_TICKS;
        boolean holding = holding(player, carrying);
        if (holding) {
            seenInInventory = true;
            return;
        }
        if (!seenInInventory) {
            // Never seen as an item: after 10 s say so once and stop watching, rather than guess.
            if (carryTicks >= 200) {
                SkyblockSimplifiedSBS.LOGGER.info(TAG + "{} never appeared in the inventory after pickup;"
                        + " placement cannot be read from it", carrying);
                carrying = null;
            }
            return;
        }
        if (placedLogged.add(carrying)) {
            Vec3 pos = player.position();
            SkyblockSimplifiedSBS.LOGGER.info(TAG + "placed {}: player at {} ; nearest cauldron {} ; block below {}",
                    carrying, fmt(pos), nearestCauldron(level, player.blockPosition()),
                    blockId(level, player.blockPosition().below()));
        }
        carrying = null;
    }

    // ---- chat ---------------------------------------------------------------------------------

    void onPickup(RelicPickup pickup) {
        if (pickup == null || !pickedLogged.add(pickup.colour())) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        Player picker = pickup.self() ? minecraft.player : findPlayer(level, pickup.player());
        String where = picker == null ? "not loaded" : fmt(picker.position());
        SkyblockSimplifiedSBS.LOGGER.info(TAG + "picked {} by {} (self={}) at {} phase={}", pickup.colour(),
                pickup.player(), pickup.self(), where, FloorSevenPhaseTimer.getInstance().current());
        if (picker != null) {
            for (Entity entity : level.getEntities(picker, picker.getBoundingBox().inflate(PICKUP_RADIUS))) {
                if (!(entity instanceof Player)) {
                    SkyblockSimplifiedSBS.LOGGER.info(TAG + "  near pickup: {}", describe(entity));
                }
            }
        }
        if (pickup.self()) {
            carrying = pickup.colour();
            seenInInventory = false;
            carryTicks = 0;
        }
    }

    // ---- helpers, shared with RelicTracker ----------------------------------------------------

    /** Whether any inventory slot holds an item whose name mentions this colour's relic. */
    static boolean holding(LocalPlayer player, RelicColour colour) {
        Inventory inventory = player.getInventory();
        String want = colour.label().toLowerCase(Locale.ROOT) + " relic";
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(want)) {
                return true;
            }
        }
        return false;
    }

    private static Player findPlayer(ClientLevel level, String name) {
        for (Player player : level.players()) {
            if (player.getName().getString().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private static String nearestCauldron(ClientLevel level, BlockPos centre) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -PLACE_RADIUS; dx <= PLACE_RADIUS; dx++) {
            for (int dy = -PLACE_RADIUS; dy <= PLACE_RADIUS; dy++) {
                for (int dz = -PLACE_RADIUS; dz <= PLACE_RADIUS; dz++) {
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (!blockId(level, cursor).contains("cauldron")) {
                        continue;
                    }
                    double distance = cursor.distSqr(centre);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best == null ? "none within " + PLACE_RADIUS
                : best.getX() + "," + best.getY() + "," + best.getZ() + " " + blockId(level, best);
    }

    private static String blockId(ClientLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    /** The entity's name plus every equipment item's name, for the "relic" word test. */
    private static String describeText(Entity entity) {
        StringBuilder text = new StringBuilder(entity.getName().getString());
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    text.append(' ').append(stack.getHoverName().getString());
                }
            }
        } else if (entity instanceof net.minecraft.world.entity.item.ItemEntity item) {
            text.append(' ').append(item.getItem().getHoverName().getString());
        }
        return text.toString();
    }

    private static String describe(Entity entity) {
        StringBuilder out = new StringBuilder();
        out.append("type=").append(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath())
                .append(" name='").append(entity.getName().getString()).append('\'')
                .append(" pos=").append(fmt(entity.position()));
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    out.append(' ').append(slot.getName()).append('=')
                            .append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath())
                            .append("('").append(stack.getHoverName().getString()).append("')");
                }
            }
        } else if (entity instanceof net.minecraft.world.entity.item.ItemEntity item) {
            out.append(" item=").append(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).getPath())
                    .append("('").append(item.getItem().getHoverName().getString()).append("')");
        }
        return out.toString();
    }

    private static String fmt(Vec3 pos) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", pos.x, pos.y, pos.z);
    }
}
