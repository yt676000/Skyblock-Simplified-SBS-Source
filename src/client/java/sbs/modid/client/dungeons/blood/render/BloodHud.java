/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.blood.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.dungeons.blood.logic.BloodMoveTimer;
import sbs.modid.client.dungeons.blood.logic.BloodRoomTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.render.SciFiRender;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The blood-room card: the countdown until the mobs are worth swinging at, the clear clock, how many
 * mobs are still up and what is currently spawning in. Stays on screen with the final clear time
 * until the run leaves the Catacombs.
 */
public final class BloodHud {

    private static final int PAD = 6;
    private static final int LINE_GAP = 3;
    private static final int MIN_W = 132;
    private static final int WAIT_COLOR = 0xFFFF5555;
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private BloodHud() {
    }

    /** Called from the HUD render hook once per frame. */
    public static void render(GuiGraphicsExtractor g) {
        var cfg = ConfigManager.getInstance().get().blood;
        if (!cfg.enabled || !cfg.showHud || Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.BLOOD_HELPER)) {
            return;
        }
        BloodRoomTracker tracker = BloodRoomTracker.getInstance();
        if (!tracker.active()) {
            return;
        }
        HudElement.Bounds bounds = HudElement.BLOOD_HELPER.defaultBounds(g.guiWidth(), g.guiHeight());
        HudLayout.begin(g, HudElement.BLOOD_HELPER);
        draw(g, tracker, (int) bounds.x(), (int) bounds.y());
        HudLayout.end(g);
    }

    private static void draw(GuiGraphicsExtractor g, BloodRoomTracker tracker, int x, int y) {
        Font font = Minecraft.getInstance().font;

        List<String> lines = new ArrayList<>();
        lines.add("Blood  §7" + (tracker.cleared() ? "cleared" : "live"));
        // Without the door line the clock was started by walking in, so it is a room timer and not
        // the clear time - the row says so rather than quietly showing a number that is off.
        lines.add("§7Time §f" + BloodRoomTracker.seconds(tracker.elapsedMs())
                + (tracker.doorTimed() ? "" : " §8since seen"));
        // The wait is the top thing to know while it is running, and meaningless once it is over -
        // so the row exists only during it, rather than sitting at 0 for the rest of the room.
        int waitRow = -1;
        int eta = tracker.startKillingEtaSeconds();
        if (eta >= 0) {
            waitRow = lines.size();
            lines.add("§7Wait §f" + eta + "s §8until mobs");
        }
        // The camp call-out, once it knows its mark: this is a row you watch tick down and swing on,
        // so it sits above the mob counts rather than under them. Absent unless a call is pending.
        long moveEta = BloodMoveTimer.getInstance().etaMs();
        if (moveEta >= 0) {
            lines.add("§7Move §f" + BloodRoomTracker.seconds(moveEta) + " §8until kill");
        }
        if (!tracker.cleared() && !tracker.insideRoom()) {
            // Says why the room is quiet rather than showing a zero: the door being open is not the
            // same as being in there, and a "Mobs 0" would read like the room was already done.
            lines.add("§8not in the room yet");
        } else if (!tracker.cleared()) {
            lines.add("§7Mobs §f" + tracker.readyCount());
            // The skulls are the earliest warning the room gives, so they get their own row rather
            // than being folded into the count of mobs that already exist.
            if (!tracker.marks().isEmpty()) {
                lines.add("§7Spawns §f" + tracker.marks().size() + markEta(tracker));
            }
            if (tracker.spawningCount() > 0) {
                // The head of the list is the soonest one, so its number is the one worth showing.
                BloodRoomTracker.BloodMob next = nextSpawn(tracker);
                lines.add("§7Spawning §f" + tracker.spawningCount()
                        + (next == null ? "" : "  §8" + BloodRoomTracker.spawnLabel(next)));
            }
        }

        int contentW = 0;
        for (String line : lines) {
            contentW = Math.max(contentW, font.width(strip(line)));
        }
        int width = Math.max(MIN_W, PAD * 2 + contentW);
        int lineH = font.lineHeight + LINE_GAP;
        int height = PAD * 2 + lineH * lines.size() - LINE_GAP;
        HudLayout.measure(HudElement.BLOOD_HELPER, x, y, width, height);

        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int iy = y + PAD;
        for (int i = 0; i < lines.size(); i++) {
            int color = i == 0 ? SBSTheme.ACCENT_BRIGHT : (i == waitRow ? WAIT_COLOR : SBSTheme.TEXT);
            g.text(font, Component.literal(lines.get(i)), ix, iy, color);
            iy += lineH;
        }
    }

    /** The soonest spawn skull's remaining wait, or nothing at all while none has been timed. */
    private static String markEta(BloodRoomTracker tracker) {
        long soonest = -1;
        for (BloodRoomTracker.BloodMark mark : tracker.marks()) {
            if (mark.etaMs() >= 0 && (soonest < 0 || mark.etaMs() < soonest)) {
                soonest = mark.etaMs();
            }
        }
        return soonest < 0 ? "" : "  §8" + soonest + "ms";
    }

    /**
     * The mob closest to going live: the smallest measured ETA, or simply the first pending one
     * while the room's spawn-in has not been timed yet (they all read "spawning" then anyway).
     */
    private static BloodRoomTracker.BloodMob nextSpawn(BloodRoomTracker tracker) {
        BloodRoomTracker.BloodMob best = null;
        for (BloodRoomTracker.BloodMob mob : tracker.mobs()) {
            if (mob.ready()) {
                continue;
            }
            if (best == null || (mob.etaMs() >= 0 && (best.etaMs() < 0 || mob.etaMs() < best.etaMs()))) {
                best = mob;
            }
        }
        return best;
    }

    private static String strip(String text) {
        return text.replaceAll(SECTION_SIGN + ".", "");
    }
}
