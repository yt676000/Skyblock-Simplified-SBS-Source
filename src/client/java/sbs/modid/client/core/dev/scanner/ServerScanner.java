/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev.scanner;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.dev.DevLogText;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.dev.LayoutRecorder;
import sbs.modid.client.core.dev.LayoutSignature;
import sbs.modid.client.core.dev.MenuReads;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.perf.Perf;
import sbs.modid.client.core.perf.TierScheduler;
import sbs.modid.client.core.tab.TabWidgets;
import sbs.modid.client.helper.scoreboard.ScoreboardLine;
import sbs.modid.client.helper.scoreboard.ScoreboardReader;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server Scanner (developer tool): a passive recorder of what the server sends - every menu with a
 * slot-level time series, optionally chat, action bar, scoreboard and tab list - plus the player's
 * own clicks in those menus, written as JSONL per session. See {@code docs/features/server-scanner.md}.
 *
 * <p><b>Off costs one check.</b> Every hook starts with {@link DevMode#ACTIVE}, then whether a
 * session runs; nothing is read or allocated before both pass.
 *
 * <p><b>Strictly passive.</b> The hooks observe at {@code TAIL} / {@code RETURN} (the player close at
 * {@code HEAD}, before the menu it names is gone); nothing is cancelled, no packet is changed, nothing
 * is sent, nothing is clicked.
 *
 * <p><b>Threads.</b> Every entry point runs on the client thread: the {@code ClientPacketListener}
 * handlers re-dispatch themselves before {@code TAIL} is reached. Items are read into
 * {@link ScanSlot}s right there; file writes go out batched through {@link ScanWriter}.
 */
public final class ServerScanner {

    /** The optional channels; menus are always recorded. */
    public enum Channel {
        CHAT("chat"), ACTIONBAR("actionbar"), SCOREBOARD("scoreboard"), TABLIST("tablist");

        final String key;

        Channel(String key) {
            this.key = key;
        }
    }

    private static final int MANIFEST_VERSION = 1;

    /** How often the scoreboard and tab list are compared with what was last written. */
    private static final long POLL_MS = 250L;

    private static final DateTimeFormatter FOLDER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final DateTimeFormatter WALL =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    /** Read first by every hook after {@link DevMode#ACTIVE}. */
    private static volatile boolean running;

    private static Session session;

    private ServerScanner() {
    }

    public static boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ hooks (client thread)

    /** {@code ClientPacketListener.handleOpenScreen}, TAIL. */
    public static void onOpenScreen(ClientboundOpenScreenPacket packet) {
        if (!DevMode.ACTIVE || !running) {
            return;
        }
        String type = String.valueOf(BuiltInRegistries.MENU.getKey(packet.getType()));
        session.open(packet.getContainerId(), MenuReads.plain(packet.getTitle()), type, liveMenu(packet.getContainerId()));
    }

    /** {@code ClientPacketListener.handleContainerContent}, TAIL. */
    public static void onContainerContent(ClientboundContainerSetContentPacket packet) {
        if (!DevMode.ACTIVE || !running) {
            return;
        }
        session.content(packet.containerId(), packet.stateId(), packet.items());
    }

    /** {@code ClientPacketListener.handleContainerSetSlot}, TAIL. */
    public static void onContainerSetSlot(ClientboundContainerSetSlotPacket packet) {
        if (!DevMode.ACTIVE || !running) {
            return;
        }
        session.slot(packet.getContainerId(), packet.getStateId(), packet.getSlot(), packet.getItem());
    }

    /** {@code ClientPacketListener.handleContainerClose}, TAIL. */
    public static void onServerClose(int containerId) {
        if (!DevMode.ACTIVE || !running) {
            return;
        }
        session.close(containerId, "server");
    }

    /** {@code LocalPlayer.closeContainer}, HEAD - the player (or a client screen) closed the menu. */
    public static void onPlayerClose(int containerId) {
        if (!DevMode.ACTIVE || !running) {
            return;
        }
        session.close(containerId, "player");
    }

    /** {@code MultiPlayerGameMode.handleContainerInput}, RETURN - observed, never changed. */
    public static void onClick(int containerId, int slot, int button, ContainerInput input) {
        if (!DevMode.ACTIVE || !running) {
            return;
        }
        session.click(containerId, slot, button, input);
    }

    /** Every chat line, from {@code ChatProbe.onChat}. */
    public static void onChat(String text, Component message) {
        if (!DevMode.ACTIVE || !running) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            // A mod that adds a chat line from its own thread: the buffers belong to the client thread.
            minecraft.execute(() -> onChat(text, message));
            return;
        }
        session.chat(text, message);
    }

    /** The action bar, from {@code HudMixin.setOverlayMessage} (the Layout Recorder's read point). */
    public static void onActionBar(Component message) {
        if (!DevMode.ACTIVE || !running || message == null) {
            return;
        }
        session.actionBar(message);
    }

    /** Client tick (GuiTrackingMixin). */
    public static void tick(Minecraft minecraft) {
        if (!running) {
            return;
        }
        try (Perf.Section perf = Perf.tick("dev.serverScanner")) {
            if (!DevMode.ACTIVE) {
                stop("developer mode was switched off");
                return;
            }
            session.tick(minecraft);
        }
    }

    // ------------------------------------------------------------------ commands

    /** {@code /sbs scan [start|stop|status] [chat] [actionbar] [scoreboard] [tablist] [all]}. */
    public static void handleCommand(String argument) {
        String[] words = (argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT)).split("\\s+");
        switch (words[0]) {
            case "start", "on" -> start(Arrays.copyOfRange(words, 1, words.length));
            case "stop", "off" -> {
                if (running) {
                    stop("/sbs scan stop");
                } else {
                    say("§7Server Scanner is not running.");
                }
            }
            default -> status();
        }
    }

    /** The Developer module's toggle: start with the module's channel toggles, or stop. */
    public static void toggle() {
        if (running) {
            stop("Developer module toggle");
        } else {
            start(new String[0]);
        }
    }

    /** {@code /sbs logmenu}: the open menu once, in full - into the session, or a file of its own. */
    public static void logMenu() {
        if (!(GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen<?> screen)) {
            say("§7Open the menu you want logged and run this again.");
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        String title = MenuReads.plain(screen.getTitle());
        if (running) {
            session.snapshot(menu, title, "/sbs logmenu");
            say("§aMenu logged §8-> §f" + session.currentFileName());
            return;
        }
        // No session: a one-off file with the same open + snapshot lines a session would hold.
        Session once = new Session(SBSFiles.scannerDir(), EnumSet.noneOf(Channel.class), Integer.MAX_VALUE);
        Path file = SBSFiles.scannerDir().resolve("logmenu_" + LocalDateTime.now().format(FOLDER) + "_"
                + ScanNames.slug(title) + ".jsonl");
        once.snapshotTo(file, menu, title, "/sbs logmenu");
        once.writer.flush(System.currentTimeMillis());
        say("§aMenu logged §8-> §f" + file);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Scanner] /sbs logmenu -> {}", file);
    }

    private static void start(String[] channelWords) {
        if (running) {
            say("§7Server Scanner is already running §8-> §f" + session.dir);
            return;
        }
        SBSConfig.DevSettings cfg = ConfigManager.getInstance().get().dev;
        Set<Channel> channels = EnumSet.noneOf(Channel.class);
        if (channelWords.length == 0) {
            if (cfg.scannerChat) {
                channels.add(Channel.CHAT);
            }
            if (cfg.scannerActionBar) {
                channels.add(Channel.ACTIONBAR);
            }
            if (cfg.scannerScoreboard) {
                channels.add(Channel.SCOREBOARD);
            }
            if (cfg.scannerTablist) {
                channels.add(Channel.TABLIST);
            }
        }
        for (String word : channelWords) {
            if (word.equals("all")) {
                channels.addAll(EnumSet.allOf(Channel.class));
                continue;
            }
            boolean known = false;
            for (Channel channel : Channel.values()) {
                if (channel.key.equals(word)) {
                    channels.add(channel);
                    known = true;
                }
            }
            if (!known && !word.equals("menus")) {
                say("§cUnknown channel §f" + word + "§c - use chat, actionbar, scoreboard, tablist or all.");
                return;
            }
        }
        Path dir = SBSFiles.scannerDir().resolve(LocalDateTime.now().format(FOLDER));
        session = new Session(dir, channels, Math.max(1, cfg.scannerMaxMb));
        running = true;
        session.writeManifest();
        say("§aServer Scanner started §8-> §f" + dir);
        say("§7Channels: §fmenus" + session.channelList(", ") + "§7. §f/sbs scan stop§7 when done.");
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Scanner] Session started -> {} (channels: menus{})",
                dir, session.channelList(", "));
        // A menu that is already open is picked up now, so starting inside it does not need a reopen.
        if (GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen<?> screen
                && screen.getMenu().containerId != 0) {
            session.snapshot(screen.getMenu(), MenuReads.plain(screen.getTitle()), "open when the session started");
        }
    }

    private static void stop(String reason) {
        if (!running) {
            return;
        }
        running = false;
        Session ended = session;
        session = null;
        ended.finish(reason);
        say("§7Server Scanner stopped (" + reason + ") after §f" + ended.events + "§7 event(s), §f"
                + mb(ended.budget.usedBytes()) + "§7 MB §8-> §f" + ended.dir);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Scanner] Session stopped ({}) after {} event(s), {} bytes -> {}",
                reason, ended.events, ended.budget.usedBytes(), ended.dir);
    }

    private static void status() {
        if (!running) {
            say("§7Server Scanner is off. §f/sbs scan start [chat] [actionbar] [scoreboard] [tablist] [all]");
            say("§8Sessions: " + SBSFiles.scannerDir());
            return;
        }
        say("§aServer Scanner is running §7(" + session.events + " event(s), " + mb(session.budget.usedBytes())
                + " of " + mb(session.budget.limitBytes()) + " MB) §8-> §f" + session.dir);
        say("§7Channels: §fmenus" + session.channelList(", ") + "§7. Menu: §f"
                + (session.menu == null ? "none" : session.menu.title));
    }

    /** The open menu, if the client already holds the container the packet names. */
    private static AbstractContainerMenu liveMenu(int containerId) {
        var player = Minecraft.getInstance().player;
        return player != null && player.containerMenu != null && player.containerMenu.containerId == containerId
                ? player.containerMenu : null;
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    private static String wall() {
        return LocalTime.now().format(WALL);
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }

    // ------------------------------------------------------------------ one session

    /** One open menu instance and the scanner's own copy of its slots. */
    private static final class MenuTrack {
        final int containerId;
        final String title;
        final String fileName;
        final Path file;
        final long openedNanos;
        ScanSlot[] slots = new ScanSlot[0];
        boolean hadContent;

        MenuTrack(int containerId, String title, String fileName, Path file) {
            this.containerId = containerId;
            this.title = title;
            this.fileName = fileName;
            this.file = file;
            this.openedNanos = System.nanoTime();
        }

        long t() {
            return (System.nanoTime() - openedNanos) / 1_000_000L;
        }

        ScanSlot get(int index) {
            return index >= 0 && index < slots.length ? slots[index] : null;
        }

        void set(int index, ScanSlot slot) {
            if (index < 0) {
                return;
            }
            if (index >= slots.length) {
                slots = Arrays.copyOf(slots, index + 1);
            }
            slots[index] = slot;
        }
    }

    private static final class Session {
        final Path dir;
        final Set<Channel> channels;
        final ScanBudget budget;
        final ScanWriter writer = new ScanWriter();
        final Instant started = Instant.now();
        final long startedNanos = System.nanoTime();
        final JsonObject manifest = new JsonObject();
        final JsonArray menus = new JsonArray();
        long events;
        MenuTrack menu;
        String lastActionBar;
        List<String> lastScoreboard = List.of();
        List<String> lastTab = List.of();
        String lastFooter;
        long lastPollAt;

        Session(Path dir, Set<Channel> channels, int limitMb) {
            this.dir = dir;
            this.channels = channels;
            this.budget = new ScanBudget(limitMb);
            manifest.addProperty("version", MANIFEST_VERSION);
            manifest.addProperty("started", started.toString());
            manifest.addProperty("mod", version("skyblock-simplified-sbs"));
            manifest.addProperty("mc", version("minecraft"));
            manifest.addProperty("island", SkyBlockLocation.island());
            manifest.addProperty("zone", SkyBlockLocation.zone());
            JsonArray list = new JsonArray();
            list.add("menus");
            channels.forEach(c -> list.add(c.key));
            manifest.add("channels", list);
            SBSConfig.ExperimentationSettings exp = ConfigManager.getInstance().get().experimentation;
            manifest.addProperty("experimentationHelpers",
                    exp.enabled && (exp.chronomatron || exp.ultrasequencer || exp.superpairs));
            JsonObject helpers = new JsonObject();
            helpers.addProperty("enabled", exp.enabled);
            helpers.addProperty("chronomatron", exp.chronomatron);
            helpers.addProperty("ultrasequencer", exp.ultrasequencer);
            helpers.addProperty("superpairs", exp.superpairs);
            helpers.addProperty("blockMisclicks", exp.blockMisclicks);
            manifest.add("experimentation", helpers);
            manifest.add("menus", menus);
        }

        String channelList(String separator) {
            StringBuilder out = new StringBuilder();
            channels.forEach(c -> out.append(separator).append(c.key));
            return out.toString();
        }

        String currentFileName() {
            return menu == null ? "(no menu)" : menu.fileName;
        }

        // -------------------------------------------------------------- menus

        void open(int containerId, String title, String menuType, AbstractContainerMenu live) {
            if (menu != null) {
                closeTracked("replaced");
            }
            LocalTime now = LocalTime.now();
            String fileName = ScanNames.menuFile(title, now, containerId);
            menu = new MenuTrack(containerId, title, fileName, dir.resolve(fileName));
            String catalog = ScanNames.catalog(title);
            JsonObject event = event("open");
            event.addProperty("containerId", containerId);
            event.addProperty("menuType", menuType);
            event.addProperty("title", title);
            event.addProperty("slots", live == null ? -1 : live.slots.size());
            event.addProperty("menuSlots", live == null ? -1 : MenuReads.containerSlots(live));
            event.addProperty("catalog", catalog);
            event.addProperty("opened", Instant.now().toString());
            write(menu.file, event);

            JsonObject entry = new JsonObject();
            entry.addProperty("file", fileName);
            entry.addProperty("title", title);
            entry.addProperty("catalog", catalog);
            entry.addProperty("containerId", containerId);
            entry.addProperty("menuType", menuType);
            entry.addProperty("opened", Instant.now().toString());
            menus.add(entry);
            writeManifest();
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][Scanner] Menu file opened: {}", fileName);
        }

        void content(int containerId, int stateId, List<ItemStack> items) {
            if (menu == null || containerId != menu.containerId) {
                return;   // the player inventory (id 0) or a stale container
            }
            ScanSlot[] next = ScanReads.readAll(items);
            JsonObject event = event("content");
            event.addProperty("stateId", stateId);
            event.addProperty("size", next.length);
            if (!menu.hadContent) {
                JsonArray full = new JsonArray();
                for (int i = 0; i < next.length; i++) {
                    if (next[i] != null) {
                        JsonObject slot = new JsonObject();
                        slot.addProperty("slot", i);
                        next[i].toJson().entrySet().forEach(e -> slot.add(e.getKey(), e.getValue()));
                        full.add(slot);
                    }
                }
                event.add("items", full);
                menu.hadContent = true;
            } else {
                JsonArray changes = new JsonArray();
                int unchanged = 0;
                for (int i = 0; i < Math.max(next.length, menu.slots.length); i++) {
                    ScanSlot before = menu.get(i);
                    ScanSlot after = i < next.length ? next[i] : null;
                    if (ScanSlot.same(before, after)) {
                        unchanged++;
                        continue;
                    }
                    JsonObject change = new JsonObject();
                    change.addProperty("slot", i);
                    change.add("old", ScanSlot.oldFields(before, after));
                    change.add("new", ScanSlot.json(after));
                    changes.add(change);
                }
                event.add("changes", changes);
                event.addProperty("unchanged", unchanged);
            }
            menu.slots = next;
            write(menu.file, event);
        }

        void slot(int containerId, int stateId, int index, ItemStack stack) {
            if (menu == null || containerId != menu.containerId) {
                return;   // the cursor (-1), the player inventory (0) or a stale container
            }
            ScanSlot after = ScanReads.read(stack);
            ScanSlot before = menu.get(index);
            JsonObject event = event("slot");
            event.addProperty("stateId", stateId);
            event.addProperty("slot", index);
            event.add("old", ScanSlot.oldFields(before, after));
            event.add("new", ScanSlot.json(after));
            menu.set(index, after);
            write(menu.file, event);
        }

        void click(int containerId, int slot, int button, ContainerInput input) {
            if (menu == null || containerId != menu.containerId) {
                return;
            }
            JsonObject event = event("click");
            event.addProperty("containerId", containerId);
            event.addProperty("slot", slot);
            event.addProperty("button", button);
            event.addProperty("clickType", input == null ? null : input.name());
            ScanSlot clicked = menu.get(slot);
            event.addProperty("itemId", clicked == null ? null : clicked.id());
            write(menu.file, event);
        }

        void close(int containerId, String by) {
            if (menu != null && containerId == menu.containerId) {
                closeTracked(by);
            }
        }

        private void closeTracked(String by) {
            JsonObject event = event("close");
            event.addProperty("by", by);
            write(menu.file, event);
            menu = null;
        }

        /**
         * A full snapshot of a live menu. Starts tracking it first when the session is not tracking
         * that container yet (a menu open before {@code /sbs scan start}), and then also adopts the
         * snapshot as the slot state the next diffs are taken against.
         */
        void snapshot(AbstractContainerMenu live, String title, String reason) {
            boolean adopt = menu == null || menu.containerId != live.containerId;
            if (adopt) {
                open(live.containerId, title, MenuReads.menuType(live), live);
            }
            ScanSlot[] slots = liveSlots(live);
            write(menu.file, snapshotEvent(slots, live, reason, menu.t()));
            if (adopt) {
                menu.slots = slots;
                menu.hadContent = true;
            }
        }

        /** The one-off {@code /sbs logmenu} form: an open line and a snapshot line in a file of their own. */
        void snapshotTo(Path file, AbstractContainerMenu live, String title, String reason) {
            JsonObject open = new JsonObject();
            open.addProperty("t", 0);
            open.addProperty("wall", wall());
            open.addProperty("type", "open");
            open.addProperty("containerId", live.containerId);
            open.addProperty("menuType", MenuReads.menuType(live));
            open.addProperty("title", title);
            open.addProperty("slots", live.slots.size());
            open.addProperty("menuSlots", MenuReads.containerSlots(live));
            open.addProperty("catalog", ScanNames.catalog(title));
            write(file, open);
            write(file, snapshotEvent(liveSlots(live), live, reason, 0));
        }

        private JsonObject snapshotEvent(ScanSlot[] slots, AbstractContainerMenu live, String reason, long t) {
            JsonObject event = new JsonObject();
            event.addProperty("t", t);
            event.addProperty("wall", wall());
            event.addProperty("type", "snapshot");
            event.addProperty("reason", reason);
            event.addProperty("stateId", live.getStateId());
            JsonArray items = new JsonArray();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] != null) {
                    JsonObject slot = new JsonObject();
                    slot.addProperty("slot", i);
                    slot.addProperty("menuSlot", MenuReads.isMenuSlot(live.slots.get(i)));
                    slots[i].toJson().entrySet().forEach(e -> slot.add(e.getKey(), e.getValue()));
                    items.add(slot);
                }
            }
            event.add("items", items);
            return event;
        }

        private static ScanSlot[] liveSlots(AbstractContainerMenu live) {
            ScanSlot[] slots = new ScanSlot[live.slots.size()];
            int i = 0;
            for (Slot slot : live.slots) {
                slots[i++] = ScanReads.read(slot.getItem());
            }
            return slots;
        }

        // -------------------------------------------------------------- text channels

        void chat(String text, Component message) {
            if (!channels.contains(Channel.CHAT)) {
                return;
            }
            Set<String> players = LayoutRecorder.knownPlayers();
            write(dir.resolve("chat.jsonl"), textEvent(
                    LayoutSignature.redactPlayersKeepCodes(DevLogText.legacy(message), players),
                    LayoutSignature.redactPlayers(text == null ? "" : text, players)));
        }

        void actionBar(Component message) {
            if (!channels.contains(Channel.ACTIONBAR)) {
                return;
            }
            String raw = DevLogText.legacy(message);
            if (raw == null || raw.equals(lastActionBar)) {
                return;
            }
            lastActionBar = raw;
            Set<String> players = LayoutRecorder.knownPlayers();
            write(dir.resolve("actionbar.jsonl"), textEvent(
                    LayoutSignature.redactPlayersKeepCodes(raw, players),
                    LayoutSignature.redactPlayers(message.getString(), players)));
        }

        private void pollScoreboard() {
            List<String> raw = new ArrayList<>();
            for (ScoreboardLine line : ScoreboardReader.lines()) {
                raw.add(DevLogText.legacy(line.display()));
            }
            if (raw.equals(lastScoreboard)) {
                return;
            }
            lastScoreboard = raw;
            Set<String> players = LayoutRecorder.knownPlayers();
            JsonObject event = sessionEvent();
            event.addProperty("title", MenuReads.plain(ScoreboardReader.title()));
            event.add("raw", redactedRaw(raw, players));
            event.add("plain", redactedPlain(raw, players));
            write(dir.resolve("scoreboard.jsonl"), event);
        }

        private void pollTab() {
            List<String> raw = new ArrayList<>();
            for (Component line : TabWidgets.components()) {
                raw.add(DevLogText.legacy(line));
            }
            String footer = TabWidgets.footerRaw();
            if (raw.equals(lastTab) && footer.equals(lastFooter)) {
                return;
            }
            lastTab = raw;
            lastFooter = footer;
            Set<String> players = LayoutRecorder.knownPlayers();
            JsonObject event = sessionEvent();
            event.add("raw", redactedRaw(raw, players));
            event.add("plain", redactedPlain(raw, players));
            event.addProperty("footerRaw", LayoutSignature.redactPlayersKeepCodes(footer, players));
            event.addProperty("footer", LayoutSignature.redactPlayers(footer, players));
            write(dir.resolve("tablist.jsonl"), event);
        }

        private static JsonArray redactedRaw(List<String> lines, Set<String> players) {
            JsonArray out = new JsonArray();
            lines.forEach(line -> out.add(LayoutSignature.redactPlayersKeepCodes(line, players)));
            return out;
        }

        private static JsonArray redactedPlain(List<String> lines, Set<String> players) {
            JsonArray out = new JsonArray();
            lines.forEach(line -> out.add(LayoutSignature.redactPlayers(line, players)));
            return out;
        }

        private JsonObject textEvent(String raw, String plain) {
            JsonObject event = sessionEvent();
            event.addProperty("raw", raw);
            event.addProperty("plain", plain);
            return event;
        }

        // -------------------------------------------------------------- tick, writing, end

        void tick(Minecraft minecraft) {
            String failure = writer.failure();
            if (failure != null) {
                stop("write failed: " + failure);
                return;
            }
            if (budget.exceeded()) {
                say("§eServer Scanner reached its size cap (" + mb(budget.limitBytes()) + " MB).");
                SkyblockSimplifiedSBS.LOGGER.info("[SBS][Scanner] Size cap of {} bytes reached", budget.limitBytes());
                stop("size cap reached");
                return;
            }
            // A tracked menu the client no longer holds: closed by a path neither close hook sees
            // (respawn, disconnect, another mod calling clientSideCloseContainer).
            if (menu != null && (minecraft.player == null || minecraft.player.containerMenu == null
                    || minecraft.player.containerMenu.containerId != menu.containerId)) {
                closeTracked("client");
            }
            long now = System.currentTimeMillis();
            if (minecraft.player != null && now - lastPollAt >= POLL_MS
                    && (channels.contains(Channel.SCOREBOARD) || channels.contains(Channel.TABLIST))
                    && Perf.allowTick("dev.serverScanner.poll", TierScheduler.Tier.BACKGROUND)) {
                lastPollAt = now;
                if (channels.contains(Channel.SCOREBOARD)) {
                    pollScoreboard();
                }
                if (channels.contains(Channel.TABLIST)) {
                    pollTab();
                }
            }
            if (writer.due(now)) {
                writer.flush(now);
            }
        }

        void finish(String reason) {
            if (menu != null) {
                closeTracked("session_end");
            }
            manifest.addProperty("stopped", Instant.now().toString());
            manifest.addProperty("stopReason", reason);
            manifest.addProperty("events", events);
            manifest.addProperty("bytes", budget.usedBytes());
            writer.flush(System.currentTimeMillis());
            writeManifest();
        }

        void writeManifest() {
            writer.replace(dir.resolve("session.json"),
                    new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(manifest) + "\n");
        }

        /** A menu event: {@code t} since the menu opened. */
        private JsonObject event(String type) {
            JsonObject event = new JsonObject();
            event.addProperty("t", menu == null ? 0 : menu.t());
            event.addProperty("wall", wall());
            event.addProperty("type", type);
            return event;
        }

        /** A text-channel event: {@code t} since the session started. */
        private JsonObject sessionEvent() {
            JsonObject event = new JsonObject();
            event.addProperty("wall", wall());
            event.addProperty("t", (System.nanoTime() - startedNanos) / 1_000_000L);
            return event;
        }

        private void write(Path file, JsonObject event) {
            events++;
            long bytes = writer.append(file, event.toString());
            // Counted here, acted on in the next tick: stopping inside a hook would end the session
            // between the two writes of one event (an open and its manifest entry).
            budget.add(bytes);
        }

        private static String version(String modId) {
            return FabricLoader.getInstance().getModContainer(modId)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        }
    }
}
