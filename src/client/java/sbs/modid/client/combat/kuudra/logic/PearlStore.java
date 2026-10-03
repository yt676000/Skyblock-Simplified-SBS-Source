/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.kuudra.logic;

import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.share.ClipboardJson;
import sbs.modid.client.core.config.share.ShareCodec;
import sbs.modid.client.combat.kuudra.model.PearlArea;
import sbs.modid.client.combat.kuudra.model.PearlPoint;
import sbs.modid.client.combat.kuudra.model.SupplySpot;
import sbs.modid.client.core.config.SBSFiles;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * The player's own pearl setup: where their throws are aimed and where each one is thrown from.
 *
 * <p><b>This ships empty, and that is the design.</b> A pearl setup is not arena geometry the way the
 * piles are - it is one player's route through the run, tuned to their own gear and their own habits,
 * and somebody else's throws are worse than useless because they land you somewhere you did not plan
 * to be. So the module records yours instead of assuming one: stand where you throw from, look at the
 * pitch you throw at, and {@code /sbs kuudra mark} writes down that line.
 *
 * <p><b>Why the marker is a point 30 blocks along your line of sight.</b> An aim marker only has to
 * sit somewhere on the line you are looking down - anywhere on it produces the same throw. Taking a
 * fixed distance along the look vector means recording a throw needs nothing but the direction you
 * were already facing, and the box lands far enough away to be aimed at without covering the screen.
 *
 * <p>Stored as readable JSON in {@code config/sbs/kuudrapearls.txt}, next to the other route files,
 * so a setup can be hand-edited or sent to somebody as text. {@code /sbs kuudra reload} re-reads it
 * without a restart.
 */
public final class PearlStore {

    private static final Type LIST_TYPE = new TypeToken<ArrayList<PearlArea>>() {
    }.getType();

    /** Our own export prefix, so a pasted blob can be recognised on sight. */
    private static final String PREFIX = "SBSKPEARL:";

    /** How far along the look vector a recorded aim point is placed. */
    private static final double MARK_DISTANCE = 30.0;

    /** Half-width of an auto-created area. Wide enough to cover a camp, tight enough not to overlap. */
    private static final double AREA_HALF = 12.0;

    private static List<PearlArea> areas = new ArrayList<>();

    private PearlStore() {
    }

    private static Path file() {
        return SBSFiles.root().resolve("kuudrapearls.txt");
    }

    /** The live, mutable list. Empty until {@link #load} has run. */
    public static List<PearlArea> areas() {
        return areas;
    }

    /** The area the player is standing in, or {@code null}. First match wins if they overlap. */
    public static PearlArea areaAt(Vec3 pos) {
        for (PearlArea area : areas) {
            if (area.visible && area.contains(pos)) {
                return area;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ persistence

    /** Reads the file. Called once on client init; safe to call again to re-read. */
    public static void load() {
        Path path = file();
        if (!Files.exists(path)) {
            areas = new ArrayList<>();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<PearlArea> parsed = SBSFiles.GSON.fromJson(reader, LIST_TYPE);
            areas = parsed == null ? new ArrayList<>() : parsed;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Kuudra] Loaded {} pearl area(s).", areas.size());
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Kuudra] Failed to read kuudrapearls.txt", e);
        }
    }

    public static void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                SBSFiles.GSON.toJson(areas, LIST_TYPE, writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][Kuudra] Failed to write kuudrapearls.txt", e);
        }
    }

    // ------------------------------------------------------------------ /sbs kuudra ...

    /** Handles the {@code /sbs kuudra <args>} sub-commands; returns the text to print back. */
    public static String handleCommand(String args) {
        String[] parts = args.trim().split("\\s+", 2);
        String sub = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        return switch (sub) {
            case "mark" -> mark(rest);
            case "area" -> area(rest);
            case "list" -> list();
            case "del", "delete" -> delete(rest);
            case "clear" -> clear();
            case "reload" -> {
                load();
                yield "§aReloaded §f" + areas.size() + "§a pearl area(s) from disk.";
            }
            case "export" -> export();
            case "import" -> importFromClipboard();
            default -> """
                    §b/sbs kuudra §7- pearl setup
                    §f  mark [pitch] §7- record a throw from where you stand, aimed where you look
                    §f  area <name> §7- (re)name the area around you, or make one
                    §f  list §7- every area and throw
                    §f  del <area> [throw] §7- remove a throw, or a whole area
                    §f  clear §7- remove everything
                    §f  reload §7- re-read config/sbs/kuudrapearls.txt
                    §f  export / import §7- clipboard""";
        };
    }

    /**
     * Records one throw: the line you are looking down, and the block you are standing on.
     *
     * <p>The label defaults to your actual pitch rounded to a whole degree, which is the number you
     * would have written down anyway. Passing one explicitly overrides it, for a throw copied from
     * somebody else.
     */
    private static String mark(String label) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return "§cNot in a world.";
        }
        if (!KuudraTracker.getInstance().inHollow()) {
            return "§cPearl throws are only recorded inside Kuudra's Hollow.";
        }
        Vec3 eye = player.getEyePosition();
        Vec3 aim = eye.add(player.getLookAngle().scale(MARK_DISTANCE));

        PearlArea area = areaAt(player.position());
        if (area == null) {
            area = createArea(player.position(), nearestSpotName(player.position()));
        }
        PearlPoint point = new PearlPoint();
        point.x = round(aim.x);
        point.y = round(aim.y);
        point.z = round(aim.z);
        point.standX = round(player.getX());
        point.standY = round(player.getY());
        point.standZ = round(player.getZ());
        point.label = label.isEmpty()
                ? String.valueOf(Math.round(player.getXRot())) : label;
        area.points.add(point);
        save();
        return "§aRecorded throw §f#" + area.points.size() + "§a in area §f" + area.name
                + " §7(pitch " + point.label + ")";
    }

    /** Creates or renames the area around the player. */
    private static String area(String name) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return "§cNot in a world.";
        }
        if (name.isEmpty()) {
            return "§cUsage: /sbs kuudra area <name>";
        }
        PearlArea existing = areaAt(player.position());
        if (existing != null) {
            existing.name = name;
            save();
            return "§aRenamed the area you are in to §f" + name + "§a.";
        }
        createArea(player.position(), name);
        save();
        return "§aCreated area §f" + name + "§a around you.";
    }

    private static PearlArea createArea(Vec3 at, String name) {
        PearlArea area = new PearlArea();
        area.name = name;
        area.x1 = round(at.x - AREA_HALF);
        area.z1 = round(at.z - AREA_HALF);
        area.x2 = round(at.x + AREA_HALF);
        area.z2 = round(at.z + AREA_HALF);
        areas.add(area);
        return area;
    }

    private static String list() {
        if (areas.isEmpty()) {
            return "§7No pearl areas yet. Stand where you throw from, look at your pitch and run "
                    + "§f/sbs kuudra mark§7.";
        }
        StringBuilder out = new StringBuilder("§bPearl areas:");
        for (int i = 0; i < areas.size(); i++) {
            PearlArea area = areas.get(i);
            out.append("\n§f").append(i + 1).append(". §b").append(area.name)
                    .append(" §7(").append(area.points.size()).append(" throw(s))");
            for (int j = 0; j < area.points.size(); j++) {
                PearlPoint point = area.points.get(j);
                out.append("\n   §8").append(j + 1).append(". pitch ").append(point.label)
                        .append(point.hasStand() ? " §8(moves with you)" : " §8(static)");
            }
        }
        return out.toString();
    }

    /** {@code del <area>} drops a whole area, {@code del <area> <throw>} drops one throw. */
    private static String delete(String rest) {
        String[] parts = rest.split("\\s+");
        Integer areaIndex = index(parts.length > 0 ? parts[0] : "", areas.size());
        if (areaIndex == null) {
            return "§cUsage: /sbs kuudra del <area> [throw] §7- see /sbs kuudra list";
        }
        PearlArea area = areas.get(areaIndex);
        if (parts.length < 2) {
            areas.remove(areaIndex);
            save();
            return "§aRemoved area §f" + area.name + "§a.";
        }
        Integer pointIndex = index(parts[1], area.points.size());
        if (pointIndex == null) {
            return "§cThat area has no throw " + parts[1] + ".";
        }
        area.points.remove(pointIndex.intValue());
        save();
        return "§aRemoved throw §f" + parts[1] + "§a from §f" + area.name + "§a.";
    }

    private static String clear() {
        int count = areas.size();
        areas.clear();
        save();
        return "§aRemoved §f" + count + "§a pearl area(s).";
    }

    /** Zero-based index from a one-based argument, or {@code null} when it is not a valid one. */
    private static Integer index(String text, int size) {
        try {
            int value = Integer.parseInt(text.trim()) - 1;
            return value >= 0 && value < size ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ clipboard

    private static String export() {
        if (areas.isEmpty()) {
            return "§cNothing to export.";
        }
        String json = SBSFiles.GSON.toJson(areas, LIST_TYPE);
        String blob = PREFIX + Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        Minecraft.getInstance().keyboardHandler.setClipboard(blob);
        return "§aCopied §f" + areas.size() + "§a pearl area(s) to the clipboard.";
    }

    /**
     * Reads a setup out of the clipboard.
     *
     * <p>Accepts our own blob with or without the prefix, and plain JSON, because a setup that has
     * been through a chat client comes back wrapped in whatever that client felt like adding.
     */
    private static String importFromClipboard() {
        String raw = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (raw == null || raw.isBlank()) {
            return "§cThe clipboard is empty.";
        }
        ClipboardJson.Result bounded;
        try {
            // A paste is untrusted input, so it is bounded and stripped to the keys this format
            // declares BEFORE Gson binds anything - see docs/CONFIG-SHARING-DESIGN.md §10.
            bounded = ClipboardJson.parse(raw, PREFIX, ALLOWED);
        } catch (ShareCodec.ShareException refused) {
            return "§c" + refused.getMessage();
        }
        ClipboardJson.logDropped("Kuudra", bounded);
        try {
            // Gson now sees a tree that can only hold the keys listed above, at a bounded size and
            // depth. That is what makes binding to PearlArea here defensible, rather than pointing
            // the trusted-local-file parser at the clipboard.
            List<PearlArea> parsed = SBSFiles.GSON.fromJson(bounded.json(), LIST_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                return "§cNo areas found in that paste.";
            }
            if (parsed.size() > MAX_IMPORTED_AREAS) {
                return "§cThat paste holds " + parsed.size() + " areas - more than a setup ever has.";
            }
            for (PearlArea area : parsed) {
                sanitise(area);
            }
            areas.addAll(parsed);
            save();
            return "§aImported §f" + parsed.size() + "§a pearl area(s).";
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.debug("[SBS][Kuudra] pearl import failed: {}", e.toString());
            return "§cCould not read that paste.";
        }
    }

    /** Most areas a pasted setup may carry - a real one has a handful. */
    private static final int MAX_IMPORTED_AREAS = 64;

    /**
     * The only keys a pasted pearl setup may carry.
     *
     * <p>A field added to {@link PearlArea} or {@link PearlPoint} later is <b>not</b> importable
     * until it is added here as well. That is the direction that fails safely: forget it and a
     * setting does not travel, rather than a stranger's paste being able to set something nobody
     * considered.
     */
    private static final ClipboardJson.Allowlist ALLOWED = new ClipboardJson.Allowlist(
            java.util.Set.of(
                    // PearlArea
                    "name", "x1", "z1", "x2", "z2",
                    "invertnorthsouth", "inverteastwest", "visible", "points",
                    // PearlPoint
                    "note", "x", "y", "z", "standx", "standy", "standz", "size", "colorhex"),
            8, 40_000);

    /**
     * Clamps the free-text and colour fields of an imported area.
     *
     * <p>The key filter decides which fields may arrive; this decides what they may hold. A name and
     * a note are drawn on screen, so both are capped and stripped of the formatting codes that would
     * otherwise let a paste recolour someone else's HUD, and a colour that is not one falls back to
     * the default instead of being drawn.
     */
    private static void sanitise(PearlArea area) {
        area.name = cleanText(area.name);
        if (area.points == null) {
            area.points = new ArrayList<>();
            return;
        }
        for (PearlPoint point : area.points) {
            if (point == null) {
                continue;
            }
            point.note = cleanText(point.note);
            point.colorHex = cleanHex(point.colorHex);
            point.size = Math.clamp(point.size, 0.05, 8.0);
        }
    }

    /** At most 48 visible characters, no formatting codes, no control characters. */
    private static String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String stripped = text.replaceAll("§.", "").replaceAll("\\p{Cntrl}", "").trim();
        return stripped.length() > 48 ? stripped.substring(0, 48) : stripped;
    }

    /** {@code RRGGBB} or nothing - never a half-parsed colour. */
    private static String cleanHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return "";
        }
        String value = hex.trim().replace("#", "");
        return value.matches("(?i)[0-9a-f]{6}") ? value.toUpperCase(java.util.Locale.ROOT) : "";
    }

    // ------------------------------------------------------------------ helpers

    /** The supply spot nearest a position, used to name an auto-created area something meaningful. */
    private static String nearestSpotName(Vec3 pos) {
        SupplySpot best = null;
        double bestSqr = Double.MAX_VALUE;
        for (SupplySpot spot : SupplySpot.values()) {
            double sqr = spot.position().distanceToSqr(pos);
            if (sqr < bestSqr) {
                bestSqr = sqr;
                best = spot;
            }
        }
        return best == null ? "Area" : best.displayName();
    }

    /** Two decimals is finer than anyone can stand, and keeps the file readable. */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
