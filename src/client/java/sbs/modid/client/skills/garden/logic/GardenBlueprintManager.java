/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import sbs.modid.client.core.build.logic.BuildLibrary;
import sbs.modid.client.core.build.logic.Clipboard;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.logic.SelectionManager;
import sbs.modid.client.core.build.logic.WorldCapture;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.model.Selection;
import sbs.modid.client.social.chat.logic.SBSChat;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.keybind.IslandCatalog;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.skills.garden.model.GardenPlot;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Garden Blueprint's actions: copy a plot or a custom area, pin the ghost to a block, show or hide
 * it, and save it to the build library.
 *
 * <p>Garden Blueprint is one <b>source</b> and one <b>preset</b> of the shared build hologram in
 * {@code core/build}: a copy here is an ordinary {@link Schematic} (put on the shared
 * {@link Clipboard} too, so {@code /.. save} works on it), shown as the one {@link Hologram} with
 * {@link GardenPreset} as its owner - which supplies the plot-grid placement, the "only in Garden"
 * gate and this page's colours. The custom-area corners are the shared {@link SelectionManager}
 * selection. None of it touches {@code config.json}: a blueprint can be hundreds of thousands of
 * blocks and lives in memory, or in the library once saved.
 *
 * <p>Copying auto-enables the preview, so one key both creates and shows the blueprint (standing on
 * the plot you copied, every block reads back as correct - instant confirmation the copy worked).
 */
public final class GardenBlueprintManager {

    private static final GardenBlueprintManager INSTANCE = new GardenBlueprintManager();

    /** Refuse a custom area larger than this many block positions, to keep the scan snappy. */
    private static final long MAX_SELECTION_VOLUME = 4_000_000L;

    private static final DateTimeFormatter SAVE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    /** The next Set Corner press sets corner 1 when true, corner 2 when false. */
    private boolean nextIsCornerA = true;

    private GardenBlueprintManager() {
    }

    public static GardenBlueprintManager getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.GardenBlueprintSettings cfg() {
        return ConfigManager.getInstance().get().gardenBlueprint;
    }

    /** The Garden hologram, or {@code null} when there is none (or another feature's is up). */
    private static Hologram gardenHologram() {
        Hologram hologram = HologramManager.getInstance().hologram();
        return hologram != null && hologram.owner() == GardenPreset.INSTANCE ? hologram : null;
    }

    /** The copied blueprint, or {@code null}. */
    public Schematic blueprint() {
        Hologram hologram = gardenHologram();
        return hologram == null ? null : hologram.display();
    }

    public boolean previewEnabled() {
        return gardenHologram() != null && HologramManager.getInstance().shown();
    }

    /**
     * The block the ghost's min corner is pinned to, or {@code null} when the ghost follows its
     * capture (the plot the player stands on, or the world spot a custom area was copied at).
     */
    public BlockPos anchor() {
        Hologram hologram = gardenHologram();
        return hologram == null ? null : hologram.anchor();
    }

    public BlockPos cornerA() {
        return SelectionManager.getInstance().corner1();
    }

    public BlockPos cornerB() {
        return SelectionManager.getInstance().corner2();
    }

    /**
     * True when the player is currently on the Garden island.
     *
     * <p>{@link SkyBlockLocation} prefers the tab list's {@code Area:} line, which keeps naming the
     * island on plots (where the scoreboard switches to the plot name - the old scoreboard-only check
     * missed them). <b>That line says "Garden", without the article the scoreboard and the warps
     * use</b>, so the spelling is seeded in {@link IslandCatalog} rather than compared literally
     * anywhere. The zone fallback covers the rare frame the tab list is not populated: "⏣ The Garden"
     * in the hub, "⏣ Plot ..." on a plot (separator varies by version).
     */
    public static boolean inGarden() {
        if (SkyBlockLocation.onIsland("The Garden")) {
            return true;
        }
        if (locationRefutesGarden()) {
            explainRefusal("island reads as somewhere else");
            return false;
        }
        String zone = SkyBlockLocation.zone().toLowerCase(Locale.ROOT);
        if (zone.contains("garden") || zone.contains("plot")) {
            return true;
        }
        explainRefusal("island unreadable and the zone names neither a garden nor a plot");
        return false;
    }

    /** Throttle for {@link #explainRefusal}: this is called from render paths, several times a frame. */
    private static long lastRefusalLogAt;

    /**
     * Says in the log why the Garden gate answered no.
     *
     * <p>Every Garden surface hangs off {@link #inGarden()} - the plot grid, the blueprint ghosts,
     * the pest highlights and, through {@link #locationRefutesGarden()}, the pest card. When this
     * answers no they all vanish together, and until now they did it without a word anywhere, so
     * "the Garden overlays stopped working" was a report nobody could act on: it could equally be the
     * gate, the tab list, the scoreboard or the feature toggles, and nothing on screen or in the log
     * told them apart. {@code PestTracker} already does exactly this for the Pests widget; this is
     * the same idea for the location half.
     *
     * <p>Only fires in a world, at most every ten seconds, and only on refusal - standing anywhere
     * that is not the Garden costs one line every ten seconds, which is the price of the answer.
     */
    private static void explainRefusal(String because) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;   // no world: "not in the Garden" is not news
        }
        long now = System.currentTimeMillis();
        if (now - lastRefusalLogAt < 10_000L) {
            return;
        }
        lastRefusalLogAt = now;
        sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Garden] Overlays hidden - {}. island='{}' zone='{}' describe='{}'. "
                        + "If you ARE on the Garden, this line is the bug: it names what the location "
                        + "service read when it decided otherwise.",
                because, SkyBlockLocation.island(), SkyBlockLocation.zone(), SkyBlockLocation.describe());
    }

    /**
     * Whether the location service positively places the player on an island that is not the Garden.
     *
     * <p>This is what keeps the Garden's two loose tests honest. Both of them - the zone-substring
     * fallback above and the Pests-widget test in {@link PestTracker#onGarden()} - exist for the
     * frames where the island is <b>not readable yet</b>, and both were written as if "not readable"
     * and "reads as somewhere else" were the same state. They are not: a named other island is
     * evidence, and evidence beats a substring.
     *
     * <p>Deliberately asymmetric. An unknown location returns {@code false} and changes nothing, so a
     * regression in the location layer still cannot switch the Garden features off - which is the
     * failure the two-legged gate in {@code PestHighlight} was built to survive. Only a positively read,
     * positively different island refuses.
     */
    public static boolean locationRefutesGarden() {
        return refutes(SkyBlockLocation.island());
    }

    /**
     * The decision alone, over a name already read - the part worth testing without a game.
     *
     * <p><b>Resolved through {@link IslandCatalog} before being judged different.</b> A raw string
     * compare made this refuse the Garden itself: the tab list's Info widget says {@code Area:
     * Garden} while the rest of the game says "The Garden", so a positively-read Garden was treated
     * as positive evidence of somewhere else and every Garden overlay hid while standing on it.
     *
     * <p>The catalog now seeds that spelling, so the compare would pass either way - but going
     * through the catalog is what stops the next island whose tab name differs from its canonical one
     * doing the same thing silently. An unknown name still refuses, which is the point: it is the
     * <i>known and different</i> case this test exists for.
     */
    static boolean refutes(String island) {
        if (island == null || island.isBlank()) {
            return false;
        }
        if (island.equalsIgnoreCase("The Garden")) {
            return false;
        }
        String resolved = IslandCatalog.islandForArea(island);
        return resolved == null || !resolved.equalsIgnoreCase("The Garden");
    }

    /** The copy key: captures a custom area when that mode is on, otherwise the plot underfoot. */
    public void copy() {
        if (cfg().customArea) {
            copySelection();
        } else {
            copyPlot();
        }
    }

    /**
     * Copies the 96x96 plot the player stands on into the blueprint and switches the preview on.
     * Refuses (with a chat note) when not in a world, or when "only in Garden" is on and the player is
     * elsewhere.
     */
    public void copyPlot() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        SBSConfig.GardenBlueprintSettings cfg = cfg();
        if (cfg.onlyInGarden && !inGarden()) {
            // Say what WAS detected - "it does nothing" bugs are undiagnosable without this.
            SBSChat.send(warn(" Garden Blueprint: not in the Garden (detected: "
                    + SkyBlockLocation.describe()
                    + ") - disable 'Only In Garden' to use it elsewhere"));
            return;
        }

        GardenPlot.Bounds plot = GardenPlot.at(player.getX(), player.getZ());
        int baseY = (int) Math.floor(player.getY());
        int depth = clamp(cfg.captureDepth, 0, 8);
        int height = clamp(cfg.captureHeight, 1, GardenPlot.PLOT_SIZE);
        int minY = Math.max(baseY - depth, level.getMinY());
        int maxY = Math.min(baseY + height - 1, level.getMaxY());

        SchematicHeader header = new SchematicHeader("plot " + plot.label(), System.currentTimeMillis(),
                List.of("garden", "plot"), "", false, SchematicHeader.Source.PLOT,
                new int[] {plot.minX(), minY, plot.minZ()}, Map.of())
                .withExtra(GardenPreset.FLOOR_DEPTH, (baseY - 1) - minY);
        int bedrockY = GardenPreset.bedrockUnder(level, player.getBlockX(), baseY, player.getBlockZ());
        if (bedrockY != GardenPreset.NO_BEDROCK) {
            header = header.withExtra(GardenPreset.BEDROCK_HEIGHT, minY - bedrockY);
        }
        Schematic captured = WorldCapture.capture(level, plot.minX(), minY, plot.minZ(),
                plot.maxX(), maxY, plot.maxZ(), header, level.registryAccess());
        show(captured);

        String body = " Copied plot " + plot.label() + "  •  " + captured.nonAirCount() + " blocks"
                + "  •  preview ON";
        SBSChat.send(Component.literal(body).withColor(SBSChat.WHITE));
    }

    /** Puts a fresh copy on the clipboard and shows it, unpinned. */
    private static void show(Schematic captured) {
        Clipboard.set(captured);
        // A fresh copy starts unpinned, not on the last blueprint's spot.
        HologramManager.getInstance().set(
                Hologram.of(captured, GardenPreset.INSTANCE, null, Hologram.Mode.COMPARE));
    }

    /**
     * Sets one corner of the custom-area selection at the block the player is looking at (or, when
     * looking at nothing, at their feet). Presses alternate corner A and corner B.
     */
    public void setCorner() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        BlockPos pos;
        HitResult hit = minecraft.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            pos = blockHit.getBlockPos();
        } else {
            pos = player.blockPosition();
        }
        String which;
        SelectionManager selection = SelectionManager.getInstance();
        if (nextIsCornerA) {
            selection.setCorner1(pos);
            which = "A";
        } else {
            selection.setCorner2(pos);
            which = "B";
        }
        nextIsCornerA = !nextIsCornerA;

        StringBuilder body = new StringBuilder(" Corner " + which + " set to "
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        Selection box = selection.selection();
        if (box != null) {
            body.append("  •  ").append(box.width()).append("x").append(box.height()).append("x")
                    .append(box.length()).append(" - press copy to capture");
        }
        SBSChat.send(Component.literal(body.toString()).withColor(SBSChat.WHITE));
    }

    /** Captures the custom-area selection (both corners set) into the blueprint and shows the ghost. */
    public void copySelection() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (minecraft.player == null || level == null) {
            return;
        }
        Selection box = SelectionManager.getInstance().selection();
        if (box == null) {
            SBSChat.send(warn(" Garden Blueprint: set both corners first (bind the Set Corner key)"));
            return;
        }
        if (box.volume() > MAX_SELECTION_VOLUME) {
            SBSChat.send(warn(" Garden Blueprint: selection too large (" + box.width() + "x" + box.height()
                    + "x" + box.length() + ") - pick a smaller area"));
            return;
        }
        String label = box.width() + "x" + box.height() + "x" + box.length() + " area";
        SchematicHeader.Source source = minecraft.getSingleplayerServer() != null
                ? SchematicHeader.Source.SINGLEPLAYER : SchematicHeader.Source.MULTIPLAYER;
        SchematicHeader header = new SchematicHeader(label, System.currentTimeMillis(), List.of("garden"),
                "", false, source, new int[] {box.minX(), box.minY(), box.minZ()}, Map.of());
        Schematic captured = WorldCapture.capture(level, box, header, level.registryAccess());
        show(captured);
        SBSChat.send(Component.literal(" Copied " + label + "  •  " + captured.nonAirCount()
                + " blocks  •  preview ON").withColor(SBSChat.WHITE));
    }

    /**
     * Pins the copied blueprint to a block, so the ghost is drawn from there: its min corner (lowest,
     * north-west) lands on the block the player looks at, or on the block under their feet when they
     * look at nothing. The preview switches on, so one key both places and shows it.
     *
     * <p>This is what makes a plot copy usable off the plot grid - without a pin the ghost re-anchors
     * to whatever plot the player stands on, and a custom area stays where it was captured. Pressing
     * while sneaking releases the pin and hands the ghost back to that default.
     */
    public void placeAtLook() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        Hologram hologram = gardenHologram();
        if (hologram == null) {
            SBSChat.send(warn(" Garden Blueprint: nothing copied yet - press the copy key first"));
            return;
        }
        Schematic copied = hologram.display();
        boolean plotRelative = GardenPreset.plotRelative(copied);
        if (player.isShiftKeyDown()) {
            if (hologram.anchor() == null) {
                SBSChat.send(warn(" Garden Blueprint: the ghost is not placed on a block"));
                return;
            }
            HologramManager.getInstance().update(hologram.withAnchor(null));
            SBSChat.send(Component.literal(" Garden Blueprint released  •  ghost back on "
                    + (plotRelative ? "the plot you stand on" : "the spot it was copied at"))
                    .withColor(SBSChat.WHITE));
            return;
        }

        BlockPos pos;
        if (plotRelative) {
            // A plot capture is placed as a plot: its corner goes on the 96x96 grid corner of the plot
            // under the player, never on the arbitrary block that was looked at. Anything else puts a
            // copied farm a few blocks off its plot, where it can never line up again.
            pos = GardenPreset.snapToPlot(player, minecraft.level, copied);
        } else {
            HitResult hit = minecraft.hitResult;
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                pos = blockHit.getBlockPos();
            } else {
                // The block under the feet, not the feet themselves: the blueprint's bottom layer is
                // the ground it was captured on, so it has to land on ground here too, not float.
                pos = player.blockPosition().below();
            }
        }
        HologramManager manager = HologramManager.getInstance();
        manager.update(hologram.withAnchor(pos));
        manager.setShown(true);
        SBSChat.send(Component.literal(" Placed " + copied.header().name() + " at "
                + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "  •  preview ON")
                .withColor(SBSChat.WHITE));
    }

    /** Shows / hides the ghost preview; tells the player to copy first when there is no blueprint. */
    public void togglePreview() {
        if (gardenHologram() == null) {
            SBSChat.send(warn(" Garden Blueprint: nothing copied yet - press the copy key first"));
            return;
        }
        HologramManager manager = HologramManager.getInstance();
        boolean shown = !manager.shown();
        manager.setShown(shown);
        SBSChat.send(Component.literal(" Garden Blueprint preview ")
                .withColor(SBSChat.WHITE)
                .append(Component.literal(shown ? "ON" : "OFF")
                        .withColor(shown ? 0x57D977 : 0xE0605F)));
    }

    /** Discards the blueprint, its placement, the selection and hides the preview. */
    public void clear() {
        HologramManager.getInstance().clearIfOwnedBy(GardenPreset.INSTANCE);
        SelectionManager.getInstance().clear();
        nextIsCornerA = true;
    }

    /**
     * Saves the current blueprint into the build library ({@code config/sbs/schematics/}) under a
     * dated name, so a plot copy outlives the session - it used to be memory-only. Never overwrites:
     * the minute stamp keeps names apart, and a clash is reported rather than replaced.
     */
    public void saveToLibrary() {
        Schematic copied = blueprint();
        if (copied == null) {
            SBSChat.send(warn(" Garden Blueprint: nothing copied yet - press the copy key first"));
            return;
        }
        String name = copied.header().name() + " " + LocalDateTime.now().format(SAVE_STAMP);
        Schematic named = copied.withHeader(copied.header().withName(name));
        BuildLibrary.saveAsync(named, false, result -> {
            if (result.ok()) {
                SBSChat.send(Component.literal(" Saved \"" + name + "\" to the build library")
                        .withColor(SBSChat.WHITE));
            } else {
                SBSChat.send(warn(" Garden Blueprint: could not save - " + result.error()));
            }
        });
    }

    private static Component warn(String text) {
        return Component.literal(text).withColor(0xE0A14D);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
