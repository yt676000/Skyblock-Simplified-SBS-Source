/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.logic;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.core.render.WorldRender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns coordinates posted in chat into world waypoints: someone drops
 * {@code "x: 187, y: 120, z: -430"} (or plain {@code "187 120 -430"}) in party chat and the spot is
 * immediately boxed and beamed in the world, labelled with who sent it and how far away it is.
 *
 * <p><b>False positives are the whole design problem</b> – SkyBlock chat is full of numbers. Three
 * guards keep them out:
 * <ul>
 *   <li>Only lines with a <b>sender</b> ({@code "Name: message"}) are read at all, so Hypixel's own
 *       system spam ("You found 3 …") can never create a waypoint.</li>
 *   <li>The numbers must be a plausible position: Y inside the world's build range, X/Z inside the
 *       world border.</li>
 *   <li>An unlabelled triple must not be three single-digit numbers, which is what a tally
 *       ("gg 1 2 3") looks like and a coordinate never does.</li>
 * </ul>
 *
 * <p>Waypoints expire on their own (configurable minutes), disappear once the player has walked into
 * them, and are capped at {@link #MAX_WAYPOINTS} so a spammer cannot flood the screen.
 * {@code /sbs waypoint clear} removes them by hand.
 */
public final class ChatWaypoints {

    private static final ChatWaypoints INSTANCE = new ChatWaypoints();

    /** Newest kept; older ones drop off the front when this is exceeded. */
    private static final int MAX_WAYPOINTS = 8;

    /** Removed once the player stands this close – the waypoint has served its purpose. */
    private static final double REACHED_DISTANCE = 4.0;

    /** How tall the beam above the marked block is drawn. */
    private static final int BEAM_HEIGHT = 24;

    /**
     * "x: 12, y: 45, z: -678" in every spelling people use: any of {@code : = } or nothing as the
     * separator, comma / semicolon / space between the axes.
     */
    private static final Pattern LABELLED = Pattern.compile(
            "(?i)\\bx\\s*[:=]?\\s*(-?\\d{1,8})\\s*[,;]?\\s+y\\s*[:=]?\\s*(-?\\d{1,4})\\s*[,;]?\\s+z\\s*[:=]?\\s*(-?\\d{1,8})");

    /**
     * A bare "187 120 -430" / "(187, 120, -430)" triple anywhere in the message, so the very common
     * "come to 187 120 -430" works too. The lookarounds keep it off numbers that are part of a longer
     * token, and {@link #validated} throws out triples that are all single digits.
     */
    private static final Pattern BARE = Pattern.compile(
            "(?<![\\w.:-])(-?\\d{1,8})\\s*[,;]?\\s+(-?\\d{1,4})\\s*[,;]?\\s+(-?\\d{1,8})(?![\\w.])");

    /** The IGN inside a chat prefix, once rank / guild-rank brackets are stripped. */
    private static final Pattern IGN = Pattern.compile("[A-Za-z0-9_]{2,16}");

    /** One shared coordinate. */
    public record Waypoint(BlockPos pos, String sender, long createdAt) {
    }

    /** Newest last. Mutated on the chat thread, read on the render thread – hence the copy-on-write. */
    private volatile List<Waypoint> waypoints = List.of();

    /**
     * The live waypoints, for anything that wants to draw the same coordinates somewhere else - the
     * Crystal Hollows map is the first.
     *
     * <p>A read accessor rather than an event: the parse rules here took several attempts to settle
     * (the sender colon, the Alex/Max/Fizz name trap, the Y range, refusing "1 2 3"), and a second
     * consumer subscribing to them is a second place they can be got wrong. The list is already
     * copy-on-write and already capped, so handing it out costs nothing and can be mutated by
     * nobody.
     */
    public List<Waypoint> active() {
        return waypoints;
    }

    private ChatWaypoints() {
    }

    public static ChatWaypoints getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.ChatOptionsSettings cfg() {
        return ConfigManager.getInstance().get().chatOptions;
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** Called for every displayed chat line (from the shared chat listener). */
    public void onChat(String rawText) {
        if (!cfg().chatWaypoints || rawText == null || rawText.isEmpty()) {
            return;
        }
        // A sender is mandatory: "<prefix>: <body>". Hypixel's own messages have no such colon in a
        // player-name position, which is exactly what keeps system lines out.
        int colon = senderColon(rawText);
        if (colon < 0 || colon + 1 >= rawText.length()) {
            return;
        }
        String prefix = rawText.substring(0, colon);
        String body = rawText.substring(colon + 1).trim();
        if (body.isEmpty()) {
            return;
        }
        BlockPos pos = parseCoordinates(body);
        if (pos == null) {
            return;
        }
        add(pos, senderOf(prefix));
    }

    /**
     * The index of the colon that separates the sender from the message, or {@code -1}. Two colons
     * are deliberately skipped: the {@code x:} / {@code y:} / {@code z:} of a labelled coordinate
     * (only when the letter stands alone – a player called <b>Alex</b> must still be recognised) and
     * the one inside a clock time ("see you at 20:30").
     */
    static int senderColon(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != ':') {
                continue;
            }
            char before = i > 0 ? Character.toLowerCase(text.charAt(i - 1)) : ' ';
            boolean standaloneAxis = (before == 'x' || before == 'y' || before == 'z')
                    && (i < 2 || !Character.isLetterOrDigit(text.charAt(i - 2)));
            if (standaloneAxis) {
                continue;
            }
            if (i > 0 && Character.isDigit(text.charAt(i - 1))
                    && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /** The position a chat body describes, or {@code null} when it holds no usable coordinate. */
    static BlockPos parseCoordinates(String body) {
        Matcher labelled = LABELLED.matcher(body);
        if (labelled.find()) {
            BlockPos hit = validated(labelled.group(1), labelled.group(2), labelled.group(3), false);
            if (hit != null) {
                return hit;
            }
        }
        Matcher bare = BARE.matcher(body);
        while (bare.find()) {
            BlockPos hit = validated(bare.group(1), bare.group(2), bare.group(3), true);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * Parses the triple and rejects anything that is not a position a player could stand at. An
     * <b>unlabelled</b> triple additionally has to look like coordinates rather than a tally: three
     * single-digit numbers ("gg 1 2 3") are never a position worth walking to.
     */
    private static BlockPos validated(String rawX, String rawY, String rawZ, boolean bare) {
        try {
            int x = Integer.parseInt(rawX);
            int y = Integer.parseInt(rawY);
            int z = Integer.parseInt(rawZ);
            if (y < -64 || y > 400 || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) {
                return null;
            }
            if (bare && Math.abs(x) < 10 && Math.abs(y) < 10 && Math.abs(z) < 10) {
                return null;
            }
            return new BlockPos(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The IGN out of a chat prefix: rank and guild-rank tags are dropped first
     * ("Guild > [VIP] Alex [Officer]" → "Alex"), then the last name-shaped token wins – which is the
     * player in every Hypixel channel format ("Party > [MVP+] Steve", "From [MVP++] Notch").
     */
    static String senderOf(String prefix) {
        String stripped = prefix.replaceAll("§.", "").replaceAll("\\[[^\\]]*\\]", "");
        Matcher matcher = IGN.matcher(stripped);
        String last = "";
        while (matcher.find()) {
            last = matcher.group();
        }
        return last.isEmpty() ? "Chat" : last;
    }

    /** Adds (or refreshes) a waypoint and confirms it in chat. */
    private void add(BlockPos pos, String sender) {
        List<Waypoint> updated = new ArrayList<>(waypoints.size() + 1);
        for (Waypoint existing : waypoints) {
            if (!existing.pos().equals(pos)) {
                updated.add(existing);
            }
        }
        updated.add(new Waypoint(pos, sender, System.currentTimeMillis()));
        while (updated.size() > MAX_WAYPOINTS) {
            updated.remove(0);
        }
        waypoints = List.copyOf(updated);

        // Deferred: this runs inside the chat-add call, so pushing another line right now would
        // modify the chat list while it is being appended to.
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal(
                        "§b[SBS] §7Waypoint from §b" + sender + "§7: §f"
                                + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                                + " §8(/sbs waypoint clear)"));
            }
        });
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    /** {@code /sbs waypoint [clear|list]}; returns the client-side feedback lines. */
    public String handleCommand(String argument) {
        String action = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        if (action.startsWith("clear") || action.startsWith("remove")) {
            int count = waypoints.size();
            waypoints = List.of();
            return "§b[SBS] §7Removed §b" + count + "§7 chat waypoint(s).";
        }
        List<Waypoint> current = waypoints;
        if (current.isEmpty()) {
            return "§b[SBS] §7No chat waypoints right now. Coordinates posted in chat create them"
                    + " while \"Chat Coordinate Waypoints\" is on.";
        }
        StringBuilder out = new StringBuilder("§b[SBS] §7Chat waypoints:");
        for (Waypoint waypoint : current) {
            out.append("\n§8 - §b").append(waypoint.sender()).append("§7: §f")
                    .append(waypoint.pos().getX()).append(' ').append(waypoint.pos().getY())
                    .append(' ').append(waypoint.pos().getZ());
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // World render (from HudMixin's world pass)
    // ------------------------------------------------------------------

    public void render(GuiGraphicsExtractor g) {
        List<Waypoint> current = waypoints;
        if (!cfg().chatWaypoints || current.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Vec3 playerPos = minecraft.player.position();
        long lifetimeMs = Math.max(0, cfg().chatWaypointMinutes) * 60_000L;
        long now = System.currentTimeMillis();

        // Expiry and "walked into it" removal both happen here: the render pass is the only place
        // that reliably runs while the player moves, and pruning a copy-on-write list is cheap.
        List<Waypoint> alive = new ArrayList<>(current.size());
        for (Waypoint waypoint : current) {
            boolean expired = lifetimeMs > 0 && now - waypoint.createdAt() > lifetimeMs;
            boolean reached = playerPos.distanceTo(Vec3.atCenterOf(waypoint.pos())) <= REACHED_DISTANCE;
            if (!expired && !reached) {
                alive.add(waypoint);
            }
        }
        if (alive.size() != current.size()) {
            waypoints = List.copyOf(alive);
        }
        if (alive.isEmpty()) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f vp = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;
        int gw = g.guiWidth();
        int gh = g.guiHeight();
        int color = SBSTheme.ACCENT;

        for (Waypoint waypoint : alive) {
            BlockPos pos = waypoint.pos();
            WorldRender.boxEdges(g, vp, camPos, pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, color, 2);
            // Beam: one line straight up, so the spot is findable from across the island.
            int[] bottom = WorldRender.projectToScreen(vp, camPos,
                    new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), gw, gh);
            int[] top = WorldRender.projectToScreen(vp, camPos,
                    new Vec3(pos.getX() + 0.5, pos.getY() + BEAM_HEIGHT, pos.getZ() + 0.5), gw, gh);
            if (bottom != null && top != null) {
                WorldRender.line(g, bottom[0], bottom[1], top[0], top[1], color, 2);
            }
            int[] labelAt = WorldRender.projectToScreen(vp, camPos,
                    new Vec3(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5), gw, gh);
            if (labelAt != null) {
                int distance = (int) Math.round(playerPos.distanceTo(Vec3.atCenterOf(pos)));
                String label = waypoint.sender() + " §7" + distance + "m";
                g.text(font, Component.literal(label),
                        labelAt[0] - font.width(label) / 2, labelAt[1], color, true);
            }
        }
    }
}
