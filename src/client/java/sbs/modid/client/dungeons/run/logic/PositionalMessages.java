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
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSConfig.PositionalMessage;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Positional Messages: fires a configured message when the player comes within a saved coordinate's
 * radius. Each entry chooses local-overlay only or an outbound {@code /pc} party message, so the whole
 * feature is off by default and party sends are opt-in per entry (sanitized) - it is user-configured,
 * not automation of gameplay.
 *
 * <p>Fires <b>once on entering</b> the radius and re-arms only after the player leaves it (×1.5), so a
 * single approach never spams. Managed with {@code /sbs posmsg add|party|list|clear}. Ticked from the
 * tracking mixin.
 */
public final class PositionalMessages {

    private static final PositionalMessages INSTANCE = new PositionalMessages();

    /** Entries the player is currently inside (fired), by identity, to fire once per approach. */
    private final Set<PositionalMessage> inside =
            Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private PositionalMessages() {
    }

    public static PositionalMessages getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** Called once per client tick. */
    public void tick(Minecraft minecraft) {
        SBSConfig.DungeonsSettings cfg = cfg();
        LocalPlayer player = minecraft.player;
        if (player == null || !cfg.positionalMessagesEnabled || cfg.positionalMessages.isEmpty()) {
            if (!inside.isEmpty()) {
                inside.clear();
            }
            return;
        }
        Vec3 pos = player.position();
        for (PositionalMessage entry : cfg.positionalMessages) {
            double radius = Math.max(1, entry.radius);
            double distSqr = pos.distanceToSqr(entry.x + 0.5, entry.y, entry.z + 0.5);
            if (distSqr <= radius * radius) {
                if (inside.add(entry)) {
                    fire(player, entry); // just entered
                }
            } else if (distSqr > (radius * 1.5) * (radius * 1.5)) {
                inside.remove(entry); // left → re-arm
            }
        }
    }

    private void fire(LocalPlayer player, PositionalMessage entry) {
        String message = sanitize(entry.message);
        if (message.isEmpty()) {
            return;
        }
        if (entry.party) {
            // Only the party branch is tagged: the overlay line is for the player's own eyes, and
            // nobody needs to be told which mod drew text on their own screen.
            player.connection.sendCommand("pc " + sbs.modid.client.core.util.ChatTag.tag(message));
        } else {
            player.sendOverlayMessage(Component.literal("§b" + message));
        }
    }

    // ------------------------------------------------------------------
    // Command handling (/sbs posmsg ...)
    // ------------------------------------------------------------------

    /** Handles {@code /sbs posmsg <args>}; returns feedback text for the caller to show. */
    public String handleCommand(String args) {
        SBSConfig.DungeonsSettings cfg = cfg();
        String[] parts = args.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase(java.util.Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        LocalPlayer player = Minecraft.getInstance().player;
        return switch (sub) {
            case "add", "party" -> {
                if (player == null) {
                    yield "§cNot in a world.";
                }
                if (rest.isEmpty()) {
                    yield "§cUsage: /sbs posmsg " + sub + " <message>";
                }
                BlockPos at = player.blockPosition();
                PositionalMessage entry = new PositionalMessage();
                entry.x = at.getX();
                entry.y = at.getY();
                entry.z = at.getZ();
                entry.message = sanitize(rest);
                entry.party = sub.equals("party");
                cfg.positionalMessages.add(entry);
                ConfigManager.getInstance().save();
                yield "§aSaved " + (entry.party ? "party" : "local") + " message at " + at.getX() + ","
                        + at.getY() + "," + at.getZ() + (cfg.positionalMessagesEnabled ? "" : " §7(module is off)");
            }
            case "list" -> {
                if (cfg.positionalMessages.isEmpty()) {
                    yield "§7No positional messages.";
                }
                StringBuilder sb = new StringBuilder("§7Positional messages:");
                List<PositionalMessage> list = cfg.positionalMessages;
                for (int i = 0; i < list.size(); i++) {
                    PositionalMessage entry = list.get(i);
                    sb.append("\n§8").append(i + 1).append(". §f").append(entry.message)
                            .append(" §7@ ").append(entry.x).append(',').append(entry.y).append(',').append(entry.z)
                            .append(entry.party ? " §d[party]" : " §b[local]");
                }
                yield sb.toString();
            }
            case "clear" -> {
                cfg.positionalMessages.clear();
                inside.clear();
                ConfigManager.getInstance().save();
                yield "§aCleared all positional messages.";
            }
            default -> "§7/sbs posmsg add|party|list|clear — add saves a message at your current spot";
        };
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replaceAll("(?i)" + (char) 0x00A7 + ".", "")
                .replaceAll("[^\\x20-\\x7E]", "").trim();
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }
}
