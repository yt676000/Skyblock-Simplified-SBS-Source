/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.model.StateStrings;
import sbs.modid.client.core.build.render.GhostCollector;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.helper.build.render.BuildHud;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Build guide: the hologram one layer at a time, each block marked right or wrong, how many are
 * left, and the next missing block boxed so you know where to put your hand next.
 *
 * <p>Pure guidance - it shows, it never places. Works on any hologram, a Garden Blueprint plot copy
 * included. The mouse wheel changes the layer while the guide is on (and nothing is being placed or
 * previewed); Shift+wheel shows all layers again.
 */
public final class BuildGuide {

    private static volatile boolean on;

    static {
        BuildKeys.register(new BuildKeys.Handler() {
            @Override
            public boolean onKey(int key, int modifiers, boolean repeat) {
                return false;
            }

            @Override
            public boolean onScroll(double yOffset, boolean shift) {
                if (!active() || Placement.active() || HologramManager.getInstance().preview() != null) {
                    return false;
                }
                Hologram hologram = HologramManager.getInstance().hologram();
                if (shift) {
                    set(hologram.withLayer(Hologram.ALL_LAYERS));
                } else {
                    int layer = hologram.layer() == Hologram.ALL_LAYERS ? 0 : hologram.layer() + (yOffset > 0 ? 1 : -1);
                    set(hologram.withLayer(layer));
                }
                return true;
            }
        });
        BuildHud.addSection(BuildGuide::hudSection);
    }

    private BuildGuide() {
    }

    public static boolean active() {
        return on && HologramManager.getInstance().hologram() != null;
    }

    /** Turns the guide on at the lowest layer that still has something missing. */
    public static boolean start() {
        HologramManager manager = HologramManager.getInstance();
        Hologram hologram = manager.hologram();
        if (hologram == null) {
            return false;
        }
        int layer = 0;
        HologramCensus.Result census = HologramCensus.result();
        if (census != null) {
            while (layer < census.missingPerLayer().length - 1 && census.missingPerLayer()[layer] == 0) {
                layer++;
            }
        }
        on = true;
        manager.setShown(true);
        set(hologram.withMode(Hologram.Mode.COMPARE).withLayer(layer));
        return true;
    }

    public static void stop() {
        on = false;
        Hologram hologram = HologramManager.getInstance().hologram();
        if (hologram != null) {
            set(hologram.withLayer(Hologram.ALL_LAYERS));
        }
    }

    private static void set(Hologram hologram) {
        HologramManager.getInstance().update(hologram);
        GhostCollector.invalidate();
    }

    private static BuildHud.Section hudSection() {
        if (!active()) {
            return null;
        }
        Hologram hologram = HologramManager.getInstance().hologram();
        int layer = hologram.layer();
        int height = hologram.display().height();
        List<BuildHud.Line> lines = new ArrayList<>();
        HologramCensus.Result census = HologramCensus.result();
        String layerText = layer == Hologram.ALL_LAYERS ? "all layers" : "layer " + (layer + 1) + " / " + height;
        if (census == null) {
            lines.add(new BuildHud.Line("Guide: " + layerText + "  •  counting...", SBSTheme.ACCENT_BRIGHT));
        } else {
            int total = census.missing() + census.wrong() + census.correct();
            String here = layer == Hologram.ALL_LAYERS ? ""
                    : String.format(Locale.ROOT, "  •  %,d left here", census.missingPerLayer()[layer]);
            lines.add(new BuildHud.Line("Guide: " + layerText + here, SBSTheme.ACCENT_BRIGHT));
            lines.add(new BuildHud.Line(String.format(Locale.ROOT, "%,d left  •  %,d wrong  •  %,d / %,d done", census.missing(),
                    census.wrong(), census.correct(), total), census.wrong() > 0 ? SBSTheme.WARN : SBSTheme.TEXT));
            if (census.next() != null) {
                BlockPos next = census.next();
                lines.add(new BuildHud.Line("Next: " + StateStrings.displayName(census.nextState()) + " at "
                        + next.getX() + ", " + next.getY() + ", " + next.getZ(), SBSTheme.TOGGLE_ON));
            } else if (census.missing() == 0 && census.wrong() == 0) {
                lines.add(new BuildHud.Line("Finished - every block is in place", SBSTheme.TOGGLE_ON));
            }
        }
        lines.add(new BuildHud.Line("Wheel: change layer  •  Shift+wheel: all  •  //guide off", SBSTheme.TEXT_MUTED));
        return new BuildHud.Section(lines, census == null ? -1f
                : (float) census.correct() / Math.max(1, census.missing() + census.wrong() + census.correct()));
    }

    /** The pulsing box around the next missing block. Called from the HUD render pass. */
    public static void renderHighlight(GuiGraphicsExtractor g, Matrix4f vp, Vec3 camPos) {
        if (!active()) {
            return;
        }
        HologramCensus.Result census = HologramCensus.result();
        if (census == null || census.next() == null) {
            return;
        }
        BlockPos next = census.next();
        double pulse = 0.08 + 0.06 * Math.sin(System.currentTimeMillis() / 180.0);
        WorldRender.boxEdges(g, vp, camPos, next.getX() - pulse, next.getY() - pulse, next.getZ() - pulse,
                next.getX() + 1 + pulse, next.getY() + 1 + pulse, next.getZ() + 1 + pulse, 0xFF57D977, 2);
    }
}
