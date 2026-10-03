/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config.share;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.fabricmc.loader.api.FabricLoader;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Config sharing: turn the shareable part of this config into a clipboard string, and take one
 * back apart safely.
 *
 * <p>Implements {@code docs/CONFIG-SHARING-DESIGN.md}. The properties that matter and where they
 * live:
 *
 * <ul>
 *   <li><b>The allowlist is the parser, not a filter after it.</b> {@link #read} walks the payload
 *       with a streaming {@link JsonReader} and only materialises values whose path is in
 *       {@link ShareWalker}'s set. A key nobody annotated is skipped without ever becoming an
 *       object, so there is no moment at which an un-allowed value exists in memory to be
 *       mishandled.</li>
 *   <li><b>No reflective binding to a DTO.</b> Not even a hand-written one - a DTO grows fields, and
 *       the next field somebody adds to it inherits trust nobody granted it. {@code SBSFiles.GSON},
 *       the trusted-local-file parser, is never handed clipboard input; that is the line this class
 *       exists to make impossible to write by accident.</li>
 *   <li><b>All or nothing.</b> Validated values are staged in a map and only a fully successful pass
 *       reaches {@link SBSConfig}. A payload that is half-good applies nothing, because a config
 *       left in a state neither side chose is worse than one that did not change.</li>
 *   <li><b>A backup before every apply</b>, so "I imported the wrong thing" is recoverable without
 *       needing to have thought about it first.</li>
 * </ul>
 */
public final class ConfigShare {

    /** Most keys a payload may contain. */
    private static final int MAX_KEYS = 5000;

    /** How deep the payload may nest. Today's deepest legitimate structure is 4. */
    private static final int MAX_DEPTH = 12;

    /** How many config backups are kept before the oldest is dropped. */
    private static final int KEEP_BACKUPS = 10;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private ConfigShare() {
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /**
     * The clipboard string for the shareable part of the live config.
     *
     * <p>Its only input is the in-memory {@link SBSConfig}; it opens no file. That is what puts the
     * licence token, the per-profile stores and every tracker structurally out of reach rather than
     * merely filtered out.
     */
    public static String export() throws ShareCodec.ShareException {
        SBSConfig config = ConfigManager.getInstance().get();
        Map<String, ShareWalker.Entry> shareable = ShareWalker.collect(config);
        StringWriter out = new StringWriter();
        try (JsonWriter json = new JsonWriter(out)) {
            json.beginObject();
            for (Map.Entry<String, ShareWalker.Entry> each : shareable.entrySet()) {
                Object value = each.getValue().read();
                if (value == null) {
                    continue;
                }
                json.name(each.getKey());
                write(json, value);
            }
            json.endObject();
        } catch (IOException impossible) {
            throw new ShareCodec.ShareException("Could not write the settings: "
                    + impossible.getMessage());
        }
        return ShareCodec.encode(out.toString(), modVersion());
    }

    /** How many settings an export would carry, for the button's own label. */
    public static int shareableCount() {
        return ShareWalker.collect(ConfigManager.getInstance().get()).size();
    }

    private static void write(JsonWriter json, Object value) throws IOException {
        if (value instanceof Boolean bool) {
            json.value(bool);
        } else if (value instanceof Number number) {
            json.value(number);
        } else if (value instanceof Enum<?> constant) {
            json.value(constant.name());   // never the ordinal - see Kind.ENUM
        } else {
            json.value(String.valueOf(value));
        }
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /** One accepted change, for the preview's {@code path: old → new} line. */
    public record Change(String path, String from, String to, boolean enablesFeature) {
    }

    /**
     * What an import would do, before anything is applied.
     *
     * @param changes  accepted changes, empty when the payload matches the current config
     * @param rejected values that failed their contract, each with why
     * @param unknown  keys this build does not know - counted, logged, dropped
     * @param sourceVersion the mod version the payload was made by
     */
    public record Preview(List<Change> changes, List<ShareValues.Rejected> rejected,
                          List<String> unknown, String sourceVersion,
                          Map<String, Object> staged) {

        /** Whether applying would change anything at all. */
        public boolean hasChanges() {
            return !changes.isEmpty();
        }

        /** The count the preview leads with: features being switched ON, which most attacks need. */
        public long featuresEnabled() {
            return changes.stream().filter(Change::enablesFeature).count();
        }
    }

    /**
     * Validates a clipboard string and reports what it would do. Applies nothing.
     *
     * <p>Rejections are collected rather than thrown on the first one, so the preview can show every
     * problem at once instead of making the player fix them one paste at a time.
     */
    public static Preview read(String clipboard) throws ShareCodec.ShareException {
        ShareCodec.Payload payload = ShareCodec.decode(clipboard);
        SBSConfig config = ConfigManager.getInstance().get();
        Map<String, ShareWalker.Entry> allowed = ShareWalker.collect(config);

        Map<String, Object> staged = new LinkedHashMap<>();
        List<Change> changes = new ArrayList<>();
        List<ShareValues.Rejected> rejected = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        try (JsonReader reader = new JsonReader(new StringReader(payload.json()))) {
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new ShareCodec.ShareException("That SBS config is not shaped like one.");
            }
            reader.beginObject();
            int keys = 0;
            while (reader.hasNext()) {
                if (++keys > MAX_KEYS) {
                    throw new ShareCodec.ShareException("That config holds more settings than a "
                            + "config can (" + MAX_KEYS + "). It has been refused.");
                }
                String path = reader.nextName();
                ShareWalker.Entry entry = allowed.get(path);
                if (entry == null) {
                    // Skipped without being materialised: an unknown key never becomes a value.
                    reader.skipValue();
                    unknown.add(path);
                    continue;
                }
                Object raw = readScalar(reader, path);
                StringBuilder why = new StringBuilder();
                Optional<Object> accepted =
                        ShareValues.accept(entry.spec(), entry.field().getType(), raw, why);
                if (accepted.isEmpty()) {
                    rejected.add(new ShareValues.Rejected(path, why.toString()));
                    continue;
                }
                Object now = entry.read();
                Object next = accepted.get();
                if (!java.util.Objects.equals(now, next)) {
                    staged.put(path, next);
                    changes.add(new Change(path, String.valueOf(now), String.valueOf(next),
                            Boolean.FALSE.equals(now) && Boolean.TRUE.equals(next)));
                }
            }
            reader.endObject();
        } catch (IOException | IllegalStateException malformed) {
            throw new ShareCodec.ShareException("That SBS config could not be read - it may have "
                    + "been damaged in transit.");
        }
        if (!unknown.isEmpty()) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Share] {} unknown key(s) ignored: {}",
                    unknown.size(), unknown.size() > 20 ? unknown.subList(0, 20) + "..." : unknown);
        }
        return new Preview(List.copyOf(changes), List.copyOf(rejected), List.copyOf(unknown),
                payload.modVersion(), staged);
    }

    /**
     * One scalar from the reader.
     *
     * <p>An object or an array where a scalar belongs is refused rather than descended into: nothing
     * annotated today is structured, and a reader that walks into a structure it has no field for is
     * how a depth cap becomes the only thing standing between a payload and the parser.
     */
    private static Object readScalar(JsonReader reader, String path) throws IOException {
        JsonToken token = reader.peek();
        return switch (token) {
            case BOOLEAN -> reader.nextBoolean();
            case NUMBER -> reader.nextDouble();
            case STRING -> {
                String text = reader.nextString();
                yield text.length() > ShareValues.MAX_STRING
                        ? text.substring(0, ShareValues.MAX_STRING) : text;
            }
            case NULL -> {
                reader.nextNull();
                yield null;
            }
            default -> {
                reader.skipValue();
                SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Share] '{}' arrived as {}, which is not a value this key can hold",
                        path, token);
                yield null;
            }
        };
    }

    /**
     * Applies a previewed import, after taking a backup. All staged values or none.
     *
     * @return how many settings changed
     */
    public static int apply(Preview preview) {
        if (preview == null || preview.staged().isEmpty()) {
            return 0;
        }
        backup();
        SBSConfig config = ConfigManager.getInstance().get();
        Map<String, ShareWalker.Entry> allowed = ShareWalker.collect(config);
        int written = 0;
        for (Map.Entry<String, Object> each : preview.staged().entrySet()) {
            ShareWalker.Entry entry = allowed.get(each.getKey());
            if (entry != null && entry.write(each.getValue())) {
                written++;
            }
        }
        ConfigManager.getInstance().save();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Share] imported {} setting(s) from a {} config",
                written, preview.sourceVersion());
        return written;
    }

    // ------------------------------------------------------------------
    // Backup
    // ------------------------------------------------------------------

    /**
     * Copies {@code config.json} aside before an import, keeping the last {@value #KEEP_BACKUPS}.
     *
     * <p>Not a config profile, although {@code ConfigProfiles} has the machinery: a profile is a
     * user-facing thing with a switcher, and auto-creating one per import would fill that list with
     * entries nobody chose. A backup is the quieter thing.
     */
    public static Path backup() {
        try {
            Path dir = SBSFiles.root().resolve("backups");
            Files.createDirectories(dir);
            Path source = SBSFiles.configFile();
            if (!Files.exists(source)) {
                return null;
            }
            Path target = dir.resolve("config-" + LocalDateTime.now().format(STAMP) + ".json");
            Files.copy(source, target);
            prune(dir);
            return target;
        } catch (IOException | RuntimeException failed) {
            // Logged, never silent - but not fatal: refusing to import because a backup could not be
            // written would be the file system's problem becoming the player's.
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Share] could not back the config up: {}",
                    failed.toString());
            return null;
        }
    }

    /** The most recent backup, for the "undo that import" button. */
    public static Optional<Path> latestBackup() {
        try (var files = Files.list(SBSFiles.root().resolve("backups"))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
        } catch (IOException | RuntimeException none) {
            return Optional.empty();
        }
    }

    /** Puts the newest backup back and reloads. Returns whether anything was restored. */
    public static boolean restoreLatest() {
        Optional<Path> newest = latestBackup();
        if (newest.isEmpty()) {
            return false;
        }
        try {
            // The current state is itself backed up first: "undo" must not be the thing that loses
            // work, and a player who restores the wrong one needs a way back.
            backup();
            Files.copy(newest.get(), SBSFiles.configFile(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ConfigManager.getInstance().reload();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Share] restored {}",
                    newest.get().getFileName());
            return true;
        } catch (IOException | RuntimeException failed) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Share] could not restore a backup: {}",
                    failed.toString());
            return false;
        }
    }

    private static void prune(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            List<Path> all = new ArrayList<>(files
                    .filter(p -> p.getFileName().toString().endsWith(".json")).toList());
            all.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (int i = 0; i < all.size() - KEEP_BACKUPS; i++) {
                Files.deleteIfExists(all.get(i));
            }
        }
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer("skyblock-simplified-sbs")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /** The decoded JSON of a payload, for the preview's "View raw". */
    public static String rawJson(String clipboard) throws ShareCodec.ShareException {
        return ShareCodec.decode(clipboard).json();
    }

    /** UTF-8 byte length, for the "copied N KB" line. */
    public static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
