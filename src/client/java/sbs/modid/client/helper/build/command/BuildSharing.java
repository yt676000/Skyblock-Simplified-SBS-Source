/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.helper.build.command;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import sbs.modid.client.core.build.io.SchematicShareCode;
import sbs.modid.client.core.build.io.SchematicStore;
import sbs.modid.client.core.build.io.VanillaStructure;
import sbs.modid.client.core.build.logic.BuildLibrary;
import sbs.modid.client.core.build.logic.Clipboard;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.helper.build.logic.BuildChat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Getting builds in and out: share codes through the system clipboard, and Minecraft's own structure
 * {@code .nbt} files in and out of the library folder.
 *
 * <p>All of it is local - the system clipboard and {@code config/sbs/schematics/}. Nothing is sent
 * anywhere. File names are confined to the library folder: an import naming {@code ../} or an
 * absolute path is refused, so a command can only ever read what the player put in that folder.
 */
public final class BuildSharing {

    /** Where exported structure files go, inside the library folder. */
    static final String EXPORT_DIR = "exports";

    /** Largest structure file read on import. */
    private static final long MAX_NBT_FILE = 32L * 1024 * 1024;

    private BuildSharing() {
    }

    /** {@code share [name]}: the clipboard's code, or a saved build's, onto the system clipboard. */
    static void share(String rest) {
        if (rest.isBlank()) {
            Schematic clipboard = BuildCommands.requireClipboard();
            if (clipboard != null) {
                copyCode(clipboard, "the clipboard");
            }
            return;
        }
        BuildLibrary.loadAsync(rest, result -> {
            if (result.ok()) {
                copyCode(result.value(), "\"" + result.value().header().name() + "\"");
            } else {
                BuildChat.warn("Could not load - " + result.error());
            }
        });
    }

    private static void copyCode(Schematic schematic, String what) {
        try {
            String code = SchematicShareCode.encode(schematic);
            Minecraft.getInstance().keyboardHandler.setClipboard(code);
            BuildChat.info("Share code for " + what + " copied (" + (code.length() / 1024 + 1)
                    + " KB) - paste it to a friend; they //import it");
        } catch (SchematicShareCode.ShareException tooBig) {
            BuildChat.warn(tooBig.getMessage());
        }
    }

    /**
     * {@code import}: a share code from the system clipboard. {@code import <file.nbt>}: a vanilla
     * structure file from the library folder. Either way the result lands on the clipboard.
     */
    static void importFrom(String rest) {
        if (rest.isBlank()) {
            String text = Minecraft.getInstance().keyboardHandler.getClipboard();
            try {
                Schematic imported = SchematicShareCode.decode(text);
                Clipboard.set(imported);
                BuildChat.info("Imported " + label(imported) + " to the clipboard");
                BuildChat.hint("//paste places it  •  //save <name> keeps it");
            } catch (SchematicShareCode.ShareException refused) {
                BuildChat.warn("Can't import: " + refused.getMessage());
            }
            return;
        }
        String file = rest.trim();
        if (!file.toLowerCase(Locale.ROOT).endsWith(".nbt")) {
            file = file + ".nbt";
        }
        String finalFile = file;
        BuildLibrary.runAsync(store -> readNbt(store, finalFile), result -> {
            if (!result.ok()) {
                BuildChat.warn("Can't import " + finalFile + ": " + result.error());
                return;
            }
            Clipboard.set(result.value());
            BuildChat.info("Imported " + label(result.value()) + " from " + finalFile + " to the clipboard");
            BuildChat.hint("//paste places it  •  //save <name> keeps it in the library");
        });
    }

    /** Reads {@code name} from the library folder or its exports folder, refusing anything outside. */
    static Schematic readNbt(SchematicStore store, String name) throws IOException {
        Path folder = store.folder().toAbsolutePath().normalize();
        for (Path candidate : new Path[] {folder.resolve(name), folder.resolve(EXPORT_DIR).resolve(name)}) {
            Path normalised = candidate.toAbsolutePath().normalize();
            if (!normalised.startsWith(folder)) {
                throw new IOException("only files inside the schematics folder can be imported");
            }
            if (Files.isRegularFile(normalised)) {
                if (Files.size(normalised) > MAX_NBT_FILE) {
                    throw new IOException("the file is larger than " + (MAX_NBT_FILE >> 20) + " MB");
                }
                String base = normalised.getFileName().toString();
                return VanillaStructure.read(Files.readAllBytes(normalised), base.substring(0, base.length() - 4));
            }
        }
        throw new IOException("no such file in " + folder + " - put the .nbt there first");
    }

    /** {@code export <name>}: the clipboard as {@code schematics/exports/<name>.nbt}. */
    static void export(String rest) {
        Schematic clipboard = BuildCommands.requireClipboard();
        if (clipboard == null) {
            return;
        }
        String slug = SchematicStore.slug(rest.replaceAll("(?i)\\.nbt$", ""));
        if (slug.isEmpty()) {
            BuildChat.warn("Usage: //export <name>");
            return;
        }
        int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        BuildLibrary.runAsync(store -> {
            Path target = store.folder().resolve(EXPORT_DIR).resolve(slug + ".nbt");
            Files.createDirectories(target.getParent());
            Files.write(target, VanillaStructure.write(clipboard, dataVersion));
            return target;
        }, result -> {
            if (result.ok()) {
                BuildChat.info("Exported " + label(clipboard) + " to " + result.value()
                        + " - a structure block can load it");
            } else {
                BuildChat.warn("Could not export - " + result.error());
            }
        });
    }

    private static String label(Schematic schematic) {
        String name = schematic.header().name();
        return (name.isBlank() ? "" : "\"" + name + "\" ") + "(" + schematic.sizeLabel()
                + String.format(Locale.ROOT, ", %,d blocks)", schematic.nonAirCount());
    }
}
