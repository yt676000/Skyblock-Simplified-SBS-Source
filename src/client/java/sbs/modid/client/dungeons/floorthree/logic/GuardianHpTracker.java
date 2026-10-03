/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorthree.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.events.DungeonEvents;
import sbs.modid.client.dungeons.floorsix.logic.GiantHpTracker;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Guardian health for the Professor's first phase (F3 / M3): what each of the four has left, in one
 * place.
 *
 * <p><b>The four are not in one place, which is the problem.</b> They sit apart around the room
 * behind their own water and their own laser, and the Professor stays untouchable until the last one
 * is down - so the question the phase actually asks is "which one is nearly dead and is anyone on
 * it", and answering it means turning around and squinting at three nametags across a room full of
 * beams. A card answers it without turning.
 *
 * <p><b>Read from the nametag, not from the entity.</b> The client's copy of a Hypixel mob's health
 * is a vanilla number with nothing to do with the millions in its tag - the tag is the only place the
 * real value exists. A guardian usually wears its own tag; when it wears a separate stand instead,
 * the nearest named thing just above the body is taken, the same association the Mob Highlight uses.
 *
 * <p><b>Kept apart by entity id, not by name.</b> All four read "Guardian", so the calibration a
 * bare {@code "1.2M"} tag needs - the fullest that one has been seen this fight - is keyed to the
 * entity. Keying it by name, the way the giants can afford to, would merge all four into one number
 * and hand three of them somebody else's maximum. That is also why the card carries a distance: four
 * identical labels in a list say nothing about which is which, and how far away it is happens to be
 * the thing you want to know about the weakest one anyway.
 */
public final class GuardianHpTracker {

    private static final GuardianHpTracker INSTANCE = new GuardianHpTracker();

    /** The Professor's room fits inside this comfortably from anywhere you can stand in it. */
    private static final double SCAN_RADIUS = 64.0;

    /** How far above an ordinary-sized mob its separate nametag stand may float. */
    private static final double TAG_HEIGHT = 3.0;
    private static final double TAG_SPREAD = 1.0;

    /** Where the number goes: just clear of the body, not up at the tag. */
    private static final double ANCHOR_HEIGHT = 1.4;

    /** What every one of them is called, and the fallback when a tag carries only a number. */
    private static final String GUARDIAN = "Guardian";

    /**
     * One guardian: its name as the tag words it, the health likewise, the fraction left ({@code -1}
     * when the tag could not be parsed into one), where to draw the number and how far off it is.
     */
    public record GuardianInfo(String name, String health, double fraction, Vec3 anchor, int distance) {
    }

    private volatile List<GuardianInfo> guardians = List.of();

    /** The fullest each guardian has been seen this fight, by entity id - see the class note. */
    private final Map<Integer, Double> maxSeen = new HashMap<>();

    private GuardianHpTracker() {
    }

    public static GuardianHpTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** The guardians that are up right now, weakest first - that is the one being focused. */
    public List<GuardianInfo> guardians() {
        return guardians;
    }

    /** Called every client tick: reads the tags while the Professor's room is the room you are in. */
    public void onClientTick() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (!cfg().guardianHp || !state.inDungeon() || state.floorNumber() != 3
                || state.phase() != DungeonEvents.Phase.BOSS || level == null || player == null) {
            guardians = List.of();
            maxSeen.clear(); // a new fight starts its own reference points
            return;
        }
        collect(level, player);
    }

    /**
     * Searched guardian-first: there are four of them and the room holds a great many other named
     * things, so asking "what is written above this guardian" four times beats asking "is there a
     * guardian under this" for every tag in the fight.
     */
    private void collect(ClientLevel level, LocalPlayer player) {
        List<GuardianInfo> found = new ArrayList<>(4);
        Set<Integer> used = new HashSet<>();

        for (Guardian guardian : level.getEntitiesOfClass(Guardian.class,
                player.getBoundingBox().inflate(SCAN_RADIUS), Entity::isAlive)) {
            LivingEntity tag = tagFor(level, guardian);
            if (tag == null) {
                continue;
            }
            Component custom = tag.getCustomName();
            String raw = custom == null ? "" : custom.getString();
            String health = GiantHpTracker.healthIn(raw);
            if (health == null) {
                continue;
            }
            used.add(guardian.getId());
            used.add(tag.getId());
            // A tag that is nothing but a number still describes a guardian - we are standing on one.
            String name = MobHighlightTracker.mobNameInNametag(raw);
            found.add(info(guardian.getId(), name == null ? GUARDIAN : name, health,
                    guardian.position().add(0, ANCHOR_HEIGHT, 0), player));
        }

        // Second pass, by name only: a guardian Hypixel did not build out of a vanilla one, or whose
        // body has not loaded yet. One walk of the entity list, no queries.
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living instanceof Player
                    || used.contains(living.getId()) || !living.hasCustomName()
                    || living.distanceToSqr(player) > SCAN_RADIUS * SCAN_RADIUS) {
                continue;
            }
            Component custom = living.getCustomName();
            String raw = custom == null ? "" : custom.getString();
            String name = MobHighlightTracker.mobNameInNametag(raw);
            if (name == null || !name.toLowerCase(Locale.ROOT).contains("guardian")) {
                continue;
            }
            String health = GiantHpTracker.healthIn(raw);
            if (health != null) {
                found.add(info(living.getId(), name, health, living.position(), player));
            }
        }

        // Weakest first: the card's top line is then always the one worth hitting.
        found.sort(Comparator.comparingDouble(guardian ->
                guardian.fraction() < 0 ? Double.MAX_VALUE : guardian.fraction()));
        guardians = List.copyOf(found);
    }

    private GuardianInfo info(int id, String name, String health, Vec3 anchor, LocalPlayer player) {
        return new GuardianInfo(name, health, fractionOf(id, health), anchor,
                (int) Math.round(Math.sqrt(player.distanceToSqr(anchor))));
    }

    /**
     * The tag a guardian's health is written on: its own name when it wears one, otherwise the
     * nearest named thing standing just above it. Both shapes turn up on Hypixel and the cheap case
     * is the common one, so it is tried first and costs no query at all.
     */
    private static LivingEntity tagFor(ClientLevel level, Guardian guardian) {
        Component own = guardian.getCustomName();
        if (own != null && GiantHpTracker.healthIn(own.getString()) != null) {
            return guardian;
        }
        List<LivingEntity> tags = level.getEntitiesOfClass(LivingEntity.class,
                guardian.getBoundingBox().inflate(TAG_SPREAD, 0, TAG_SPREAD)
                        .expandTowards(0, TAG_HEIGHT, 0),
                candidate -> candidate != guardian && candidate.hasCustomName());
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity tag : tags) {
            double distance = tag.distanceToSqr(guardian);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = tag;
            }
        }
        return best;
    }

    /**
     * How much of it is left, {@code -1} when that cannot be worked out. {@code "1.2M/2M"} answers
     * itself; a bare {@code "1.2M"} is measured against the fullest this guardian has been seen, which
     * is its maximum because the first sight of one is before anybody has hit it.
     */
    private double fractionOf(int id, String health) {
        int slash = health.indexOf('/');
        double current = amount(slash < 0 ? health : health.substring(0, slash));
        if (current < 0) {
            return -1;
        }
        double max = slash < 0 ? -1 : amount(health.substring(slash + 1));
        if (max <= 0) {
            max = maxSeen.merge(id, current, Math::max);
        }
        return max <= 0 ? -1 : Math.min(1.0, current / max);
    }

    /** {@code "1.2M"} → {@code 1_200_000}; -1 when it is not a number. */
    private static double amount(String text) {
        String value = text.trim().replace(",", "");
        if (value.isEmpty()) {
            return -1;
        }
        double scale = switch (Character.toLowerCase(value.charAt(value.length() - 1))) {
            case 'k' -> 1_000d;
            case 'm' -> 1_000_000d;
            case 'b' -> 1_000_000_000d;
            default -> 1d;
        };
        if (scale > 1) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Double.parseDouble(value) * scale;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
