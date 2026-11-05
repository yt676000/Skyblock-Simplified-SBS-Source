/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.hunting.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.EntityKind;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.core.util.PlainText;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds Trevor (the trapper NPC) for the cooldown line under his name, off the render path.
 *
 * <p>A throttled tick task, gated on the Farming Islands, picks the nearest entity named Trevor from
 * the {@link EntityIndex} buckets and keeps only its id; the HUD resolves that id per frame with
 * {@code level.getEntity(id)}. Hypixel NPCs are player entities carrying their own display name, but
 * the tag also turns up on a separate stand on some builds - so both are accepted.
 */
public final class TrevorLocator {

    /** How far from Trevor the cooldown line is worth drawing - it is a "standing at him" readout. */
    public static final double RANGE = 24.0;

    private static final int INTERVAL_TICKS = 10;

    private static final Pattern TREVOR = Pattern.compile("trevor", Pattern.CASE_INSENSITIVE);

    private static int tickCounter;
    private static int trevorId = -1;
    private static ClientLevel trevorLevel;
    /** Scratch for one look. */
    private static double bestDistance;

    private TrevorLocator() {
    }

    /** Whether a colour-stripped nametag is Trevor's. */
    static boolean isTrevorName(String plain) {
        return plain != null && !plain.isEmpty() && TREVOR.matcher(plain).find();
    }

    /** Tick body; the task gate already limits it to the Farming Islands. */
    public static void onClientTick() {
        SBSConfig.PeltSettings cfg = ConfigManager.getInstance().get().pelt;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (!cfg.enabled || !cfg.trapperCooldown || level == null || player == null) {
            clear();
            return;
        }
        if (level != trevorLevel) {
            clear();
            tickCounter = INTERVAL_TICKS;   // look right away in a fresh world
        }
        if (++tickCounter < INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        bestDistance = RANGE * RANGE;
        int found = scan(EntityIndex.bucket(EntityKind.PLAYER), true, x, y, z, -1);
        found = scan(EntityIndex.bucket(EntityKind.ARMOR_STAND), false, x, y, z, found);
        found = scan(EntityIndex.bucket(EntityKind.LIVING), false, x, y, z, found);
        trevorId = found;
        trevorLevel = level;
    }

    private static int scan(List<Tracked> bucket, boolean players, double x, double y, double z,
                            int found) {
        for (int i = 0, n = bucket.size(); i < n; i++) {
            Tracked tracked = bucket.get(i);
            Entity entity = tracked.entity;
            double distance = entity.distanceToSqr(x, y, z);
            if (distance >= bestDistance) {
                continue;
            }
            String plain = tracked.plain();
            if (plain.isEmpty() && players && entity.getCustomName() == null) {
                // NPC players carry the name as their profile name, not as a custom name.
                plain = PlainText.strip(entity.getName().getString());
            }
            if (isTrevorName(plain)) {
                bestDistance = distance;
                found = entity.getId();
            }
        }
        return found;
    }

    /** Trevor if he was found near the player on the last look, else {@code null}. Render thread. */
    public static Entity trevor(ClientLevel level) {
        if (trevorId < 0 || level == null || level != trevorLevel || !PeltTracker.onFarmingIslands()) {
            return null;
        }
        Entity entity = level.getEntity(trevorId);
        return entity == null || entity.isRemoved() ? null : entity;
    }

    private static void clear() {
        trevorId = -1;
        trevorLevel = null;
    }
}
