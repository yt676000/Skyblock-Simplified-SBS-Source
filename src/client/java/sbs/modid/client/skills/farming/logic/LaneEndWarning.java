/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.farming.logic;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.alert.Alerts;
import sbs.modid.client.core.audio.SbsAudio;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.skills.farming.model.CropBlocks;
import sbs.modid.client.skills.farming.model.CropType;
import sbs.modid.client.skills.farming.model.Farm;
import sbs.modid.client.skills.farming.model.Lane;
import sbs.modid.client.skills.farming.ui.LaneFarmsScreen;
import sbs.modid.client.skills.garden.logic.GardenBlueprintManager;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.util.List;
import java.util.Locale;

/**
 * Lane End Warning: a sound (or title) a few blocks before the end of a lane the player marked.
 * <b>Manual only</b> - see {@link LaneEnd}: no block reads, no lane detection, just the player's
 * position against lanes they drew. Display and sound only; it never moves, turns, stops or clicks.
 *
 * <p>Lanes belong to farms ({@link Farm}). Marking: the "Lane start" / "Lane end" keys (unbound by
 * default) or {@code /sbs lane start|end}; press start at one end, walk, press end at the other, and
 * the lane is in the current farm - the next start begins the next lane. {@code repeat} copies the
 * last lane sideways, {@code area} marks a whole rectangle as one lane group, {@code farm} manages
 * farms. While a lane or rectangle is half-marked, it is previewed up to where you stand.
 */
public final class LaneEndWarning {

    private static final LaneEndWarning INSTANCE = new LaneEndWarning();

    private static final int CURRENT_COLOR = 0xFF57D977;
    private static final int OTHER_COLOR = 0xA0659A73;
    private static final int PENDING_COLOR = 0xFFE0A14D;
    private static final int START_COLOR = 0xFF6FE3FF;
    private static final int END_COLOR = 0xFFFF6B6B;
    /** Lanes further than this from you are not drawn. */
    private static final double DRAW_RANGE = 64;
    /** A second {@code farm delete} of the same farm within this long deletes it. */
    private static final long DELETE_CONFIRM_MS = 10_000L;
    private static final String USAGE = "Usage: /sbs lane start|end|repeat <count> <spacing>|area|undo|clear"
            + "|width <1-5>|axis x|z|farm new|select|delete <name>|farm list|list";

    private final LaneEnd.Heading heading = new LaneEnd.Heading();
    private final LaneEnd.Warner warner = new LaneEnd.Warner();
    private Lane current;
    private int deleteAsked;
    private long deleteAskedAt;

    private LaneEndWarning() {
    }

    public static LaneEndWarning getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.FarmingSettings cfg() {
        return ConfigManager.getInstance().get().farming;
    }

    private static LaneAreaStore store() {
        return LaneAreaStore.getInstance();
    }

    /** From {@code BlockBreakEvents}. */
    public void onBlockBroken(BlockPos pos, BlockState state) {
        if (!cfg().laneEndWarning || CropBlocks.of(state, CropType.forHeldTool()) == null
                || !GardenBlueprintManager.inGarden()) {
            return;
        }
        warner.onCropBroken(System.currentTimeMillis());
    }

    /** Once per client tick. */
    public void tick() {
        SBSConfig.FarmingSettings cfg = cfg();
        LocalPlayer player = Minecraft.getInstance().player;
        if (!cfg.laneEndWarning || player == null || !GardenBlueprintManager.inGarden()) {
            current = null;
            heading.reset();
            return;
        }
        long now = System.currentTimeMillis();
        LaneFarms.Hit hit = LaneFarms.laneAt(store().farms(), player.getX(), player.getZ());
        Lane lane = hit == null ? null : hit.lane();
        if (lane != current) {
            current = lane;
            heading.reset();
        }
        if (lane == null) {
            warner.tick(now, null, 0, 0, 0, 0);
            return;
        }
        double along = lane.along(player.getX(), player.getZ());
        heading.record(now, along);
        int sign = heading.sign();
        if (sign == 0) {
            warner.tick(now, null, 0, 0, 0, 0);
            return;
        }
        double remaining = LaneEnd.remaining(lane, along, sign);
        String key = System.identityHashCode(lane) + (sign > 0 ? "+" : "-");
        if (warner.tick(now, key, remaining, heading.speed(), cfg.laneEndBlocks, cfg.laneEndTenths / 10.0)) {
            Alerts.send(new Alerts.Alert("Lane end in " + (int) Math.ceil(remaining),
                    hit.farm().name + " · lane " + (hit.index() + 1), SbsAudio.Tone.BLIP, null),
                    cfg.laneEndChannels);
        }
    }

    // ------------------------------------------------------------------ marking

    /** From {@code KeybindDispatch}, on a fresh press in-world. */
    public void onKeyPressed(int keyCode) {
        SBSConfig.FarmingSettings cfg = cfg();
        // The fields kept their pre-farm names so existing bindings survive; they are start / end now.
        if (cfg.laneCorner1Key != 0 && keyCode == cfg.laneCorner1Key) {
            SBSChat.send(handleCommand("start"));
        } else if (cfg.laneCorner2Key != 0 && keyCode == cfg.laneCorner2Key) {
            SBSChat.send(handleCommand("end"));
        }
    }

    /** {@code /sbs lane ...}; returns the chat reply. Farm names keep their case. */
    public String handleCommand(String args) {
        String[] parts = args == null || args.isBlank() ? new String[0] : args.trim().split("\\s+");
        String verb = parts.length == 0 ? "list" : parts[0].toLowerCase(Locale.ROOT);
        LocalPlayer player = Minecraft.getInstance().player;
        switch (verb) {
            case "start" -> {
                BlockPos feet = feet(player);
                if (feet == null) {
                    return "Lanes are marked on the Garden.";
                }
                Farm farm = farmForMarking(feet);
                store().setPendingStart(new int[] {feet.getX(), feet.getY(), feet.getZ()}, farm.id);
                return "Lane " + (farm.lanes.size() + 1) + " of " + farm.name + ": start set at " + feet.getX()
                        + ", " + feet.getZ() + ". Walk to the other end and press Lane End.";
            }
            case "end" -> {
                BlockPos feet = feet(player);
                if (feet == null) {
                    return "Lanes are marked on the Garden.";
                }
                int[] start = store().pendingStart();
                Farm farm = store().farm(store().pendingFarm());
                if (start == null || farm == null) {
                    return "Press Lane Start at one end of the lane first.";
                }
                Lane lane = Lane.between(start, new int[] {feet.getX(), feet.getY(), feet.getZ()},
                        cfg().laneWidth);
                if (lane == null) {
                    return "You are still on the start block - walk to the other end of the lane.";
                }
                store().addLanes(farm, List.of(lane));
                store().setPendingStart(null, 0);
                return "Lane " + farm.lanes.size() + " added to " + farm.name + ": " + lane.length()
                        + " blocks along " + lane.axis() + ". Next: Lane Start again, or /sbs lane repeat "
                        + "<count> <spacing>.";
            }
            case "repeat" -> {
                return repeat(player, parts);
            }
            case "area", "pos1", "pos2", "corner1", "corner2" -> {
                return area(player, verb);
            }
            case "undo" -> {
                Farm farm = currentFarm(player);
                if (farm == null || farm.lanes.isEmpty()) {
                    return "No lane to undo in the current farm.";
                }
                store().removeLane(farm, farm.lanes.get(farm.lanes.size() - 1));
                return "Removed lane " + (farm.lanes.size() + 1) + " of " + farm.name + ".";
            }
            case "clear" -> {
                if (store().pendingStart() == null && store().pendingCorner() == null) {
                    return "Nothing is being marked. Delete lanes and farms in the Farms screen (/sbs lane list).";
                }
                store().setPendingStart(null, 0);
                store().setPendingCorner(null);
                return "Half-marked lane cleared.";
            }
            case "width" -> {
                int width = parts.length > 1 ? parseInt(parts[1], 0) : 0;
                if (width < 1 || width > 5) {
                    return "Use /sbs lane width 1 to 5 (blocks either side of the line you walk).";
                }
                cfg().laneWidth = width;
                ConfigManager.getInstance().save();
                Lane lane = targetLane(player);
                if (lane != null && !lane.rows) {
                    lane.setWidth(width);
                    store().changed();
                    return "Lane width " + width + ": set for new lanes and for this lane.";
                }
                return "Lane width " + width + " for new lanes.";
            }
            case "axis" -> {
                Lane lane = targetLane(player);
                if (lane == null) {
                    return "Stand in a lane (or mark one) to set its axis.";
                }
                if (!lane.rows) {
                    return "A lane's axis is set by its start and end. Only a rectangle (/sbs lane area) can turn.";
                }
                String value = parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "";
                if (!value.equals("X") && !value.equals("Z")) {
                    return "Use /sbs lane axis x or z.";
                }
                if (!value.equals(lane.axis().name())) {
                    lane.flipAxis();
                    store().changed();
                }
                return "Rectangle: lanes along " + lane.axis() + " (" + lane.length() + " blocks).";
            }
            case "farm" -> {
                return farm(player, parts);
            }
            case "list", "farms" -> {
                LaneFarmsScreen.open();
                return store().farms().size() + " farm(s).";
            }
            default -> {
                return USAGE;
            }
        }
    }

    private String repeat(LocalPlayer player, String[] parts) {
        Farm farm = currentFarm(player);
        if (farm == null || farm.lanes.isEmpty()) {
            return "Mark one lane first, then /sbs lane repeat <count> <spacing>.";
        }
        int count = parts.length > 1 ? parseInt(parts[1], 0) : 0;
        int spacing = parts.length > 2 ? parseInt(parts[2], 0) : 0;
        String problem = LaneFarms.repeatProblem(count, spacing);
        if (problem != null) {
            return problem + " Usage: /sbs lane repeat <count> <spacing>, e.g. repeat 9 3.";
        }
        Lane last = farm.lanes.get(farm.lanes.size() - 1);
        store().addLanes(farm, LaneFarms.repeat(last, count, spacing));
        String side = last.axis() == Lane.Axis.X ? (spacing > 0 ? "+Z" : "-Z") : (spacing > 0 ? "+X" : "-X");
        return "Added " + count + " copies of lane " + (farm.lanes.size() - count) + ", " + Math.abs(spacing)
                + " blocks apart towards " + side + ": " + farm.name + " has " + farm.lanes.size()
                + " lanes. /sbs lane undo removes one.";
    }

    private String area(LocalPlayer player, String verb) {
        BlockPos feet = feet(player);
        if (feet == null) {
            return "Lanes are marked on the Garden.";
        }
        int[] here = {feet.getX(), feet.getY(), feet.getZ()};
        int[] corner = store().pendingCorner();
        boolean first = verb.equals("pos1") || verb.equals("corner1") || (verb.equals("area") && corner == null);
        if (first) {
            store().setPendingCorner(here);
            return "Rectangle corner 1 set at " + here[0] + ", " + here[2]
                    + ". Walk to the opposite corner and run /sbs lane area again.";
        }
        if (corner == null) {
            return "Set rectangle corner 1 first (/sbs lane area).";
        }
        int dx = Math.abs(here[0] - corner[0]);
        int dz = Math.abs(here[2] - corner[2]);
        Lane lane = Lane.rows(corner[0], corner[2], here[0], here[2], Math.min(corner[1], here[1]),
                dz > dx ? Lane.Axis.Z : Lane.Axis.X);
        Farm farm = farmForMarking(feet);
        store().addLanes(farm, List.of(lane));
        store().setPendingCorner(null);
        return "Rectangle added to " + farm.name + ": " + (dx + 1) + " x " + (dz + 1) + ", every row a lane along "
                + lane.axis() + " (" + lane.length() + " blocks). /sbs lane axis x|z to turn it.";
    }

    private String farm(LocalPlayer player, String[] parts) {
        String action = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "list";
        String name = parts.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length)) : "";
        List<Farm> farms = store().farms();
        switch (action) {
            case "new" -> {
                BlockPos feet = feet(player);
                int plot = feet == null ? -1 : LaneAreaStore.plotAt(feet.getX(), feet.getZ());
                Farm farm = store().newFarm(name.isBlank() ? defaultName(plot) : name, plot);
                return "New farm " + farm.name + " (" + farm.plotLabel() + "), selected. Lane Start to mark lanes.";
            }
            case "select" -> {
                Farm farm = LaneFarms.named(farms, name);
                if (farm == null) {
                    return "No farm called \"" + name + "\". /sbs lane farm list shows them.";
                }
                store().select(farm);
                return farm.name + " selected: new lanes go there unless you stand on another farm's plot.";
            }
            case "delete" -> {
                Farm farm = LaneFarms.named(farms, name);
                if (farm == null) {
                    return "No farm called \"" + name + "\".";
                }
                long now = System.currentTimeMillis();
                if (deleteAsked != farm.id || now - deleteAskedAt > DELETE_CONFIRM_MS) {
                    deleteAsked = farm.id;
                    deleteAskedAt = now;
                    return "Delete " + farm.name + " and its " + farm.lanes.size()
                            + " lane(s)? Run the same command again within 10 s to confirm.";
                }
                deleteAsked = 0;
                store().removeFarm(farm);
                return "Deleted " + farm.name + ".";
            }
            case "list" -> {
                if (farms.isEmpty()) {
                    return "No farms yet. Lane Start on the Garden makes one for the plot you stand on.";
                }
                StringBuilder out = new StringBuilder("Farms:");
                for (Farm farm : farms) {
                    out.append("\n ").append(farm.id == store().selectedId() ? "> " : "  ").append(farm.name)
                            .append(" (").append(farm.plotLabel()).append(", ").append(farm.lanes.size())
                            .append(" lanes)");
                }
                return out.toString();
            }
            default -> {
                return "Usage: /sbs lane farm new [name]|select <name>|delete <name>|list";
            }
        }
    }

    /** Your block on the Garden, or {@code null} off it. */
    private static BlockPos feet(LocalPlayer player) {
        return player == null || !GardenBlueprintManager.inGarden() ? null : player.blockPosition();
    }

    /** The current farm here, made (named after the plot) when there is none yet. */
    private static Farm farmForMarking(BlockPos feet) {
        Farm farm = store().current(feet.getX(), feet.getZ());
        if (farm == null) {
            int plot = LaneAreaStore.plotAt(feet.getX(), feet.getZ());
            farm = store().newFarm(defaultName(plot), plot);
        } else if (farm.id != store().selectedId()) {
            store().select(farm);
        }
        return farm;
    }

    private static String defaultName(int plot) {
        return plot > 0 ? "Plot " + plot : "Farm";
    }

    /** The farm you stand on, else the last selected. */
    private static Farm currentFarm(LocalPlayer player) {
        if (player == null) {
            return store().farm(store().selectedId());
        }
        return store().current(player.getBlockX(), player.getBlockZ());
    }

    /** The lane you stand in, else the current farm's last lane. */
    private static Lane targetLane(LocalPlayer player) {
        if (player != null) {
            LaneFarms.Hit hit = LaneFarms.laneAt(store().farms(), player.getX(), player.getZ());
            if (hit != null) {
                return hit.lane();
            }
        }
        Farm farm = currentFarm(player);
        return farm == null || farm.lanes.isEmpty() ? null : farm.lanes.get(farm.lanes.size() - 1);
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ preview

    /**
     * From the HUD render hook: every lane near you as a strip with a start and an end marker and its
     * number, the current farm bright and the others dim; the half-marked lane or rectangle live.
     * Shown while "Show Lanes" is on and you hold a farming tool, and always while marking.
     */
    public void render(GuiGraphicsExtractor g) {
        SBSConfig.FarmingSettings cfg = cfg();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        int[] pendingStart = store().pendingStart();
        int[] pendingCorner = store().pendingCorner();
        boolean marking = pendingStart != null || pendingCorner != null;
        if (player == null || mc.level == null || !GardenBlueprintManager.inGarden()) {
            return;
        }
        boolean show = cfg.laneAreaPreview && (marking || CropType.forHeldTool() != null);
        if (!show && !marking) {
            return;
        }
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 cam = camera.position();
        Matrix4f vp = camera.getViewRotationProjectionMatrix(new Matrix4f());
        BlockPos feet = player.blockPosition();
        if (show) {
            Farm here = store().current(feet.getX(), feet.getZ());
            int only = store().onlyFarm();
            for (Farm farm : store().farms()) {
                if (only != 0 && farm.id != only) {
                    continue;
                }
                int color = farm == here ? CURRENT_COLOR : OTHER_COLOR;
                for (int i = 0; i < farm.lanes.size(); i++) {
                    Lane lane = farm.lanes.get(i);
                    if (near(lane, player)) {
                        drawLane(g, vp, cam, lane, color, String.valueOf(i + 1), farm == here, mc.font);
                    }
                }
            }
        }
        int[] now = {feet.getX(), feet.getY(), feet.getZ()};
        if (pendingStart != null) {
            Lane preview = Lane.between(pendingStart, now, cfg.laneWidth);
            if (preview != null) {
                drawLane(g, vp, cam, preview, PENDING_COLOR, "new", true, mc.font);
            } else {
                WorldRender.boxEdges(g, vp, cam, pendingStart[0], pendingStart[1], pendingStart[2],
                        pendingStart[0] + 1, pendingStart[1] + 0.1, pendingStart[2] + 1, PENDING_COLOR, 2);
            }
        }
        if (pendingCorner != null) {
            int dx = Math.abs(now[0] - pendingCorner[0]);
            int dz = Math.abs(now[2] - pendingCorner[2]);
            Lane preview = Lane.rows(pendingCorner[0], pendingCorner[2], now[0], now[2],
                    Math.min(pendingCorner[1], now[1]), dz > dx ? Lane.Axis.Z : Lane.Axis.X);
            drawLane(g, vp, cam, preview, PENDING_COLOR, "new", true, mc.font);
        }
    }

    private static boolean near(Lane lane, LocalPlayer player) {
        double a = lane.along(player.getX(), player.getZ());
        double c = lane.cross(player.getX(), player.getZ());
        double da = Math.max(0, Math.max(lane.low() - a, a - lane.high()));
        double dc = Math.max(0, Math.max(lane.crossMin - c, c - lane.crossMax - 1));
        return da * da + dc * dc < DRAW_RANGE * DRAW_RANGE;
    }

    /** The strip's outline, a centre line, a start block and an end block, and a label over the start. */
    private static void drawLane(GuiGraphicsExtractor g, Matrix4f vp, Vec3 cam, Lane lane, int color, String label,
                                 boolean labelled, Font font) {
        double y = lane.y + 0.05;
        double c0 = lane.crossMin;
        double c1 = lane.crossMax + 1;
        double lo = lane.low();
        double hi = lane.high();
        Vec3 a = point(lane, lo, c0, y);
        Vec3 b = point(lane, hi, c0, y);
        Vec3 c = point(lane, hi, c1, y);
        Vec3 d = point(lane, lo, c1, y);
        WorldRender.line3d(g, vp, cam, a, b, color, 1);
        WorldRender.line3d(g, vp, cam, b, c, color, 1);
        WorldRender.line3d(g, vp, cam, c, d, color, 1);
        WorldRender.line3d(g, vp, cam, d, a, color, 1);
        double mid = lane.crossCentre();
        WorldRender.line3d(g, vp, cam, point(lane, lo, mid, y), point(lane, hi, mid, y), color, 2);
        // Start and end markers: the block the lane was started on and the block it was ended on.
        marker(g, vp, cam, lane, lane.start, START_COLOR);
        marker(g, vp, cam, lane, lane.end, END_COLOR);
        if (!labelled) {
            return;
        }
        Vec3 at = point(lane, lane.start + 0.5, mid, lane.y + 1.6);
        int[] screen = WorldRender.projectToScreen(vp, cam, at, g.guiWidth(), g.guiHeight());
        if (screen != null) {
            String text = lane.rows ? label + " · rows" : label;
            g.text(font, Component.literal(text), screen[0] - font.width(text) / 2, screen[1], color, true);
        }
    }

    private static void marker(GuiGraphicsExtractor g, Matrix4f vp, Vec3 cam, Lane lane, int along, int color) {
        double mid = lane.crossCentre();
        double x0 = lane.worldX(along, mid - 0.5);
        double z0 = lane.worldZ(along, mid - 0.5);
        WorldRender.boxEdges(g, vp, cam, x0, lane.y, z0, x0 + 1, lane.y + 0.4, z0 + 1, color, 2);
    }

    private static Vec3 point(Lane lane, double along, double cross, double y) {
        return new Vec3(lane.worldX(along, cross), y, lane.worldZ(along, cross));
    }
}
