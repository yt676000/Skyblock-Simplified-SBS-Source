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

    /** The input help never changes, so it is one section built once. */
    private static final Section INPUT_HELP = new Section(List.of(
            new Line("Left-click: corner 1  •  Right-click: corner 2", SBSTheme.TEXT),
            new Line("Sneak + right-click air: clear  •  Alt + wheel: step deeper", SBSTheme.TEXT)), -1f);

    /**
     * The placing section as last built and the key it was built for: the same instance is handed
     * back until one of these moves. Render thread only.
     */
    private static Section placing;
    private static Object placingDisplay;
    private static BlockPos placingAt;
    private static int placingCollisions = -1;
    private static boolean placingWhole;
    private static boolean placingHelp;
    private static boolean placingSingleplayer;

    /**
     * The card as last built, the sections it was built from (by identity) and the screen width it
     * was capped to; {@code current} is this frame's, reused so a frame allocates nothing.
     * Render thread only.
     */
    private static Card card;
    private static Section[] built = new Section[0];
    private static Section[] current = new Section[0];
    private static int builtGuiWidth = -1;

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
        // Sections are compared by identity: the built-in ones hand back the same instance while
        // nothing they show changed, so a steady card is not re-measured. A section that builds a new
        // one every call simply rebuilds the card every frame, as before.
        int count = SECTIONS.size();
        if (current.length != count) {
            current = new Section[count];
            built = new Section[count];
            card = null;
        }
        boolean changed = card == null || g.guiWidth() != builtGuiWidth;
        boolean any = false;
        for (int i = 0; i < count; i++) {
            Section section = SECTIONS.get(i).get();
            if (section != null && section.lines().isEmpty()) {
                section = null;
            }
            current[i] = section;
            any |= section != null;
            changed |= section != built[i];
        }
        if (!any) {
            return;
        }
        if (changed) {
            System.arraycopy(current, 0, built, 0, count);
            builtGuiWidth = g.guiWidth();
            card = build(current, g.guiWidth());
        }
        Card model = card;
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        HudElement.Bounds b = HudElement.BUILD_TOOLS.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.BUILD_TOOLS, x, y, model.width(), model.height());
        HudLayout.begin(g, HudElement.BUILD_TOOLS);
        HudCard.draw(g, x, y, model.width(), model.height());
        int iy = y + PAD;
        int textW = model.width() - PAD * 2;
        for (BuiltSection section : model.sections()) {
            for (HudCard.Line line : section.lines()) {
                HudCard.drawLine(g, font, line, x + PAD, x + PAD + textW, iy);
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

    /** The card measured, its lines fitted to the width, from the non-null entries of {@code sections}. */
    private static Card build(Section[] sections, int guiWidth) {
        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        // Measured from the text actually drawn, capped by the screen so it can never run off it.
        int maxWidth = Math.max(80, guiWidth - 2 * SBSTheme.SCREEN_MARGIN);
        int width = 0;
        int height = PAD * 2;
        for (Section section : sections) {
            if (section == null) {
                continue;
            }
            for (Line line : section.lines()) {
                width = Math.max(width, font.width(line.text()));
                height += lineH;
            }
            if (section.progress() >= 0) {
                height += BAR_HEIGHT + 3;
            }
        }
        width = Math.min(maxWidth, width + PAD * 2);
        int textW = width - PAD * 2;
        List<BuiltSection> built = new ArrayList<>(sections.length);
        for (Section section : sections) {
            if (section == null) {
                continue;
            }
            List<HudCard.Line> lines = new ArrayList<>(section.lines().size());
            for (Line line : section.lines()) {
                lines.add(HudCard.Line.of(font, RowText.fit(font, line.text(), textW), line.color()));
            }
            built.add(new BuiltSection(List.copyOf(lines), section.progress()));
        }
        return new Card(List.copyOf(built), width, height);
    }

    /** One section of the cached card: its fitted lines and its bar (-1 for none). */
    private record BuiltSection(List<HudCard.Line> lines, float progress) {
    }

    /** The cached card: its sections and measured size. */
    private record Card(List<BuiltSection> sections, int width, int height) {
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
        return INPUT_HELP;
    }

    private static Section placingSection() {
        if (!Placement.active()) {
            return null;
        }
        Hologram hologram = HologramManager.getInstance().hologram();
        BlockPos at = hologram.anchor();
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
        boolean helpShowing = BuildHelpHud.showing();
        boolean singleplayer = BuildGate.singleplayer();
        // The display schematic carries the name and the size; together with the rest these are
        // everything the section shows, so an unchanged key hands back the same section.
        if (placing == null || hologram.display() != placingDisplay || at != placingAt
                || collisions != placingCollisions || whole != placingWhole
                || helpShowing != placingHelp || singleplayer != placingSingleplayer) {
            placingDisplay = hologram.display();
            placingAt = at;
            placingCollisions = collisions;
            placingWhole = whole;
            placingHelp = helpShowing;
            placingSingleplayer = singleplayer;
            placing = buildPlacing(hologram, at, collisions, whole, helpShowing, singleplayer);
        }
        return placing;
    }

    private static Section buildPlacing(Hologram hologram, BlockPos at, int collisions, boolean whole,
                                        boolean helpShowing, boolean singleplayer) {
        List<Line> lines = new ArrayList<>();
        String name = hologram.display().header().name();
        lines.add(new Line("Placing " + (name.isBlank() ? "clipboard" : name) + "  •  "
                + hologram.display().sizeLabel() + "  •  at " + at.getX() + ", " + at.getY() + ", " + at.getZ(),
                SBSTheme.ACCENT_BRIGHT));
        if (collisions > 0) {
            lines.add(new Line(String.format(Locale.ROOT, "%,d block%s%s would be overwritten (red)",
                    collisions, collisions == 1 ? "" : "s", whole ? "" : " nearby"), SBSTheme.WARN));
        } else if (whole) {
            lines.add(new Line("Nothing in the way", SBSTheme.TOGGLE_ON));
        }
        // The key lines live on the help card when it is up; never the same lines on two cards.
        if (!helpShowing) {
            lines.add(new Line("Arrows / wheel: move (Shift x5)  •  PgUp/PgDn: up/down", SBSTheme.TEXT));
            lines.add(new Line("R: turn  •  F: flip  •  G: plot grid  •  Enter: "
                    + (singleplayer ? "place" : "pin here") + "  •  Esc: cancel", SBSTheme.TEXT));
        }
        return new Section(List.copyOf(lines), -1f);
    }
}
