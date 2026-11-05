/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle.logic;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.EntityKind;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.dungeons.puzzle.logic.BlazeOrder.Direction;
import sbs.modid.client.dungeons.puzzle.model.PuzzleHighlight;
import sbs.modid.client.dungeons.puzzle.model.PuzzleType;
import sbs.modid.client.dungeons.run.logic.DungeonRoomBorders;
import sbs.modid.client.dungeons.run.logic.DungeonRoomTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Higher or Lower: the blaze puzzle. Each blaze floats its health; they die in health order, and one
 * killed out of order fails the room ("PUZZLE FAIL! &lt;player&gt; killed a Blaze in the wrong order!").
 * Boxes the next blaze strongly and the rest faintly. Reads and draws; never shoots or aims.
 *
 * <p><b>The direction is the player's setting ({@code blazeHighestFirst}), not detected.</b> The
 * variant is told apart by the room's chest - bottom: lowest first, top: highest first - and this
 * solver does find that chest on entry and work the rule out, but only <b>logs</b> it next to the
 * setting. The rule comes from the wiki and has not been watched in a run; until one run confirms it,
 * a detected direction that silently overrode the player's choice would be an unverified guess
 * deciding which blaze fails the room.
 *
 * <p>Runs only in the room {@link DungeonRoomTracker} identifies as Higher or Lower, so a slayer or
 * floor blaze elsewhere is never boxed.
 */
public final class BlazeSolver implements PuzzleStrategy {

    private static final int OTHER_COLOR = 0x80FFC94D;   // faint gold on the rest
    private static final String NEXT_LABEL = "§aNEXT";
    /** The blaze after the next one: cyan, so it never reads as the next. */
    private static final int AFTER_COLOR = 0xFF55FFFF;
    private static final String AFTER_LABEL = "§bTHEN";

    /** Entity id -> the plain nametag last parsed, so the regex only runs when a name changes. */
    private final Int2ObjectOpenHashMap<String> parsedNames = new Int2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap parsedHealth = new Int2LongOpenHashMap();

    /** Y of the room's one chest, found on entry; {@code Integer.MIN_VALUE} when not exactly one. */
    private int chestY = Integer.MIN_VALUE;
    private boolean chestSearched;
    private boolean directionLogged;
    /** The chest rule's verdict for this room, or {@code null} when it does not decide. */
    private Direction decided;
    private boolean readLogged;
    /** Whether this room has shown two or more read blazes at once - then a lone one is the last. */
    private boolean sawPuzzle;

    /** How far sideways a health tag may stand from its blaze. */
    private static final double TAG_REACH = 1.0;
    private String lastLogged = "";

    private List<PuzzleHighlight> highlights = List.of();

    @Override
    public PuzzleType type() {
        return PuzzleType.HIGHER_OR_LOWER;
    }

    @Override
    public void onTick(Minecraft minecraft) {
        if (!chestSearched) {
            chestSearched = true;
            chestY = findChestY(minecraft.level);
        }

        DungeonRoomBorders.Borders borders = DungeonRoomTracker.getInstance().borders();
        if (borders == null) {
            highlights = List.of();
            return;
        }
        List<Tracked> living = EntityIndex.bucket(EntityKind.LIVING);
        List<Tracked> stands = EntityIndex.bucket(EntityKind.ARMOR_STAND);
        List<Entity> blazes = new ArrayList<>();
        List<Long> healths = new ArrayList<>();
        int seen = 0;
        for (int i = 0, n = living.size(); i < n; i++) {
            Entity entity = living.get(i).entity;
            if (!(entity instanceof Blaze) || !entity.isAlive() || !borders.contains(entity.blockPosition())) {
                continue;
            }
            seen++;
            long health = health(tagOf(entity, stands));
            if (health > 0) {
                blazes.add(entity);
                healths.add(health);
            }
        }
        logReadOnce(seen, blazes.size(), stands);

        long[] values = new long[healths.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = healths.get(i);
        }
        boolean highestFirst = ConfigManager.getInstance().get().dungeons.blazeHighestFirst;
        Direction setting = highestFirst ? Direction.HIGHEST_FIRST : Direction.LOWEST_FIRST;
        Direction direction = roomDirection(blazes, setting);
        if (values.length >= 2) {
            sawPuzzle = true;
        }
        int next = BlazeOrder.nextIndex(values, direction);
        // nextIndex refuses a lone blaze (on its own it is not the puzzle). After this room has
        // shown several, the lone one is the last of them and must be shown too (asked 2026-10-16).
        if (next < 0 && sawPuzzle && values.length == 1) {
            next = 0;
        }
        if (next < 0) {
            highlights = List.of();
            return;
        }
        logOrderOnce(values, direction);

        int targetColor = PuzzleSettings.argb(PuzzleType.HIGHER_OR_LOWER);   // cached parse
        int after = BlazeOrder.afterIndex(values, direction, next);
        List<PuzzleHighlight> out = new ArrayList<>(blazes.size());
        for (int i = 0; i < blazes.size(); i++) {
            Entity blaze = blazes.get(i);
            if (i == next) {
                out.add(PuzzleHighlight.answer(blaze.getBoundingBox(), targetColor, NEXT_LABEL));
            } else if (i == after) {
                // The one after, in its own colour, so the next shot can be lined up early.
                out.add(PuzzleHighlight.answer(blaze.getBoundingBox(), AFTER_COLOR, AFTER_LABEL));
            } else {
                out.add(PuzzleHighlight.context(blaze.getBoundingBox(), OTHER_COLOR, "§7" + compact(values[i])));
            }
        }
        highlights = List.copyOf(out);
    }

    /**
     * The entity carrying this blaze's health tag. On Hypixel that is a nametag armor stand just
     * above the mob, not the blaze itself - the blaze's own name is plain "Blaze", which is why the
     * solver drew nothing in the maintainer's 2026-10-16 room. The blaze is the fallback.
     */
    private static Entity tagOf(Entity blaze, List<Tracked> stands) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0, n = stands.size(); i < n; i++) {
            Entity stand = stands.get(i).entity;
            Component name = stand.getCustomName();
            if (name == null || !stand.isAlive()) {
                continue;
            }
            double dx = stand.getX() - blaze.getX();
            double dz = stand.getZ() - blaze.getZ();
            double dy = stand.getY() - blaze.getY();
            if (Math.abs(dx) > TAG_REACH || Math.abs(dz) > TAG_REACH || dy < -0.5 || dy > 3.5) {
                continue;
            }
            double d = dx * dx + dz * dz + dy * dy;
            if (d < bestDist && name.getString().contains("❤")) {
                bestDist = d;
                best = stand;
            }
        }
        return best != null ? best : blaze;
    }

    /** Once per room: how many blazes stood there and how many health tags were read. */
    private void logReadOnce(int seen, int read, List<Tracked> stands) {
        if (readLogged || seen == 0) {
            return;
        }
        readLogged = true;
        String sample = "";
        for (int i = 0, n = stands.size(); i < n && sample.isEmpty(); i++) {
            Component name = stands.get(i).entity.getCustomName();
            if (name != null && name.getString().contains("❤")) {
                sample = ChatFormatting.stripFormatting(name.getString());
            }
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] blaze: {} blaze(s) in the room, {} health tag(s) read; "
                + "a heart tag nearby reads '{}'", seen, read, sample);
    }

    /** Parsed health, re-running the regex only when the entity's nametag text changed. */
    private long health(Entity entity) {
        Component name = entity.getCustomName() != null ? entity.getCustomName() : entity.getDisplayName();
        String plain = name == null ? "" : ChatFormatting.stripFormatting(name.getString());
        int id = entity.getId();
        if (plain != null && plain.equals(parsedNames.get(id))) {
            return parsedHealth.get(id);
        }
        long health = BlazeOrder.parseHealth(plain);
        parsedNames.put(id, plain);
        parsedHealth.put(id, health);
        return health;
    }

    /**
     * The Y of the room's chest, or {@code Integer.MIN_VALUE} unless there is exactly one. Walks the
     * block entities of the chunks under the room's footprint - a handful of maps, once per entry -
     * rather than the room's blocks.
     */
    private static int findChestY(ClientLevel level) {
        DungeonRoomBorders.Borders borders = DungeonRoomTracker.getInstance().borders();
        if (level == null || borders == null) {
            return Integer.MIN_VALUE;
        }
        int found = Integer.MIN_VALUE;
        int count = 0;
        for (int cx = borders.min().getX() >> 4; cx <= borders.max().getX() >> 4; cx++) {
            for (int cz = borders.min().getZ() >> 4; cz <= borders.max().getZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (blockEntity instanceof ChestBlockEntity && borders.contains(pos)) {
                        found = pos.getY();
                        count++;
                    }
                }
            }
        }
        return count == 1 ? found : Integer.MIN_VALUE;
    }

    /**
     * The order this room wants: the chest rule when it decides, else the setting. The rule now
     * wins (2026-10-16): in two of the maintainer's rooms it was right - one completed lowest-first
     * as the rule said, and one failed "killed a Blaze in the wrong order" the moment the setting
     * (highest first) overrode the rule (lowest first). Decided once per room, logged once.
     */
    private Direction roomDirection(List<Entity> blazes, Direction setting) {
        if (directionLogged) {
            return decided != null ? decided : setting;
        }
        if (blazes.size() < 2) {
            return setting;   // not enough blazes standing to judge their height range yet
        }
        directionLogged = true;
        if (chestY == Integer.MIN_VALUE) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] Blaze room: chest not found (need exactly one) "
                    + "- using the setting {}", setting);
            return setting;
        }
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Entity blaze : blazes) {
            minY = Math.min(minY, blaze.getY());
            maxY = Math.max(maxY, blaze.getY());
        }
        Direction detected = BlazeOrder.directionFromChest(chestY, minY, maxY);
        decided = detected;
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] Blaze room: chest y={} blazes y={}..{} -> rule says {}, "
                        + "setting {} - using {}", chestY, Math.round(minY), Math.round(maxY),
                detected == null ? "undecided" : detected, setting, detected != null ? "the rule" : "the setting");
        return detected != null ? detected : setting;
    }

    private void logOrderOnce(long[] values, Direction direction) {
        StringBuilder sb = new StringBuilder();
        for (long value : values) {
            sb.append(value).append(' ');
        }
        String key = direction + " " + sb;
        if (!key.equals(lastLogged)) {
            lastLogged = key;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] Blaze {} healths {} - verify nametag health live",
                    direction, sb.toString().trim());
        }
    }

    private static String compact(long value) {
        if (value >= 1_000_000) {
            return Math.round(value / 1_000_000.0) + "M";
        }
        if (value >= 1_000) {
            return Math.round(value / 1_000.0) + "K";
        }
        return String.valueOf(value);
    }

    @Override
    public List<PuzzleHighlight> highlights() {
        return highlights;
    }

    @Override
    public void reset() {
        parsedNames.clear();
        parsedHealth.clear();
        chestY = Integer.MIN_VALUE;
        chestSearched = false;
        directionLogged = false;
        decided = null;
        readLogged = false;
        sawPuzzle = false;
        lastLogged = "";
        highlights = List.of();
    }
}
