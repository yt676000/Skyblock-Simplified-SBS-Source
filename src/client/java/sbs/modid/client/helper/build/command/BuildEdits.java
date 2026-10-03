/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import sbs.modid.client.core.build.logic.Clipboard;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.logic.SelectionManager;
import sbs.modid.client.core.build.model.BuildDir;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.model.Selection;
import sbs.modid.client.helper.build.logic.BuildChat;
import sbs.modid.client.helper.build.logic.BuildGate;
import sbs.modid.client.helper.build.logic.BuildToolsOwner;
import sbs.modid.client.helper.build.logic.EditEngine;
import sbs.modid.client.helper.build.logic.EditPlanner;
import sbs.modid.client.helper.build.logic.PendingEdit;
import sbs.modid.client.helper.build.logic.Placement;
import sbs.modid.client.helper.build.logic.ServerCapture;
import sbs.modid.client.helper.build.logic.Timeline;
import sbs.modid.client.helper.build.model.BlockPattern;
import sbs.modid.client.helper.build.model.EditHistory;
import sbs.modid.client.helper.build.model.EditPlan;
import sbs.modid.client.helper.build.model.EditRecord;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The singleplayer edit verbs: set, replace, walls, outline, hollow, fill, move, stack, cut, undo,
 * redo - and applying a placed paste.
 *
 * <p>Every one of these is {@code Reach.SINGLEPLAYER}; the dispatcher has already refused them on a
 * server before they get here, and {@link EditEngine} refuses again before it queues anything. A
 * trailing {@code !} on the arguments applies without the preview.
 */
public final class BuildEdits {

    /** Most cells {@code //fill} floods before it gives up and calls the area open. */
    private static final int FILL_LIMIT = 1_000_000;

    /** Half-size of the box a fill searches when there is no selection. */
    private static final int FILL_RADIUS = 48;

    private BuildEdits() {
    }

    /** Splits a trailing {@code !} off: {@code {text, "!"}} or {@code {text, ""}}. */
    static String[] bang(String rest) {
        String trimmed = rest.trim();
        if (trimmed.endsWith("!")) {
            return new String[] {trimmed.substring(0, trimmed.length() - 1).trim(), "!"};
        }
        return new String[] {trimmed, ""};
    }

    private static Selection selection() {
        Selection box = SelectionManager.getInstance().selection();
        if (box == null) {
            BuildChat.warn("No selection - set both corners first (the Magic Stick Thingy, //pos1 and //pos2)");
            return null;
        }
        if (box.volume() > EditEngine.MAX_EDIT_BLOCKS) {
            BuildChat.warn(String.format(Locale.ROOT, "The selection holds %,d blocks; an edit may touch at most %,d",
                    box.volume(), EditEngine.MAX_EDIT_BLOCKS));
            return null;
        }
        return box;
    }

    private static ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    private static EditPlanner.Resolved pattern(String text, String usage) {
        if (text.isEmpty()) {
            BuildChat.warn("Usage: //" + usage);
            return null;
        }
        try {
            return EditPlanner.resolve(BlockPattern.parse(text, sbs.modid.client.helper.build.logic.HeldBlocks.RESOLVER));
        } catch (BlockPattern.PatternException bad) {
            BuildChat.warn("Can't use that block: " + bad.getMessage());
            BuildChat.hint("Examples: stone  •  hand (the block you hold)  •  50%hand,50%stone  •  oak_stairs[facing=east]");
            return null;
        }
    }

    /** {@code set|walls|outline <pattern>[!]}. */
    static void shape(BuildCommand command, String rest) {
        Selection box = selection();
        if (box == null) {
            return;
        }
        String[] parts = bang(rest);
        if (command == BuildCommand.SET) {
            // A bare //set places the held block - through the preview, which is what makes it safe.
            parts[0] = sbs.modid.client.helper.build.model.HeldBlockRule.setArgument(parts[0]);
        }
        EditPlanner.Resolved pattern = pattern(parts[0], command.word() + " " + command.usage());
        if (pattern == null) {
            return;
        }
        EditPlan plan = switch (command) {
            case WALLS -> EditPlanner.walls(box, pattern, level());
            case OUTLINE -> EditPlanner.outline(box, pattern, level());
            default -> EditPlanner.set(box, pattern, level());
        };
        PendingEdit.offer(plan, !parts[1].isEmpty(), null);
    }

    /** {@code replace <from> <to>[!]}. */
    static void replace(String rest) {
        Selection box = selection();
        if (box == null) {
            return;
        }
        String[] parts = bang(rest);
        String[] words = parts[0].split("\\s+", 2);
        if (words.length < 2) {
            BuildChat.warn("Usage: //replace <blocks to find> <block to put>   e.g. //replace dirt,grass_block stone");
            return;
        }
        EditPlanner.Mask mask;
        try {
            mask = EditPlanner.mask(words[0]);
        } catch (BlockPattern.PatternException bad) {
            BuildChat.warn("Can't search for that: " + bad.getMessage());
            return;
        }
        EditPlanner.Resolved pattern = pattern(words[1].trim(), "replace <from> <to>");
        if (pattern == null) {
            return;
        }
        PendingEdit.offer(EditPlanner.replace(box, mask, pattern, level()), !parts[1].isEmpty(), null);
    }

    /** {@code hollow[!]}. */
    static void hollow(String rest) {
        Selection box = selection();
        if (box == null) {
            return;
        }
        if (box.width() < 3 || box.height() < 3 || box.length() < 3) {
            BuildChat.warn("The selection must be at least 3 blocks in every direction to have an inside");
            return;
        }
        PendingEdit.offer(EditPlanner.hollow(box, level()), rest.trim().equals("!"), null);
    }

    /**
     * {@code fill <pattern>[!]}: floods the air pocket in front of the face you look at (or at your
     * feet), inside the selection when there is one, else inside a box around you.
     */
    static void fill(String rest) {
        String[] parts = bang(rest);
        EditPlanner.Resolved pattern = pattern(parts[0], "fill <block>");
        if (pattern == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        BlockPos start;
        HitResult hit = minecraft.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            start = blockHit.getBlockPos().relative(blockHit.getDirection());
        } else if (minecraft.player != null) {
            start = minecraft.player.blockPosition();
        } else {
            return;
        }
        Selection bounds = SelectionManager.getInstance().selection();
        if (bounds == null || !bounds.contains(start.getX(), start.getY(), start.getZ())) {
            bounds = Selection.single(start.getX(), start.getY(), start.getZ()).expandAll(FILL_RADIUS);
        }
        EditPlanner.FillResult result = EditPlanner.fill(start, bounds, pattern, level(), FILL_LIMIT);
        if (result.error() != null) {
            BuildChat.warn("Can't fill: " + result.error());
            return;
        }
        PendingEdit.offer(result.plan(), !parts[1].isEmpty(), null);
    }

    /** {@code move <n> [dir][!]}: the selection's contents and the selection itself move. */
    static void move(String rest) {
        Selection box = selection();
        if (box == null) {
            return;
        }
        String[] parts = bang(rest);
        String[] words = parts[0].isEmpty() ? new String[0] : parts[0].split("\\s+");
        Integer amount = words.length > 0 ? BuildCommands.parseInt(words[0]) : null;
        BuildDir dir = BuildCommands.direction(words.length > 1 ? words[1] : null);
        if (amount == null || amount == 0 || dir == null) {
            BuildChat.warn("Usage: //move <blocks> [up|down|north|south|east|west]  (default: where you look)");
            return;
        }
        boolean now = !parts[1].isEmpty();
        String name = "move " + amount + " " + dir.label();
        ServerCapture.capture(box, SchematicHeader.untitled(), source -> {
            if (source == null) {
                BuildChat.warn("Can't move: the world could not be read");
                return;
            }
            EditPlan plan = EditPlanner.move(source, box, dir.dx * amount, dir.dy * amount, dir.dz * amount, level(), name);
            PendingEdit.offer(plan, now, () -> SelectionManager.getInstance().set(box.shift(amount, dir)));
        });
    }

    /** {@code stack <n> [dir][!]}: {@code n} copies of the selection, one after another. */
    static void stack(String rest) {
        Selection box = selection();
        if (box == null) {
            return;
        }
        String[] parts = bang(rest);
        String[] words = parts[0].isEmpty() ? new String[0] : parts[0].split("\\s+");
        Integer count = words.length > 0 ? BuildCommands.parseInt(words[0]) : null;
        BuildDir dir = BuildCommands.direction(words.length > 1 ? words[1] : null);
        if (count == null || count < 1 || dir == null) {
            BuildChat.warn("Usage: //stack <copies> [up|down|north|south|east|west]  (default: where you look)");
            return;
        }
        if (box.volume() * count > EditEngine.MAX_EDIT_BLOCKS) {
            BuildChat.warn(String.format(Locale.ROOT, "%d copies of %,d blocks is more than one edit may touch (%,d)",
                    count, box.volume(), EditEngine.MAX_EDIT_BLOCKS));
            return;
        }
        boolean now = !parts[1].isEmpty();
        String name = "stack " + count + " " + dir.label();
        ServerCapture.capture(box, SchematicHeader.untitled(), source -> {
            if (source == null) {
                BuildChat.warn("Can't stack: the world could not be read");
                return;
            }
            PendingEdit.offer(EditPlanner.stack(source, box, dir, count, level(), name), now, null);
        });
    }

    /** {@code cut[!]}: copy to the clipboard, then clear the selection. */
    static void cut(String rest) {
        Selection box = selection();
        if (box == null) {
            return;
        }
        boolean now = rest.trim().equals("!");
        SchematicHeader header = new SchematicHeader("", System.currentTimeMillis(), List.of(), "", false,
                SchematicHeader.Source.SINGLEPLAYER, new int[] {box.minX(), box.minY(), box.minZ()}, Map.of());
        ServerCapture.capture(box, header, captured -> {
            if (captured == null) {
                BuildChat.warn("Can't cut: the world could not be read");
                return;
            }
            Clipboard.set(captured);
            BuildChat.info("Copied " + captured.sizeLabel() + String.format(Locale.ROOT, "  •  %,d blocks",
                    captured.nonAirCount()) + " to the clipboard");
            PendingEdit.offer(EditPlanner.clear(box, level(), "cut " + captured.sizeLabel()), now, null);
        });
    }

    /** {@code undo [n]}. */
    static void undo(String rest) {
        int n = count(rest);
        EditHistory<EditRecord> history = Timeline.history();
        if (!history.canUndo()) {
            BuildChat.info("Nothing to undo");
            return;
        }
        List<EditHistory.Step<EditRecord>> steps = history.undoSteps(n);
        EditEngine.replay(steps, history.cursor() - steps.size(), "undo " + describe(steps));
    }

    /** {@code redo [n]}. */
    static void redo(String rest) {
        int n = count(rest);
        EditHistory<EditRecord> history = Timeline.history();
        if (!history.canRedo()) {
            BuildChat.info("Nothing to redo");
            return;
        }
        List<EditHistory.Step<EditRecord>> steps = history.redoSteps(n);
        EditEngine.replay(steps, history.cursor() + steps.size(), "redo " + describe(steps));
    }

    /** Jumps the world to timeline entry {@code target} (-1 = before the first). Used by the screen. */
    public static void jumpTo(int target) {
        if (!BuildGate.singleplayer()) {
            BuildChat.warn(BuildCommand.SINGLEPLAYER_ONLY);
            return;
        }
        EditHistory<EditRecord> history = Timeline.history();
        List<EditHistory.Step<EditRecord>> steps = history.stepsTo(target);
        if (steps.isEmpty()) {
            BuildChat.info("The world is already at that point");
            return;
        }
        boolean back = steps.get(0).backward();
        EditEngine.replay(steps, target, (back ? "back " : "forward ") + steps.size() + " step"
                + (steps.size() == 1 ? "" : "s") + " in the timeline");
    }

    private static String describe(List<EditHistory.Step<EditRecord>> steps) {
        return steps.size() == 1 ? "\"" + steps.get(0).entry().name() + "\"" : steps.size() + " edits";
    }

    private static int count(String rest) {
        Integer n = rest.isBlank() ? Integer.valueOf(1) : BuildCommands.parseInt(rest);
        return n == null || n < 1 ? 1 : n;
    }

    /**
     * Enter on a placed paste, in singleplayer: the hologram's blocks (after turns, flips and palette
     * swaps) go into the world through the engine - no second preview, the floating hologram already
     * was one, collisions in red included.
     */
    public static void applyPaste(Hologram hologram, BlockPos base) {
        Schematic shown = hologram.display();
        String name = "paste " + (shown.header().name().isBlank() ? shown.sizeLabel() : shown.header().name());
        EditPlan plan = EditPlanner.place(name, shown, hologram.palette(), base, false, level());
        if (plan.size() == 0) {
            BuildChat.info("Nothing to place - every block is already there");
            HologramManager.getInstance().clearIfOwnedBy(BuildToolsOwner.INSTANCE);
            return;
        }
        if (EditEngine.submit(plan)) {
            HologramManager.getInstance().clearIfOwnedBy(BuildToolsOwner.INSTANCE);
        } else {
            Placement.resume();
        }
    }
}
