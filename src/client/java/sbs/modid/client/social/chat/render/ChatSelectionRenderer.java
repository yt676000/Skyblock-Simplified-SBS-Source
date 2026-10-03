/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.social.chat.logic.ChatSelection;
import sbs.modid.client.social.chat.model.ChatGeometry;

import java.util.List;

/**
 * Paints the selection highlight behind the selected chat messages.
 *
 * <p>Without this the feature would be unusable – a selection you cannot see is just a hidden mode.
 *
 * <p>Highlights are drawn per <b>line</b>, from the selected messages' identity, so a message that
 * wraps across three lines highlights all three rather than only the one clicked. Geometry comes
 * from {@link ChatGeometry}, the same source the click uses, so the highlight always sits exactly
 * where clicking would select.
 */
public final class ChatSelectionRenderer {

    /** Selection fill – translucent so the message text stays readable through it. */
    private static final int FILL = 0x553FB4FF;
    /** A brighter left edge, so a run of selected lines reads as one block. */
    private static final int EDGE = 0xFF3FB4FF;
    private static final int EDGE_WIDTH = 2;

    private ChatSelectionRenderer() {
    }

    /** Called at the end of the chat's HUD render. */
    public static void render(GuiGraphicsExtractor g) {
        if (!ConfigManager.getInstance().get().chatOptions.chatSelection
                || ChatSelection.isEmpty()) {
            return;
        }
        List<GuiMessage.Line> lines = ChatGeometry.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (!ChatSelection.isSelected(lines.get(i).parent())) {
                continue;
            }
            int[] bounds = ChatGeometry.lineBounds(i);
            if (bounds == null) {
                continue; // scrolled out of view
            }
            g.fill(bounds[0], bounds[1], bounds[2], bounds[3], FILL);
            g.fill(bounds[0], bounds[1], bounds[0] + EDGE_WIDTH, bounds[3], EDGE);
        }
    }
}
