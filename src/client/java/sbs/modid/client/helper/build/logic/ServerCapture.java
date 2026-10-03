/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import sbs.modid.client.core.build.logic.WorldCapture;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;
import sbs.modid.client.core.build.model.Selection;

import java.util.function.Consumer;

/**
 * Reads a box of the world with everything in it: in singleplayer from the integrated server's own
 * level, on its thread, block entities included; on a server from the client's loaded blocks.
 *
 * <p>Copy, cut, move and stack all start here, so a chest that is moved keeps its contents - the
 * client's copy of a chest does not have them, the server's does.
 */
public final class ServerCapture {

    private ServerCapture() {
    }

    /** Captures {@code box}; {@code done} runs on the client thread with the result, or null on failure. */
    public static void capture(Selection box, SchematicHeader header, Consumer<Schematic> done) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            done.accept(null);
            return;
        }
        IntegratedServer server = BuildGate.server();
        if (server == null) {
            // On a server: the blocks this client has loaded, and nothing else. Unloaded chunks read as air.
            // Block entities are the client's copies - partial (a chest has no items), but a head has
            // its skin, which is the part a hologram needs.
            done.accept(WorldCapture.capture(minecraft.level, box, header, minecraft.level.registryAccess()));
            return;
        }
        var dimension = minecraft.level.dimension();
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            Schematic captured = level == null ? null : WorldCapture.capture(level, box, header, level.registryAccess());
            minecraft.execute(() -> done.accept(captured));
        });
    }
}
