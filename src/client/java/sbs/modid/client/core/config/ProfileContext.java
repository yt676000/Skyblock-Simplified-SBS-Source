/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import sbs.modid.SkyblockSimplifiedSBS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks which <b>account</b> and <b>SkyBlock profile</b> the player is on, and points every
 * {@link ProfileScopedStore} at that profile's own folder – so switching profile or account never
 * means re-scanning storages / loadouts / … again.
 *
 * <pre>
 * config/sbs/Accounts/&lt;ign&gt;-&lt;uuid8&gt;/profile/&lt;cute&gt;/
 *   storage_cache.json   loadouts_cache.json   bazaar_orders.json   …
 * </pre>
 *
 * <p><b>Account</b> is the logged-in Minecraft account ({@code User.getName()} + the first 8 hex of
 * its UUID – readable and rename-proof). <b>Profile</b> is the SkyBlock cute name, read best-effort
 * from the tab list ("Profile: Apple"); until it is known everything lands under {@code default}, and
 * once it resolves the caches move to the real profile. Only the SBS <i>settings</i> (config.json,
 * HUD layout, keybinds, shared market/repo caches) stay global.
 *
 * <p><b>Switch handshake:</b> ticked once a second; on a change it {@link ProfileScopedStore#flushProfile()
 * flushes} every store to the CURRENT (old) folder, swaps the account/profile, then
 * {@link ProfileScopedStore#reloadProfile() reloads} them from the NEW folder.
 */
public final class ProfileContext {

    private static final ProfileContext INSTANCE = new ProfileContext();

    private static final long CHECK_INTERVAL_MS = 1000;
    private static final String DEFAULT = "default";
    /** Tab-list widget line "Profile: Apple" (cute names are a single word). */
    private static final Pattern PROFILE_TAB = Pattern.compile("(?i)Profile:\\s*([A-Za-z]+)");
    private static final String SECTION_SIGN = String.valueOf((char) 0x00A7);

    private final List<ProfileScopedStore> stores = new CopyOnWriteArrayList<>();
    private volatile String account = DEFAULT;
    private volatile String profile = DEFAULT;
    private boolean initialised;
    private long lastCheckAt;

    private ProfileContext() {
    }

    public static ProfileContext getInstance() {
        return INSTANCE;
    }

    public String account() {
        return account;
    }

    public String profile() {
        return profile;
    }

    /**
     * Whether a real (non-{@code default}) profile is current.
     *
     * <p>Since the remembered-profile bootstrap this is true from the first tick of any account that
     * has played before: the session starts on last session's profile instead of on an empty
     * {@code default}. That answer is a <b>well-founded guess</b>, not a confirmation — it is wrong
     * exactly when the player has just switched profiles, and corrects itself the moment the tab
     * list names the real one. Readers get last session's data for those seconds rather than no
     * data, which is the lesser wrong of the two: an empty record is also an answer, and features
     * acted on it with full confidence (a collected Fairy Soul drawn uncollected, a named loadout
     * shown as plain "Equipped").
     */
    public boolean known() {
        return !DEFAULT.equals(profile);
    }

    /** The current account+profile directory (created lazily by the stores when they write). */
    public Path dir() {
        return SBSFiles.accountsDir().resolve(account).resolve("profile").resolve(profile);
    }

    /** A file inside the current profile directory. */
    public Path file(String name) {
        return dir().resolve(name);
    }

    /**
     * Registers a profile-scoped store. If the context is already initialised the store is reloaded
     * immediately so it picks up the current profile's data even when registered late.
     */
    public void register(ProfileScopedStore store) {
        stores.add(store);
        if (initialised) {
            safe(store::reloadProfile);
        }
    }

    /** Ticked from the client tick (throttled): detect the current account/profile and react to changes. */
    public void tick(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckAt = now;

        String acc = detectAccount(minecraft);
        String prof = detectProfile(minecraft);   // null when unknown → keep the current one

        if (!initialised) {
            account = acc;
            // Until the tab list names the profile, start from the account's REMEMBERED one rather
            // than from 'default'. The tab row takes 13-61 seconds to appear after a join (measured
            // across five sessions), and for that whole window every profile-scoped store used to
            // answer from the empty default folder - the loadout widget said "Equipped" instead of
            // the loadout's name, storages read as unscanned, and whatever was captured in the
            // window was stranded under default/ forever. The remembered profile is last session's,
            // which is the right answer every time except immediately after switching profiles -
            // and that case corrects itself the moment the tab list is readable, exactly as before.
            profile = prof != null ? prof : rememberedProfile(acc);
            initialised = true;
            migrateLegacy();
            reloadAll();
            rememberProfile();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Profile] init account={} profile={}{}",
                    account, profile, prof == null && !DEFAULT.equals(profile) ? " (remembered)" : "");
            return;
        }

        boolean accChanged = !acc.equals(account);
        boolean profChanged = prof != null && !prof.equals(profile);
        if (accChanged || profChanged) {
            flushAll();                       // save to the CURRENT (old) folder
            account = acc;
            if (prof != null) {
                profile = prof;
            } else if (accChanged) {
                // A new account with the profile not readable yet: the OLD account's profile name
                // would be a guess about the wrong player entirely, so use what the new account
                // remembers instead (or default until its tab list answers).
                profile = rememberedProfile(acc);
            }
            migrateLegacy();
            reloadAll();                      // load from the NEW folder
            rememberProfile();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Profile] switch -> account={} profile={}", account, profile);
        }
    }

    // ------------------------------------------------------------------ remembered profile

    /** Where an account's last confirmed profile name is kept, one plain line. */
    private static Path rememberedProfileFile(String account) {
        return SBSFiles.accountsDir().resolve(account).resolve("last_profile.txt");
    }

    /** The profile this account was on last session, or {@code default} when nothing is recorded. */
    private static String rememberedProfile(String account) {
        if (DEFAULT.equals(account)) {
            return DEFAULT;
        }
        try {
            Path file = rememberedProfileFile(account);
            if (Files.exists(file)) {
                String stored = sanitize(Files.readString(file).trim());
                if (!stored.isEmpty()) {
                    return stored;
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Profile] could not read the remembered profile: {}",
                    e.toString());
        }
        return DEFAULT;
    }

    /** Records the current profile for the current account, so the next session starts on it. */
    private void rememberProfile() {
        if (DEFAULT.equals(account) || DEFAULT.equals(profile)) {
            return;   // nothing worth remembering: a guess must come from a confirmed session
        }
        try {
            Path file = rememberedProfileFile(account);
            SBSFiles.ensureParent(file);
            Files.writeString(file, profile);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Profile] could not remember the profile: {}",
                    e.toString());
        }
    }

    private void flushAll() {
        for (ProfileScopedStore store : stores) {
            safe(store::flushProfile);
        }
    }

    private void reloadAll() {
        for (ProfileScopedStore store : stores) {
            safe(store::reloadProfile);
        }
    }

    // ------------------------------------------------------------------ detection

    private static String detectAccount(Minecraft minecraft) {
        User user = minecraft.getUser();
        if (user == null) {
            return DEFAULT;
        }
        String name = sanitize(user.getName());
        UUID id = user.getProfileId();
        String uuid8 = id != null ? id.toString().replace("-", "") : "";
        uuid8 = uuid8.length() >= 8 ? uuid8.substring(0, 8) : (uuid8.isEmpty() ? "00000000" : uuid8);
        return (name.isEmpty() ? "player" : name) + "-" + uuid8;
    }

    /** The SkyBlock cute name from the tab list, or {@code null} when it cannot be read right now. */
    private static String detectProfile(Minecraft minecraft) {
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            return null;
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            Matcher matcher = PROFILE_TAB.matcher(display.getString().replace(SECTION_SIGN, ""));
            if (matcher.find()) {
                return sanitize(matcher.group(1));
            }
        }
        return null;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim().replaceAll(SECTION_SIGN + ".", "").replaceAll("[^A-Za-z0-9_.-]", "_");
        return out.length() > 40 ? out.substring(0, 40) : out;
    }

    // ------------------------------------------------------------------ legacy migration

    /**
     * Moves the pre-refactor global caches into the current profile folder, once. Gated on a real
     * (non-{@code default}) profile so the data lands under the profile it was captured on, and on
     * per-file "destination absent" checks so it is idempotent and safe to extend with more files.
     */
    private void migrateLegacy() {
        if (profile.equals(DEFAULT)) {
            return;   // wait for the real profile so legacy caches don't get stranded under 'default'
        }
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            moveIfAbsent(SBSFiles.guiDir().resolve("storage_cache.json"), dir.resolve("storage_cache.json"));
            moveIfAbsent(SBSFiles.guiDir().resolve("loadouts_cache.json"), dir.resolve("loadouts_cache.json"));

            // Bazaar orders used to live inside the global config.json – move them into the profile
            // file and clear the config field so the tracker reads them per profile from now on.
            SBSConfig config = ConfigManager.getInstance().get();
            Path bazaarFile = dir.resolve("bazaar_orders.json");
            if (config.bazaarOrders != null && !config.bazaarOrders.isEmpty() && !Files.exists(bazaarFile)) {
                SBSFiles.ensureParent(bazaarFile);
                try (var writer = Files.newBufferedWriter(bazaarFile)) {
                    SBSFiles.GSON.toJson(config.bazaarOrders, writer);
                }
                config.bazaarOrders = new java.util.ArrayList<>();
                ConfigManager.getInstance().save();
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Profile] migrated bazaar orders -> {}", bazaarFile);
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Profile] legacy migration failed: {}", e.toString());
        }
    }

    private static void moveIfAbsent(Path from, Path to) throws java.io.IOException {
        if (Files.exists(from) && !Files.exists(to)) {
            SBSFiles.ensureParent(to);
            Files.move(from, to);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Profile] migrated {} -> {}", from.getFileName(), to);
        }
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Profile] store hook failed: {}", t.toString());
        }
    }
}
