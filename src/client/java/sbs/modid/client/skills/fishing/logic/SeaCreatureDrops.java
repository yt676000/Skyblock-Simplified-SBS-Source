/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.EntityKind;
import sbs.modid.client.core.entity.NameClass;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.skills.fishing.model.FishingData;

import java.util.List;

/**
 * The short-lived ledger of sea creatures the player was near when they died - the eligibility half
 * of rare-drop attribution ({@link DropAttribution} is the rule, this is its input).
 *
 * <p><b>Detection.</b> A sea creature is its health nametag ("[Lv…] Lord Jawbus 50M/50M❤"): the
 * armor-stand bucket of {@link EntityIndex} is walked a few times a second, rejecting in the cheap
 * order (distance, name class, cached mob name), and every stand whose mob name is a known sea
 * creature is remembered with its last position. When a remembered stand leaves the level while the
 * player is within the configured radius of where it last stood, the creature died next to the
 * player - the kill, an assist, or simply standing beside it. A stand that leaves while the player
 * is far away (walked off, warped) never qualifies.
 *
 * <p><b>Why no "hit" ledger.</b> A melee hit needs the player within reach, which the radius already
 * covers, and the abilities lava fishers kill with (the capture this was built from is all
 * Implosion) hit by area, so there is no per-entity hit event to read. A kill made purely from range
 * beyond the radius is the one case this misses; the radius is a setting for that reason.
 *
 * <p><b>Ownership.</b> Not checked. No capture in this tree records a sea creature's nametag stack,
 * so whether Hypixel puts a "Spawned by" line on them is unknown; until a probe shows one, a
 * neighbour's creature dying beside the player is eligible too. The death log line names every
 * eligible creature, which is what that probe reads.
 */
public final class SeaCreatureDrops {

    private static final SeaCreatureDrops INSTANCE = new SeaCreatureDrops();

    /** How far from the player a sea-creature nametag is followed at all (blocks). */
    private static final double FOLLOW_RANGE = 48.0;

    /** Ticks between ledger scans: a death is a removal, so a quarter-second resolution loses nothing. */
    private static final int SCAN_EVERY_TICKS = 5;

    /** One followed creature: its name and where its nametag last stood. */
    private static final class Seen {
        final String creature;
        double x;
        double y;
        double z;
        boolean live;

        Seen(String creature) {
            this.creature = creature;
        }
    }

    private final Int2ObjectOpenHashMap<Seen> followed = new Int2ObjectOpenHashMap<>();
    private final DropAttribution attribution = new DropAttribution();
    private int tickCounter;

    private SeaCreatureDrops() {
    }

    public static SeaCreatureDrops getInstance() {
        return INSTANCE;
    }

    /** The configured death radius (blocks). */
    static int radius() {
        return Math.max(1, ConfigManager.getInstance().get().fishing.rareDropRadius);
    }

    /** The configured attribution window (ms). */
    static long windowMs() {
        return Math.max(5, ConfigManager.getInstance().get().fishing.rareDropWindowSec) * 1000L;
    }

    /** Called from {@link FishingTracker#onTick()}, which already carries the fishing gate. */
    void onTick(Player player, long now) {
        if (++tickCounter < SCAN_EVERY_TICKS) {
            return;
        }
        tickCounter = 0;
        for (Int2ObjectMap.Entry<Seen> entry : followed.int2ObjectEntrySet()) {
            entry.getValue().live = false;
        }
        double rangeSq = FOLLOW_RANGE * FOLLOW_RANGE;
        List<Tracked> stands = EntityIndex.bucket(EntityKind.ARMOR_STAND);
        for (int i = 0; i < stands.size(); i++) {
            Tracked tracked = stands.get(i);
            Entity stand = tracked.entity;
            if (stand.distanceToSqr(player) > rangeSq || tracked.nameClass() != NameClass.HEALTH_TAG) {
                continue;
            }
            Seen seen = followed.get(stand.getId());
            if (seen == null) {
                String creature = FishingData.canonicalSeaCreature(tracked.mobName());
                if (creature == null) {
                    continue;
                }
                seen = new Seen(creature);
                followed.put(stand.getId(), seen);
            }
            seen.x = stand.getX();
            seen.y = stand.getY();
            seen.z = stand.getZ();
            seen.live = true;
        }
        double radiusSq = (double) radius() * radius();
        var it = followed.int2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            Int2ObjectMap.Entry<Seen> entry = it.next();
            Seen seen = entry.getValue();
            if (seen.live) {
                continue;
            }
            it.remove();
            if (EntityIndex.get(entry.getIntKey()) != null) {
                continue;   // still in the level, just out of follow range or renamed
            }
            double distanceSq = player.distanceToSqr(seen.x, seen.y, seen.z);
            if (distanceSq <= radiusSq) {
                attribution.onDeath(seen.creature, now);
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Fishing][Drop] {} died {} blocks away - eligible for {}s",
                        seen.creature, Math.round(Math.sqrt(distanceSq)), windowMs() / 1000);
            }
        }
    }

    /** The creature that explains {@code itemId} arriving now, or null (see {@link DropAttribution}). */
    String attribute(String itemId, long now) {
        return attribution.attribute(itemId, now, windowMs());
    }

    /** The newest eligible death inside the window, for logging pickups the table cannot explain. */
    String recentDeath(long now) {
        return attribution.recentDeath(now, windowMs());
    }

    /** World change: every followed entity id belongs to the old level. */
    public void onWorldChange() {
        followed.clear();
        attribution.reset();
        tickCounter = 0;
    }

    /** Session reset: forget the deaths, keep following what is on screen. */
    void resetSession() {
        attribution.reset();
    }
}
