/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorthree.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.floorthree.logic.GuardianHpTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The guardian health readout: a card listing the ones still up, weakest first, and - when the world
 * toggle is on - each number drawn over the guardian it belongs to.
 *
 * <p>The colour is the fraction left: green while it is somebody else's problem, amber once it is
 * worth committing to, red when it is nearly down. Sorting by health puts the one being focused on
 * the top line, so the card answers "who am I hitting" rather than listing four identical names.
 *
 * <p>The world number is the health alone, with no name over it. Four things called "Guardian"
 * labelled "Guardian" is four words that say nothing; the mob under the number is the identification,
 * and a name is only drawn when the tag turns out to say something other than the obvious.
 */
public final class GuardianHpHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 138;
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private static final int COLOR_HIGH = 0xFF7CFF6A;
    private static final int COLOR_MID = 0xFFFFC24A;
    private static final int COLOR_LOW = 0xFFFF5555;

    private GuardianHpHud() {
    }

    /** Called from the HUD render hook once per frame: the world numbers, then the card. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().dungeons;
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.guardianHp || minecraft.player == null || minecraft.level == null) {
            return;
        }
        List<GuardianHpTracker.GuardianInfo> guardians = GuardianHpTracker.getInstance().guardians();
        if (guardians.isEmpty()) {
            return;
        }
        if (cfg.guardianHpWorld) {
            drawInWorld(g, minecraft, guardians);
        }
        if (HudLayout.isHidden(HudElement.GUARDIAN_HP)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.GUARDIAN_HP.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.GUARDIAN_HP);
        drawCard(g, guardians, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void drawInWorld(GuiGraphicsExtractor g, Minecraft minecraft,
                                    List<GuardianHpTracker.GuardianInfo> guardians) {
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;
        for (GuardianHpTracker.GuardianInfo guardian : guardians) {
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos, guardian.anchor(),
                    g.guiWidth(), g.guiHeight());
            if (screen == null) {
                continue;
            }
            int color = color(guardian.fraction());
            if (!guardian.name().equalsIgnoreCase("guardian")) {
                g.centeredText(font, Component.literal(guardian.name()), screen[0], screen[1] - 10, color);
            }
            g.centeredText(font, Component.literal(guardian.health()), screen[0], screen[1], color);
        }
    }

    private static void drawCard(GuiGraphicsExtractor g,
                                 List<GuardianHpTracker.GuardianInfo> guardians, int x, int y) {
        Font font = Minecraft.getInstance().font;

        List<String> lines = new ArrayList<>();
        lines.add("Guardians  §7" + guardians.size() + " up");
        for (GuardianHpTracker.GuardianInfo guardian : guardians) {
            lines.add("§7" + guardian.name() + " §f" + guardian.health()
                    + " §8· " + guardian.distance() + "m");
        }

        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        HudLayout.measure(HudElement.GUARDIAN_HP, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(lines.get(0)), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (int i = 0; i < guardians.size(); i++) {
            g.text(font, Component.literal(lines.get(i + 1)), ix, iy,
                    color(guardians.get(i).fraction()));
            iy += lineH;
        }
    }

    /** Green while it is healthy, amber once it is worth committing to, red when it is nearly down. */
    private static int color(double fraction) {
        if (fraction < 0) {
            return SBSTheme.TEXT;
        }
        return fraction > 0.5 ? COLOR_HIGH : fraction > 0.2 ? COLOR_MID : COLOR_LOW;
    }

    private static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
