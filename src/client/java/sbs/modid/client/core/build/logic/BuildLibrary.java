/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.build.io.SchematicStore;
import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.config.SBSFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The game's handle on the schematic library: the store over {@code config/sbs/schematics/}, and
 * a single background thread for the slow parts (encoding a big build, drawing its thumbnail with
 * {@link Thumbnails}) so neither ever stalls a frame.
 *
 * <p>Results come back on the client thread through {@link Minecraft#execute}, so callers can talk
 * to chat and to the hologram without thinking about threads.
 */
public final class BuildLibrary {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SBS-BuildLibrary");
        thread.setDaemon(true);
        return thread;
    });

    /** Slugs for tab completion, refreshed after every write and at most every few seconds. */
    private static volatile List<String> cachedSlugs = List.of();
    private static volatile long slugsReadAt;
    private static volatile boolean slugsRead;

    private BuildLibrary() {
    }

    public static SchematicStore store() {
        return new SchematicStore(SBSFiles.schematicsDir());
    }

    /** The outcome of a background operation: a value, or the reason it failed. */
    public record Result<T>(T value, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    /** Saves off-thread, then its thumbnail; {@code done} runs on the client thread. */
    public static void saveAsync(Schematic schematic, boolean overwrite, Consumer<Result<Path>> done) {
        IO.execute(() -> {
            Result<Path> result;
            try {
                SchematicStore store = store();
                Path file = store.save(schematic, overwrite);
                writeThumbnail(store, schematic);
                result = new Result<>(file, null);
            } catch (IOException | RuntimeException failed) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Build] Saving '{}' failed: {}",
                        schematic.header().name(), failed.getMessage());
                result = new Result<>(null, failed.getMessage());
            }
            invalidateSlugs();
            Result<Path> finalResult = result;
            Minecraft.getInstance().execute(() -> done.accept(finalResult));
        });
    }

    /** Loads off-thread; {@code done} runs on the client thread. */
    public static void loadAsync(String name, Consumer<Result<Schematic>> done) {
        IO.execute(() -> {
            Result<Schematic> result;
            try {
                result = new Result<>(store().load(name), null);
            } catch (IOException | RuntimeException failed) {
                result = new Result<>(null, failed.getMessage());
            }
            Result<Schematic> finalResult = result;
            Minecraft.getInstance().execute(() -> done.accept(finalResult));
        });
    }

    /** Runs any library chore off-thread and hands its result back on the client thread. */
    public static <T> void runAsync(IoTask<T> task, Consumer<Result<T>> done) {
        IO.execute(() -> {
            Result<T> result;
            try {
                result = new Result<>(task.run(store()), null);
            } catch (IOException | RuntimeException failed) {
                result = new Result<>(null, failed.getMessage());
            }
            invalidateSlugs();
            Result<T> finalResult = result;
            Minecraft.getInstance().execute(() -> done.accept(finalResult));
        });
    }

    /** A library operation that may fail with a reason. */
    public interface IoTask<T> {
        T run(SchematicStore store) throws IOException;
    }

    private static void writeThumbnail(SchematicStore store, Schematic schematic) {
        try {
            Thumbnails.write(schematic, store.thumbnailFor(schematic.header().name()));
        } catch (IOException | RuntimeException failed) {
            // The build itself is saved; a missing picture is shown as a placeholder, not an error.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Build] Thumbnail for '{}' failed: {}",
                    schematic.header().name(), failed.getMessage());
        }
    }

    /**
     * Draws the thumbnail of a saved build that has none (saved before thumbnails existed, or
     * imported by hand), off-thread; {@code done} runs on the client thread afterwards.
     */
    public static void ensureThumbnailAsync(String slug, Runnable done) {
        IO.execute(() -> {
            SchematicStore store = store();
            if (!java.nio.file.Files.exists(store.thumbnailFor(slug))) {
                try {
                    Schematic loaded = store.load(slug);
                    Thumbnails.write(loaded, store.thumbnailFor(slug));
                } catch (IOException | RuntimeException failed) {
                    SkyblockSimplifiedSBS.LOGGER.info("[SBS][Build] No thumbnail for '{}': {}", slug, failed.getMessage());
                }
            }
            Minecraft.getInstance().execute(done);
        });
    }

    /**
     * Saved slugs for tab completion. Served from a cache and refreshed in the background, because
     * completion runs on every keystroke and a folder listing reads every file's header.
     */
    public static List<String> slugsForCompletion() {
        long now = System.currentTimeMillis();
        if (!slugsRead || now - slugsReadAt > 5_000L) {
            slugsRead = true;
            slugsReadAt = now;
            IO.execute(() -> cachedSlugs = List.copyOf(store().slugs()));
        }
        return cachedSlugs;
    }

    private static void invalidateSlugs() {
        cachedSlugs = List.copyOf(store().slugs());
        slugsReadAt = System.currentTimeMillis();
        slugsRead = true;
    }
}
