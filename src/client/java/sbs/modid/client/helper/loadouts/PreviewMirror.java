/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.loadouts;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.mixin.LivingEntityUseAccessor;

/**
 * "Mirror My Player": copies what the real player is doing onto the Loadouts preview model before it
 * is drawn - walk swing, sneaking, both held items, the use animation and the attack swing. Armour is
 * never copied; the card keeps the loadout's own.
 *
 * <p><b>Copy fields, never tick.</b> The preview is a {@code RemotePlayer} that is deliberately never
 * ticked or added to the level. {@code InventoryScreen.extractEntityInInventoryFollowsMouse} builds its
 * render state from scratch at partial tick 1.0 (javap-verified, 26.2), so the <i>current</i> value
 * of each field is exactly what gets drawn - setting them before the call is the whole mechanism. No
 * allocation: every value copied is a primitive or an existing stack reference (stacks are only
 * referenced, never modified).
 *
 * <p><b>Left out, deliberately</b> (see {@code docs/features/loadout-mirror.md}): swimming, crawling,
 * elytra and sleeping/riding poses - their animation reads private tick-driven fields (swim amount,
 * fall-fly ticks, bed position, the vehicle), so a copied pose alone would draw a wrong body - and the
 * cape's swing, which lives in private fields of {@code ClientAvatarState}. Those poses fall back to
 * standing.
 */
final class PreviewMirror {

    private PreviewMirror() {
    }

    /** Copies the live player's pose, hands and animations onto {@code preview}. */
    static void copy(Player live, AbstractClientPlayer preview, float partialTick) {
        // Walk swing: position and speed are what the render state reads at partial tick 1.0.
        // WalkAnimationState has no position setter; update(delta, 1, 1) moves position by exactly
        // delta (and sets speed to it), then setSpeed puts the real speed back. Scale 1 = adult.
        var from = live.walkAnimation;
        var to = preview.walkAnimation;
        to.update(from.position(partialTick) - to.position(), 1.0F, 1.0F);
        to.setSpeed(from.speed(partialTick));

        Pose pose = live.getPose();
        preview.setPose(pose == Pose.CROUCHING ? Pose.CROUCHING : Pose.STANDING);

        setHand(preview, EquipmentSlot.MAINHAND, live.getMainHandItem());
        setHand(preview, EquipmentSlot.OFFHAND, live.getOffhandItem());

        LivingEntityUseAccessor use = (LivingEntityUseAccessor) preview;
        boolean using = live.isUsingItem();
        use.sbs$setUseItem(using ? live.getUseItem() : ItemStack.EMPTY);
        use.sbs$setUseItemRemaining(using ? live.getUseItemRemainingTicks() : 0);
        use.sbs$setLivingEntityFlag(1, using);
        use.sbs$setLivingEntityFlag(2, using && live.getUsedItemHand() == InteractionHand.OFF_HAND);

        preview.swinging = live.swinging;
        preview.swingTime = live.swingTime;
        preview.swingingArm = live.swingingArm;
        preview.attackAnim = live.getAttackAnim(partialTick);
    }

    /** Back to the static model: exactly the state a fresh, never-mirrored preview has. */
    static void clear(AbstractClientPlayer preview) {
        var walk = preview.walkAnimation;
        walk.update(-walk.position(), 1.0F, 1.0F);
        walk.setSpeed(0.0F);
        walk.update(0.0F, 0.0F, 1.0F);   // speedOld = speed = 0, position unchanged at 0
        preview.setPose(Pose.STANDING);
        setHand(preview, EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        setHand(preview, EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        LivingEntityUseAccessor use = (LivingEntityUseAccessor) preview;
        use.sbs$setUseItem(ItemStack.EMPTY);
        use.sbs$setUseItemRemaining(0);
        use.sbs$setLivingEntityFlag(1, false);
        use.sbs$setLivingEntityFlag(2, false);
        preview.swinging = false;
        preview.swingTime = 0;
        preview.swingingArm = null;   // what a fresh preview has - the off state is pixel for pixel
        preview.attackAnim = 0.0F;
    }

    /** Sets a hand only when the reference changed, so an unchanged frame does no work. */
    private static void setHand(AbstractClientPlayer preview, EquipmentSlot slot, ItemStack stack) {
        if (preview.getItemBySlot(slot) != stack) {
            preview.setItemSlot(slot, stack);
        }
    }

    /**
     * The look point that makes {@code extractEntityInInventoryFollowsMouse} turn the head the way
     * the live player's head is turned. The method sets the head's yaw relative to the body to
     * {@code atan((cx - lookX) / 40) * 20} and its pitch to {@code -atan((cy - lookY) / 40) * 20}
     * (javap, 26.2), so the inverse is {@code lookX = cx - 40 tan(yaw / 20)} and
     * {@code lookY = cy + 40 tan(pitch / 20)}. The formula cannot express more than about 31 degrees
     * either way, so both are clamped to {@link #MAX_HEAD_TURN}. Two methods rather than one
     * returning a pair: this runs every frame and a pair would be an allocation per frame.
     */
    static final float MAX_HEAD_TURN = 30.0F;

    /** The live player's head yaw relative to their body, clamped to what the model can show. */
    static float relativeYaw(Player live) {
        return Mth.clamp(Mth.wrapDegrees(live.getYHeadRot() - live.yBodyRot), -MAX_HEAD_TURN, MAX_HEAD_TURN);
    }

    /** The live player's pitch, clamped the same way. */
    static float pitch(Player live) {
        return Mth.clamp(live.getXRot(), -MAX_HEAD_TURN, MAX_HEAD_TURN);
    }

    static float lookX(float cx, float relativeYaw) {
        return cx - 40.0F * (float) Math.tan(relativeYaw / 20.0F);
    }

    static float lookY(float cy, float pitch) {
        return cy + 40.0F * (float) Math.tan(pitch / 20.0F);
    }

    /** What the vanilla formula turns a look point into, for the test. */
    static float yawFor(float cx, float lookX) {
        return (float) Math.atan((cx - lookX) / 40.0F) * 20.0F;
    }

    static float pitchFor(float cy, float lookY) {
        return -(float) Math.atan((cy - lookY) / 40.0F) * 20.0F;
    }
}
