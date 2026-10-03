/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.livid.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.livid.logic.LividTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.Locale;

/**
 * The Livid card: which Livid was identified and how sure the tracker is of it, its health, how many
 * clones are still standing and the fight clock. Only drawn while the tracker is live (inside the
 * F5/M5 boss room with Livids in it). Self-measuring and movable via the GUI editor, like the
 * Dungeon Score card it is modelled on.
 */
public final class LividHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 140;
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private LividHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().dungeons;
        if (!cfg.lividTracker || !cfg.lividHud || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.LIVID_TRACKER)) {
            return;
        }
        LividTracker tracker = LividTracker.getInstance();
        if (!tracker.active()) {
            return;
        }
        HudElement.Bounds bounds = HudElement.LIVID_TRACKER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.LIVID_TRACKER);
        draw(g, tracker, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, LividTracker tracker, int x, int y) {
        Font font = Minecraft.getInstance().font;
        LividTracker.Identification id = tracker.identification();

        String header = "Livid  §7" + time(tracker.fightSeconds());
        String pick = id == null ? "§7searching…" : "§f" + id.candidate().label() + source(id);
        // The Livids are NPC players, which report a player's 20 hearts rather than the boss bar's
        // millions - so the HP line is only worth a row when the entity actually carries boss health.
        int others = tracker.candidates().size() - (id == null ? 0 : 1);
        String health = id != null && id.candidate().maxHealth() > 20f
                ? "§7HP §f" + health(id) + "   §7Clones §f" + others
                : "§7Clones §f" + others;

        String[] lines = {header, pick, health};
        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.length - LINE_GAP;
        HudLayout.measure(HudElement.LIVID_TRACKER, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        // The pick line wears the Livid's own nametag colour - the one thing you look for in the room.
        g.text(font, Component.literal(pick), ix, iy,
                id == null ? SBSTheme.TEXT : id.candidate().color());
        iy += lineH;
        g.text(font, Component.literal(health), ix, iy, SBSTheme.TEXT);
    }

    /** What the pick rests on - proof reads green, the health guess stays yellow and questioned. */
    private static String source(LividTracker.Identification id) {
        return switch (id.source()) {
            case DAMAGE -> "  §a✔ confirmed";
            case WOOL -> "  §a✔ wool";
            case HEALTH -> "  §e? best guess";
        };
    }

    private static String health(LividTracker.Identification id) {
        return compact(id.candidate().health()) + "/" + compact(id.candidate().maxHealth());
    }

    /** Health in SkyBlock's own shorthand (4.2M / 850k), which is how the nametag reads it out. */
    private static String compact(float value) {
        if (value >= 1_000_000f) {
            return String.format(Locale.US, "%.1fM", value / 1_000_000f);
        }
        if (value >= 1_000f) {
            return String.format(Locale.US, "%.0fk", value / 1_000f);
        }
        return String.valueOf(Math.round(value));
    }

    private static String time(int seconds) {
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
