/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.command;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import sbs.modid.client.core.build.io.SchematicStore;
import sbs.modid.client.core.build.logic.BuildLibrary;
import sbs.modid.client.core.build.logic.Clipboard;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.logic.SelectionManager;
import sbs.modid.client.core.build.model.BuildDir;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.model.SchematicTransform;
import sbs.modid.client.core.build.model.Selection;
import sbs.modid.client.core.build.render.GhostModels;
import sbs.modid.client.core.build.logic.BlockStates;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.build.logic.BuildChat;
import sbs.modid.client.helper.build.logic.BuildGate;
import sbs.modid.client.helper.build.logic.BuildToolsOwner;
import sbs.modid.client.helper.build.logic.MagicStick;
import sbs.modid.client.helper.build.logic.Placement;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs a typed {@code /..} command. Reached from {@code SBSCommands.tryExecute}, which the chat mixin
 * calls before anything is sent - so a {@code /..} line never reaches the server, on Hypixel or in
 * singleplayer. The Brigadier nodes in {@link BuildCommandTree} exist only for suggestions.
 *
 * <p>Order of checks for every verb: module on, then the verb's reach ({@link BuildCommand#refusal}),
 * then its arguments. So an edit typed on a server gets the singleplayer answer, not a usage error
 * about its arguments.
 */
public final class BuildCommands {

    /** Largest selection {@code //copy} reads - 8M cells is a 200-cube, far past any real build. */
    static final long MAX_COPY_VOLUME = 8_000_000L;

    /** Selections up to this size get a block count in {@code //size}; beyond it the scan would stall. */
    private static final long COUNT_LIMIT = 2_000_000L;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private BuildCommands() {
    }

    /** @param args everything after {@code /..}, possibly empty */
    public static void execute(String args) {
        String trimmed = args == null ? "" : args.trim();
        if (!ConfigManager.getInstance().get().buildTools.enabled) {
            BuildChat.warn("Build Tools is off - switch it on in /sbs, Quality of Life > Build Tools");
            return;
        }
        if (trimmed.isEmpty()) {
            help();
            return;
        }
        String[] split = trimmed.split("\\s+", 2);
        String rest = split.length > 1 ? split[1].trim() : "";
        BuildCommand command = BuildCommand.parse(split[0]);
        if (command == null) {
            BuildChat.warn("Unknown command \"" + split[0] + "\" - //help lists them");
            return;
        }
        sbs.modid.client.helper.build.logic.SelectionActions.touch();
        String refusal = command.refusal(BuildGate.singleplayer());
        if (refusal != null) {
            BuildChat.warn(refusal);
            return;
        }
        try {
            run(command, rest);
        } catch (RuntimeException failed) {
            // Nothing fails silently: say what broke and log the rest.
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Build] //{} failed", command.word(), failed);
            BuildChat.warn("//" + command.word() + " failed: " + failed.getMessage());
        }
    }

    private static void run(BuildCommand command, String rest) {
        switch (command) {
            case POS1 -> corner(true);
            case POS2 -> corner(false);
            case EXPAND -> resize(rest, true);
            case CONTRACT -> resize(rest, false);
            case SHIFT -> shift(rest);
            case SEL -> selClear(rest);
            case SIZE -> size();
            case COPY -> copy();
            case PASTE -> paste();
            case ROTATE -> rotate(rest);
            case FLIP -> flip(rest);
            case SAVE -> save(rest);
            case LOAD -> load(rest);
            case LIST -> list(rest);
            case DELETE -> delete(rest);
            case HOLOGRAM -> hologram(rest);
            case SELECT -> BuildHelpers.select(rest);
            case MATERIALS -> BuildHelpers.materials(rest);
            case GUIDE -> BuildHelpers.guide(rest);
            case SWAP -> BuildHelpers.swap(rest);
            case SHARE -> BuildSharing.share(rest);
            case IMPORT -> BuildSharing.importFrom(rest);
            case EXPORT -> BuildSharing.export(rest);
            case QUICK -> openQuickPaste();
            case LIBRARY -> openLibrary();
            case FREECAM -> sbs.modid.client.helper.build.logic.Freecam.toggle();
            case CANCEL -> cancel();
            case HELP -> help();
            case STICK -> stick();
            case SET, WALLS, OUTLINE -> BuildEdits.shape(command, rest);
            case REPLACE -> BuildEdits.replace(rest);
            case HOLLOW -> BuildEdits.hollow(rest);
            case FILL -> BuildEdits.fill(rest);
            case MOVE -> BuildEdits.move(rest);
            case STACK -> BuildEdits.stack(rest);
            case CUT -> BuildEdits.cut(rest);
            case UNDO -> BuildEdits.undo(rest);
            case REDO -> BuildEdits.redo(rest);
            case TIMELINE -> openTimeline();
        }
    }

    // ---------------------------------------------------------------- selection

    /** The block the crosshair is on, or the block at the player's feet. */
    static BlockPos lookTarget() {
        // In freecam this is the camera's ray, and Alt+wheel steps deeper - see BuildTargeting.
        sbs.modid.client.helper.build.logic.BuildTargeting.Target target =
                sbs.modid.client.helper.build.logic.BuildTargeting.target();
        if (target != null) {
            return target.pos();
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition();
    }

    static void corner(boolean first) {
        BlockPos pos = lookTarget();
        SelectionManager selection = SelectionManager.getInstance();
        if (first) {
            selection.setCorner1(pos);
        } else {
            selection.setCorner2(pos);
        }
        reportCorner(first ? 1 : 2, pos);
    }

    /** "Corner 1 set to x, y, z" plus the size once both corners exist. Shared with the wand and keys. */
    public static void reportCorner(int which, BlockPos pos) {
        StringBuilder text = new StringBuilder("Corner " + which + " set to " + pos.getX() + ", "
                + pos.getY() + ", " + pos.getZ());
        Selection box = SelectionManager.getInstance().selection();
        if (box != null) {
            text.append("  •  ").append(box.sizeLabel()).append("  •  ")
                    .append(String.format(Locale.ROOT, "%,d", box.volume())).append(" blocks");
        }
        BuildChat.info(text.toString());
    }

    private static Selection requireSelection() {
        Selection box = SelectionManager.getInstance().selection();
        if (box == null) {
            BuildChat.warn("No selection - set both corners first (//pos1 and //pos2, the corner keys, "
                    + "or the Magic Stick Thingy in singleplayer)");
        }
        return box;
    }

    /** {@code <n> [dir|all]} for expand and contract. */
    private static void resize(String rest, boolean expand) {
        Selection box = requireSelection();
        if (box == null) {
            return;
        }
        String[] words = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        Integer amount = words.length > 0 ? parseInt(words[0]) : null;
        if (amount == null) {
            usage(expand ? BuildCommand.EXPAND : BuildCommand.CONTRACT);
            return;
        }
        Selection result;
        String where;
        if (words.length > 1 && words[1].equalsIgnoreCase("all")) {
            result = expand ? box.expandAll(amount) : box.contractAll(amount);
            where = "on every side";
        } else {
            BuildDir dir = direction(words.length > 1 ? words[1] : null);
            if (dir == null) {
                BuildChat.warn("Unknown direction \"" + words[1] + "\" - up, down, north, south, east, west or all");
                return;
            }
            result = expand ? box.expand(amount, dir) : box.contract(amount, dir);
            where = dir.label();
        }
        SelectionManager.getInstance().set(result);
        BuildChat.info((expand ? "Expanded " : "Contracted ") + amount + " " + where + "  •  now "
                + result.sizeLabel() + "  •  " + String.format(Locale.ROOT, "%,d", result.volume()) + " blocks");
    }

    private static void shift(String rest) {
        Selection box = requireSelection();
        if (box == null) {
            return;
        }
        String[] words = rest.isEmpty() ? new String[0] : rest.split("\\s+");
        Integer amount = words.length > 0 ? parseInt(words[0]) : null;
        if (amount == null) {
            usage(BuildCommand.SHIFT);
            return;
        }
        BuildDir dir = direction(words.length > 1 ? words[1] : null);
        if (dir == null) {
            BuildChat.warn("Unknown direction \"" + words[1] + "\" - up, down, north, south, east or west");
            return;
        }
        SelectionManager.getInstance().set(box.shift(amount, dir));
        BuildChat.info("Moved the selection " + amount + " " + dir.label());
    }

    private static void selClear(String rest) {
        if (!rest.equalsIgnoreCase("clear")) {
            usage(BuildCommand.SEL);
            return;
        }
        SelectionManager.getInstance().clear();
        BuildChat.info("Selection cleared");
    }

    private static void size() {
        Selection box = requireSelection();
        if (box == null) {
            return;
        }
        String text = "Selection " + box.sizeLabel() + "  •  " + String.format(Locale.ROOT, "%,d", box.volume())
                + " blocks  •  from " + box.minX() + ", " + box.minY() + ", " + box.minZ();
        Minecraft minecraft = Minecraft.getInstance();
        if (box.volume() <= COUNT_LIMIT && minecraft.level != null) {
            int solid = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    for (int x = box.minX(); x <= box.maxX(); x++) {
                        if (!minecraft.level.getBlockState(pos.set(x, y, z)).isAir()) {
                            solid++;
                        }
                    }
                }
            }
            text += "  •  " + String.format(Locale.ROOT, "%,d", solid) + " not air";
        }
        BuildChat.info(text);
    }

    /** A direction word, or where the player looks when there is none. */
    static BuildDir direction(String word) {
        if (word == null || word.isBlank() || word.equalsIgnoreCase("me") || word.equalsIgnoreCase("look")) {
            Player player = Minecraft.getInstance().player;
            return player == null ? BuildDir.NORTH : BuildDir.fromLook(player.getYRot(), player.getXRot());
        }
        return BuildDir.parse(word);
    }

    // ---------------------------------------------------------------- clipboard

    static void copy() {
        Selection box = requireSelection();
        if (box == null) {
            return;
        }
        if (box.volume() > MAX_COPY_VOLUME) {
            BuildChat.warn("Selection too large to copy (" + box.sizeLabel() + ", "
                    + String.format(Locale.ROOT, "%,d", box.volume()) + " blocks; the limit is "
                    + String.format(Locale.ROOT, "%,d", MAX_COPY_VOLUME) + ")");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        boolean singleplayer = BuildGate.singleplayer();
        SchematicHeader header = new SchematicHeader("", System.currentTimeMillis(), List.of(), "", false,
                singleplayer ? SchematicHeader.Source.SINGLEPLAYER : SchematicHeader.Source.MULTIPLAYER,
                new int[] {box.minX(), box.minY(), box.minZ()}, Map.of());
        // Singleplayer reads the server's own level on its thread, the only place the contents of
        // chests, signs and banners exist; a server copy reads only the client's loaded blocks.
        sbs.modid.client.helper.build.logic.ServerCapture.capture(box, header, captured -> {
            if (captured == null) {
                BuildChat.warn("Copy failed - the world could not be read");
            } else {
                copied(captured);
            }
        });
    }

    private static void copied(Schematic captured) {
        Clipboard.set(captured);
        BuildChat.info("Copied " + captured.sizeLabel() + "  •  " + String.format(Locale.ROOT, "%,d",
                captured.nonAirCount()) + " blocks"
                + (captured.blockEntities().isEmpty() ? "" : " (" + captured.blockEntities().size() + " with contents)"));
        BuildChat.hint("//save <name> keeps it  •  //paste places it");
    }

    static Schematic requireClipboard() {
        Schematic clipboard = Clipboard.get();
        if (clipboard == null) {
            BuildChat.warn("The clipboard is empty - //copy a selection or //load a saved build");
        }
        return clipboard;
    }

    static void paste() {
        Schematic clipboard = requireClipboard();
        if (clipboard == null) {
            return;
        }
        if (Placement.start(clipboard)) {
            BuildChat.info("Placing " + describe(clipboard) + " - arrows/wheel move it, R turns, F flips, "
                    + "G snaps to the plot grid, Enter " + (BuildGate.singleplayer() ? "places it" : "pins it")
                    + ", Esc cancels");
        }
    }

    private static void rotate(String rest) {
        Schematic clipboard = requireClipboard();
        if (clipboard == null) {
            return;
        }
        Integer degrees = rest.isEmpty() ? Integer.valueOf(90) : parseInt(rest);
        int turns = degrees == null ? -1 : SchematicTransform.quarterTurns(degrees);
        if (turns < 0) {
            usage(BuildCommand.ROTATE);
            return;
        }
        Clipboard.set(SchematicTransform.rotate(clipboard, turns, BlockStates.MAPPER));
        if (Placement.active()) {
            Hologram hologram = HologramManager.getInstance().hologram();
            HologramManager.getInstance().update(hologram.rotated(turns));
        }
        BuildChat.info("Turned the clipboard " + (turns * 90) + "° clockwise  •  now " + Clipboard.get().sizeLabel());
    }

    private static void flip(String rest) {
        Schematic clipboard = requireClipboard();
        if (clipboard == null) {
            return;
        }
        SchematicTransform.Axis axis;
        if (rest.isEmpty()) {
            Player player = Minecraft.getInstance().player;
            BuildDir facing = player == null ? BuildDir.NORTH : BuildDir.horizontalFromYaw(player.getYRot());
            // Left-right as the player sees it: facing along Z, that is the X axis.
            axis = facing == BuildDir.NORTH || facing == BuildDir.SOUTH
                    ? SchematicTransform.Axis.X : SchematicTransform.Axis.Z;
        } else {
            axis = SchematicTransform.Axis.parse(rest);
            if (axis == null) {
                usage(BuildCommand.FLIP);
                return;
            }
        }
        Clipboard.set(SchematicTransform.flip(clipboard, axis, BlockStates.MAPPER));
        if (Placement.active()) {
            Hologram hologram = HologramManager.getInstance().hologram();
            HologramManager.getInstance().update(hologram.flipped(axis));
        }
        BuildChat.info("Mirrored the clipboard across " + axis.name().toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- library

    private static void save(String rest) {
        Schematic clipboard = requireClipboard();
        if (clipboard == null) {
            return;
        }
        boolean overwrite = rest.endsWith("!");
        String name = (overwrite ? rest.substring(0, rest.length() - 1) : rest).trim();
        if (name.isEmpty()) {
            usage(BuildCommand.SAVE);
            return;
        }
        SchematicHeader header = clipboard.header().withName(name);
        if (header.createdAt() == 0L) {
            header = header.withCreatedAt(System.currentTimeMillis());
        }
        Schematic named = clipboard.withHeader(header);
        BuildLibrary.saveAsync(named, overwrite, result -> {
            if (result.ok()) {
                Clipboard.set(named);
                BuildChat.info("Saved \"" + name + "\"  •  " + describe(named));
            } else if (!overwrite && result.error() != null && result.error().contains("already exists")) {
                BuildChat.warn("A build called \"" + SchematicStore.slug(name) + "\" already exists");
                BuildChat.hint("//save " + name + "! replaces it");
            } else {
                BuildChat.warn("Could not save - " + result.error());
            }
        });
    }

    private static void load(String rest) {
        if (rest.isEmpty()) {
            usage(BuildCommand.LOAD);
            return;
        }
        BuildLibrary.loadAsync(rest, result -> {
            if (!result.ok()) {
                BuildChat.warn("Could not load - " + result.error());
                return;
            }
            Clipboard.set(result.value());
            BuildChat.info("Loaded \"" + result.value().header().name() + "\"  •  " + describe(result.value()));
            BuildChat.hint("//paste places it");
        });
    }

    private static void list(String rest) {
        String search = rest.toLowerCase(Locale.ROOT);
        BuildLibrary.runAsync(SchematicStore::list, result -> {
            if (!result.ok()) {
                BuildChat.warn("Could not read the library - " + result.error());
                return;
            }
            List<SchematicStore.Entry> entries = result.value().entries().stream()
                    .filter(entry -> search.isEmpty() || entry.displayName().toLowerCase(Locale.ROOT).contains(search)
                            || entry.summary().header().tags().stream().anyMatch(tag -> tag.contains(search)))
                    .toList();
            if (entries.isEmpty()) {
                BuildChat.info(search.isEmpty() ? "The library is empty - //save <name> adds the clipboard"
                        : "No saved build matches \"" + rest + "\"");
                return;
            }
            BuildChat.info(entries.size() + " saved build" + (entries.size() == 1 ? "" : "s")
                    + (entries.size() > 20 ? " (newest 20 shown)" : "") + " - click one to load it");
            for (SchematicStore.Entry entry : entries.subList(0, Math.min(20, entries.size()))) {
                String line = "  " + entry.displayName() + "  •  " + entry.summary().sizeLabel() + "  •  "
                        + String.format(Locale.ROOT, "%,d", entry.summary().blocks()) + " blocks  •  "
                        + DATE.format(Instant.ofEpochMilli(entry.summary().header().createdAt()))
                        + "  •  " + entry.summary().header().source().label();
                SBSChat.send(Component.literal(line).withColor(SBSChat.WHITE).withStyle(style -> style
                        .withClickEvent(new ClickEvent.SuggestCommand("//load " + entry.slug()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click: //load " + entry.slug())))));
            }
            if (!result.value().broken().isEmpty()) {
                BuildChat.warn(result.value().broken().size() + " file(s) in the folder could not be read - see the log");
                result.value().broken().forEach(broken -> sbs.modid.SkyblockSimplifiedSBS.LOGGER.info(
                        "[SBS][Build] Unreadable library file {}: {}", broken.file(), broken.reason()));
            }
        });
    }

    private static void delete(String rest) {
        if (rest.isEmpty()) {
            usage(BuildCommand.DELETE);
            return;
        }
        BuildLibrary.runAsync(store -> store.delete(rest), result -> {
            if (result.ok()) {
                BuildChat.info("Moved \"" + rest + "\" to the library's .deleted folder - move it back to restore it");
            } else {
                BuildChat.warn("Could not delete - " + result.error());
            }
        });
    }

    // ---------------------------------------------------------------- hologram and misc

    private static void hologram(String rest) {
        HologramManager manager = HologramManager.getInstance();
        String word = rest.toLowerCase(Locale.ROOT);
        if (word.equals("debug")) {
            // Allowed without a hologram: arm first, then paste, and the first frame is logged.
            GhostModels.armProbe();
            BuildChat.info("Ghost probe armed - the next frame logs each fluid and head once ([SBS][Blueprint] in latest.log)");
            return;
        }
        if (manager.hologram() == null) {
            BuildChat.warn("No hologram - //paste shows the clipboard as one");
            return;
        }
        switch (word) {
            case "clear" -> {
                if (Placement.active()) {
                    Placement.cancel();
                } else {
                    manager.clear();
                    BuildChat.info("Hologram removed");
                }
            }
            case "on", "off", "" -> {
                boolean show = word.isEmpty() ? !manager.shown() : word.equals("on");
                manager.setShown(show);
                BuildChat.info("Hologram " + (show ? "shown" : "hidden"));
            }
            default -> usage(BuildCommand.HOLOGRAM);
        }
    }

    private static void cancel() {
        if (sbs.modid.client.helper.build.logic.PendingEdit.cancel()) {
            return;
        }
        if (sbs.modid.client.helper.build.logic.EditEngine.cancel()) {
            return;
        }
        if (Placement.active()) {
            Placement.cancel();
            return;
        }
        BuildChat.info("Nothing to cancel");
    }

    /** Opens Quick Paste; installed at start-up so this class does not depend on UI. */
    private static volatile Runnable quickPasteOpener;

    public static void setQuickPasteOpener(Runnable opener) {
        quickPasteOpener = opener;
    }

    private static void openQuickPaste() {
        Runnable opener = quickPasteOpener;
        if (opener != null) {
            Minecraft.getInstance().execute(opener);
        }
    }

    /** Opens the Build Library; installed at start-up so this class does not depend on UI. */
    private static volatile Runnable libraryOpener;

    public static void setLibraryOpener(Runnable opener) {
        libraryOpener = opener;
    }

    public static void openLibrary() {
        Runnable opener = libraryOpener;
        if (opener != null) {
            Minecraft.getInstance().execute(opener);
        }
    }

    /** Opens the timeline screen; installed by the screen so this class does not depend on UI. */
    private static volatile Runnable timelineOpener;

    public static void setTimelineOpener(Runnable opener) {
        timelineOpener = opener;
    }

    private static void openTimeline() {
        Runnable opener = timelineOpener;
        if (opener != null) {
            // A command runs from the chat screen, which closes after this returns; open next tick.
            Minecraft.getInstance().execute(opener);
        }
    }

    private static void stick() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        MagicStick.give(player.getUUID(), added -> {
            if (added) {
                BuildChat.info("Here is the " + MagicStick.NAME + " - left-click a block for corner 1, right-click for corner 2");
            } else {
                BuildChat.warn("No room in your inventory for the " + MagicStick.NAME);
            }
        });
    }

    static void help() {
        BuildChat.info("Build Tools - //<command>" + (BuildGate.singleplayer() ? "" : " (on a server only the read-only ones work)"));
        for (BuildCommand command : BuildCommand.values()) {
            String usage = command.usage().isEmpty() ? "" : " " + command.usage();
            String reach = command.reach() == BuildCommand.Reach.SINGLEPLAYER ? " [singleplayer]" : "";
            SBSChat.send(Component.literal("  //" + command.word() + usage).withColor(0x7FD4FF)
                    .append(Component.literal(" - " + command.help() + reach).withColor(0xA0A0A0)));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void usage(BuildCommand command) {
        BuildChat.warn("Usage: //" + command.word() + (command.usage().isEmpty() ? "" : " " + command.usage()));
    }

    static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** {@code W×H×L, N blocks}. */
    static String describe(Schematic schematic) {
        return schematic.sizeLabel() + ", " + String.format(Locale.ROOT, "%,d", schematic.nonAirCount()) + " blocks";
    }

    /** Whether the hologram on screen is one this feature placed. */
    static boolean ownHologram() {
        Hologram hologram = HologramManager.getInstance().hologram();
        return hologram != null && hologram.owner() == BuildToolsOwner.INSTANCE;
    }
}
