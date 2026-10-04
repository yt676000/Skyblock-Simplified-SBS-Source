/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.npc;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.core.location.SkyBlockLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code /sbs npccheck} (developer mode): settles a coordinate the table and the item repo disagree
 * on, by asking the world. Lists every catalogued NPC standing near the player - found by its
 * nametag stand, placed at the body under it - next to what {@link SkyblockNpcs} says for this
 * island and how far apart the two are. Reads only; nothing in the table changes.
 */
public final class NpcCheck {

    /** How far around the player nametags are read. */
    private static final double RADIUS = 16.0;

    /** A body counts as the stand's NPC when it is this close horizontally and not above the tag. */
    private static final double BODY_REACH = 1.5;

    /** One nametag seen in the world, at the NPC's feet when a body was found under it. */
    record Seen(String name, double x, double y, double z) {
    }

    private NpcCheck() {
    }

    /** Runs the check and prints it. Silent without a player. */
    public static void run() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        for (String line : report(SkyBlockLocation.island(), scan(player))) {
            player.sendSystemMessage(Component.literal("[SBS] " + line));
        }
    }

    /** The catalogued NPCs whose nametag stands around the player, nearest first. */
    private static List<Seen> scan(Player player) {
        Vec3 at = player.position();
        AABB box = new AABB(at, at).inflate(RADIUS);
        List<Seen> out = new ArrayList<>();
        for (ArmorStand stand : player.level().getEntitiesOfClass(ArmorStand.class, box,
                e -> !e.isRemoved() && e.hasCustomName())) {
            String name = SkyblockNpcs.cleanName(stand.getCustomName().getString());
            if (name.isEmpty() || SkyblockNpcs.findAll(name).isEmpty()) {
                continue;
            }
            Vec3 feet = bodyUnder(player, stand);
            out.add(new Seen(name, feet.x, feet.y, feet.z));
        }
        out.sort(Comparator.comparingDouble(s -> at.distanceToSqr(s.x(), s.y(), s.z())));
        return out;
    }

    /** The NPC body's feet under a nametag stand, or the stand itself when there is none. */
    private static Vec3 bodyUnder(Player player, ArmorStand stand) {
        Vec3 tag = stand.position();
        AABB below = new AABB(tag, tag).inflate(BODY_REACH, 3.0, BODY_REACH);
        LivingEntity best = null;
        for (LivingEntity body : player.level().getEntitiesOfClass(LivingEntity.class, below,
                e -> !(e instanceof ArmorStand) && e != player && e.getY() <= tag.y)) {
            if (best == null || body.distanceToSqr(tag) < best.distanceToSqr(tag)) {
                best = body;
            }
        }
        return best == null ? tag : best.position();
    }

    /**
     * What the check prints for {@code seen} on {@code island}: per NPC, where it really stands and
     * every table entry for it on this island with the distance. Pure, so it is testable.
     */
    static List<String> report(String island, List<Seen> seen) {
        List<String> lines = new ArrayList<>();
        if (seen.isEmpty()) {
            lines.add("§7No catalogued NPC nametag within " + (int) RADIUS + " blocks.");
            return lines;
        }
        for (Seen s : seen) {
            lines.add("§f" + s.name() + " §7stands at §f" + coords(s.x(), s.y(), s.z()));
            boolean any = false;
            for (SkyblockNpcs.Npc npc : SkyblockNpcs.findAll(s.name())) {
                if (island != null && !island.equalsIgnoreCase(npc.island())) {
                    continue;
                }
                any = true;
                double d = Math.sqrt(Math.pow(npc.x() - s.x(), 2) + Math.pow(npc.y() - s.y(), 2)
                        + Math.pow(npc.z() - s.z(), 2));
                lines.add("  §7table: §f" + coords(npc.x(), npc.y(), npc.z()) + " §7("
                        + String.format(Locale.ROOT, "%.1f", d) + " blocks" + (d > 8 ? ", §cover 8" : "") + "§7)");
            }
            if (!any) {
                lines.add("  §7table: §cnone on " + (island == null ? "this island" : island));
            }
        }
        return lines;
    }

    private static String coords(double x, double y, double z) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z);
    }
}
