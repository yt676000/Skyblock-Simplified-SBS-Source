/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.build.logic.Hologram;
import sbs.modid.client.core.build.logic.HologramManager;
import sbs.modid.client.core.build.render.GhostCollector;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.helper.build.logic.BuildGate;
import sbs.modid.client.helper.build.logic.Placement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.RowText;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Build Tools' HUD card: what the keys do while a hologram is placed, an edit's progress bar, the
 * "Enter to apply / Esc to cancel" prompt of a pending preview, the build guide's count. Shown only
 * while one of those is happening; a movable, scalable {@link HudElement} like every other card.
 */
public final class BuildHud {

    private static final int PAD = 5;
    private static final int BAR_HEIGHT = 5;

    /** One line of the card. */
    public record Line(String text, int color) {
    }

    /** What one state wants on the card this frame: lines, and an optional progress bar (0-1, or -1). */
    public record Section(List<Line> lines, float progress) {
    }

    private static final List<Supplier<Section>> SECTIONS = new ArrayList<>();

    static {
        SECTIONS.add(BuildHud::placingSection);
        SECTIONS.add(BuildHud::inputHelpSection);
    }

    private BuildHud() {
    }

    /** Adds a state's section after the built-in ones. Call once. */
    public static void addSection(Supplier<Section> section) {
        SECTIONS.add(section);
    }

    public static void render(GuiGraphicsExtractor g) {
        // Cinematic freecam is a pure camera: no Build HUD, whatever Build Tools is doing.
        if (!ConfigManager.getInstance().get().buildTools.enabled || HudLayout.isHidden(HudElement.BUILD_TOOLS)
                || sbs.modid.client.helper.build.logic.Freecam.cinematic()) {
            return;
        }
        List<Section> sections = new ArrayList<>();
        for (Supplier<Section> supplier : SECTIONS) {
            Section section = supplier.get();
            if (section != null && !section.lines().isEmpty()) {
                sections.add(section);
            }
        }
        if (sections.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        // Measured from the text actually drawn, capped by the screen so it can never run off it.
        int maxWidth = Math.max(80, g.guiWidth() - 2 * SBSTheme.SCREEN_MARGIN);
        int width = 0;
        int height = PAD * 2;
        for (Section section : sections) {
            for (Line line : section.lines()) {
                width = Math.max(width, font.width(line.text()));
                height += lineH;
            }
            if (section.progress() >= 0) {
                height += BAR_HEIGHT + 3;
            }
        }
        width = Math.min(maxWidth, width + PAD * 2);
        HudElement.Bounds b = HudElement.BUILD_TOOLS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.BUILD_TOOLS, x, y, width, height);
        HudLayout.begin(g, HudElement.BUILD_TOOLS);
        HudCard.draw(g, x, y, width, height);
        int iy = y + PAD;
        int textW = width - PAD * 2;
        for (Section section : sections) {
            for (Line line : section.lines()) {
                g.text(font, Component.literal(RowText.fit(font, line.text(), textW)), x + PAD, iy, line.color());
                iy += lineH;
            }
            if (section.progress() >= 0) {
                int filled = Math.round(textW * Math.min(1f, section.progress()));
                g.fill(x + PAD, iy, x + PAD + textW, iy + BAR_HEIGHT, 0x60FFFFFF);
                g.fill(x + PAD, iy, x + PAD + filled, iy + BAR_HEIGHT, 0xFF000000 | SBSTheme.ACCENT);
                iy += BAR_HEIGHT + 3;
            }
        }
        HudLayout.end(g);
    }

    /**
     * The help card: what the clicks do while build input is active - the stick in hand or build
     * freecam, one rule ({@code BuildInput}), so both show the same card. Not while a paste is placed:
     * its own keys are on the card then.
     */
    private static Section inputHelpSection() {
        if (Placement.active() || !sbs.modid.client.helper.build.logic.BuildInput.active()) {
            return null;
        }
        return new Section(List.of(
                new Line("Left-click: corner 1  •  Right-click: corner 2", SBSTheme.TEXT),
                new Line("Sneak + right-click air: clear  •  Alt + wheel: step deeper", SBSTheme.TEXT)), -1f);
    }

    private static Section placingSection() {
        if (!Placement.active()) {
            return null;
        }
        Hologram hologram = HologramManager.getInstance().hologram();
        BlockPos at = hologram.anchor();
        List<Line> lines = new ArrayList<>();
        String name = hologram.display().header().name();
        lines.add(new Line("Placing " + (name.isBlank() ? "clipboard" : name) + "  •  "
                + hologram.display().sizeLabel() + "  •  at " + at.getX() + ", " + at.getY() + ", " + at.getZ(),
                SBSTheme.ACCENT_BRIGHT));
        // The whole build counted against the world, once the census has finished a pass at this
        // spot; until then the nearby cells the renderer already classified.
        sbs.modid.client.helper.build.logic.HologramCensus.Result census =
                sbs.modid.client.helper.build.logic.HologramCensus.result();
        int collisions = 0;
        boolean whole = census != null && at.equals(census.base());
        if (whole) {
            collisions = census.wrong();
        } else {
            for (GhostCollector.Ghost ghost : GhostCollector.collect().ghosts()) {
                if (ghost.status() == GhostCollector.Status.WRONG) {
                    collisions++;
                }
            }
        }
        if (collisions > 0) {
            lines.add(new Line(String.format(Locale.ROOT, "%,d block%s%s would be overwritten (red)",
                    collisions, collisions == 1 ? "" : "s", whole ? "" : " nearby"), SBSTheme.WARN));
        } else if (whole) {
            lines.add(new Line("Nothing in the way", SBSTheme.TOGGLE_ON));
        }
        // The key lines live on the help card when it is up; never the same lines on two cards.
        if (!BuildHelpHud.showing()) {
            lines.add(new Line("Arrows / wheel: move (Shift x5)  •  PgUp/PgDn: up/down", SBSTheme.TEXT));
            lines.add(new Line("R: turn  •  F: flip  •  G: plot grid  •  Enter: "
                    + (BuildGate.singleplayer() ? "place" : "pin here") + "  •  Esc: cancel", SBSTheme.TEXT));
        }
        return new Section(lines, -1f);
    }
}
