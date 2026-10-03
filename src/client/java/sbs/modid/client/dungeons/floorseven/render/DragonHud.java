/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.floorseven.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.render.WorldRender;
import sbs.modid.client.dungeons.floorseven.logic.DragonTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The M7 dragon card: every dragon that is up, weakest first, in its own colour - and, with the
 * world toggle on, the same health drawn at the dragon itself.
 *
 * <p>Each line carries the one thing besides health that decides what to do with a dragon: whether
 * it is <b>at its statue</b>. A dragon at 5% on the wrong side of the room is not nearly done, it is
 * about to be wasted, and the card says so ("§a✔" when it is in its zone, the distance to it when it
 * is not).
 */
public final class DragonHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 150;
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    /** How far above the dragon's own position its health floats. */
    private static final double LABEL_HEIGHT = 3.0;

    private DragonHud() {
    }

    /** Called from the HUD render hook once per frame: the world numbers, then the card. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().dungeons;
        Minecraft minecraft = Minecraft.getInstance();
        if (!cfg.m7DragonHp || minecraft.player == null || minecraft.level == null) {
            return;
        }
        List<DragonTracker.Dragon> dragons = DragonTracker.getInstance().dragons();
        if (dragons.isEmpty()) {
            return;
        }
        if (cfg.m7DragonHpWorld) {
            drawInWorld(g, minecraft, dragons);
        }
        if (HudLayout.isHidden(HudElement.M7_DRAGONS)) {
            return;
        }
        HudElement.Bounds bounds = HudElement.M7_DRAGONS.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.M7_DRAGONS);
        drawCard(g, dragons, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void drawInWorld(GuiGraphicsExtractor g, Minecraft minecraft,
                                    List<DragonTracker.Dragon> dragons) {
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        Font font = minecraft.font;
        for (DragonTracker.Dragon dragon : dragons) {
            Vec3 anchor = dragon.position().add(0, LABEL_HEIGHT, 0);
            int[] screen = WorldRender.projectToScreen(viewProjection, camPos, anchor,
                    g.guiWidth(), g.guiHeight());
            if (screen == null) {
                continue;
            }
            g.centeredText(font, Component.literal(dragon.kind().label()),
                    screen[0], screen[1] - 10, dragon.kind().color());
            g.centeredText(font, Component.literal(health(dragon)), screen[0], screen[1],
                    dragon.inZone() ? 0xFF7CFF6A : 0xFFFFFFFF);
        }
    }

    private static void drawCard(GuiGraphicsExtractor g, List<DragonTracker.Dragon> dragons,
                                 int x, int y) {
        Font font = Minecraft.getInstance().font;

        List<String> lines = new ArrayList<>();
        lines.add("Dragons  §7" + dragons.size() + " up");
        for (DragonTracker.Dragon dragon : dragons) {
            lines.add(dragon.kind().label() + " §f" + health(dragon) + "  " + zone(dragon));
        }

        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        HudLayout.measure(HudElement.M7_DRAGONS, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(lines.get(0)), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (int i = 0; i < dragons.size(); i++) {
            g.text(font, Component.literal(lines.get(i + 1)), ix, iy, dragons.get(i).kind().color());
            iy += lineH;
        }
    }

    /** "12.4M §7(38%)" - the figure and the fraction, since either can be the readable one. */
    private static String health(DragonTracker.Dragon dragon) {
        String amount = compact(dragon.health());
        double fraction = dragon.fraction();
        return fraction < 0 ? amount : amount + " §7(" + Math.round(fraction * 100) + "%)";
    }

    /** Whether it is where it has to die, or how far off it is. */
    private static String zone(DragonTracker.Dragon dragon) {
        return dragon.inZone() ? "§a✔ at statue"
                : "§c" + Math.round(dragon.statueDistance()) + "m off";
    }

    private static String compact(double value) {
        if (value >= 1_000_000_000d) {
            return String.format(Locale.US, "%.1fB", value / 1_000_000_000d);
        }
        if (value >= 1_000_000d) {
            return String.format(Locale.US, "%.1fM", value / 1_000_000d);
        }
        if (value >= 1_000d) {
            return String.format(Locale.US, "%.0fk", value / 1_000d);
        }
        return String.valueOf(Math.round(value));
    }

    private static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
