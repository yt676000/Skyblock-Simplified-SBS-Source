/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.helper.map.logic.MapDatabase;
import sbs.modid.client.helper.map.model.IslandMap;
import sbs.modid.client.helper.map.model.MapLocation;
import sbs.modid.client.skills.mining.model.CommissionTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a commission's name into somewhere to walk.
 *
 * <p><b>Matched against vocabularies, not against sentence templates.</b> Hypixel rewords its
 * commissions between mining updates, and a parser built on "{@code <place> <material>}" or on a list
 * of known verbs breaks the first time one is phrased differently. What does <i>not</i> change is that
 * the commission names a real place, a real mob or a real material, so the parse asks three sources
 * that already know those names: the island map data for places, the world itself for mobs, and
 * {@link CommissionBlocks} for materials. Anything that matches none of the three is reported unknown.
 *
 * <p><b>The world classifies mobs, so no mob table exists here.</b> "Is Goblin a mob?" is answered by
 * looking for something nearby called a Goblin rather than by consulting a list - which means the
 * classification is right by construction, needs no maintenance when Hypixel adds a mob, and cannot
 * be a table copied from anywhere. The cost is that a mob is only recognised once the player is
 * within scanning range of one, which is exactly when the answer is useful anyway.
 *
 * <p><b>Areas are the only fixed answer.</b> A place has one position for the commission's whole life,
 * so it is looked up once and never scanned for. Mobs and materials move and run out, so their
 * position is whatever was nearest on the last scan and is expected to change.
 */
public final class CommissionTargets {

    /** How far to look for a named mob. Beyond this the answer is "none nearby", not "no such mob". */
    private static final double MOB_RADIUS = 64.0;

    /** How far to look for a material block, as a cube half-edge around the player. */
    private static final int BLOCK_RADIUS = 24;

    /**
     * Words that carry no target on their own. Kept to the handful that are pure grammar: every one
     * of these appears <i>around</i> the thing being named rather than being part of its name, so
     * dropping them cannot hide a place, a mob or a material.
     */
    private static final List<String> FILLER = List.of("the", "a", "an", "of", "and", "commission");

    private CommissionTargets() {
    }

    /**
     * Works out where {@code commissionName} sends the player, from where they are standing now.
     *
     * <p>Called on the scan throttle rather than once, because two of the three answers are live: the
     * nearest Goblin is a different Goblin a minute later, and the ore that was nearest may have been
     * mined out. An area, once found, is returned unchanged every time and costs a map lookup.
     */
    public static CommissionTarget resolve(String commissionName, ClientLevel level, BlockPos from) {
        String name = PlainText.strip(commissionName == null ? "" : commissionName).trim();
        if (name.isEmpty()) {
            return CommissionTarget.unresolved(name);
        }

        CommissionTarget area = matchArea(name);
        if (area != null) {
            return area;
        }
        if (level == null || from == null) {
            return CommissionTarget.unresolved(name);
        }

        List<String> tokens = tokensOf(name);
        CommissionTarget mob = matchMob(name, tokens, level, from);
        if (mob != null) {
            return mob;
        }
        CommissionTarget material = matchMaterial(name, tokens, level, from);
        if (material != null) {
            return material;
        }
        // Nothing matched. The token list goes into the target so the log and the command can say
        // WHICH word went unrecognised - that is what makes the vocabulary extendable instead of
        // leaving "it doesn't work" as the only available report.
        return new CommissionTarget(CommissionTarget.Kind.UNRESOLVED, name,
                tokens.isEmpty() ? "" : String.join(" ", tokens), null, "");
    }

    // ------------------------------------------------------------------ areas

    /**
     * The island map's place whose area or name the commission mentions, longest match first so
     * "Upper Mines" is never lost to a shorter "Mines" sitting elsewhere in the table.
     *
     * <p>A place the map file knows by name but carries no usable coordinates for cannot be routed to,
     * and is reported unresolved with the name that was recognised - which is the shape of the Glacite
     * gap: the tunnels are a real place with real commissions and no interior anchor data yet.
     */
    private static CommissionTarget matchArea(String name) {
        IslandMap map = MapDatabase.current();
        return map == null ? null : matchArea(name, map.locations);
    }

    /**
     * {@link #matchArea(String)} against an explicit table, for tests. The destination is the
     * location's commission anchor when it has one, else its marker.
     */
    static CommissionTarget matchArea(String name, List<MapLocation> locations) {
        String haystack = normalise(name);
        MapLocation best = null;
        String bestKey = "";
        for (MapLocation location : locations) {
            for (String candidate : new String[]{location.area, location.name}) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                String key = normalise(candidate);
                if (key.length() > bestKey.length() && haystack.contains(key)) {
                    best = location;
                    bestKey = key;
                }
            }
        }
        if (best == null) {
            return null;
        }
        String label = best.area == null || best.area.isBlank() ? best.name : best.area;
        return new CommissionTarget(CommissionTarget.Kind.AREA, name, label,
                best.commissionPos(), best.warp == null ? "" : best.warp);
    }

    // ------------------------------------------------------------------ mobs

    /** The nearest living thing whose display name carries one of the commission's words. */
    private static CommissionTarget matchMob(String name, List<String> tokens, ClientLevel level,
                                             BlockPos from) {
        if (tokens.isEmpty()) {
            return null;
        }
        AABB box = new AABB(from).inflate(MOB_RADIUS);
        Entity nearest = null;
        String matched = "";
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : level.getEntities(null, box)) {
            String display = displayName(entity);
            if (display.isEmpty()) {
                continue;
            }
            for (String token : tokens) {
                if (!display.contains(token)) {
                    continue;
                }
                double distance = entity.blockPosition().distSqr(from);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = entity;
                    matched = token;
                }
                break;
            }
        }
        if (nearest == null) {
            return null;
        }
        return new CommissionTarget(CommissionTarget.Kind.MOB, name, matched,
                nearest.blockPosition(), "");
    }

    /**
     * An entity's name reduced to letters, or empty when it carries none.
     *
     * <p><b>Custom names only, on purpose.</b> Hypixel labels its mobs - that nametag is what makes a
     * zombie a "Goblin" - so the custom name is the only one that can answer what a commission is
     * talking about. Falling back to the vanilla type name would match a commission naming a real mob
     * against every ordinary zombie in the tunnel, which is a confident wrong answer rather than a
     * missing one.
     */
    private static String displayName(Entity entity) {
        return entity.hasCustomName() && entity.getCustomName() != null
                ? normalise(entity.getCustomName().getString()) : "";
    }

    // ------------------------------------------------------------------ materials

    /**
     * The nearest block belonging to a material the commission names.
     *
     * <p>Returns {@code null} - not an empty target - when nothing is known about any of the words,
     * so the caller reports unresolved rather than "searching forever". With
     * {@link CommissionBlocks} empty, which is how it ships, this is every material commission.
     */
    private static CommissionTarget matchMaterial(String name, List<String> tokens, ClientLevel level,
                                                  BlockPos from) {
        for (String token : tokens) {
            List<String> fragments = CommissionBlocks.fragmentsFor(token);
            if (fragments.isEmpty()) {
                continue;
            }
            BlockPos found = nearestBlock(level, from, fragments);
            if (found != null) {
                return new CommissionTarget(CommissionTarget.Kind.MATERIAL, name, token, found, "");
            }
            // The material is known but none is in range: still a material commission, still worth
            // saying so, and the scan will find one when the player is closer.
            return new CommissionTarget(CommissionTarget.Kind.MATERIAL, name, token, null, "");
        }
        return null;
    }

    /**
     * Nearest matching block within {@link #BLOCK_RADIUS}, expanding shell by shell so the search
     * stops at the first hit rather than measuring the whole cube. One mutable cursor for the whole
     * walk - a fresh {@code BlockPos} per read would dominate the cost, the same reason
     * {@code Walkability} takes a caller-owned cursor.
     */
    private static BlockPos nearestBlock(ClientLevel level, BlockPos from, List<String> fragments) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int radius = 1; radius <= BLOCK_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        // Only the shell: everything closer was covered by a previous radius.
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != radius) {
                            continue;
                        }
                        cursor.set(from.getX() + dx, from.getY() + dy, from.getZ() + dz);
                        if (matches(level, cursor, fragments)) {
                            return cursor.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean matches(ClientLevel level, BlockPos pos, List<String> fragments) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().toLowerCase(Locale.ROOT);
        for (String fragment : fragments) {
            if (id.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ text

    /**
     * The words of a commission worth asking the world about, longest first so a two-word name is
     * tried before either of its halves ("Ice Walker" before "Ice").
     */
    static List<String> tokensOf(String name) {
        String[] words = normalise(name).split(" ");
        List<String> tokens = new ArrayList<>(words.length * 2);
        for (int i = 0; i + 1 < words.length; i++) {
            if (!words[i].isBlank() && !words[i + 1].isBlank()) {
                tokens.add(words[i] + " " + words[i + 1]);
            }
        }
        for (String word : words) {
            if (word.length() > 2 && !FILLER.contains(word)) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    /** Lower case, punctuation dropped, single-spaced - the form both sides of a match are in. */
    private static String normalise(String raw) {
        return PlainText.strip(raw == null ? "" : raw)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
