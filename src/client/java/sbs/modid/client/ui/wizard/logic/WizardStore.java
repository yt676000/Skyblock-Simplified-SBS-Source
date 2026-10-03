/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads and writes one account's wizard file.
 *
 * <p>Free of any Minecraft type, like {@code ConsentStore} and for the same reason: the rules about
 * when the wizard appears are only worth what their tests are worth, and a class that needs a
 * launched game to instantiate does not get tested.
 *
 * <p><b>Absence means "not done".</b> A missing file, a truncated one, a value of the wrong type -
 * every read failure resolves to the state a fresh install is in, which is the state that <i>shows</i>
 * the wizard. That direction is deliberate and it is the whole failsafe: the only way to suppress a
 * page is a record that was successfully written saying so. A parse error can therefore cost a
 * player a second look at a page they have already seen, and can never cost them the page entirely.
 *
 * <p>The one field that does not follow that rule is {@code lastShowcaseSeen} - see
 * {@link WizardState#showcaseBaseline}, which explains why "unknown" there has to mean "nothing due"
 * rather than "everything due".
 */
public final class WizardStore {

    /** Envelope version of the file's shape. Page-level resets are not this field's job. */
    private static final int FILE_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public WizardStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /**
     * Everything the file holds.
     *
     * @param onboardingCompleted whether the first-run flow has been finished or skipped
     * @param seenPages           ids of pages already shown; the record that reaches existing users
     * @param lastShowcaseSeen    the raw stored version string, or {@code null} when never written.
     *                            Kept as text rather than parsed here so {@link WizardState} can tell
     *                            "absent" from "present but unreadable" and repair the second
     * @param showcaseOptOut      the permanent "don't show update notices" choice
     */
    public record Snapshot(boolean onboardingCompleted, Set<String> seenPages,
                           String lastShowcaseSeen, boolean showcaseOptOut) {
    }

    /** The state of a fresh install: nothing done, nothing seen, no opt-out. */
    public static Snapshot blank() {
        return new Snapshot(false, new LinkedHashSet<>(), null, false);
    }

    /** Loads the file, or {@link #blank()} when it is missing or unreadable. */
    public Snapshot load() {
        if (!Files.exists(file)) {
            return blank();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return blank();
            }
            boolean completed = root.has("onboardingCompleted")
                    && root.get("onboardingCompleted").getAsBoolean();
            boolean optOut = root.has("showcaseOptOut")
                    && root.get("showcaseOptOut").getAsBoolean();
            String showcase = root.has("lastShowcaseSeen") && root.get("lastShowcaseSeen").isJsonPrimitive()
                    ? root.get("lastShowcaseSeen").getAsString()
                    : null;

            Set<String> seen = new LinkedHashSet<>();
            if (root.has("seenPages") && root.get("seenPages").isJsonArray()) {
                JsonArray array = root.getAsJsonArray("seenPages");
                for (JsonElement element : array) {
                    if (element != null && element.isJsonPrimitive()) {
                        String id = element.getAsString();
                        if (id != null && !id.isBlank()) {
                            seen.add(id);
                        }
                    }
                }
            }
            return new Snapshot(completed, seen, showcase, optOut);
        } catch (Exception e) {
            // Corrupt file: back to the fresh-install state, which shows the wizard again. Not
            // rethrown - a parse error must not become a startup crash, and must not become a
            // silent "already seen" either.
            return blank();
        }
    }

    /**
     * Writes the file.
     *
     * <p>Through a temporary file moved into place, so a crash mid-write leaves the previous file
     * intact. A truncated file reads as a fresh install, which is safe but would replay pages the
     * player has already worked through.
     */
    public void save(String accountId, Snapshot snapshot) throws IOException {
        JsonArray seen = new JsonArray();
        for (String id : snapshot.seenPages()) {
            seen.add(id);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        root.addProperty("accountId", accountId == null ? "" : accountId);
        root.addProperty("onboardingCompleted", snapshot.onboardingCompleted());
        root.addProperty("showcaseOptOut", snapshot.showcaseOptOut());
        if (snapshot.lastShowcaseSeen() != null) {
            root.addProperty("lastShowcaseSeen", snapshot.lastShowcaseSeen());
        }
        root.add("seenPages", seen);

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
