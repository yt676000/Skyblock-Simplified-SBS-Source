/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sbs.modid.client.core.command.SBSCommands;

/**
 * Runs our own commands when a chat {@code RunCommand} click names them ({@code [+ Khazad-dûm]},
 * Lowball's copy links). A click does not go through {@code ChatScreen.handleChatInput}, where
 * {@link ChatScreenMixin} catches typed {@code /sbs}: it goes {@code Screen.clickCommandAction}
 * → {@code ClientPacketListener.sendUnattendedCommand} → the server, which answers "Unknown command".
 *
 * <p>Verified with javap against 26.2: {@code static void clickCommandAction(LocalPlayer, String, Screen)}.
 * Anything {@link SBSCommands#tryExecute} does not claim goes on to vanilla unchanged.
 */
@Mixin(Screen.class)
public class ClickCommandMixin {

    @Inject(method = "clickCommandAction", at = @At("HEAD"), cancellable = true)
    private static void skyblockSimplified$runOwnCommand(LocalPlayer player, String command, Screen screen,
                                                         CallbackInfo ci) {
        if (command == null) {
            return;
        }
        String body = command.startsWith("/") ? command.substring(1) : command;
        if (SBSCommands.tryExecute(body)) {
            ci.cancel();
        }
    }
}
