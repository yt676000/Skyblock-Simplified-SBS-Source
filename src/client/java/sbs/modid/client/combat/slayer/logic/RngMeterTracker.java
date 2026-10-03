/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.combat.slayer.logic;

import com.google.gson.reflect.TypeToken;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.combat.carry.model.SlayerBoss;
import sbs.modid.client.combat.slayer.model.RngMeterState;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Each slayer's RNG meter, per account and SkyBlock profile: read exactly from the
 * {@code <Boss> RNG Meter} menu slot, then moved by the chat line after every boss. Parsing is in
 * {@link RngMeterParser}; the gain and reset rules are in {@link RngMeterState}.
 *
 * <p>Fed by {@link SlayerTracker}: chat from its quest-complete handling (no second chat
 * listener) and the menu scan from its client tick.
 */
public final class RngMeterTracker implements ProfileScopedStore {

    private static final RngMeterTracker INSTANCE = new RngMeterTracker();
    private static final String FILE = "slayer_rng.json";

    /** How long after a {@code <Type> Slayer LVL} line its RNG line may follow. */
    private static final long LEVEL_LINE_WINDOW_MS = 5_000L;
    private static final long SCAN_INTERVAL_MS = 500L;

    private final Map<SlayerBoss, RngMeterState> states = new EnumMap<>(SlayerBoss.class);
    /** This session's known gains per slayer: {sum, count}. Not persisted. */
    private final Map<SlayerBoss, long[]> session = new EnumMap<>(SlayerBoss.class);
    private volatile boolean loaded;

    private SlayerBoss levelLineBoss;
    private long levelLineAt;
    private long lastScanAt;

    private RngMeterTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static RngMeterTracker getInstance() {
        return INSTANCE;
    }

    /** The meter of {@code boss}, or {@code null} when it has never been read on this profile. */
    public synchronized RngMeterState state(SlayerBoss boss) {
        ensureLoaded();
        return boss == null ? null : states.get(boss);
    }

    /** The slayer whose meter moved most recently (menu or chat), or {@code null}. */
    public synchronized SlayerBoss mostRecent() {
        ensureLoaded();
        SlayerBoss best = null;
        long bestAt = 0;
        for (var entry : states.entrySet()) {
            long at = Math.max(entry.getValue().menuAt, entry.getValue().chatAt);
            if (at > bestAt) {
                bestAt = at;
                best = entry.getKey();
            }
        }
        return best;
    }

    /** XP gained on {@code boss}'s meter this session (known gains only). */
    public synchronized long sessionGain(SlayerBoss boss) {
        long[] s = session.get(boss);
        return s == null ? 0 : s[0];
    }

    /**
     * The gain one boss is expected to add: this session's average, else the last known gain.
     * Depends on the tier killed, which is why every figure built on it is shown as an estimate.
     */
    public synchronized double averageGain(SlayerBoss boss) {
        long[] s = session.get(boss);
        if (s != null && s[1] > 0) {
            return (double) s[0] / s[1];
        }
        RngMeterState state = states.get(boss);
        return state == null ? -1 : state.lastGain;
    }

    // ------------------------------------------------------------------ chat

    /**
     * One stripped chat line from the slayer quest-complete block. Remembers the
     * {@code <Type> Slayer LVL} line and books the RNG line that follows it.
     */
    public synchronized void onChat(String plain, long now) {
        SlayerBoss boss = RngMeterParser.parseLevelLine(plain);
        if (boss != null) {
            levelLineBoss = boss;
            levelLineAt = now;
            return;
        }
        long total = RngMeterParser.parseChatStoredXp(plain);
        if (total < 0) {
            return;
        }
        if (levelLineBoss == null || now - levelLineAt > LEVEL_LINE_WINDOW_MS) {
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Slayer] RNG line with no slayer LVL line before it, not booked: '{}'", plain);
            return;
        }
        ensureLoaded();
        boolean exact = !RngMeterParser.chatTotalIsAbbreviated(plain);
        RngMeterState state = states.computeIfAbsent(levelLineBoss, b -> new RngMeterState());
        long before = state.current;
        long gain = state.applyChatTotal(total, exact, now);
        if (gain > 0) {
            long[] s = session.computeIfAbsent(levelLineBoss, b -> new long[2]);
            s[0] += gain;
            s[1]++;
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Slayer] RNG meter {}: {} -> {}{} (gain {})",
                levelLineBoss, before, total, exact ? "" : " (abbreviated)", gain < 0 ? "unknown" : gain);
        levelLineBoss = null;
        save();
    }

    // ------------------------------------------------------------------ menu

    /** Throttled scan of an open Slayer menu or boss page for the RNG meter slot. */
    public void onClientTick() {
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanAt = now;
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (!(screen instanceof AbstractContainerScreen<?> container) || !isSlayerMenu(screen)) {
            return;
        }
        AbstractContainerMenu menu = container.getMenu();
        int containerSlots = Math.max(0, menu.slots.size() - 36);   // skip the player inventory
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name = stack.getHoverName().getString();
            if (!name.endsWith("RNG Meter")) {
                continue;
            }
            RngMeterParser.MenuReading reading = RngMeterParser.parseMenu(name, loreOf(stack));
            if (reading != null) {
                applyMenu(reading, now);
            }
        }
    }

    private synchronized void applyMenu(RngMeterParser.MenuReading reading, long now) {
        ensureLoaded();
        RngMeterState state = states.computeIfAbsent(reading.boss(), b -> new RngMeterState());
        boolean changed = state.current != reading.current() || state.approximate
                || (reading.goal() > 0 && state.goal != reading.goal())
                || !java.util.Objects.equals(state.selectedDrop, reading.selectedDrop());
        if (changed) {
            // The "chat had" figure is what settles whether chat and menu agree after a kill.
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Slayer] RNG meter {} from the menu: {}/{} ({}%), drop '{}'"
                            + " - chat had {}", reading.boss(), reading.current(), reading.goal(),
                    reading.percent(), reading.selectedDrop(), state.current);
        }
        state.applyMenu(reading.current(), reading.goal(), reading.selectedDrop(), now);
        if (changed) {
            save();
        }
    }

    private static boolean isSlayerMenu(Screen screen) {
        String title = screen.getTitle() == null ? "" : screen.getTitle().getString()
                .replaceAll("(?i)§.", "").trim();
        if (title.equals("Slayer")) {
            return true;
        }
        for (SlayerBoss boss : SlayerBoss.values()) {
            if (title.equals(boss.displayName())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> loreOf(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> lines = new ArrayList<>();
        if (lore != null) {
            for (Component line : lore.lines()) {
                lines.add(line.getString());
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ persistence

    private Path file() {
        return ProfileContext.getInstance().file(FILE);
    }

    private void ensureLoaded() {
        if (!loaded) {
            reloadProfile();
        }
    }

    @Override
    public synchronized void reloadProfile() {
        states.clear();
        session.clear();
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    Map<String, RngMeterState> read = SBSFiles.GSON.fromJson(reader,
                            new TypeToken<HashMap<String, RngMeterState>>() { }.getType());
                    if (read != null) {
                        for (var entry : read.entrySet()) {
                            try {
                                states.put(SlayerBoss.valueOf(entry.getKey().toUpperCase(Locale.ROOT)),
                                        entry.getValue());
                            } catch (IllegalArgumentException unknown) {
                                // a slayer this build does not know - skipped, kept out of memory
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Slayer] could not read {} ({})", FILE, e.toString());
        }
        loaded = true;
    }

    @Override
    public void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private synchronized void save() {
        // "default" is the placeholder before the profile context knows where it is.
        if (ProfileContext.getInstance().profile().equals("default")) {
            return;
        }
        try {
            Map<String, RngMeterState> out = new HashMap<>();
            states.forEach((boss, state) -> out.put(boss.name(), state));
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(out), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Slayer] could not write {}", FILE, e);
        }
    }
}
