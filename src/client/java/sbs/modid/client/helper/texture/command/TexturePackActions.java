/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.texture.command;

import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.helper.texture.logic.UserPack;
import sbs.modid.client.helper.texture.model.TexturePackMode;
import sbs.modid.client.social.chat.logic.SBSChat;

/**
 * Shared behaviour of the Texture Pack module UI (screen + settings rows): the mode cycle with its
 * side effects, and the Hypixel+ / Furfsky Reborn download-page buttons. Kept here so both surfaces stay in
 * sync.
 */
public final class TexturePackActions {

    private TexturePackActions() {
    }

    private static SBSConfig.TexturePackSettings settings() {
        return ConfigManager.getInstance().get().texturePack;
    }

    /**
     * A third-party-pack mode that is still locked because the player has not installed that pack, so the
     * cycle skips over it. {@link TexturePackMode#DEFAULT} and {@link TexturePackMode#SBS} are never
     * locked, which is what keeps {@link #cycleMode()}'s skip loop finite.
     */
    private static boolean isLocked(TexturePackMode mode) {
        return (mode == TexturePackMode.HYPIXEL_PLUS && !UserPack.HYPIXEL_PLUS.isInstalled())
                || (mode == TexturePackMode.FURFSKY_REBORN && !UserPack.FURFSKY_REBORN.isInstalled());
    }

    /**
     * Advances the Pack Theme Mode. {@link TexturePackMode#HYPIXEL_PLUS} and
     * {@link TexturePackMode#FURFSKY_REBORN} are only offered once the player has put their pack into
     * {@code resourcepacks/} (the "Open download page" buttons below say where to get it);
     * entering/leaving one of them enables/disables the
     * corresponding resource pack itself.
     */
    public static void cycleMode() {
        TexturePackMode previous = settings().packTheme;
        TexturePackMode next = previous.next();
        while (isLocked(next)) {
            next = next.next(); // locked until installed
        }
        setMode(next);
    }

    /** The modes the picker may offer: the locked ones are not choices until they are installed. */
    public static java.util.List<String> modeOptions() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (TexturePackMode mode : TexturePackMode.values()) {
            if (!isLocked(mode) || mode == settings().packTheme) {
                out.add(mode.displayName());
            }
        }
        return out;
    }

    /** Selects a mode by its display name (the picker's option), applying the pack switch. */
    public static void setModeByName(String displayName) {
        for (TexturePackMode mode : TexturePackMode.values()) {
            if (mode.displayName().equals(displayName) && !isLocked(mode)) {
                setMode(mode);
                return;
            }
        }
    }

    /** Stores a mode and runs the enable/disable side effects the switch implies. */
    private static void setMode(TexturePackMode next) {
        TexturePackMode previous = settings().packTheme;
        settings().packTheme = next;
        ConfigManager.getInstance().save();
        applyModeChange(previous, next);
    }

    /**
     * Enables/disables the third-party packs when the mode crosses their boundary, keeping them
     * mutually exclusive: the pack we are leaving is turned off, the pack we are entering is turned
     * on. {@code setActive(false)} on an inactive pack is a harmless no-op (no reload).
     */
    private static void applyModeChange(TexturePackMode previous, TexturePackMode next) {
        if (previous == next) {
            return;
        }
        if (previous == TexturePackMode.HYPIXEL_PLUS) {
            UserPack.HYPIXEL_PLUS.setActive(false);
        } else if (previous == TexturePackMode.FURFSKY_REBORN) {
            UserPack.FURFSKY_REBORN.setActive(false);
        }
        if (next == TexturePackMode.HYPIXEL_PLUS) {
            UserPack.HYPIXEL_PLUS.setActive(true);
        } else if (next == TexturePackMode.FURFSKY_REBORN) {
            UserPack.FURFSKY_REBORN.setActive(true);
        }
    }

    /**
     * The "Open download page" button. The mod never fetches a pack itself: the player downloads it
     * from the pack's official page and drops it into {@code resourcepacks/}; the mode cycle then
     * finds it there.
     */
    public static void openDownloadPage(UserPack pack) {
        if (pack.isInstalled()) {
            SBSChat.send(pack.displayName() + " is installed - pick it in Pack Theme Mode. "
                    + "Opening its page in case you want a newer version.");
        } else {
            SBSChat.send("Download " + pack.displayName() + " from its page, put the file into "
                    + "your resourcepacks folder, then pick it in Pack Theme Mode.");
        }
        pack.openDownloadPage();
    }
}
