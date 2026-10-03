/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.run.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;
import sbs.modid.client.dungeons.run.model.DungeonBlessings;
import sbs.modid.client.dungeons.run.model.DungeonScore;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;

import java.util.Locale;

/**
 * The Dungeon Score HUD card: floor, secrets, crypts, deaths, cleared-room %, elapsed time, the
 * <b>estimated</b> run score with its S / S+ status, and the blessings collected. Reads {@link DungeonStateManager} + computes with
 * {@link DungeonScore}. Only drawn inside a dungeon with the toggle on (default off, like every render
 * feature). Movable / scalable via the GUI editor. Self-measuring, mirroring {@code CollectionTrackerHud}.
 *
 * <p>The score is shown with a {@code ~} and, when any input was unknown, an {@code est} tag - it is a
 * documented approximation (see {@link DungeonScore}) until the coefficients are calibrated live.
 *
 * <p>The blessings are a line of this card rather than a card of their own: same gate, same state,
 * five short numbers - a second movable element would be one more thing to place for no gain.
 */
public final class DungeonScoreHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 150;

    private DungeonScoreHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().dungeons;
        if (!cfg.showScore || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.DUNGEON_SCORE)) {
            return;
        }
        DungeonStateManager state = DungeonStateManager.getInstance();
        if (!state.inDungeon()) {
            return; // rendering only inside a dungeon
        }
        HudElement.Bounds bounds = HudElement.DUNGEON_SCORE.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.DUNGEON_SCORE);
        draw(g, state, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, DungeonStateManager state, int x, int y) {
        Font font = Minecraft.getInstance().font;

        boolean spiritPet = ConfigManager.getInstance().get().dungeons.spiritPetForScore;
        DungeonScore.Result score = DungeonScore.compute(new DungeonScore.Inputs(
                state.roomClearPct(), state.secretPct(), state.deaths(), state.crypts(),
                state.elapsedSeconds(), spiritPet, false,
                sbs.modid.client.dungeons.run.logic.PrinceTracker.getInstance().killed()));

        String header = "Catacombs " + state.floorLabel() + "  §7" + phaseLabel(state);
        String scoreLine = "§fScore §b~" + score.total()
                + (score.estimated() ? " §8est" : "")
                + "  " + rank(score);
        String secrets = "§7Secrets §f" + count(state.secretsFound())
                + (state.secretPct() >= 0 ? " §8(" + state.secretPct() + "%)" : "");
        String rooms = "§7Rooms §f" + roomText(state) + "   §7Crypts §f" + count(state.crypts());
        String deaths = "§7Deaths §f" + count(state.deaths()) + "   §7Time §f" + time(state.elapsedSeconds());

        String[] lines = ConfigManager.getInstance().get().dungeons.showBlessings
                ? new String[] {header, scoreLine, secrets, rooms, deaths, blessings(state.blessings())}
                : new String[] {header, scoreLine, secrets, rooms, deaths};
        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.length - LINE_GAP;
        HudLayout.measure(HudElement.DUNGEON_SCORE, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        for (int i = 1; i < lines.length; i++) {
            iy += lineH;
            g.text(font, Component.literal(lines[i]), ix, iy, SBSTheme.TEXT);
        }
    }

    /** "Power 7  Life 3  Wisdom 0  Stone 4  Time 0" - see {@link DungeonBlessings#display}. */
    private static String blessings(DungeonBlessings blessings) {
        StringBuilder out = new StringBuilder();
        for (DungeonBlessings.Type type : DungeonBlessings.Type.values()) {
            if (!out.isEmpty()) {
                out.append("  ");
            }
            out.append("§7").append(type.label()).append(" §f").append(blessings.display(type));
        }
        return out.toString();
    }

    private static String rank(DungeonScore.Result score) {
        if (score.isSPlus()) {
            return "§a§lS+";
        }
        if (score.isS()) {
            return "§aS";
        }
        int toS = DungeonScore.SCORE_S - score.total();
        return "§7(" + Math.max(0, toS) + " to S)";
    }

    private static String phaseLabel(DungeonStateManager state) {
        return switch (state.phase()) {
            case START -> "start";
            case RUN -> "run";
            case BOSS -> "§cboss";
        };
    }

    private static String roomText(DungeonStateManager state) {
        int pct = state.roomClearPct();
        String pctStr = pct >= 0 ? " §8(" + pct + "%)" : "";
        if (state.totalRooms() > 0) {
            return Math.max(0, state.clearedRooms()) + "/" + state.totalRooms() + pctStr;
        }
        return pct >= 0 ? pct + "%" : "?";
    }

    private static String count(int value) {
        return value < 0 ? "?" : String.valueOf(value);
    }

    private static String time(int seconds) {
        if (seconds <= 0) {
            return "-";
        }
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
