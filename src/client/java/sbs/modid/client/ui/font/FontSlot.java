/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.font;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The four places SBS draws text, each choosing its own font.
 *
 * <p>Four rather than one because they have genuinely different jobs: a HUD of coin counts wants
 * fixed-width digits that stay in a column, and a paragraph of tooltip prose wants a proportional
 * face. One global font would force a compromise on all four.
 *
 * <p>None of these touch text that is not ours. Minecraft's own text keeps the game's font until
 * {@code Override Minecraft Font} is switched on, which is a separate thing entirely.
 */
public enum FontSlot {

    /** Config screens, overlays, windows - everything with a border round it. */
    UI("Menu Font", "Every SBS screen, window and settings row"),

    /** HUD cards and trackers drawn over the world. */
    HUD("HUD Font", "The trackers and cards drawn over the world"),

    /** Text SBS writes into or on top of the chat area. */
    CHAT_OVERLAY("Chat Font", "SBS lines in chat and the chat overlays"),

    /** Item tooltips SBS draws or extends. */
    TOOLTIP("Tooltip Font", "The tooltip lines SBS adds to items");

    private final String displayName;
    private final String blurb;

    FontSlot(String displayName, String blurb) {
        this.displayName = displayName;
        this.blurb = blurb;
    }

    public String displayName() {
        return displayName;
    }

    /** One line for the settings row, saying which text this slot covers. */
    public String blurb() {
        return blurb;
    }

    private static SBSConfig.FontSettings cfg() {
        return ConfigManager.getInstance().get().fonts;
    }

    /** Reads the configured font id for this slot. */
    public Supplier<String> stored() {
        return switch (this) {
            case UI -> () -> cfg().ui;
            case HUD -> () -> cfg().hud;
            case CHAT_OVERLAY -> () -> cfg().chatOverlay;
            case TOOLTIP -> () -> cfg().tooltip;
        };
    }

    /** Writes the font id for this slot. The caller saves. */
    public Consumer<String> store() {
        return switch (this) {
            case UI -> value -> cfg().ui = value;
            case HUD -> value -> cfg().hud = value;
            case CHAT_OVERLAY -> value -> cfg().chatOverlay = value;
            case TOOLTIP -> value -> cfg().tooltip = value;
        };
    }
}
