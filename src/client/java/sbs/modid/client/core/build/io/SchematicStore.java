/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.build.io;

import sbs.modid.client.core.build.model.Schematic;
import sbs.modid.client.core.build.model.SchematicHeader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The schematic library: one {@code .sbsbp} file per build in a visible folder
 * ({@code config/sbs/schematics/}), its thumbnail {@code .png} beside it.
 *
 * <p>The file name is a slug of the display name ({@link #slug}); the display name itself lives in
 * the file's header, so "My House" and "my-house" are one entry and renaming is a header rewrite plus
 * a file move. Writes go to a temp file and are moved into place, so a crash mid-save never leaves a
 * half-written build where a whole one was.
 *
 * <p><b>Nothing is destroyed outright.</b> {@link #delete} moves both files into a {@code .deleted}
 * subfolder, where a later save of the same name does not reach them; the player can take one back by
 * hand. A build is hours of somebody's work, and the settings screen's rule - never destroy a
 * hand-built thing without a confirmation or an undo - applies here too.
 *
 * <p>Plain {@code java.nio}, no Minecraft types: the folder is passed in, so tests run on a temp dir.
 */
public final class SchematicStore {

    public static final String EXTENSION = ".sbsbp";
    public static final String THUMBNAIL_EXTENSION = ".png";
    public static final String DELETED_DIR = ".deleted";

    /** Longest slug; long enough for any sensible name, short enough for every file system. */
    static final int MAX_SLUG = 48;

    /** Files larger than this are not a build we wrote; refused before reading. */
    static final long MAX_FILE_BYTES = 48L * 1024 * 1024;

    private final Path folder;

    public SchematicStore(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    /** One library entry, read from the header alone. */
    public record Entry(String slug, Path file, SchematicCodec.Summary summary, long modifiedAt) {

        public String displayName() {
            String name = summary.header().name();
            return name.isBlank() ? slug : name;
        }
    }

    /** A file that is in the folder but could not be listed, and why. */
    public record Broken(Path file, String reason) {
    }

    /** A listing: every readable entry (newest first) and everything that was skipped. */
    public record Listing(List<Entry> entries, List<Broken> broken) {
    }

    /**
     * The file-name form of a display name: lower case, letters, digits and {@code -}/{@code _},
     * runs of anything else collapsed to one {@code -}. Empty when nothing usable is left.
     */
    public static String slug(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        boolean dash = false;
        for (char c : name.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (keep) {
                out.append(c);
                dash = false;
            } else if (!dash && out.length() > 0) {
                out.append('-');
                dash = true;
            }
            if (out.length() >= MAX_SLUG) {
                break;
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    public Path fileFor(String name) {
        return folder.resolve(slug(name) + EXTENSION);
    }

    public Path thumbnailFor(String name) {
        return folder.resolve(slug(name) + THUMBNAIL_EXTENSION);
    }

    public boolean exists(String name) {
        String slug = slug(name);
        return !slug.isEmpty() && Files.isRegularFile(folder.resolve(slug + EXTENSION));
    }

    /** Lists every build in the folder, newest first. Never throws for one bad file. */
    public Listing list() {
        List<Entry> entries = new ArrayList<>();
        List<Broken> broken = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return new Listing(entries, broken);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*" + EXTENSION)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String slug = fileName.substring(0, fileName.length() - EXTENSION.length());
                try {
                    if (Files.size(file) > MAX_FILE_BYTES) {
                        broken.add(new Broken(file, "larger than " + (MAX_FILE_BYTES >> 20) + " MB"));
                        continue;
                    }
                    SchematicCodec.Summary summary;
                    try (InputStream in = Files.newInputStream(file)) {
                        summary = SchematicCodec.readSummary(in);
                    }
                    entries.add(new Entry(slug, file, summary, Files.getLastModifiedTime(file).toMillis()));
                } catch (IOException unreadable) {
                    broken.add(new Broken(file, unreadable.getMessage()));
                }
            }
        } catch (IOException folderUnreadable) {
            broken.add(new Broken(folder, folderUnreadable.getMessage()));
        }
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.summary().header().createdAt())
                .reversed().thenComparing(Entry::slug));
        return new Listing(entries, broken);
    }

    /** Every saved slug, for tab completion. */
    public List<String> slugs() {
        List<String> out = new ArrayList<>();
        for (Entry entry : list().entries()) {
            out.add(entry.slug());
        }
        out.sort(String::compareTo);
        return out;
    }

    /** Loads a build by display name or slug. */
    public Schematic load(String name) throws IOException {
        Path file = fileFor(name);
        if (slug(name).isEmpty() || !Files.isRegularFile(file)) {
            throw new IOException("no saved build called \"" + name + "\"");
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("\"" + name + "\" is larger than " + (MAX_FILE_BYTES >> 20) + " MB");
        }
        try (InputStream in = Files.newInputStream(file)) {
            return SchematicCodec.decode(in);
        }
    }

    /** The summary of one build, if it exists and reads. */
    public Optional<SchematicCodec.Summary> summary(String name) {
        Path file = fileFor(name);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return Optional.of(SchematicCodec.readSummary(in));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Writes {@code schematic} under its header's name. Refuses to replace an existing build unless
     * {@code overwrite} is set, so a typo cannot silently destroy a saved one.
     *
     * @return the file written
     */
    public Path save(Schematic schematic, boolean overwrite) throws IOException {
        String name = schematic.header().name();
        String slug = slug(name);
        if (slug.isEmpty()) {
            throw new IOException("give the build a name with at least one letter or digit");
        }
        Path file = folder.resolve(slug + EXTENSION);
        if (!overwrite && Files.exists(file)) {
            throw new IOException("a build called \"" + slug + "\" already exists");
        }
        Files.createDirectories(folder);
        writeAtomically(file, SchematicCodec.encode(schematic));
        return file;
    }

    /** Replaces a saved build's header (tags, favourite, folder) without touching its blocks. */
    public void updateHeader(String name, SchematicHeader header) throws IOException {
        Schematic loaded = load(name);
        writeAtomically(fileFor(name), SchematicCodec.encode(loaded.withHeader(
                header.withName(loaded.header().name()))));
    }

    /**
     * Renames a build: new display name in the header, file and thumbnail moved to the new slug.
     * Refuses when the new name is taken.
     */
    public void rename(String from, String to) throws IOException {
        String toSlug = slug(to);
        if (toSlug.isEmpty()) {
            throw new IOException("give the build a name with at least one letter or digit");
        }
        Schematic loaded = load(from);
        Path oldFile = fileFor(from);
        Path newFile = folder.resolve(toSlug + EXTENSION);
        if (!oldFile.equals(newFile) && Files.exists(newFile)) {
            throw new IOException("a build called \"" + toSlug + "\" already exists");
        }
        writeAtomically(newFile, SchematicCodec.encode(loaded.withHeader(loaded.header().withName(to.trim()))));
        if (!oldFile.equals(newFile)) {
            Files.deleteIfExists(oldFile);
            Path oldThumb = thumbnailFor(from);
            if (Files.exists(oldThumb)) {
                Files.move(oldThumb, folder.resolve(toSlug + THUMBNAIL_EXTENSION),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Moves a build and its thumbnail into {@code .deleted/}, stamped so repeated deletes of one
     * name never overwrite each other there.
     *
     * @return where the build file went
     */
    public Path delete(String name) throws IOException {
        Path file = fileFor(name);
        if (slug(name).isEmpty() || !Files.isRegularFile(file)) {
            throw new IOException("no saved build called \"" + name + "\"");
        }
        Path bin = folder.resolve(DELETED_DIR);
        Files.createDirectories(bin);
        String stamp = slug(name) + "." + System.currentTimeMillis();
        Path target = bin.resolve(stamp + EXTENSION);
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        Path thumb = thumbnailFor(name);
        if (Files.exists(thumb)) {
            Files.move(thumb, bin.resolve(stamp + THUMBNAIL_EXTENSION), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * Copies a build as {@code "<name> (copy)"} (then "(copy 2)", ...), thumbnail included, dated now.
     *
     * @return the copy's display name
     */
    public String duplicate(String name) throws IOException {
        Schematic loaded = load(name);
        String base = loaded.header().name().isBlank() ? slug(name) : loaded.header().name();
        String copyName = sbs.modid.client.core.build.model.LibraryNames.duplicateName(base, this::exists);
        save(loaded.withHeader(loaded.header().withName(copyName).withCreatedAt(System.currentTimeMillis())
                .withFavourite(false)), false);
        Path thumb = thumbnailFor(name);
        if (Files.exists(thumb)) {
            Files.copy(thumb, thumbnailFor(copyName), StandardCopyOption.REPLACE_EXISTING);
        }
        return copyName;
    }

    /**
     * Renames a build, adding {@code " (2)"}, {@code " (3)"}, ... when the wanted name is taken by
     * another build. Renaming to its own name (a change of case, say) is not a clash.
     *
     * @return the name it got
     */
    public String renameUnique(String from, String to) throws IOException {
        String wanted = to.trim();
        if (slug(wanted).isEmpty()) {
            throw new IOException("give the build a name with at least one letter or digit");
        }
        String own = slug(from);
        String name = sbs.modid.client.core.build.model.LibraryNames.unique(wanted,
                candidate -> !slug(candidate).equals(own) && exists(candidate));
        rename(from, name);
        return name;
    }

    /** Writes a thumbnail next to its build. */
    public void writeThumbnail(String name, byte[] png) throws IOException {
        Files.createDirectories(folder);
        writeAtomically(thumbnailFor(name), png);
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            out.write(bytes);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
