/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorsix.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Giant;
import net.minecraft.world.phys.Vec3;
import sbs.modid.client.combat.mobhighlight.logic.MobHighlightTracker;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.events.DungeonEvents;
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
 * Giant health for Sadan's second phase (F6 / M6): what each of the four has left, where you can
 * actually read it.
 *
 * <p><b>The health is on screen already - that is not the problem.</b> A giant is about twelve
 * blocks tall and its nametag floats above <i>that</i>, so in the arena the numbers sit above the
 * top of the screen while you are standing in melee looking at its knees. The fight is decided by
 * focusing one giant down before the next damage window, and that decision needs a number nobody can
 * see. So this reads the tags and puts them somewhere useful: a card, and the number drawn low on
 * the giant it belongs to.
 *
 * <p><b>Read from the nametag, not from the entity.</b> The client's copy of a Hypixel mob's health
 * is a vanilla number that has nothing to do with the millions in its tag - the tag is the only place
 * the real value exists. Each tag is matched to the giant standing under it, which for a mob this
 * tall means searching a good deal further down than the usual stand→mob lookup does.
 *
 * <p>Names are matched loosely on purpose: the four are The Diamond Giant, L.A.S.R., Bigfoot and The
 * Jolly Pink Giant, Master Mode replaces all four with Mutant Giants, and only two of those six
 * names contain the word "giant" at all - so the entity type carries the match wherever the name
 * does not.
 */
public final class GiantHpTracker {

    private static final GiantHpTracker INSTANCE = new GiantHpTracker();

    /** The arena fits inside this; a giant is big enough to be read from across it. */
    private static final double SCAN_RADIUS = 64.0;

    /** How far below a nametag its giant may stand - they are roughly twelve blocks tall. */
    private static final double TAG_HEIGHT = 18.0;
    private static final double TAG_SPREAD = 5.0;

    /** Health marks in a nametag - what tells a mob tag from a decoration stand. */
    private static final String HEALTH_MARKS = "❤♥";

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    /** The four by name, for the ones whose entity is not a vanilla giant underneath. */
    private static final Set<String> GIANT_NAMES = Set.of(
            "the diamond giant", "l.a.s.r.", "lasr", "bigfoot", "the jolly pink giant", "mutant giant");

    /**
     * One giant: its name, the health exactly as its tag words it, the fraction left ({@code -1} when
     * the tag could not be parsed into one) and where to draw it - low on the body, not up at the tag.
     */
    public record GiantInfo(String name, String health, double fraction, Vec3 anchor) {
    }

    private volatile List<GiantInfo> giants = List.of();

    /** The fullest each giant has been seen this fight - the maximum its own tag never states. */
    private final Map<String, Double> maxSeen = new HashMap<>();

    private GiantHpTracker() {
    }

    public static GiantHpTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /** The giants that are up right now, weakest first - that is the one being focused. */
    public List<GiantInfo> giants() {
        return giants;
    }

    /** Called every client tick: reads the giant tags while Sadan's arena is the room you are in. */
    public void onClientTick() {
        DungeonStateManager state = DungeonStateManager.getInstance();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (!cfg().giantHp || !state.inDungeon() || state.floorNumber() != 6
                || state.phase() != DungeonEvents.Phase.BOSS || level == null || player == null) {
            giants = List.of();
            maxSeen.clear(); // a new fight starts its own reference points
            return;
        }
        collect(level, player);
    }

    /**
     * Searched giant-first, not tag-first: there are four giants and there can be thirty named mobs
     * in that arena, so asking "what is above this giant" four times is cheaper than asking "is
     * there a giant under this tag" for every terracotta in the room.
     */
    private void collect(ClientLevel level, LocalPlayer player) {
        List<GiantInfo> found = new ArrayList<>(4);
        Set<Integer> used = new HashSet<>();

        for (Giant giant : level.getEntitiesOfClass(Giant.class,
                player.getBoundingBox().inflate(SCAN_RADIUS), Entity::isAlive)) {
            LivingEntity tag = tagAbove(level, giant);
            if (tag == null) {
                continue;
            }
            Component custom = tag.getCustomName();
            String raw = custom == null ? "" : custom.getString();
            String name = MobHighlightTracker.mobNameInNametag(raw);
            String health = healthIn(raw);
            if (name == null || health == null) {
                continue;
            }
            used.add(tag.getId());
            // Drawn at the head height of an ordinary mob rather than at the giant's own - low
            // enough to be on screen while you are hitting its legs, which is where this is fought.
            found.add(new GiantInfo(name, health, fractionOf(name, health), giant.position().add(0, 3.0, 0)));
        }

        // Second pass, by name only: a giant that is not built out of a vanilla giant, or whose body
        // has not loaded. Costs one walk of the entity list and no queries at all.
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || used.contains(living.getId())
                    || !living.hasCustomName()
                    || living.distanceToSqr(player) > SCAN_RADIUS * SCAN_RADIUS) {
                continue;
            }
            Component custom = living.getCustomName();
            String raw = custom == null ? "" : custom.getString();
            String name = MobHighlightTracker.mobNameInNametag(raw);
            if (name == null || !isGiantName(name)) {
                continue;
            }
            String health = healthIn(raw);
            if (health != null) {
                found.add(new GiantInfo(name, health, fractionOf(name, health), living.position()));
            }
        }

        // Weakest first: the card's top line is then always the one worth hitting.
        found.sort(Comparator.comparingDouble(giant ->
                giant.fraction() < 0 ? Double.MAX_VALUE : giant.fraction()));
        giants = List.copyOf(found);
    }

    /** The nametag belonging to a giant: the nearest tag with health on it, standing above the body. */
    private static LivingEntity tagAbove(ClientLevel level, Giant giant) {
        List<LivingEntity> tags = level.getEntitiesOfClass(LivingEntity.class,
                giant.getBoundingBox().inflate(TAG_SPREAD, 0, TAG_SPREAD).expandTowards(0, TAG_HEIGHT, 0),
                candidate -> candidate != giant && candidate.hasCustomName());
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity tag : tags) {
            double distance = tag.distanceToSqr(giant);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = tag;
            }
        }
        return best;
    }

    private static boolean isGiantName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("giant") || GIANT_NAMES.contains(lower);
    }

    /**
     * The health readout in a nametag ({@code "8.2M/20M"}), or null when there is none. Taken as the
     * run of digits and scale letters in front of the heart, so it survives whatever colouring and
     * spacing Hypixel puts around it.
     */
    public static String healthIn(String raw) {
        String clean = raw.replaceAll(SECTION_SIGN + ".", "");
        int heart = -1;
        for (int i = clean.length() - 1; i >= 0; i--) {
            if (HEALTH_MARKS.indexOf(clean.charAt(i)) >= 0) {
                heart = i;
                break;
            }
        }
        if (heart <= 0) {
            return null;
        }
        int start = heart;
        while (start > 0 && isHealthChar(clean.charAt(start - 1))) {
            start--;
        }
        String health = clean.substring(start, heart).trim();
        // Both shapes are accepted: "8.2M/20M" and the bare "8.2M" Hypixel uses on plenty of mobs.
        // Insisting on the slash silently dropped every giant whose tag does not carry a maximum.
        return health.isEmpty() ? null : health;
    }

    private static boolean isHealthChar(char c) {
        return Character.isDigit(c) || c == '.' || c == ',' || c == '/'
                || "kKmMbB".indexOf(c) >= 0;
    }

    /**
     * How much of it is left, {@code -1} when that cannot be worked out.
     *
     * <p>{@code "8.2M/20M"} answers itself. A bare {@code "8.2M"} does not, so the maximum is taken
     * from the highest reading this giant has shown during the fight - which is its full health,
     * because the first time you see one it has not been hit yet. Self-calibrating, and it costs a
     * name-keyed number per giant.
     */
    private double fractionOf(String name, String health) {
        int slash = health.indexOf('/');
        double current = amount(slash < 0 ? health : health.substring(0, slash));
        if (current < 0) {
            return -1;
        }
        double max = slash < 0 ? -1 : amount(health.substring(slash + 1));
        if (max <= 0) {
            max = maxSeen.merge(name, current, Math::max);
        }
        return max <= 0 ? -1 : Math.min(1.0, current / max);
    }

    /** {@code "8.2M"} → {@code 8_200_000}; -1 when it is not a number. */
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
