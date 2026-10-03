/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.render;

/**
 * How badly one of the mod's drawing passes over a container screen wants the frame.
 *
 * <p><b>The order, in full, is three deep - and the first tier is not in this enum.</b>
 *
 * <ol>
 *   <li><b>Minecraft.</b> The game's own rendering is never scheduled, never measured against a
 *       budget and never skipped. Everything the mod draws over a menu is an addition to a frame
 *       vanilla has already earned, so the mod gives way to it rather than competing with it. There
 *       is deliberately no constant for this: a tier the mod is not allowed to schedule should not
 *       be something a pass can ask for.</li>
 *   <li>{@link #RELEVANT} - the part of the mod that is about the menu actually open. On the Bazaar
 *       that is the order panels, the flip window and the order highlights. It runs every frame,
 *       whatever the frame costs.</li>
 *   <li>{@link #BACKGROUND} - everything else the mod would otherwise run on this screen: the
 *       Garden decorations, the Bits Shop ranking, the Accessory Bag reader. None of them have
 *       anything to draw on a Bazaar page; they only cost the frame the work of finding that out.
 *       These are the passes {@link MenuRenderPriority} sheds while the frame is over budget.</li>
 * </ol>
 *
 * <p><b>The default is {@link #RELEVANT}, and that is the safe direction.</b> A pass that never
 * declares a tier keeps running exactly as it did. Being wrong the other way - declaring something
 * background that is in fact drawing - costs the player a visible overlay, so the queue backs the
 * declaration up with a heartbeat rather than trusting it outright: see
 * {@link MenuRenderPriority}.
 */
public enum RenderTier {

    /** About the open menu. Runs every frame. */
    RELEVANT,

    /** Not about the open menu. First to be dropped when the frame runs out of budget. */
    BACKGROUND;

    /**
     * {@link #RELEVANT} when the pass is about the open menu, {@link #BACKGROUND} when it is not.
     *
     * <p>Written this way at the call site so the condition reads as the question it answers -
     * {@code RenderTier.when(bazaar)} - rather than as a ternary repeated twenty times.
     */
    public static RenderTier when(boolean aboutThisMenu) {
        return aboutThisMenu ? RELEVANT : BACKGROUND;
    }
}
