/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.command;

import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.Set;

/**
 * Hypixel refuses a server transfer that comes too soon after the last one – the warp is simply
 * dropped and you stand where you were. This holds the second warp back instead of losing it: the
 * command is queued and sent the moment the cooldown is over.
 *
 * <p>Hooked into {@link SBSCommands#tryExecute(String)}, so it covers every route the mod knows –
 * typed in chat, from a keybind, from the Warp Menu and from the built-in short warps – and returns
 * {@code true} there to stop the too-early command from reaching the server at all.
 *
 * <p>The cooldown is measured from the last transfer command that actually went out, so a normal,
 * unhurried warp never waits.
 */
public final class TransferCooldown {

    private static final TransferCooldown INSTANCE = new TransferCooldown();

    /** Hypixel's transfer cooldown - measured, not documented, so it is a value and not a constant. */
    private static final long COOLDOWN_MS = 3000;

    /** The commands that move you to another server. */
    private static final Set<String> TRANSFER =
            Set.of("warp", "hub", "is", "island", "warpforge", "play");

    private long lastTransfer;

    /** The held-back command (without a leading slash) and the moment it may go out. */
    private String queued;
    private long queuedAt;

    private TransferCooldown() {
    }

    public static TransferCooldown getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.ShortCommandsSettings cfg() {
        return ConfigManager.getInstance().get().shortCommands;
    }

    /**
     * @param name    the command name, lower-case and without its leading slash
     * @param command the full command as typed (without the leading slash)
     * @return {@code true} when the command was held back – the caller must not send it
     */
    public boolean intercept(String name, String command) {
        SBSConfig.ShortCommandsSettings cfg = cfg();
        if (!cfg.enabled || !cfg.fixTransferCooldown || !TRANSFER.contains(name)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long remaining = COOLDOWN_MS - (now - lastTransfer);
        if (remaining <= 0) {
            lastTransfer = now;
            return false; // free to go – send it normally
        }
        queued = command;
        queuedAt = now + remaining;
        return true;
    }

    /** Sends the held-back command once the cooldown has run out. */
    public void onClientTick() {
        if (queued == null || System.currentTimeMillis() < queuedAt) {
            return;
        }
        String command = queued;
        queued = null;
        if (cfg().transferCooldownMessage) {
            SBSChat.send("Transfer cooldown over - running /" + command);
        }
        SBSCommands.run("/" + command);
    }
}
