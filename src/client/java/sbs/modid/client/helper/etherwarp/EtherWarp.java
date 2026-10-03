/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.etherwarp;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.item.SkyblockItem;

/**
 * Shared Ether Warp logic: recognising an etherwarp-capable weapon and resolving the block the warp
 * would land on. Both Ether Warp features (the target highlight and the zoom) read their state from
 * here, so they always agree on when the ability is armed – but they stay independently toggleable,
 * because neither knows about the other.
 *
 * <p><b>Scope guard.</b> Everything here is gated on the held item being an Aspect of the End or
 * Aspect of the Void that actually carries the {@code ethermerge} upgrade. Any other item – an AOTE
 * without Ethermerge included – resolves to nothing, so no other item and no normal gameplay is
 * affected.
 *
 * <p>Client-side and read-only: this never sends a packet or performs the warp, it only mirrors
 * what the server would do so the player can aim.
 */
public final class EtherWarp {

    private static final String AOTE = "ASPECT_OF_THE_END";
    private static final String AOTV = "ASPECT_OF_THE_VOID";

    /** Hypixel's etherwarp reach in blocks, before Tuned Transmission adds to it. */
    private static final int BASE_ETHER_RANGE = 57;

    /** Instant Transmission's reach in blocks, before Tuned Transmission adds to it. */
    private static final int BASE_INSTANT_RANGE = 8;

    /** How much room a player needs above the landing block to fit. */
    private static final int PLAYER_HEIGHT_BLOCKS = 2;

    private EtherWarp() {
    }

    /**
     * A resolved warp target.
     *
     * @param pos   the block that would be landed on
     * @param valid whether the warp would actually succeed – i.e. the player fits above the block
     */
    public record Target(BlockPos pos, boolean valid) {
    }

    /** Whether a stack is an AOTE/AOTV with the Ethermerge upgrade (i.e. can etherwarp at all). */
    public static boolean isCapable(ItemStack stack) {
        return etherRange(stack) > 0;
    }

    /**
     * Whether a stack is an Aspect of the End or an Aspect of the Void, Ethermerge or not.
     *
     * <p>Broader than {@link #isCapable} on purpose: Instant Transmission is on <b>every</b> one of
     * these, while Ether Transmission needs the upgrade. Callers that only care about the short hop
     * (the pathfinder) must not be gated on an Ethermerge Prism nobody has to own.
     */
    public static boolean isTransmissionItem(ItemStack stack) {
        String id = SkyblockItem.extraAttributes(stack).getStringOr("id", "");
        return AOTE.equals(id) || AOTV.equals(id);
    }

    /**
     * Instant Transmission's reach for a specific weapon in blocks, or {@code 0} when the stack is
     * not one of the two items. Tuned Transmission adds one block per level, exactly as it does to
     * the etherwarp reach.
     */
    public static int instantRange(ItemStack stack) {
        if (!isTransmissionItem(stack)) {
            return 0;
        }
        return BASE_INSTANT_RANGE + tuned(stack);
    }

    /**
     * Etherwarp reach of a specific weapon in blocks – the base range plus its Tuned Transmission
     * level – or {@code 0} when the stack cannot etherwarp at all.
     */
    public static int etherRange(ItemStack stack) {
        if (!isTransmissionItem(stack)
                || SkyblockItem.extraAttributes(stack).getIntOr("ethermerge", 0) != 1) {
            return 0;
        }
        return BASE_ETHER_RANGE + tuned(stack);
    }

    private static int tuned(ItemStack stack) {
        return SkyblockItem.extraAttributes(stack).getIntOr("tuned_transmission", 0);
    }

    /**
     * True while the ability is armed: the player is sneaking and holding a capable weapon. Reads
     * the sneak <i>input</i> rather than the crouch pose, so releasing shift disarms instantly
     * instead of waiting out the pose animation.
     */
    public static boolean armed() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        return player != null && minecraft.level != null
                && player.isShiftKeyDown()
                && isCapable(player.getMainHandItem());
    }

    /**
     * The block the etherwarp would land on right now, or {@code null} when the ability is not armed
     * or the ray leaves the world without hitting anything.
     *
     * <p>Uses a {@link ClipContext.Block#COLLIDER} ray so pass-through decoration (grass, torches,
     * ...) is ignored rather than treated as a target, which mirrors how the real ability behaves.
     *
     * @param partialTick render partial tick, so the ray follows the camera smoothly between ticks
     */
    public static Target resolve(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            return null;
        }
        ItemStack held = player.getMainHandItem();
        int range = etherRange(held);
        if (range <= 0) {
            return null;
        }
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 end = eye.add(player.getViewVector(partialTick).scale(range));
        BlockHitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        return new Target(pos, fits(level, pos));
    }

    /** Whether the player would fit standing on {@code ground} (its headroom is clear). */
    private static boolean fits(Level level, BlockPos ground) {
        for (int i = 1; i <= PLAYER_HEIGHT_BLOCKS; i++) {
            BlockPos above = ground.above(i);
            if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
