/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.fishing.logic;

import com.google.gson.JsonObject;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Bait Sack's favourite bait, per account + profile ({@code favourite_bait.json}). The sack is
 * opened rarely, and the favourite decides which bait every sack-fed cast is booked against, so it
 * has to outlive the session: without it the first session after a restart would book the casts
 * against whatever bait the sack happens to list first.
 *
 * <p>Client thread for reads and {@link #set}; the file write goes to {@link SbsExecutors#io()}.
 */
public final class FavouriteBaitStore implements ProfileScopedStore {

    // Above INSTANCE: the constructor registers with ProfileContext, which may load the profile now.
    private static final String FILE = "favourite_bait.json";

    private static final FavouriteBaitStore INSTANCE = new FavouriteBaitStore();

    private volatile String baitId;
    /** When the favourite was last read off the sack menu - observations newer than this win. */
    private volatile long readAt;
    private volatile boolean loaded;

    private FavouriteBaitStore() {
        ProfileContext.getInstance().register(this);
    }

    public static FavouriteBaitStore getInstance() {
        return INSTANCE;
    }

    /** The favourite bait id, or {@code null} when none has been read on this profile. */
    public String baitId() {
        ensureLoaded();
        return baitId;
    }

    /** Wall-clock of the read that set {@link #baitId()}, 0 when unknown. */
    public long readAt() {
        ensureLoaded();
        return readAt;
    }

    /** Records a favourite read off the open sack ({@code null} clears it); writes only on a change. */
    public void set(String id, long now) {
        ensureLoaded();
        readAt = now;
        if (java.util.Objects.equals(id, baitId)) {
            return;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Fishing] favourite bait {} -> {}", baitId, id);
        baitId = id;
        save();
    }

    private void ensureLoaded() {
        if (!loaded) {
            reloadProfile();
        }
    }

    @Override
    public void reloadProfile() {
        baitId = null;
        readAt = 0;
        try {
            Path path = ProfileContext.getInstance().file(FILE);
            if (Files.isRegularFile(path)) {
                JsonObject json = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8),
                        JsonObject.class);
                if (json != null && json.has("baitId") && !json.get("baitId").isJsonNull()) {
                    baitId = json.get("baitId").getAsString();
                    readAt = json.has("readAt") ? json.get("readAt").getAsLong() : 0;
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Fishing] could not read {} ({})", FILE, e.toString());
        }
        loaded = true;
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private void save() {
        // "default" is the placeholder before the profile context knows where it is.
        if (!ProfileContext.getInstance().known()) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("baitId", baitId);
        json.addProperty("readAt", readAt);
        String text = SBSFiles.GSON.toJson(json);
        Path path = ProfileContext.getInstance().file(FILE);
        SbsExecutors.io().execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, text, StandardCharsets.UTF_8);
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Fishing] could not write {}", FILE, e);
            }
        });
    }
}
