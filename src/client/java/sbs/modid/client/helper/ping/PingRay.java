/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.ping;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Resolves what the player is pointing at, for the ping keybind.
 *
 * <p>One block clip plus one sweep over the entities being rendered; the closer hit wins. The same
 * shape as {@code helper/lookingat/LookingAtHud.resolve}, which is the other feature in this mod
 * that has to answer "what is under the crosshair" far beyond the distance vanilla cares about -
 * that one is throttled, config-coupled and returns display text, so this returns the two things a
 * ping needs instead: the entity to follow, or the block to sit on.
 *
 * <p><b>This is not the vanilla reach ray.</b> Nothing here touches the world - no block is broken,
 * no entity is hit, no packet is sent - so the distance is a display question, not a gameplay one,
 * and it comes from the setting rather than from {@code Player.blockInteractionRange()}.
 *
 * <p><b>The ray always resolves to something.</b> Pointing at the sky is a legitimate ping: the
 * marker goes at the far end of the ray, which is what "over there" means when there is nothing to
 * stand on.
 */
public final class PingRay {

    /**
     * Slack when the entity hit is marginally behind the block hit. A mob standing flush against a
     * wall has an inflated bounding box whose entry point can sit just past the wall plane, and
     * without this the wall steals a ping from a mob that is plainly in front of it. Same value and
     * same reason as the Looking At scan.
     */
    private static final double OCCLUSION_SLACK = 0.5;

    /** Entity boxes are inflated by this much, so a ping does not need pixel-exact aim on a mob. */
    private static final double ENTITY_INFLATE = 0.25;

    private PingRay() {
    }

    /**
     * What a ping would attach to.
     *
     * @param entity the entity the marker should follow, or {@code null} for a fixed spot
     * @param block  where the marker sits: the entity's block for an entity hit, the block that was
     *               hit for a block hit, and the block at the end of the ray when nothing was hit
     */
    public record Aim(Entity entity, BlockPos block) {
    }

    /**
     * Casts the ray from {@code player}'s eyes and returns what it found. Never {@code null}.
     *
     * @param maxDistance    how far the ray reaches, in blocks
     * @param followEntities whether an entity hit may win; {@code false} makes this a block-only ray
     */
    public static Aim resolve(ClientLevel level, LocalPlayer player, double maxDistance,
                              boolean followEntities) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(maxDistance));

        // OUTLINE rather than COLLIDER: a torch, a crop or a button is a thing worth pointing at,
        // and it is the shape the vanilla crosshair picks. The clip runs even with entity following
        // off, because it is also what stops a ping landing on a mob behind a wall.
        BlockHitResult blockHit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double blockDistance = blockHit.getType() == HitResult.Type.BLOCK
                ? blockHit.getLocation().distanceTo(eye) : Double.MAX_VALUE;

        if (followEntities) {
            Entity entity = nearestEntity(level, player, eye, end, maxDistance, blockDistance);
            if (entity != null) {
                return new Aim(entity, entity.blockPosition());
            }
        }
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            return new Aim(null, blockHit.getBlockPos());
        }
        // Nothing in the way: the marker goes where the ray ran out, which is the honest answer to
        // pointing at the horizon. It is a real position in the world, so it renders and measures
        // exactly like any other ping.
        return new Aim(null, BlockPos.containing(end));
    }

    /** The closest entity the ray enters, or {@code null} when a block gets there first. */
    private static Entity nearestEntity(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 end,
                                        double maxDistance, double blockDistance) {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        double rangeSq = (maxDistance + 2.0) * (maxDistance + 2.0);

        for (Entity entity : level.entitiesForRendering()) {
            // A nametag stand is a label hovering over a mob, not a target: pinging "it" means
            // pinging the mob underneath, and the mob is in this same list.
            if (entity == player || entity == player.getVehicle()
                    || entity instanceof ArmorStand || entity.isSpectator()) {
                continue;
            }
            if (entity.distanceToSqr(eye.x, eye.y, eye.z) > rangeSq) {
                continue;
            }
            Optional<Vec3> clip = entity.getBoundingBox().inflate(ENTITY_INFLATE).clip(eye, end);
            if (clip.isEmpty()) {
                continue;
            }
            double distance = clip.get().distanceTo(eye);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best != null && bestDistance <= blockDistance + OCCLUSION_SLACK ? best : null;
    }
}
