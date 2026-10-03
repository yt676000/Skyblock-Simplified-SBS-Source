/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config;

import sbs.modid.SkyblockSimplifiedSBS;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The config profiles: named alternative copies of the whole SBS config, switchable from the
 * dropdown in the bottom-left corner of the main screen.
 *
 * <p><b>Default is not a file of its own.</b> It is {@link SBSFiles#configFile()}, the config that
 * has always been there – so a player who never opens the dropdown notices nothing, and deleting
 * every custom profile leaves the original setup untouched. Custom profiles live one file each in
 * {@code config/sbs/profiles/<name>.json}.
 *
 * <p>The list of custom profiles is <b>derived from the directory</b>, never from an index file.
 * An index would be a second source of truth that a hand-deleted file could silently contradict;
 * the files themselves cannot lie. Only the active profile's name needs storing, and that goes in
 * {@code profiles/active.txt}.
 *
 * <p>Switching is {@link ConfigManager#switchProfile}: this class only owns names and files.
 */
public final class ConfigProfiles {

    /** The name of the built-in profile, which maps to the base {@code config.json}. */
    public static final String DEFAULT = "Default";

    /** Prefix for auto-generated names when a profile is created without one ("Custom 1", ...). */
    private static final String AUTO_PREFIX = "Custom ";

    /** Long enough for a readable name, short enough to stay inside the sidebar's width. */
    public static final int MAX_NAME_LENGTH = 24;

    private static ConfigProfiles instance;

    /** Cached active name; {@code null} until first read from disk. */
    private String active;

    private ConfigProfiles() {
    }

    public static ConfigProfiles getInstance() {
        if (instance == null) {
            instance = new ConfigProfiles();
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    /** Whether {@code name} is the built-in profile (case-insensitively – it is a file name). */
    public static boolean isDefault(String name) {
        return name == null || name.isBlank() || DEFAULT.equalsIgnoreCase(name.trim());
    }

    /**
     * The selected profile, healed against the disk: a profile whose file has been deleted from
     * outside the game falls back to {@link #DEFAULT} rather than sending every later read to a path
     * that is not there.
     */
    public String active() {
        if (active == null) {
            active = readActive();
        }
        if (!isDefault(active) && !Files.exists(SBSFiles.configProfileFile(active))) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS] Config profile '{}' is gone – falling back to {}",
                    active, DEFAULT);
            active = DEFAULT;
            writeActive(active);
        }
        return active;
    }

    /** Every selectable profile: {@link #DEFAULT} first, then the custom ones alphabetically. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        out.add(DEFAULT);
        out.addAll(customNames());
        return out;
    }

    /** The custom profiles, read from the directory and sorted case-insensitively. */
    public List<String> customNames() {
        List<String> out = new ArrayList<>();
        Path dir = SBSFiles.configProfilesDir();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(file -> file.endsWith(".json"))
                    .map(file -> file.substring(0, file.length() - ".json".length()))
                    .filter(name -> !name.isBlank() && !isDefault(name))
                    .forEach(out::add);
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to list config profiles", e);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** The config file backing {@code name} – the base config for Default, else its own file. */
    public Path fileFor(String name) {
        return isDefault(name) ? SBSFiles.configFile() : SBSFiles.configProfileFile(name);
    }

    // ------------------------------------------------------------------
    // Create / delete / rename
    // ------------------------------------------------------------------

    /**
     * Creates a profile and returns the name it actually got.
     *
     * <p>A blank request becomes the next free {@code Custom N} – the dropdown lets you confirm
     * without typing anything, and a nameless profile still has to be findable afterwards. The
     * requested name is sanitised into something safe as a file name and de-duplicated, so no input
     * can fail the creation or overwrite an existing profile.
     *
     * <p>The new profile starts from <b>factory defaults</b>, not from a copy of the current one:
     * a profile exists to be a different setup, and starting as a duplicate of the config you are
     * already on makes the switch look like nothing happened.
     *
     * @return the created profile's name, or {@code null} when the file could not be written
     */
    public String create(String requested) {
        String name = unique(sanitize(requested));
        Path file = SBSFiles.configProfileFile(name);
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                SBSFiles.GSON.toJson(new SBSConfig(), writer);
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Created config profile '{}'", name);
            return name;
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to create config profile '{}'", name, e);
            return null;
        }
    }

    /**
     * Deletes a custom profile's file. {@link #DEFAULT} is never deletable – it is the base config,
     * and "delete" would mean wiping the settings of a player who never asked for profiles at all.
     *
     * @return true when the profile is gone afterwards
     */
    public boolean delete(String name) {
        if (isDefault(name)) {
            return false;
        }
        try {
            Files.deleteIfExists(SBSFiles.configProfileFile(name));
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Deleted config profile '{}'", name);
            return true;
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to delete config profile '{}'", name, e);
            return false;
        }
    }

    /**
     * Renames a custom profile, moving its file. Renaming {@link #DEFAULT} is refused: it has no file
     * of its own to move, and the base config must stay findable under the name everything else uses.
     *
     * @return the new name, or {@code null} when nothing was renamed
     */
    public String rename(String from, String requested) {
        if (isDefault(from)) {
            return null;
        }
        if (requested == null || requested.isBlank()) {
            return null;   // an empty rename keeps the name; only CREATE invents a "Custom N"
        }
        String target = sanitize(requested);
        if (target.equalsIgnoreCase(from)) {
            return null;   // unchanged – not a failure, just nothing to do
        }
        String name = unique(target);
        try {
            Files.move(SBSFiles.configProfileFile(from), SBSFiles.configProfileFile(name));
            if (from.equals(active)) {
                active = name;
                writeActive(name);
            }
            SkyblockSimplifiedSBS.LOGGER.info("[SBS] Renamed config profile '{}' -> '{}'", from, name);
            return name;
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to rename config profile '{}'", from, e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Active pointer (package-private: switching goes through ConfigManager)
    // ------------------------------------------------------------------

    void setActive(String name) {
        active = isDefault(name) ? DEFAULT : name;
        writeActive(active);
    }

    private String readActive() {
        Path file = SBSFiles.activeConfigProfileFile();
        try {
            if (Files.exists(file)) {
                String name = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to read the active config profile", e);
        }
        return DEFAULT;
    }

    private void writeActive(String name) {
        Path file = SBSFiles.activeConfigProfileFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, name, StandardCharsets.UTF_8);
        } catch (IOException e) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS] Failed to store the active config profile", e);
        }
    }

    // ------------------------------------------------------------------
    // Name hygiene
    // ------------------------------------------------------------------

    /**
     * Turns typed text into something safe to use as a file name: path separators, wildcards and
     * control characters out, whitespace collapsed, length capped. Anything that sanitises down to
     * nothing (or to "Default") becomes the next free auto name instead of being rejected – a
     * dropdown is no place to argue with the player about punctuation.
     */
    private String sanitize(String requested) {
        String text = requested == null ? "" : requested.trim();
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length() && sb.length() < MAX_NAME_LENGTH; i++) {
            char c = text.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c == '.' || c < ' ') {
                continue;
            }
            if (c == ' ' && (sb.length() == 0 || sb.charAt(sb.length() - 1) == ' ')) {
                continue;   // no leading or doubled spaces
            }
            sb.append(c);
        }
        String cleaned = sb.toString().trim();
        return cleaned.isEmpty() || isDefault(cleaned) ? nextAutoName() : cleaned;
    }

    /** The lowest {@code Custom N} not already taken. */
    private String nextAutoName() {
        List<String> taken = customNames();
        for (int i = 1; ; i++) {
            String candidate = AUTO_PREFIX + i;
            if (!containsIgnoreCase(taken, candidate)) {
                return candidate;
            }
        }
    }

    /** Appends " 2", " 3", ... until the name is free, so a create/rename can never overwrite. */
    private String unique(String name) {
        List<String> taken = customNames();
        if (!containsIgnoreCase(taken, name)) {
            return name;
        }
        for (int i = 2; ; i++) {
            String suffix = " " + i;
            String base = name.length() + suffix.length() > MAX_NAME_LENGTH
                    ? name.substring(0, MAX_NAME_LENGTH - suffix.length()).trim()
                    : name;
            String candidate = base + suffix;
            if (!containsIgnoreCase(taken, candidate)) {
                return candidate;
            }
        }
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        for (String other : list) {
            if (other.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
