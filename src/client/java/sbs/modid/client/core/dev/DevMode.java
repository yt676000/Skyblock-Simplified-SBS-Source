/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

/**
 * Developer mode – now <b>persisted</b> in the config.
 *
 * <p>Enabling writes {@code "devMode": true} to {@code config/sbs/config.json} and generates the hidden
 * {@code Development_Stuff/} structure; disabling removes the key again (default off). The runtime flag
 * {@link #ACTIVE} is restored from the config on startup via {@link #init()}, so it survives restarts
 * and the developer overlay module only appears while it is on.
 *
 * <p>Toggled by the exact chat command {@code /sbs developermode password:simplified} (parsed in
 * {@code SBSCommands}) or from the Developer module's toggle in the SBS overlay. A wrong password never
 * prints anything (see the silence requirement in {@code SBSCommands}); a successful toggle is
 * confirmed in chat + action bar.
 */
public final class DevMode {

    /** Global dev-mode flag. Read by the dev keybinds, the waypoint exporter and the overlay. */
    public static volatile boolean ACTIVE = false;

    private DevMode() {
    }

    /** Restores {@link #ACTIVE} from the persisted config (called once on client init). */
    public static void init() {
        Boolean persisted = ConfigManager.getInstance().get().devMode;
        ACTIVE = Boolean.TRUE.equals(persisted);
        if (ACTIVE) {
            WaypointExporter.ensureStructure();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Developer mode restored from config (ACTIVE)");
        }
    }

    /** Flips dev mode, persists it, and confirms via chat + action bar. */
    public static void toggle() {
        setActive(!ACTIVE);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            Component message = Component.literal(ACTIVE
                    ? "§a[SBS] Developer Mode ENABLED — reopen /sbs to see the Developer module"
                    : "§7[SBS] Developer Mode disabled");
            minecraft.player.sendSystemMessage(message);
            minecraft.player.sendOverlayMessage(message);
        }
    }

    /**
     * Sets dev mode and persists it: {@code true} writes the flag and creates the dev structure;
     * {@code false} clears the flag (key removed from config.json). Never deletes existing waypoint data.
     */
    public static void setActive(boolean active) {
        ACTIVE = active;
        SBSConfig config = ConfigManager.getInstance().get();
        config.devMode = active ? Boolean.TRUE : null; // null -> omitted from config.json (default off)
        ConfigManager.getInstance().save();
        if (active) {
            WaypointExporter.ensureStructure();
        }
        // The Developer card appears and disappears with this flag, so the option index - which is
        // built from the visible module list - no longer describes the config it indexed.
        sbs.modid.client.ui.settings.OptionIndex.invalidate();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS] Developer mode {}", active ? "ENABLED" : "disabled");
    }
}
