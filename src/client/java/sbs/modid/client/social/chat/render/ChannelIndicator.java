/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.social.chat.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.social.chat.logic.ActiveChannel;
import sbs.modid.client.social.chat.model.SendChannel;
import sbs.modid.client.ui.render.SciFiRender;

/**
 * Says where the next message is going, on the chat input, the whole time a channel is selected.
 *
 * <p><b>This is not decoration.</b> The feature's entire safety argument is that the player can see
 * the destination before pressing enter — a party message reaching a lobby is not recoverable, and
 * nothing else in the send path can catch it. So this draws whenever a channel is prefixing,
 * unconditionally, and there is no setting that turns it off. The colour is switchable; the label
 * is not, because colour alone is not a signal every player can read.
 *
 * <h2>Beside the input rather than inside it</h2>
 *
 * <p>The brief asked for the label inside the field, in front of what is typed. Vanilla's chat input
 * is an {@code EditBox} that owns its own text layout: putting a label in front of the caret means
 * reimplementing that box, and a reimplemented chat input is a much larger surface to get wrong than
 * this feature is worth. What is drawn instead is a chip hard against the input's right edge — the
 * end that is empty until a message is long — over an opaque background so it stays readable when a
 * message does reach it, plus a border in the channel's colour around the whole box.
 *
 * <p>That is a deviation from the request and it is recorded in the feature's spec file rather than
 * only here. A true inline prefix remains possible; it is a bigger change than a label.
 */
public final class ChannelIndicator {

    /** Vanilla's chat input box: 12px tall, 2px from the left and bottom, full width less 4. */
    private static final int INPUT_HEIGHT = 12;
    private static final int INPUT_MARGIN = 2;

    /** Chip padding, and the gap it keeps from the input's right edge. */
    private static final int PAD = 3;
    private static final int EDGE_GAP = 2;

    private ChannelIndicator() {
    }

    /**
     * Draws the border and the chip, if a channel is prefixing and the chat is open.
     *
     * <p>Only while the chat screen is up: with the chat closed there is no message being composed,
     * so there is nothing to warn about and a permanent badge would be noise. The selection itself
     * survives — see {@code ActiveChannel}.
     */
    public static void render(GuiGraphicsExtractor g) {
        Minecraft minecraft = Minecraft.getInstance();
        // Through GuiStateManager, like the tab strip beside it: the mod tracks the current
        // screen itself, and reading it from two places is how the two come to disagree.
        if (minecraft == null || !(sbs.modid.client.core.api.GuiStateManager.getInstance()
                .getCurrentScreen() instanceof ChatScreen)) {
            return;
        }
        SendChannel channel = ActiveChannel.getInstance().active();
        if (!channel.prefixes()) {
            return;   // Public sends untouched: nothing is being changed, so nothing is claimed
        }
        Font font = minecraft.font;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();

        int boxX = INPUT_MARGIN;
        int boxY = screenHeight - INPUT_HEIGHT - INPUT_MARGIN;
        int boxWidth = screenWidth - INPUT_MARGIN * 2;

        if (ConfigManager.getInstance().get().channelSend.colorInput) {
            SciFiRender.roundedRectWithBorder(g, boxX - 1, boxY - 1, boxWidth + 2, INPUT_HEIGHT + 2,
                    1, 0x00000000, channel.color());
        }

        String label = channel.label();
        int textWidth = font.width(label);
        int chipWidth = textWidth + PAD * 2;
        int chipX = boxX + boxWidth - chipWidth - EDGE_GAP;
        int chipY = boxY + 1;
        int chipHeight = INPUT_HEIGHT - 2;

        // Opaque, not tinted: this sits over the input, and a message long enough to reach it must
        // not be able to show through the one element saying where that message is about to go.
        SciFiRender.roundedRect(g, chipX, chipY, chipWidth, chipHeight, 2, 0xFF0A1622);
        SciFiRender.roundedRectWithBorder(g, chipX, chipY, chipWidth, chipHeight, 2,
                0x00000000, channel.color());
        g.text(font, Component.literal(label), chipX + PAD,
                chipY + (chipHeight - font.lineHeight) / 2 + 1, channel.color());
    }
}
