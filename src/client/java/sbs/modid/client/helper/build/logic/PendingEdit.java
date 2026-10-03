/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.core.BlockPos;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.render.GhostCollector;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.build.model.EditPlan;
import sbs.modid.client.helper.build.model.EditShapes;
import sbs.modid.client.helper.build.render.BuildHud;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.List;
import java.util.Locale;

/**
 * An edit shown before it happens: its result drawn as a hologram - green where blocks are added or
 * changed, red where they are removed - until the player presses Enter to apply it or Escape to drop
 * it.
 *
 * <p>The preview is drawn from the very plan Enter applies, so nothing differs between what was shown
 * and what is placed; a weighted mix places the same blocks the preview showed
 * ({@code BlockPattern.pick} is a function of position). Adding {@code !} to a command, or switching
 * "Preview Edits" off, skips this and applies at once.
 */
public final class PendingEdit {

    /** A preview box larger than this is not drawn (the prompt and the outline still are). */
    private static final long MAX_PREVIEW_VOLUME = 8_000_000L;

    private static volatile EditPlan plan;
    private static volatile Runnable afterApply;

    static {
        BuildKeys.register(new BuildKeys.Handler() {
            @Override
            public boolean onKey(int key, int modifiers, boolean repeat) {
                if (plan == null) {
                    return false;
                }
                boolean mine = key == Placement.Keys.ENTER || key == Placement.Keys.KP_ENTER
                        || key == Placement.Keys.ESCAPE;
                if (!mine || repeat) {
                    return mine;
                }
                if (key == Placement.Keys.ESCAPE) {
                    cancel();
                } else {
                    apply();
                }
                return true;
            }

            @Override
            public boolean onScroll(double yOffset, boolean shift) {
                return false;
            }
        });
        BuildHud.addSection(PendingEdit::hudSection);
        BuildSession.onLeave(() -> {
            plan = null;
            afterApply = null;
        });
    }

    private PendingEdit() {
    }

    public static boolean active() {
        return plan != null;
    }

    /**
     * Offers an edit: straight to the engine when {@code applyNow} (the {@code !} suffix) or previews
     * are off, otherwise as a preview awaiting Enter. {@code then} runs once the edit is queued - a
     * move uses it to carry the selection along.
     */
    public static void offer(EditPlan edit, boolean applyNow, Runnable then) {
        if (edit.size() == 0) {
            BuildChat.info("Nothing to change - every block already is what that edit would make it");
            return;
        }
        if (edit.size() > EditEngine.MAX_EDIT_BLOCKS) {
            BuildChat.warn(String.format(Locale.ROOT, "That edit touches %,d blocks; the limit is %,d",
                    edit.size(), EditEngine.MAX_EDIT_BLOCKS));
            return;
        }
        if (applyNow || !ConfigManager.getInstance().get().buildTools.previewEdits) {
            if (EditEngine.submit(edit) && then != null) {
                then.run();
            }
            return;
        }
        plan = edit;
        afterApply = then;
        HologramManager.getInstance().setPreview(previewOf(edit));
        GhostCollector.invalidate();
        BuildChat.info("Preview: " + edit.name() + String.format(Locale.ROOT, "  •  %,d blocks", edit.size())
                + "  •  Enter applies, Esc cancels");
    }

    /** Enter: queue the previewed edit. */
    public static void apply() {
        EditPlan edit = plan;
        if (edit == null) {
            return;
        }
        if (!EditEngine.submit(edit)) {
            return;   // the engine said why; the preview stays so the player can try again
        }
        Runnable then = afterApply;
        clear();
        if (then != null) {
            then.run();
        }
    }

    /** Escape or {@code //cancel}: drop the preview, change nothing. */
    public static boolean cancel() {
        if (plan == null) {
            return false;
        }
        clear();
        BuildChat.info("Edit cancelled - nothing was changed");
        return true;
    }

    private static void clear() {
        plan = null;
        afterApply = null;
        HologramManager.getInstance().setPreview(null);
        GhostCollector.invalidate();
    }

    /** The plan as a hologram schematic: each target state, or the remove marker where it becomes air. */
    private static Hologram previewOf(EditPlan edit) {
        int[] b = edit.bounds();
        long volume = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
        if (volume > MAX_PREVIEW_VOLUME || volume > Schematic.MAX_VOLUME) {
            BuildChat.hint("Too large to draw as a preview - the box outline shows where it goes");
            Schematic outline = Schematic.builder(1, 1, 1).build();
            return Hologram.of(outline, BuildToolsOwner.INSTANCE, new BlockPos(b[0], b[1], b[2]), Hologram.Mode.PREVIEW);
        }
        Schematic.Builder builder = Schematic.builder(b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1)
                .header(SchematicHeader.untitled().withName(edit.name()));
        List<String> palette = edit.palette();
        int[] remap = new int[palette.size()];
        for (int i = 0; i < remap.length; i++) {
            remap[i] = builder.intern(i == 0 ? Hologram.REMOVE_MARKER : palette.get(i));
        }
        for (int i = 0; i < edit.size(); i++) {
            long packed = edit.position(i);
            int x = EditShapes.unpackX(packed) - b[0];
            int y = EditShapes.unpackY(packed) - b[1];
            int z = EditShapes.unpackZ(packed) - b[2];
            builder.setIndex(x + builder.width() * (z + builder.length() * y), remap[edit.target(i)]);
        }
        return Hologram.of(builder.build(), BuildToolsOwner.INSTANCE, new BlockPos(b[0], b[1], b[2]),
                Hologram.Mode.PREVIEW);
    }

    private static BuildHud.Section hudSection() {
        EditPlan edit = plan;
        if (edit != null) {
            return new BuildHud.Section(List.of(
                    new BuildHud.Line("Preview: " + edit.name() + String.format(Locale.ROOT, "  •  %,d blocks",
                            edit.size()), SBSTheme.ACCENT_BRIGHT),
                    new BuildHud.Line("Green: added or changed  •  Red: removed", SBSTheme.TEXT),
                    new BuildHud.Line("Enter: apply  •  Esc: cancel", SBSTheme.TOGGLE_ON)), -1f);
        }
        String running = EditEngine.label();
        if (running != null) {
            return new BuildHud.Section(List.of(new BuildHud.Line(running, SBSTheme.ACCENT_BRIGHT)),
                    EditEngine.progress());
        }
        return null;
    }
}
