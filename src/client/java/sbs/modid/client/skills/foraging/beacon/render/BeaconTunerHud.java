/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.foraging.beacon.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import sbs.modid.client.skills.foraging.beacon.logic.BeaconBeatReader;
import sbs.modid.client.skills.foraging.beacon.model.BeatReading;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.hud.render.HudCard;
import sbs.modid.client.ui.theme.SBSTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * The Beacon Tuner card: what the beat is doing, in the terms a player can act on.
 *
 * <p><b>Every row is allowed to be absent.</b> The rule this card exists to honour is that a property
 * which cannot be read confidently shows nothing — not a zero, not a guess, not a placeholder that
 * looks like a reading. So pitch appears only once heard, speed only once the gaps agree, and colour
 * never, because nothing here can read it yet and pretending otherwise is the failure mode.
 *
 * <p><b>The unread properties are named rather than omitted.</b> A card that silently shows two rows
 * looks broken or looks complete, depending on who is reading it, and neither is true. Saying "colour
 * — read it off the panes" and "controls — not read yet" costs two grey lines and tells the player
 * exactly where the tool stops, which is the difference between a partial feature and an unreliable
 * one.
 */
public final class BeaconTunerHud {

    private static final int PAD = 5;

    private BeaconTunerHud() {
    }

    public static void render(GuiGraphicsExtractor g) {
        if (Minecraft.getInstance().player == null
                || HudLayout.isHidden(HudElement.BEACON_TUNER)) {
            return;
        }
        BeaconBeatReader reader = BeaconBeatReader.getInstance();
        if (!reader.atBeacon()) {
            return;
        }
        BeatReading beat = reader.reading();

        List<String[]> rows = new ArrayList<>(4);
        if (!beat.heard()) {
            rows.add(new String[]{"Listening", "§8for the beat..."});
        } else {
            String pitch = beat.pitchLine();
            if (pitch != null) {
                rows.add(new String[]{"Pitch", (beat.pitchState() == BeatReading.Confidence.FIXED
                        ? "§e" : "§a") + pitch});
            }
            String speed = beat.speedLine();
            if (speed != null) {
                rows.add(new String[]{"Speed", "§a" + speed});
            } else {
                rows.add(new String[]{"Speed", "§8not steady enough to call"});
            }
        }
        // Named, not omitted: see the class note.
        rows.add(new String[]{"Colour", "§8read it off the panes"});
        rows.add(new String[]{"Controls", "§8not read yet"});

        Font font = Minecraft.getInstance().font;
        int lineH = font.lineHeight + 2;
        String header = "Beacon Tuner";
        int contentW = font.width(header);
        for (String[] row : rows) {
            contentW = Math.max(contentW, font.width(row[0]) + 12 + font.width(strip(row[1])));
        }
        int width = Math.max(140, contentW + PAD * 2);
        int height = PAD * 2 + lineH * (1 + rows.size()) - 2;

        HudElement.Bounds b = HudElement.BEACON_TUNER.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.BEACON_TUNER, x, y, width, height);

        HudLayout.begin(g, HudElement.BEACON_TUNER);
        HudCard.draw(g, x, y, width, height);

        int ix = x + PAD;
        int right = x + width - PAD;
        int iy = y + PAD;
        g.text(font, Component.literal(header), ix, iy, SBSTheme.ACCENT_BRIGHT);
        iy += lineH;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), ix, iy, SBSTheme.TEXT_MUTED);
            String value = row[1];
            g.text(font, Component.literal(value), right - font.width(strip(value)), iy, SBSTheme.TEXT);
            iy += lineH;
        }
        HudLayout.end(g);
    }

    /** Width has to be measured on the visible text, not on the colour codes in front of it. */
    private static String strip(String text) {
        return text.replaceAll("§.", "");
    }
}
