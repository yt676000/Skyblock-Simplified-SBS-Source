/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.pausemenu;

import com.google.gson.reflect.TypeToken;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns the per-button pause-menu styling: the persisted {@link PauseButtonStyle} map plus the
 * widget → id binding the renderer needs.
 *
 * <p><b>Identity.</b> Pause-menu widgets are rebuilt from scratch every time the screen opens, so a
 * style has to key off something stable. That is the widget's message: for vanilla buttons it is a
 * translatable component, whose key ({@code menu.returnToGame}, {@code menu.options}, ...) is the same
 * in every language and across versions. Anything else falls back to its literal text, which is the
 * best a third-party button offers.
 *
 * <p><b>Binding.</b> {@link #bind} is called while {@link PauseMenuLayout} captures the freshly built
 * screen, and is what lets the widget renderer ask "is this a pause button, and what is its corner
 * radius" without knowing anything about screens. The map is identity-keyed and replaced wholesale on
 * every capture, so it never outlives the widgets by more than one screen open.
 */
public final class PauseMenuStyles {

    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, PauseButtonStyle>>() {
    }.getType();

    /** Shared read-only default for unstyled widgets – avoids map churn while rendering. */
    private static final PauseButtonStyle DEFAULT = new PauseButtonStyle();

    private static final Map<AbstractWidget, String> BOUND = new IdentityHashMap<>();

    private static Map<String, PauseButtonStyle> styles;

    private PauseMenuStyles() {
    }

    // ------------------------------------------------------------------
    // Identity + binding
    // ------------------------------------------------------------------

    /** The stable config key for a widget – its translation key where it has one, else its text. */
    public static String idOf(AbstractWidget widget) {
        if (widget instanceof PauseMenuButton) {
            return PauseMenuButton.ID;   // its label is a literal that shrinks when the button does
        }
        Component message = widget.getMessage();
        if (message.getContents() instanceof TranslatableContents translatable) {
            return translatable.getKey();
        }
        String text = message.getString();
        return text.isBlank() ? widget.getClass().getSimpleName() : text;
    }

    /** Starts a fresh binding for a newly built pause screen. */
    public static void clearBindings() {
        BOUND.clear();
    }

    public static void bind(AbstractWidget widget, String id) {
        BOUND.put(widget, id);
    }

    /** The style of a bound pause-menu widget, or null when this widget is not one. */
    public static PauseButtonStyle forWidget(Object widget) {
        if (BOUND.isEmpty() || !(widget instanceof AbstractWidget w)) {
            return null;
        }
        String id = BOUND.get(w);
        return id == null ? null : get(id);
    }

    /**
     * The corner radius to draw {@code widget} with: its own when it is a pause button that was given
     * one, otherwise {@code fallback}. This is the whole hook the widget renderer needs.
     */
    public static int cornerFor(Object widget, int fallback) {
        PauseButtonStyle style = forWidget(widget);
        return style == null || style.corner < 0 ? fallback : style.corner;
    }

    // ------------------------------------------------------------------
    // The persisted map
    // ------------------------------------------------------------------

    private static Map<String, PauseButtonStyle> map() {
        if (styles == null) {
            load();
        }
        return styles;
    }

    /** Read-only style for an id (a shared default if unset) – safe to call every frame. */
    public static PauseButtonStyle get(String id) {
        PauseButtonStyle style = map().get(id);
        return style != null ? style : DEFAULT;
    }

    /** Mutable style for an id, inserting a fresh default entry if needed (editor only). */
    public static PauseButtonStyle getOrCreate(String id) {
        return map().computeIfAbsent(id, k -> new PauseButtonStyle());
    }

    /** Restores every button to vanilla's own position, size and the theme's corner radius. */
    public static void resetAll() {
        map().clear();
    }

    private static void load() {
        Path path = SBSFiles.pauseMenuFile();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Map<String, PauseButtonStyle> parsed = SBSFiles.GSON.fromJson(reader, MAP_TYPE);
                    if (parsed != null) {
                        styles = parsed;
                        return;
                    }
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to read pause menu styles, using defaults", e);
        }
        styles = new LinkedHashMap<>();
    }

    /** Persists the styles to {@code gui/pause_menu.json} (best effort – never throws). */
    public static void save() {
        Path path = SBSFiles.pauseMenuFile();
        try {
            SBSFiles.ensureParent(path);
            try (Writer writer = Files.newBufferedWriter(path)) {
                SBSFiles.GSON.toJson(map(), writer);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to write pause menu styles", e);
        }
    }
}
