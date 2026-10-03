/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.sendcoords;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.module.ModuleGroup;
import sbs.modid.client.core.module.SbsModule;
import sbs.modid.client.ui.settings.SettingRow;

import java.util.List;

/**
 * Send Coordinates module (Party &amp; Chat): the settings page behind {@code /sendcoords}, whose
 * whole behaviour lives in {@link SendCoords}. Self-registered via
 * {@code META-INF/services/sbs.modid.client.core.module.SbsModule}.
 *
 * <p><b>There is no master toggle, on purpose.</b> The feature is a command: it does nothing until
 * the player types it, so an off switch would only decide what happens when they do - and both
 * answers are bad. Swallowing the command silently is the failure this repository has a rule
 * against, and letting it through to Hypixel produces an "Unknown command" for something the player
 * has switched off deliberately.
 */
public final class SendCoordsModule implements SbsModule {

    /** How long a format string may be. Well under the chat limit, which the command checks anyway. */
    private static final int MAX_FORMAT_LENGTH = 64;

    /** ServiceLoader needs a public no-arg constructor. */
    public SendCoordsModule() {
    }

    @Override
    public String id() {
        return "send_coords";
    }

    @Override
    public ModuleGroup group() {
        return ModuleGroup.PARTY_CHAT;
    }

    @Override
    public String displayName() {
        return "Send Coordinates";
    }

    @Override
    public String description() {
        return "/sendcoords puts your position into the chat you are currently in";
    }

    private static SBSConfig.SendCoordsSettings cfg() {
        return ConfigManager.getInstance().get().sendCoords;
    }

    private static void save() {
        ConfigManager.getInstance().save();
    }

    @Override
    public List<SettingRow> settings() {
        return List.of(
                SettingRow.label("/sendcoords  •  sends where you are standing, once, when you type it"),
                SettingRow.label("§8/sendcoords [party|guild|coop|all] [note]  •  /sbssendcoords works too"),

                SettingRow.label("— Where it goes —"),
                SettingRow.label("Into the chat you are already in, the same one a message you type "
                        + "right now would go to"),
                SettingRow.label("§8That is the Send Channel on the Chat Tabs page, the one the chat "
                        + "box shows while you type. With none selected the line is sent plain, so "
                        + "your server-side chat setting decides - there is no separate default "
                        + "here to get out of step with it."),
                SettingRow.label("§8Naming a channel wins over it: /sendcoords guild goes to the "
                        + "guild whatever you are in. Officer chat is not one of those words - a "
                        + "coordinate in front of the wrong people is not undoable - but selecting "
                        + "officer as your channel does send there."),

                SettingRow.text("Message Format", SendCoords.DEFAULT_FORMAT, MAX_FORMAT_LENGTH,
                        () -> cfg().format, value -> { cfg().format = value; save(); })
                        .describe("The line that is sent. %x%, %y% and %z% become your block "
                                + "position, and all three have to be in it - a format missing one "
                                + "is refused and the default used instead. Kept plain so another "
                                + "player's waypoint feature can read the numbers out of it; colour "
                                + "codes and newlines are removed before sending."),
                SettingRow.button("Reset Format", () -> { cfg().format = SendCoords.DEFAULT_FORMAT; save(); })
                        .describe("Puts the format back to " + SendCoords.DEFAULT_FORMAT + "."),

                SettingRow.label("— What is sent —"),
                SettingRow.label("Your block position as whole numbers, plus any note you type"),
                SettingRow.label("§8Two seconds between uses; a repeat of the same line is flagged, "
                        + "because Hypixel drops it"),
                SettingRow.label("§8The [SBS] marker in front is the mod-wide switch under Quality "
                        + "of Life"));
    }
}
