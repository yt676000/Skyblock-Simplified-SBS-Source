/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.licence.privacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Reads and writes one account's consent file.
 *
 * <p>Deliberately free of any Minecraft type: it is handed a {@link Path} and knows nothing about
 * where that path came from. That is what lets the consent rules be tested for real - a gate that
 * only runs inside a launched game is a gate nobody writes a "no consent means no traffic" test
 * for. {@link ConsentManager} is the half that knows about Minecraft.
 *
 * <p><b>Failure is a "no".</b> Every read error - missing file, truncated json, a value of the
 * wrong type, an id this build does not know - resolves to {@link ConsentState#none()} for the
 * affected scope rather than to a thrown exception or a skipped check. There is no state of this
 * file that can be interpreted as consent the user did not give.
 */
public final class ConsentStore {

    /**
     * Envelope version of the file itself, not of any disclosure. Bumped only if the file's shape
     * changes; scope-level resets are {@link ConsentScope#disclosureVersion()}'s job.
     */
    private static final int FILE_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public ConsentStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /**
     * Everything the file holds: the answers, plus whether this account has already been shown the
     * one-time notice that the privacy screen exists.
     *
     * <p>The flag lives here rather than in the config because the config is per-profile and this
     * is per-person: kept there, making a new profile would re-announce the notice to someone who
     * has already read it and made their choices.
     */
    public record Snapshot(Map<ConsentScope, ConsentState> states, boolean noticeShown) {
    }

    /**
     * Loads the stored answers. Scopes absent from the file - including every scope on a fresh
     * install - come back as {@link ConsentState#none()}, so callers never have to distinguish
     * "missing" from "declined".
     */
    public Snapshot load() {
        Map<ConsentScope, ConsentState> states = blank();
        if (!Files.exists(file)) {
            return new Snapshot(states, false);
        }
        boolean noticeShown = false;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return new Snapshot(states, false);
            }
            noticeShown = root.has("noticeShown") && root.get("noticeShown").getAsBoolean();
            if (!root.has("scopes") || !root.get("scopes").isJsonObject()) {
                return new Snapshot(states, noticeShown);
            }
            JsonObject scopes = root.getAsJsonObject("scopes");
            for (Map.Entry<String, JsonElement> entry : scopes.entrySet()) {
                // An id this build does not know is dropped, not carried forward: we cannot show
                // the user what it meant, so we cannot claim they agreed to it.
                ConsentScope scope = ConsentScope.byId(entry.getKey()).orElse(null);
                if (scope == null || !entry.getValue().isJsonObject()) {
                    continue;
                }
                states.put(scope, readState(entry.getValue().getAsJsonObject()));
            }
        } catch (Exception e) {
            // Corrupt file: fall back to "nothing granted". Deliberately not rethrown - a parse
            // error must not become a startup crash, and must not become a silent yes either.
            return new Snapshot(blank(), false);
        }
        return new Snapshot(states, noticeShown);
    }

    private static ConsentState readState(JsonObject json) {
        try {
            boolean granted = json.has("granted") && json.get("granted").getAsBoolean();
            long grantedAt = json.has("grantedAt") ? json.get("grantedAt").getAsLong() : 0L;
            int version = json.has("disclosureVersion") ? json.get("disclosureVersion").getAsInt() : 0;
            ConsentSource source = ConsentSource.byId(
                    json.has("source") ? json.get("source").getAsString() : null);
            return new ConsentState(granted, grantedAt, version, source);
        } catch (Exception e) {
            return ConsentState.none();
        }
    }

    /**
     * Writes the answers for the given account.
     *
     * <p>Written through a temporary file and moved into place, so a crash mid-write leaves the
     * previous file intact rather than a half-written one. A truncated consent file would read as
     * "nothing granted", which is safe but would silently throw away answers the user had given.
     */
    public void save(String accountId, Map<ConsentScope, ConsentState> states, boolean noticeShown)
            throws IOException {
        JsonObject scopes = new JsonObject();
        for (Map.Entry<ConsentScope, ConsentState> entry : states.entrySet()) {
            ConsentState state = entry.getValue();
            if (state == null || (!state.granted() && state.grantedAt() == 0L)) {
                continue;   // never answered - nothing worth persisting
            }
            JsonObject json = new JsonObject();
            json.addProperty("granted", state.granted());
            json.addProperty("grantedAt", state.grantedAt());
            json.addProperty("disclosureVersion", state.disclosureVersion());
            json.addProperty("source", state.source().id());
            scopes.add(entry.getKey().id(), json);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        root.addProperty("accountId", accountId == null ? "" : accountId);
        root.addProperty("noticeShown", noticeShown);
        root.add("scopes", scopes);

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Every scope at {@link ConsentState#none()} - the shape of "no answers at all". */
    public static Map<ConsentScope, ConsentState> blank() {
        Map<ConsentScope, ConsentState> states = new EnumMap<>(ConsentScope.class);
        for (ConsentScope scope : ConsentScope.values()) {
            states.put(scope, ConsentState.none());
        }
        return states;
    }
}
