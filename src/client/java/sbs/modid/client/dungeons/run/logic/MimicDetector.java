/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Announces the F6/F7/M6/M7 Mimic to party chat - a <b>notification only, never an auto-kill</b> and
 * never automation. Two paths, both off by default because they send a message on the player's behalf:
 * <ul>
 *   <li><b>Manual</b> (a keybind): the reliable path - press it when you see the mimic, it sends the
 *       configured message to party chat. No detection, no false positives.</li>
 *   <li><b>Auto</b> ({@code mimicAutoAnnounce}, default off): best-effort - fires when a trapped chest
 *       is broken on a mimic floor. Imperfect on purpose-noted: some rooms use a trapped chest as a
 *       normal secret, so this can mis-fire; that is why it is opt-in and the manual key exists.</li>
 * </ul>
 * The message is sanitized before it is ever sent, and each run announces at most once.
 */
public final class MimicDetector {

    private static final MimicDetector INSTANCE = new MimicDetector();

    private boolean announcedThisRun;
    private boolean wasInDungeon;

    private MimicDetector() {
    }

    public static MimicDetector getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** Resets the once-per-run guard when leaving the dungeon. Called from the tracking mixin. */
    public void tick(Minecraft minecraft) {
        boolean inDungeon = DungeonStateManager.getInstance().inDungeon();
        if (!inDungeon && wasInDungeon) {
            announcedThisRun = false;
        }
        wasInDungeon = inDungeon;
    }

    /** Manual announce (keybind): the reliable, user-triggered path. */
    public void onKeyPressed(int keyCode) {
        SBSConfig.DungeonsSettings cfg = cfg();
        if (keyCode != 0 && keyCode == cfg.mimicAnnounceKey && DungeonStateManager.getInstance().inDungeon()) {
            announce("manual");
        }
    }

    /** Auto path: a broken trapped chest on a mimic floor (best-effort; only when opted in). */
    public void onBlockBroken(BlockPos pos, BlockState state) {
        if (!cfg().mimicAutoAnnounce || state.getBlock() != Blocks.TRAPPED_CHEST) {
            return;
        }
        DungeonStateManager state2 = DungeonStateManager.getInstance();
        boolean mimicFloor = (state2.floorType() == 'F' || state2.floorType() == 'M') && state2.floorNumber() >= 6;
        if (state2.inDungeon() && mimicFloor) {
            announce("auto");
        }
    }

    /** Sends the announce once per run: party-chat message (sanitized) + a local sound/overlay. */
    private void announce(String source) {
        if (announcedThisRun) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        announcedThisRun = true;
        String message = sanitize(cfg().mimicMessage);
        if (!message.isEmpty()) {
            player.connection.sendCommand(
                    "pc " + sbs.modid.client.core.util.ChatTag.tag(message));
        }
        player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 0.8f);
        player.sendSystemMessage(Component.literal("§7[SBS] Mimic announced to party §8(" + source + ")"));
    }

    /** Party-chat text is user-authored but still clamped to a tidy single printable line. */
    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replaceAll("(?i)" + (char) 0x00A7 + ".", "")
                .replaceAll("[^\\x20-\\x7E]", "").trim();
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }
}
