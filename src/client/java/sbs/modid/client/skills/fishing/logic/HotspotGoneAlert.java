/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.skills.fishing.render.FishingAlert;

/**
 * Feeds {@link HotspotGoneWatch} from the world once a second while fishing, and raises the
 * centre-screen notice when it reports the fished hotspot gone. Its own scan, so it works with the
 * hotspot share buttons switched off.
 */
public final class HotspotGoneAlert {

    private static final HotspotGoneAlert INSTANCE = new HotspotGoneAlert();
    private static final int SCAN_EVERY_TICKS = 20;

    private final HotspotGoneWatch watch = new HotspotGoneWatch();
    private int tickCounter;

    private HotspotGoneAlert() {
    }

    public static HotspotGoneAlert getInstance() {
        return INSTANCE;
    }

    /** Tick gate. */
    public static boolean on() {
        var fishing = ConfigManager.getInstance().get().fishing;
        return fishing.enabled && fishing.hotspotGoneAlert;
    }

    public void onClientTick() {
        if (++tickCounter < SCAN_EVERY_TICKS) {
            return;
        }
        tickCounter = 0;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        double r = HotspotGoneWatch.LOADED_RADIUS;
        AABB box = player.getBoundingBox().inflate(r, 16.0, r);
        List<HotspotGoneWatch.Pos> labels = new ArrayList<>();
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, box, ArmorStand::hasCustomName)) {
            var name = stand.getCustomName();
            if (name != null && HotspotParser.isHotspotLabel(PlainText.strip(name.getString()))) {
                labels.add(new HotspotGoneWatch.Pos(stand.getX(), stand.getZ()));
            }
        }
        var hook = player.fishing;
        HotspotGoneWatch.Pos bobber = hook == null ? null : new HotspotGoneWatch.Pos(hook.getX(), hook.getZ());
        if (watch.onScan(labels, bobber, new HotspotGoneWatch.Pos(player.getX(), player.getZ()))) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.info("[SBS][Fishing] fished hotspot despawned - alert shown");
            FishingAlert.getInstance().triggerHotspotGone();
        }
    }

    /** A new lobby has its own hotspots; a label gone with the old world is no despawn. */
    public void onWorldChange() {
        watch.reset();
        tickCounter = 0;
    }
}
