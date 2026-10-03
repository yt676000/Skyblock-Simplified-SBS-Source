/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.puzzle;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.core.render.WorldRender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon Puzzle Solver framework (display only, never automates). Detects the puzzle the player is in
 * from public game state and highlights the solution in the world. The first solver is the
 * <b>Blaze ("Higher Or Lower")</b> puzzle, whose mechanic is entirely observable: each blaze floats a
 * nametag carrying its health, and you shoot them in health order - so the next target is simply the
 * lowest- (or highest-) health blaze still alive.
 *
 * <p><b>Derived from the game mechanic, not from any other mod's source.</b> The health is read off the
 * blaze nametag; the exact wording can only be confirmed live, so every parse is logged once per change
 * ({@code [SBS][Puzzle]}). Rendering mirrors {@code SecretRoutesRenderer}: hand-projected boxes/labels in
 * the HUD pass. Nothing here clicks, shoots or moves the player.
 */
public final class PuzzleSolver {

    private static final PuzzleSolver INSTANCE = new PuzzleSolver();

    private static final int TARGET_COLOR = 0xFF55FF55; // green box on the next blaze
    private static final int OTHER_COLOR = 0x80FFC94D;   // faint gold on the rest

    /** A resolved blaze: its entity, parsed health, and whether it is the next target. */
    private record BlazeTarget(Entity entity, long health, boolean next) {
    }

    /** Matches a health token in a blaze nametag, e.g. "46M", "1,200,000", "12.5k". */
    private static final Pattern HEALTH = Pattern.compile("([0-9][0-9.,]*)\\s*([kKmMbB]?)");

    private volatile List<BlazeTarget> blazes = List.of();
    private String lastLogged = "";

    private PuzzleSolver() {
    }

    public static PuzzleSolver getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    // ------------------------------------------------------------------
    // Tick: detect + solve
    // ------------------------------------------------------------------

    public void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null
                || !cfg().puzzleSolver || !DungeonStateManager.getInstance().inDungeon()) {
            blazes = List.of();
            return;
        }
        blazes = solveBlaze(minecraft.level);
    }

    /**
     * Reads every blaze's health from its nametag, sorts, and marks the next target (lowest health by
     * default, highest when {@code blazeHighestFirst}). The blaze puzzle is unmistakable - a cluster of
     * blazes with health nametags - so it is detected by their presence, not a room-name guess.
     */
    private List<BlazeTarget> solveBlaze(ClientLevel level) {
        List<BlazeTarget> found = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof Blaze)) {
                continue;
            }
            long health = parseHealth(nametag(entity));
            if (health > 0) {
                found.add(new BlazeTarget(entity, health, false));
            }
        }
        if (found.size() < 2) {
            return List.of(); // not the puzzle (a lone slayer/other blaze) - show nothing
        }
        boolean highestFirst = cfg().blazeHighestFirst;
        found.sort(Comparator.comparingLong(BlazeTarget::health));
        int targetIndex = highestFirst ? found.size() - 1 : 0;
        BlazeTarget target = found.get(targetIndex);
        List<BlazeTarget> out = new ArrayList<>(found.size());
        for (BlazeTarget blaze : found) {
            out.add(new BlazeTarget(blaze.entity(), blaze.health(), blaze == target));
        }
        logOnce(found, highestFirst);
        return out;
    }

    /** The floating text of an entity (its custom name, else display name). */
    private static String nametag(Entity entity) {
        Component name = entity.getCustomName() != null ? entity.getCustomName() : entity.getDisplayName();
        return name == null ? "" : name.getString().replaceAll("§.", "");
    }

    /** Parses the largest health-looking number in a nametag (k/m/b suffixes), or 0 when none. */
    private static long parseHealth(String text) {
        long best = 0;
        Matcher matcher = HEALTH.matcher(text);
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1).replace(",", ""));
                switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                    case "k" -> value *= 1_000;
                    case "m" -> value *= 1_000_000;
                    case "b" -> value *= 1_000_000_000;
                    default -> { }
                }
                best = Math.max(best, (long) value);
            } catch (NumberFormatException ignored) {
                // a non-numeric token, skip
            }
        }
        return best;
    }

    private void logOnce(List<BlazeTarget> found, boolean highestFirst) {
        StringBuilder sb = new StringBuilder();
        for (BlazeTarget blaze : found) {
            sb.append(blaze.health()).append(' ');
        }
        String key = highestFirst + " " + sb;
        if (!key.equals(lastLogged)) {
            lastLogged = key;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Puzzle] Blaze {} sorted {} - verify nametag health live",
                    highestFirst ? "highest-first" : "lowest-first", sb.toString().trim());
        }
    }

    // ------------------------------------------------------------------
    // Render (HUD world pass, like SecretRoutesRenderer)
    // ------------------------------------------------------------------

    public void render(GuiGraphicsExtractor g) {
        if (!cfg().puzzleSolver) {
            return;
        }
        List<BlazeTarget> list = blazes;
        if (list.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;

        for (BlazeTarget blaze : list) {
            AABB box = blaze.entity().getBoundingBox();
            int color = blaze.next() ? TARGET_COLOR : OTHER_COLOR;
            WorldRender.boxEdges(g, viewProjection, camPos,
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, color, blaze.next() ? 3 : 1);
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos,
                    new Vec3((box.minX + box.maxX) / 2, box.maxY + 0.4, (box.minZ + box.maxZ) / 2),
                    g.guiWidth(), g.guiHeight());
            if (screen != null) {
                String label = blaze.next() ? "§aNEXT" : "§7" + compact(blaze.health());
                g.centeredText(font, Component.literal(label), screen[0], screen[1], 0xFFFFFFFF);
            }
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
}
