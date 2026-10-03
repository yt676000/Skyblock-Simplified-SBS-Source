/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.item.SkyblockItem;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.helper.scoreboard.ScoreboardElements;
import sbs.modid.client.helper.scoreboard.ScoreboardLine;
import sbs.modid.client.helper.scoreboard.ScoreboardReader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Layout Recorder (developer tool): stores every <b>unique</b> Hypixel screen layout once under
 * {@code Development_Stuff/layouts/} - container menus, tab widgets, scoreboard elements and the
 * action bar's shape. "Unique" means unique after {@link LayoutSignature} has replaced the values,
 * so a changed purse, date or timer is not a new layout. See {@code docs/features/layout-recorder.md}.
 *
 * <p><b>Off costs one check.</b> Every entry point starts with {@link #on()}: dev mode and the
 * setting, both plain fields. Nothing is scanned, loaded or written while it is off, and the store
 * is created on first use.
 *
 * <p><b>A menu is captured once it has settled</b> - its state id unchanged for
 * {@value #SETTLE_TICKS} ticks - because Hypixel fills a menu over several ticks and every
 * half-filled frame would otherwise look like a new variant.
 */
public final class LayoutRecorder {

    private static final LayoutRecorder INSTANCE = new LayoutRecorder();

    private static final int SETTLE_TICKS = 5;
    private static final long SIDEBAR_TAB_MS = 5_000L;
    private static final long ACTION_BAR_MS = 500L;
    private static final long PLAYERS_MS = 5_000L;

    /** Your own money lines: values replaced in the stored raw example too. */
    private static final Pattern OWN_MONEY = Pattern.compile(
            "(?i)^(?:purse|piggy|bank|interest|bits|copper|motes|gems|north stars)\\b.*");

    private LayoutStore store;
    private AbstractContainerScreen<?> lastScreen;
    private int lastState = -1;
    private int stableTicks;
    private boolean captured;
    private long lastSidebarTabAt;
    private long lastActionBarAt;
    private String lastActionBar = "";
    private Set<String> players = Set.of();
    private long playersAt;

    private LayoutRecorder() {
    }

    public static LayoutRecorder getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.DevSettings cfg() {
        return ConfigManager.getInstance().get().dev;
    }

    /** The one check every hook makes first. */
    private static boolean on() {
        return DevMode.ACTIVE && cfg().recordLayouts;
    }

    /** Whether the recorder runs - the gate {@link ScreenOpeners} shares. */
    static boolean isOn() {
        return on();
    }

    /** Player names on the tab list replaced, as in every stored example. */
    static String redactText(String text) {
        return text == null ? "" : LayoutSignature.redactPlayers(text, INSTANCE.players());
    }

    private LayoutStore store() {
        if (store == null) {
            store = new LayoutStore();
        }
        return store;
    }

    // ------------------------------------------------------------------ hooks

    /** Client tick (GuiTrackingMixin, next to MenuProbe). */
    public void tick(Minecraft minecraft) {
        if (!on()) {
            return;
        }
        long now = System.currentTimeMillis();
        var current = GuiStateManager.getInstance().getCurrentScreen();
        ScreenOpeners.getInstance().tick(current, current instanceof AbstractContainerScreen<?>);
        tickMenu();
        if (store != null) {
            store.tick(now);
        }
        if (now - lastSidebarTabAt >= SIDEBAR_TAB_MS && minecraft.player != null) {
            lastSidebarTabAt = now;
            recordTab();
            recordScoreboard();
        }
    }

    /** The action bar (HudMixin.setOverlayMessage). */
    public void onActionBar(String raw) {
        if (!on() || raw == null || raw.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (raw.equals(lastActionBar) || now - lastActionBarAt < ACTION_BAR_MS) {
            return;
        }
        lastActionBar = raw;
        lastActionBarAt = now;
        // The shape: the bar's segments (Hypixel separates them with runs of spaces), normalised.
        List<String> segments = new ArrayList<>();
        List<String> redacted = new ArrayList<>();
        for (String part : LayoutSignature.stripCodes(raw).trim().split("\\s{2,}")) {
            segments.add(LayoutSignature.normalise(part, players()));
            redacted.add(LayoutSignature.redactPlayers(part, players()));
        }
        JsonArray example = new JsonArray();
        redacted.forEach(example::add);
        record("actionbar", null, "actionbar:\n" + String.join("\n", segments), null, List.of(), example);
    }

    // ------------------------------------------------------------------ menus

    private void tickMenu() {
        if (!(GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen<?> screen)) {
            lastScreen = null;
            return;
        }
        int state = screen.getMenu().getStateId();
        if (screen != lastScreen || state != lastState) {
            lastScreen = screen;
            lastState = state;
            stableTicks = 0;
            captured = false;
            return;
        }
        if (captured || ++stableTicks < SETTLE_TICKS) {
            return;
        }
        captured = true;
        recordMenu(screen);
    }

    private void recordMenu(AbstractContainerScreen<?> screen) {
        String title = screen.getTitle() == null ? "" : screen.getTitle().getString();
        Set<String> names = players();
        List<LayoutCanon.Slot> slots = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;   // the player's own inventory is not the menu's layout
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String id = SkyblockItem.id(stack);
            if (id == null || id.isEmpty()) {
                id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            }
            slots.add(new LayoutCanon.Slot(slot.index, id, stack.getHoverName().getString(),
                    ItemPriceKey.lore(stack), stack.getCount(), stack.hasFoil()));
        }
        List<String> parts = new ArrayList<>(slots.size());
        JsonArray example = new JsonArray();
        for (LayoutCanon.Slot slot : slots) {
            parts.add(LayoutCanon.slotFine(slot, names));
            JsonObject s = new JsonObject();
            s.addProperty("index", slot.index());
            s.addProperty("id", slot.id());
            s.addProperty("name", redact(slot.name(), names));
            JsonArray lore = new JsonArray();
            slot.lore().forEach(line -> lore.add(redact(line, names)));
            s.add("lore", lore);
            s.addProperty("count", slot.count());
            s.addProperty("glint", slot.glint());
            example.add(s);
        }
        JsonObject raw = new JsonObject();
        raw.addProperty("title", redact(title, names));
        raw.add("slots", example);
        JsonObject origin = ScreenOpeners.getInstance().originFor(screen, System.currentTimeMillis());
        SBSConfig.DevSettings cfg = cfg();
        store().record("menus", LayoutSignature.slug(title), LayoutCanon.menuFine(title, slots, names),
                LayoutCanon.menuCoarse(title, slots, names), parts, raw, where(), version(),
                Math.max(1, cfg.layoutVariantCap), Math.max(1, cfg.layoutMaxMb) * 1024L * 1024L,
                origin, redact(title, names), Math.max(1, cfg.layoutOriginCap));
    }

    // ------------------------------------------------------------------ tab + scoreboard

    private void recordTab() {
        Set<String> names = players();
        for (LayoutCanon.Section section : LayoutCanon.tabSections(TabWidgets.lines())) {
            JsonArray example = new JsonArray();
            section.lines().forEach(line -> example.add(redact(line, names)));
            record("tab", section.key(), LayoutCanon.sectionCanon(section, names), null, List.of(), example);
        }
    }

    private void recordScoreboard() {
        List<ScoreboardLine> lines = ScoreboardReader.lines();
        if (lines.isEmpty()) {
            return;
        }
        Set<String> names = players();
        List<String> ids = ScoreboardElements.idsFor(lines);
        // The order of elements is a layout of its own; each element's wording is another.
        record("scoreboard", "_order", "scoreboard-order:\n" + String.join("\n", ids), null, List.of(),
                idsArray(ids));
        for (int i = 0; i < lines.size() && i < ids.size(); i++) {
            String id = ids.get(i);
            String text = lines.get(i).stripped();
            JsonArray example = new JsonArray();
            example.add(redact(text, names));
            record("scoreboard", LayoutSignature.slug(id.replace("sbs:", "")),
                    "scoreboard:" + id + "\n" + LayoutSignature.normalise(text, names), null, List.of(), example);
        }
    }

    private static JsonArray idsArray(List<String> ids) {
        JsonArray out = new JsonArray();
        ids.forEach(out::add);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private void record(String category, String group, String fine, String coarse, List<String> parts,
                        com.google.gson.JsonElement raw) {
        SBSConfig.DevSettings cfg = cfg();
        store().record(category, group, fine, coarse, parts, raw, where(), version(),
                Math.max(1, cfg.layoutVariantCap), Math.max(1, cfg.layoutMaxMb) * 1024L * 1024L,
                null, null, 0);
    }

    /** Players redacted; your own money lines keep their words but lose their numbers. */
    private static String redact(String line, Set<String> names) {
        String plain = LayoutSignature.redactPlayers(line, names);
        return OWN_MONEY.matcher(plain.trim()).matches() ? LayoutSignature.normalise(plain, names) : plain;
    }

    /** Names on the tab list plus your own, refreshed every few seconds. */
    private Set<String> players() {
        long now = System.currentTimeMillis();
        if (now - playersAt < PLAYERS_MS) {
            return players;
        }
        playersAt = now;
        Set<String> names = new HashSet<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            names.add(mc.player.getGameProfile().name());
        }
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().name();
                // Hypixel's widget rows are fake players named like "!C-a"; only real names count.
                if (TabWidgets.isPlayer(info) && name != null && name.matches("[A-Za-z0-9_]{3,16}")) {
                    names.add(name);
                }
            }
        }
        players = Set.copyOf(names);
        return players;
    }

    private static String where() {
        String island = SkyBlockLocation.island();
        String zone = SkyBlockLocation.zone();
        return (island == null ? "" : island) + (zone == null || zone.isEmpty() ? "" : " / " + zone);
    }

    private static String version() {
        return FabricLoader.getInstance().getModContainer("skyblock-simplified-sbs")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    // ------------------------------------------------------------------ settings actions

    /** Deletes everything recorded (after the settings row's two-step confirm). */
    public void clear() {
        store().clear();
        lastScreen = null;
        lastActionBar = "";
    }

    /** Opens the layouts folder in the system file browser, creating it if needed. */
    public void openFolder() {
        try {
            java.nio.file.Files.createDirectories(LayoutStore.dir());
            net.minecraft.util.Util.getPlatform().openPath(LayoutStore.dir());
        } catch (Exception e) {
            sbs.modid.SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Layouts] could not open {}: {}",
                    LayoutStore.dir(), e.toString());
        }
    }
}
