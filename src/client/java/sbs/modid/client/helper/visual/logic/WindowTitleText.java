/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.visual.logic;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the operating system's window title says - the text beside the icon in the Windows title bar
 * and in the taskbar, which is the other half of {@link WindowTitleBar}'s colours.
 *
 * <p>The default reads {@code Minecraft[Skyblock Simplified Mod]*26.2}: the mod says it is here, on
 * the one surface the game cannot draw on.
 *
 * <p><b>Nothing in that string is written down.</b> It is a template of placeholders resolved at
 * run time, which is the whole design:
 *
 * <ul>
 *   <li>{@code 26.2} is {@link #MINECRAFT}, read from the game itself. A hardcoded version is wrong
 *       the day the game updates, and wrong in the most embarrassing possible place - a title bar
 *       confidently announcing the previous version.</li>
 *   <li>{@code Skyblock Simplified} is {@link #MOD_NAME}, read from {@code fabric.mod.json} through
 *       the loader. Renaming the mod renames the title with no code change.</li>
 *   <li>{@link #VANILLA} hands back whatever Minecraft itself would have shown, untouched. It is the
 *       future-proof escape hatch: whatever Mojang decides to put in the title next - a new suffix, a
 *       different separator, a world name - comes through that placeholder without this class
 *       knowing anything about it.</li>
 * </ul>
 *
 * <p>An unknown placeholder is left standing as it was typed rather than silently deleted, so a typo
 * shows up in the title bar as {@code {mcc}} instead of quietly producing a shorter string that
 * looks deliberate.
 */
public final class WindowTitleText {

    /** Whatever Minecraft itself would have put in the title bar. */
    public static final String VANILLA = "vanilla";

    /** The Minecraft version, as the game reports it - "26.2". */
    public static final String MINECRAFT = "mc";

    /** The mod's own display name, as declared to the loader. */
    public static final String MOD_NAME = "mod";

    /** The mod's own version, as declared to the loader. */
    public static final String MOD_VERSION = "modversion";

    /**
     * The title used when the player has not changed it - and the answer to "what does this mod do
     * to my title bar", so it stays literal enough to read at a glance.
     */
    public static final String DEFAULT_TEMPLATE = "Minecraft[{" + MOD_NAME + "} Mod]*{" + MINECRAFT + "}";

    /** The loader's id for this mod - the key its own metadata is looked up under. */
    private static final String MOD_ID = "skyblock-simplified-sbs";

    /** Shown for a value the loader cannot answer, rather than an empty gap or an exception. */
    private static final String UNKNOWN = "?";

    private WindowTitleText() {
    }

    private static SBSConfig.VisualsSettings cfg() {
        return ConfigManager.getInstance().get().visuals;
    }

    /** Whether the mod is writing the title at all. */
    public static boolean active() {
        return cfg().customWindowTitle;
    }

    /**
     * The title to show, or {@code null} to leave Minecraft's own alone.
     *
     * <p>Falls back to {@code vanillaTitle} rather than to anything of its own whenever the template
     * would produce nothing: a window with a blank title is indistinguishable from a crashed one in
     * the taskbar, and that is a worse outcome than ignoring the setting.
     *
     * @param vanillaTitle what Minecraft was about to use, which is also the {@code vanilla}
     *                     placeholder's value
     */
    public static String resolve(String vanillaTitle) {
        if (!active()) {
            return null;
        }
        String template = cfg().windowTitle;
        if (template == null || template.isBlank()) {
            return null;
        }
        String resolved = fill(template, values(vanillaTitle));
        return resolved.isBlank() ? null : resolved;
    }

    /**
     * Every placeholder and what it currently stands for, in the order the settings page lists them.
     *
     * @param vanillaTitle Minecraft's own title, or {@code null} when it is not being built right
     *                     now - the settings page has no vanilla title to hand, so it shows the
     *                     live one from the window instead of leaving the row blank
     */
    public static Map<String, String> values(String vanillaTitle) {
        Map<String, String> values = new LinkedHashMap<>(4);
        values.put(VANILLA, vanillaTitle == null ? minecraftVersion() : vanillaTitle);
        values.put(MINECRAFT, minecraftVersion());
        values.put(MOD_NAME, metadata().map(meta -> meta.getMetadata().getName()).orElse(UNKNOWN));
        values.put(MOD_VERSION, metadata()
                .map(meta -> meta.getMetadata().getVersion().getFriendlyString()).orElse(UNKNOWN));
        return values;
    }

    /**
     * {@code template} with every known {@code {placeholder}} replaced.
     *
     * <p>Written as one left-to-right scan rather than a chain of {@code String.replace} calls, so a
     * value that happens to contain braces cannot be re-scanned and expanded again - a mod named
     * "{mc}" would otherwise put the game version in the title twice.
     */
    static String fill(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = template.indexOf('}', i + 1);
            if (close < 0) {
                // An unclosed brace is the rest of the string, and it is what the player typed.
                out.append(template, i, template.length());
                break;
            }
            String key = template.substring(i + 1, close).trim().toLowerCase(java.util.Locale.ROOT);
            String value = values.get(key);
            out.append(value == null ? template.substring(i, close + 1) : value);
            i = close + 1;
        }
        return out.toString();
    }

    /** The running game's version - never a constant, see the class note. */
    private static String minecraftVersion() {
        try {
            return SharedConstants.getCurrentVersion().name();
        } catch (Throwable unavailable) {
            // Only reachable from tooling that never bootstrapped the game. A title bar is not worth
            // a crash, but a silently wrong version would be worse than a visible "?".
            return UNKNOWN;
        }
    }

    private static Optional<ModContainer> metadata() {
        try {
            return FabricLoader.getInstance().getModContainer(MOD_ID);
        } catch (Throwable unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Asks the game to re-title the window now, so a changed setting is visible without a restart.
     * Safe to call before the window exists - it simply does nothing.
     */
    public static void apply() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        try {
            minecraft.updateTitle();
        } catch (Throwable failed) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Window] could not re-title the window: {}",
                    failed.toString());
        }
    }
}
