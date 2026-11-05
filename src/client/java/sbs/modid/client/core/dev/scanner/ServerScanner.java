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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
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
import sbs.modid.client.core.sound.SoundListeners;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server Scanner (developer tool): a passive recorder of what the server sends - every menu with a
 * slot-level time series, and on request chat, action bar, scoreboard, tab list, nearby entities,
 * damage, sounds, titles and boss bars, the inventory, particles, blocks and world changes - written
 * as JSONL per session. See {@code docs/features/server-scanner.md}.
 *
 * <p><b>Off costs one check.</b> Every hook starts with one static boolean - {@link DevMode#ACTIVE}
 * plus {@code running} for the menu and text hooks, one per-hook flag for the world hooks, which is
 * only ever true while a session that wants that hook runs. Nothing is read or allocated before it.
 *
 * <p><b>Strictly passive.</b> The hooks observe; nothing is cancelled, no packet is changed, nothing
 * is sent, nothing is clicked.
 *
 * <p><b>Threads.</b> Every entry point runs on the client thread (packet handlers past vanilla's
 * thread redirect; a sound from another thread is skipped). Hooks take cheap snapshots into small
 * JSON trees; {@link ScanWriter} serializes and writes them on the IO thread.
 *
 * <p><b>One pipeline.</b> Every non-menu event goes through {@code Session.emit}: the channel's
 * budget ({@link ScanBudget}), the location stamp, then {@link ScanQueue}, which folds repeats and
 * releases in order to {@code <channel>.jsonl} and the merged {@code timeline.jsonl}.
 */
public final class ServerScanner {

    private static final int MANIFEST_VERSION = 2;

    /** How often the scoreboard and tab list are compared with what was last written. */
    private static final long POLL_MS = 250L;

    private static final DateTimeFormatter FOLDER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    private static final DateTimeFormatter WALL =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    /** Read first by every menu and text hook after {@link DevMode#ACTIVE}. */
    private static volatile boolean running;

    // One flag per world hook, true only while a running session records that hook's channel. Each
    // hook reads its flag and nothing else before returning, so an idle scanner costs one field read.
    public static volatile boolean ATTACKS;
    public static volatile boolean BLOCKS;
    public static volatile boolean PARTICLES;
    public static volatile boolean TITLES;
    public static volatile boolean PICKUPS;
    public static volatile boolean RESPAWNS;
    public static volatile boolean MOUSE;

    private static final SoundListeners.Listener SOUNDS = ServerScanner::onSound;

    private static Session session;

    private ServerScanner() {
    }

    public static boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ menu and text hooks

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

    // ------------------------------------------------------------------ world hooks

    /** {@code MultiPlayerGameMode.attack}, HEAD. */
    public static void onAttack(Entity target) {
        if (!ATTACKS) {
            return;
        }
        try (Perf.Section perf = Perf.tick("dev.scan.damage")) {
            ScanCapture capture = capture();
            if (capture != null && target != null) {
                capture.onAttack(target);
            }
        }
    }

    /** {@code ClientLevel.setServerVerifiedBlockState}, HEAD. */
    public static void onBlock(BlockPos pos, BlockState old, BlockState now) {
        if (!BLOCKS) {
            return;
        }
        try (Perf.Section perf = Perf.tick("dev.scan.blocks")) {
            ScanCapture capture = capture();
            if (capture != null) {
                capture.onBlock(pos, old, now);
            }
        }
    }

    /** {@code ClientPacketListener.handleParticleEvent}, past the thread redirect. */
    public static void onParticles(ClientboundLevelParticlesPacket packet) {
        if (!PARTICLES) {
            return;
        }
        try (Perf.Section perf = Perf.tick("dev.scan.particles")) {
            ScanCapture capture = capture();
            if (capture != null) {
                capture.onParticles(packet);
            }
        }
    }

    /** {@code ClientPacketListener.setTitleText} / {@code setSubtitleText}, past the thread redirect. */
    public static void onTitle(Component text, boolean subtitle) {
        if (!TITLES) {
            return;
        }
        try (Perf.Section perf = Perf.tick("dev.scan.title")) {
            ScanCapture capture = capture();
            if (capture != null) {
                capture.onTitle(text, subtitle);
            }
        }
    }

    /** {@code ClientPacketListener.handleTakeItemEntity}, past the thread redirect. */
    public static void onTakeItem(ClientboundTakeItemEntityPacket packet) {
        if (!PICKUPS) {
            return;
        }
        try (Perf.Section perf = Perf.tick("dev.scan.inventory")) {
            ScanCapture capture = capture();
            if (capture != null) {
                capture.onTakeItem(packet);
            }
        }
    }

    /** Keep Mouse Position events from {@code MouseKeeper.debug} (client thread). */
    public static void onMouse(String what, double x, double y, double keptX, double keptY, String detail) {
        if (!MOUSE) {
            return;
        }
        ScanCapture capture = capture();
        if (capture != null) {
            capture.onMouse(what, x, y, keptX, keptY, detail);
        }
    }

    /** {@code ClientPacketListener.handleRespawn}, past the thread redirect. */
    public static void onRespawn() {
        if (!RESPAWNS) {
            return;
        }
        try (Perf.Section perf = Perf.tick("dev.scan.world")) {
            ScanCapture capture = capture();
            if (capture != null) {
                capture.onRespawn();
            }
        }
    }

    /** Subscribed to {@link SoundListeners} only while a session records sounds. */
    private static void onSound(SoundInstance instance) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;   // the capture's state belongs to the client thread
        }
        try (Perf.Section perf = Perf.tick("dev.scan.sound")) {
            ScanCapture capture = capture();
            if (capture != null) {
                capture.onSound(instance);
            }
        }
    }

    private static ScanCapture capture() {
        Session current = session;
        return running && current != null ? current.capture : null;
    }

    /** Client tick (TickTasks). The core and every world channel are separate Perf sections. */
    public static void tick(Minecraft minecraft) {
        if (!running) {
            return;
        }
        if (!DevMode.ACTIVE) {
            stop("developer mode was switched off");
            return;
        }
        try (Perf.Section perf = Perf.tick("dev.serverScanner")) {
            session.tick(minecraft);
        }
        if (running && session.capture != null) {
            session.capture.tick(minecraft, System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------ commands

    /**
     * {@code /sbs scan [start <channels>|full [preset] [channels]|mark <text>|stop|status]}; either
     * start form also takes {@code radius=<blocks>}.
     */
    public static void handleCommand(String argument) {
        String text = argument == null ? "" : argument.trim();
        String[] words = text.toLowerCase(Locale.ROOT).split("\\s+");
        String[] rest = Arrays.copyOfRange(words, 1, words.length);
        switch (words[0]) {
            case "start", "on" -> start(null, rest);
            case "full" -> {
                ScanPreset preset = rest.length > 0 ? ScanPreset.of(rest[0]) : null;
                start(preset == null ? ScanPreset.FULL : preset,
                        preset == null ? rest : Arrays.copyOfRange(rest, 1, rest.length));
            }
            case "mark" -> mark(text.length() > 4 ? text.substring(4).trim() : "");
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
            start(null, new String[0]);
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
        Session once = new Session(SBSFiles.scannerDir(), EnumSet.noneOf(ScanChannel.class), Integer.MAX_VALUE,
                null, 0);
        Path file = SBSFiles.scannerDir().resolve("logmenu_" + LocalDateTime.now().format(FOLDER) + "_"
                + ScanNames.slug(title) + ".jsonl");
        once.snapshotTo(file, menu, title, "/sbs logmenu");
        once.writer.flush(System.currentTimeMillis());
        say("§aMenu logged §8-> §f" + file);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Scanner] /sbs logmenu -> {}", file);
    }

    /** @param preset {@code null} for {@code start}: only the channels named (or the module toggles) */
    private static void start(ScanPreset preset, String[] words) {
        if (running) {
            say("§7Server Scanner is already running §8-> §f" + session.dir);
            return;
        }
        SBSConfig.DevSettings cfg = ConfigManager.getInstance().get().dev;
        Set<ScanChannel> channels = preset == null ? EnumSet.noneOf(ScanChannel.class) : preset.channels();
        int radius = preset == null ? ScanPreset.FULL.radius : preset.radius;
        boolean named = false;
        for (String word : words) {
            if (word.isEmpty() || word.equals("menus")) {
                continue;
            }
            if (word.startsWith("radius=") || word.startsWith("r=")) {
                try {
                    radius = Math.max(4, Math.min(256, Integer.parseInt(word.substring(word.indexOf('=') + 1))));
                } catch (NumberFormatException e) {
                    say("§cradius=§f" + word.substring(word.indexOf('=') + 1) + "§c is not a number.");
                    return;
                }
                continue;
            }
            if (word.equals("all")) {
                channels.addAll(ScanPreset.FULL.channels());
                named = true;
                continue;
            }
            ScanChannel channel = ScanChannel.of(word);
            if (channel == null || channel.always()) {
                say("§cUnknown channel §f" + word + "§c - use " + ScanChannel.choosable() + " or all.");
                return;
            }
            channels.add(channel);
            named = true;
        }
        if (preset == null && !named) {
            if (cfg.scannerChat) {
                channels.add(ScanChannel.CHAT);
            }
            if (cfg.scannerActionBar) {
                channels.add(ScanChannel.ACTIONBAR);
            }
            if (cfg.scannerScoreboard) {
                channels.add(ScanChannel.SCOREBOARD);
            }
            if (cfg.scannerTablist) {
                channels.add(ScanChannel.TABLIST);
            }
        }
        Path dir = SBSFiles.scannerDir().resolve(LocalDateTime.now().format(FOLDER));
        session = new Session(dir, channels, Math.max(1, cfg.scannerMaxMb), preset, radius);
        running = true;
        armHooks(channels);
        session.writeManifest();
        say("§aServer Scanner started" + (preset == null ? "" : " §7(" + preset.key() + ", radius " + radius + ")")
                + " §8-> §f" + dir);
        say("§7Channels: §fmenus" + session.channelList(", ")
                + "§7. §f/sbs scan mark <text>§7 to flag a moment, §f/sbs scan stop§7 when done.");
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Scanner] Session started -> {} (preset: {}, radius {}, channels: menus{})",
                dir, preset == null ? "none" : preset.key(), radius, session.channelList(", "));
        // A menu that is already open is picked up now, so starting inside it does not need a reopen.
        if (GuiStateManager.getInstance().getCurrentScreen() instanceof AbstractContainerScreen<?> screen
                && screen.getMenu().containerId != 0) {
            session.snapshot(screen.getMenu(), MenuReads.plain(screen.getTitle()), "open when the session started");
        }
    }

    private static void armHooks(Set<ScanChannel> channels) {
        // Attacks feed kill detection on the entity channel too, not only the damage channel.
        ATTACKS = channels.contains(ScanChannel.DAMAGE) || channels.contains(ScanChannel.ENTITY);
        BLOCKS = channels.contains(ScanChannel.BLOCKS);
        PARTICLES = channels.contains(ScanChannel.PARTICLES);
        TITLES = channels.contains(ScanChannel.TITLE);
        PICKUPS = channels.contains(ScanChannel.INVENTORY);
        RESPAWNS = channels.contains(ScanChannel.WORLD);
        MOUSE = channels.contains(ScanChannel.MOUSE);
        if (channels.contains(ScanChannel.SOUND)) {
            SoundListeners.add(SOUNDS);
        }
    }

    private static void disarmHooks() {
        ATTACKS = false;
        BLOCKS = false;
        PARTICLES = false;
        TITLES = false;
        PICKUPS = false;
        RESPAWNS = false;
        MOUSE = false;
        SoundListeners.remove(SOUNDS);
    }

    private static void mark(String text) {
        if (!running) {
            say("§7Server Scanner is not running - start it with §f/sbs scan full§7.");
            return;
        }
        if (text.isEmpty()) {
            say("§7Usage: §f/sbs scan mark <text>§7, e.g. §f/sbs scan mark killed the boss now");
            return;
        }
        long t = session.t();
        session.mark(text);
        say("§aMarked §7at §f" + wall() + "§7: §f" + text + " §8(t=" + t + " ms)");
    }

    private static void stop(String reason) {
        if (!running) {
            return;
        }
        running = false;
        disarmHooks();
        Session ended = session;
        session = null;
        ended.finish(reason, (bytes, line) -> Minecraft.getInstance().execute(() -> {
            say("§7Server Scanner stopped (" + reason + ") after §f" + ended.summary.total() + "§7 event(s), §f"
                    + mb(bytes) + "§7 MB §8-> §f" + ended.dir);
            say("§7Per channel: §f" + line);
        }));
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Scanner] Session stopped ({}) after {} event(s) -> {} ({})",
                reason, ended.summary.total(), ended.dir, ended.summary.countsLine());
    }

    private static void status() {
        if (!running) {
            say("§7Server Scanner is off. §f/sbs scan full §7[" + ScanPreset.names() + "] §7or §f/sbs scan start §7["
                    + ScanChannel.choosable() + "|all]§7; §fradius=<n>§7 with either.");
            say("§7While running: §f/sbs scan mark <text>§7, §f/sbs scan stop§7. §8Sessions: " + SBSFiles.scannerDir());
            return;
        }
        say("§aServer Scanner is running §7(" + session.summary.total() + " event(s), " + mb(session.budget.usedBytes())
                + " of " + mb(session.budget.limitBytes()) + " MB) §8-> §f" + session.dir);
        say("§7Channels: §fmenus" + session.channelList(", ") + "§7. Radius: §f" + session.radius + "§7. Menu: §f"
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

    /** Told, once the files are written, the final size and the per-channel counts line. */
    private interface Stopped {
        void report(long bytes, String countsLine);
    }

    private static final class Session implements ScanCapture.Out {
        final Path dir;
        final Set<ScanChannel> channels;
        final ScanPreset preset;
        final int radius;
        final ScanBudget budget;
        final ScanWriter writer;
        final ScanQueue queue = new ScanQueue();
        final ScanSummary summary = new ScanSummary();
        final ScanCapture capture;
        final Map<ScanChannel, Path> files = new EnumMap<>(ScanChannel.class);
        final Path timeline;
        final Instant started = Instant.now();
        final long startedNanos = System.nanoTime();
        final JsonObject manifest = new JsonObject();
        final JsonArray menus = new JsonArray();
        long ticks;
        MenuTrack menu;
        String lastActionBar;
        List<String> lastScoreboard = List.of();
        List<String> lastScoreboardPlain = List.of();
        List<String> lastTab = List.of();
        List<String> lastTabPlain = List.of();
        String lastFooter;
        long lastPollAt;
        // The location stamp, read once per tick rather than once per event.
        String island = "";
        String zone = "";

        Session(Path dir, Set<ScanChannel> channels, int limitMb, ScanPreset preset, int radius) {
            this.dir = dir;
            this.channels = channels;
            this.preset = preset;
            this.radius = radius;
            this.budget = new ScanBudget(limitMb);
            this.writer = ScanWriter.onIo(budget::add);
            this.timeline = dir.resolve("timeline.jsonl");
            for (ScanChannel channel : ScanChannel.values()) {
                files.put(channel, dir.resolve(channel.key + ".jsonl"));
            }
            boolean world = false;
            for (ScanChannel channel : channels) {
                world |= channel.ordinal() >= ScanChannel.ENTITY.ordinal();
            }
            this.capture = world ? new ScanCapture(this, channels, radius,
                    preset != null && preset.keepPlayers) : null;
            island = SkyBlockLocation.island();
            zone = SkyBlockLocation.zone();

            manifest.addProperty("version", MANIFEST_VERSION);
            manifest.addProperty("started", started.toString());
            manifest.addProperty("mod", version("skyblock-simplified-sbs"));
            manifest.addProperty("mc", version("minecraft"));
            manifest.addProperty("island", island);
            manifest.addProperty("zone", zone);
            manifest.addProperty("preset", preset == null ? null : preset.key());
            manifest.addProperty("radius", radius);
            JsonArray list = new JsonArray();
            list.add("menus");
            channels.forEach(c -> list.add(c.key));
            manifest.add("channels", list);
            JsonObject filters = new JsonObject();
            filters.addProperty("radius", radius);
            filters.addProperty("keepOtherPlayers", preset != null && preset.keepPlayers);
            filters.addProperty("dedupWindowMs", ScanQueue.HOLD_MS);
            filters.addProperty("killWindowMs", ScanCapture.KILL_WINDOW_MS);
            JsonArray noise = new JsonArray();
            ScanNames.NOISE_RULES.forEach(noise::add);
            filters.add("noiseRules", noise);
            JsonObject limits = new JsonObject();
            for (ScanChannel channel : channels) {
                JsonObject limit = new JsonObject();
                limit.addProperty("perSecond", channel.perSecond);
                limit.addProperty("maxMb", channel.maxMb);
                limits.add(channel.key, limit);
            }
            filters.add("limits", limits);
            manifest.add("filters", filters);
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

        long t() {
            return (System.nanoTime() - startedNanos) / 1_000_000L;
        }

        // -------------------------------------------------------------- the pipeline (ScanCapture.Out)

        @Override
        public JsonObject event(ScanChannel channel, String type) {
            JsonObject event = new JsonObject();
            event.addProperty("t", t());
            event.addProperty("tick", ticks);
            event.addProperty("wall", wall());
            event.addProperty("ch", channel.key);
            event.addProperty("type", type);
            return event;
        }

        @Override
        public void emit(ScanChannel channel, JsonObject event, String key) {
            long now = System.currentTimeMillis();
            ScanBudget.Verdict verdict = budget.admit(channel, now);
            if (verdict == ScanBudget.Verdict.DROP) {
                return;
            }
            if (verdict == ScanBudget.Verdict.THROTTLED) {
                boolean size = budget.sizeCapped(channel);
                JsonObject notice = event(channel, "throttled");
                notice.addProperty("reason", size ? "size" : "rate");
                notice.addProperty("limit", size ? channel.maxMb + " MB" : channel.perSecond + "/s");
                notice.addProperty("dropped", event.get("type").getAsString());
                stamp(notice);
                queue.offer(channel, null, notice, now, 0);
                return;
            }
            stamp(event);
            queue.offer(channel, key, event, now, event.get("t").getAsLong());
        }

        @Override
        public ScanSummary summary() {
            return summary;
        }

        @Override
        public String wall() {
            return ServerScanner.wall();
        }

        /** Island, zone and the player's position, rounded to 0.1. */
        private void stamp(JsonObject event) {
            event.addProperty("island", island);
            event.addProperty("zone", zone);
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                event.add("pos", ScanCapture.pos(player.getX(), player.getY(), player.getZ()));
            }
        }

        /** Out of the queue: to the channel's own file (menus have theirs already) and the timeline. */
        private void release(ScanChannel channel, JsonObject event) {
            summary.count(channel);
            if (channel != ScanChannel.MENUS) {
                writer.append(files.get(channel), event, channel);
            }
            writer.append(timeline, event, channel);
        }

        void mark(String text) {
            JsonObject event = event(ScanChannel.MARK, "mark");
            event.addProperty("text", text);
            summary.marker(wall(), event.get("t").getAsLong(), text);
            emit(ScanChannel.MARK, event, null);
        }

        /** A compact line on the timeline for a menu event; the menu's own file has the detail. */
        private void mirror(String type, JsonObject fields) {
            JsonObject event = event(ScanChannel.MENUS, type);
            event.addProperty("menu", menu == null ? null : menu.title);
            fields.entrySet().forEach(e -> event.add(e.getKey(), e.getValue()));
            emit(ScanChannel.MENUS, event, null);
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
            JsonObject event = menuEvent("open");
            event.addProperty("containerId", containerId);
            event.addProperty("menuType", menuType);
            event.addProperty("title", title);
            event.addProperty("slots", live == null ? -1 : live.slots.size());
            event.addProperty("menuSlots", live == null ? -1 : MenuReads.containerSlots(live));
            event.addProperty("catalog", catalog);
            event.addProperty("opened", Instant.now().toString());
            write(menu.file, event);

            JsonObject mirrored = new JsonObject();
            mirrored.addProperty("file", fileName);
            mirrored.addProperty("containerId", containerId);
            mirror("open", mirrored);

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
            if (containerId == 0) {
                return;   // the player inventory: the inventory channel polls it
            }
            if (menu == null || containerId != menu.containerId) {
                return;   // a stale container
            }
            ScanSlot[] next = ScanReads.readAll(items);
            JsonObject event = menuEvent("content");
            event.addProperty("stateId", stateId);
            event.addProperty("size", next.length);
            int changed = 0;
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
                changed = full.size();
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
                changed = changes.size();
            }
            menu.slots = next;
            write(menu.file, event);
            if (changed > 0) {
                JsonObject mirrored = new JsonObject();
                mirrored.addProperty("slots", changed);
                mirror("content", mirrored);
            }
        }

        void slot(int containerId, int stateId, int index, ItemStack stack) {
            if (menu == null || containerId != menu.containerId) {
                return;   // the cursor (-1), the player inventory (0) or a stale container
            }
            ScanSlot after = ScanReads.read(stack);
            ScanSlot before = menu.get(index);
            JsonObject event = menuEvent("slot");
            event.addProperty("stateId", stateId);
            event.addProperty("slot", index);
            event.add("old", ScanSlot.oldFields(before, after));
            event.add("new", ScanSlot.json(after));
            menu.set(index, after);
            write(menu.file, event);
            JsonObject mirrored = new JsonObject();
            mirrored.addProperty("slot", index);
            mirrored.addProperty("item", after == null ? null : after.name());
            mirror("slot", mirrored);
        }

        void click(int containerId, int slot, int button, ContainerInput input) {
            if (menu == null || containerId != menu.containerId) {
                return;
            }
            JsonObject event = menuEvent("click");
            event.addProperty("containerId", containerId);
            event.addProperty("slot", slot);
            event.addProperty("button", button);
            event.addProperty("clickType", input == null ? null : input.name());
            ScanSlot clicked = menu.get(slot);
            event.addProperty("itemId", clicked == null ? null : clicked.id());
            write(menu.file, event);
            JsonObject mirrored = new JsonObject();
            mirrored.addProperty("slot", slot);
            mirrored.addProperty("button", button);
            mirrored.addProperty("clickType", input == null ? null : input.name());
            mirrored.addProperty("item", clicked == null ? null : clicked.name());
            mirror("click", mirrored);
        }

        void close(int containerId, String by) {
            if (menu != null && containerId == menu.containerId) {
                closeTracked(by);
            }
        }

        private void closeTracked(String by) {
            JsonObject event = menuEvent("close");
            event.addProperty("by", by);
            write(menu.file, event);
            JsonObject mirrored = new JsonObject();
            mirrored.addProperty("by", by);
            mirror("close", mirrored);
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
            open.addProperty("wall", ServerScanner.wall());
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
            event.addProperty("wall", ServerScanner.wall());
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
            if (!channels.contains(ScanChannel.CHAT)) {
                return;
            }
            Set<String> players = LayoutRecorder.knownPlayers();
            String raw = LayoutSignature.redactPlayersKeepCodes(DevLogText.legacy(message), players);
            JsonObject event = event(ScanChannel.CHAT, "chat");
            event.addProperty("raw", raw);
            event.addProperty("plain", LayoutSignature.redactPlayers(text == null ? "" : text, players));
            emit(ScanChannel.CHAT, event, "chat|" + raw);
        }

        void actionBar(Component message) {
            if (!channels.contains(ScanChannel.ACTIONBAR)) {
                return;
            }
            String raw = DevLogText.legacy(message);
            if (raw == null || raw.equals(lastActionBar)) {
                return;
            }
            lastActionBar = raw;
            Set<String> players = LayoutRecorder.knownPlayers();
            JsonObject event = event(ScanChannel.ACTIONBAR, "actionbar");
            event.addProperty("raw", LayoutSignature.redactPlayersKeepCodes(raw, players));
            event.addProperty("plain", LayoutSignature.redactPlayers(message.getString(), players));
            emit(ScanChannel.ACTIONBAR, event, null);
        }

        /**
         * The sidebar: the first state in full, after that only the lines that changed ({@link ScanDiff},
         * on the plain text - a colour-only change is not written).
         */
        private void pollScoreboard() {
            List<String> raw = new ArrayList<>();
            for (ScoreboardLine line : ScoreboardReader.lines()) {
                raw.add(DevLogText.legacy(line.display()));
            }
            if (raw.equals(lastScoreboard)) {
                return;
            }
            Set<String> players = LayoutRecorder.knownPlayers();
            List<String> plain = redactedPlain(raw, players);
            boolean first = lastScoreboard.isEmpty() && lastScoreboardPlain.isEmpty();
            JsonArray changes = first ? null : ScanDiff.lines(lastScoreboardPlain, plain);
            lastScoreboard = raw;
            lastScoreboardPlain = plain;
            if (changes != null && changes.isEmpty()) {
                return;
            }
            JsonObject event = event(ScanChannel.SCOREBOARD, first ? "scoreboard" : "scoreboard_diff");
            event.addProperty("title", MenuReads.plain(ScoreboardReader.title()));
            if (first) {
                event.add("raw", redactedRaw(raw, players));
                event.add("plain", toJson(plain));
            } else {
                event.add("changes", changes);
            }
            emit(ScanChannel.SCOREBOARD, event, null);
        }

        /** The tab list, the same way, plus the footer when it changes. */
        private void pollTab() {
            List<String> raw = new ArrayList<>();
            for (Component line : TabWidgets.components()) {
                raw.add(DevLogText.legacy(line));
            }
            String footer = TabWidgets.footerRaw();
            if (raw.equals(lastTab) && footer.equals(lastFooter)) {
                return;
            }
            Set<String> players = LayoutRecorder.knownPlayers();
            List<String> plain = redactedPlain(raw, players);
            boolean first = lastFooter == null;
            JsonArray changes = first ? null : ScanDiff.lines(lastTabPlain, plain);
            boolean footerChanged = !footer.equals(lastFooter);
            lastTab = raw;
            lastTabPlain = plain;
            lastFooter = footer;
            if (changes != null && changes.isEmpty() && !footerChanged) {
                return;
            }
            JsonObject event = event(ScanChannel.TABLIST, first ? "tablist" : "tablist_diff");
            if (first) {
                event.add("raw", redactedRaw(raw, players));
                event.add("plain", toJson(plain));
            } else if (!changes.isEmpty()) {
                event.add("changes", changes);
            }
            if (footerChanged) {
                event.addProperty("footerRaw", LayoutSignature.redactPlayersKeepCodes(footer, players));
                event.addProperty("footer", LayoutSignature.redactPlayers(footer, players));
            }
            emit(ScanChannel.TABLIST, event, null);
        }

        private static JsonArray redactedRaw(List<String> lines, Set<String> players) {
            JsonArray out = new JsonArray();
            lines.forEach(line -> out.add(LayoutSignature.redactPlayersKeepCodes(line, players)));
            return out;
        }

        private static List<String> redactedPlain(List<String> lines, Set<String> players) {
            List<String> out = new ArrayList<>(lines.size());
            lines.forEach(line -> out.add(LayoutSignature.redactPlayers(line, players)));
            return out;
        }

        private static JsonArray toJson(List<String> lines) {
            JsonArray out = new JsonArray();
            lines.forEach(out::add);
            return out;
        }

        // -------------------------------------------------------------- tick, writing, end

        void tick(Minecraft minecraft) {
            ticks++;
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
            if (minecraft.player != null) {
                island = SkyBlockLocation.island();
                zone = SkyBlockLocation.zone();
            }
            long now = System.currentTimeMillis();
            if (minecraft.player != null && now - lastPollAt >= POLL_MS
                    && (channels.contains(ScanChannel.SCOREBOARD) || channels.contains(ScanChannel.TABLIST))
                    && Perf.allowTick("dev.serverScanner.poll", TierScheduler.Tier.BACKGROUND)) {
                lastPollAt = now;
                if (channels.contains(ScanChannel.SCOREBOARD)) {
                    pollScoreboard();
                }
                if (channels.contains(ScanChannel.TABLIST)) {
                    pollTab();
                }
            }
            queue.release(now, this::release);
            if (writer.due(now)) {
                writer.flush(now);
            }
        }

        void finish(String reason, Stopped stopped) {
            if (menu != null) {
                closeTracked("session_end");
            }
            long now = System.currentTimeMillis();
            if (capture != null) {
                capture.finish(now);
            }
            queue.drain(this::release);
            writer.flush(now);
            long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
            List<String> throttled = throttledLines();
            String countsLine = summary.countsLine();
            manifest.addProperty("stopped", Instant.now().toString());
            manifest.addProperty("stopReason", reason);
            manifest.addProperty("events", summary.total());
            manifest.add("counts", summary.countsJson());
            manifest.add("suppressed", summary.suppressedJson());
            JsonArray throttledJson = new JsonArray();
            throttled.forEach(throttledJson::add);
            manifest.add("throttled", throttledJson);
            String header = "Server Scanner session " + dir.getFileName() + "  (" + reason + ")\n"
                    + "Preset: " + (preset == null ? "none" : preset.key()) + "   Radius: " + radius
                    + "   Channels: menus" + channelList(", ") + "\n"
                    + "Island at start: " + manifest.get("island").getAsString();
            // The size is only final once the IO thread has written everything queued above.
            writer.afterWrites(() -> {
                long bytes = budget.usedBytes();
                manifest.addProperty("bytes", bytes);
                writer.replace(dir.resolve("summary.txt"), summary.render(header, durationMs, bytes, throttled));
                writeManifest();
                stopped.report(bytes, countsLine);
            });
        }

        /** One line per channel the budget refused events of. */
        private List<String> throttledLines() {
            List<String> out = new ArrayList<>();
            for (ScanChannel channel : channels) {
                if (budget.episodes(channel) == 0) {
                    continue;
                }
                out.add(channel.key + ": " + budget.dropped(channel) + " event(s) dropped in "
                        + budget.episodes(channel) + " episode(s) - "
                        + (budget.sizeCapped(channel) ? "size cap " + channel.maxMb + " MB reached"
                        : "over " + channel.perSecond + "/s"));
            }
            return out;
        }

        void writeManifest() {
            writer.replace(dir.resolve("session.json"),
                    new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(manifest) + "\n");
        }

        /** A menu-file event: {@code t} since the menu opened. */
        private JsonObject menuEvent(String type) {
            JsonObject event = new JsonObject();
            event.addProperty("t", menu == null ? 0 : menu.t());
            event.addProperty("wall", ServerScanner.wall());
            event.addProperty("type", type);
            return event;
        }

        private void write(Path file, JsonObject event) {
            writer.append(file, event, ScanChannel.MENUS);
        }

        private static String version(String modId) {
            return FabricLoader.getInstance().getModContainer(modId)
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        }
    }
}
