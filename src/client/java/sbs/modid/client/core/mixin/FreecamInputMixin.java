/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.helper.build.logic.Freecam;

/**
 * While freecam is on, the movement keys move the camera and not the player: after vanilla has read
 * the keys, they are handed to {@link Freecam} and the player's input is emptied, so the player entity
 * stands exactly as if nothing were pressed. Only the client's own input object is touched - what is
 * sent is then what standing still sends.
 */
@Mixin(KeyboardInput.class)
public abstract class FreecamInputMixin extends ClientInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void skyblockSimplified$freecamInput(CallbackInfo ci) {
        if (!Freecam.active()) {
            return;
        }
        Input keys = this.keyPresses;
        Freecam.takeInput(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(),
                keys.sprint());
        this.keyPresses = Input.EMPTY;
        this.moveVector = Vec2.ZERO;
    }
}
