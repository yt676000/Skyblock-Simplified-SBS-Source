/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.util.PlainText;
import sbs.modid.client.skills.hunting.logic.ShardCatalog;
import sbs.modid.client.skills.hunting.logic.ShardResolver;
import sbs.modid.client.skills.hunting.model.ShardContext;
import sbs.modid.client.social.chat.logic.SBSChat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What our resolver makes of every slot in the open chest - slot, name, id, and which strategy
 * answered.
 *
 * <p><b>The one question a shard bug always turns into.</b> "Nothing is detected" has three causes
 * that look identical from outside: the title did not match so no context was chosen, the context
 * was chosen but its strategy found nothing in the slot, or it found something and the catalogue
 * does not carry it. Each needs a different fix and none of them is visible on screen. This prints
 * all three, per slot, in one table.
 *
 * <p><b>It captures on a tick rather than on the command, because chat cannot be opened over a
 * container screen.</b> A typed command can never run <i>inside</i> the menu being studied, which is
 * the only place the interesting stacks exist. So the open menu is snapshotted while it is up and
 * the command prints the snapshot afterwards: open the menu, close it, run the command. Menus
 * {@link ShardContext} recognises are snapshotted always; {@code arm} widens that to <b>any</b>
 * container, which is what a menu whose title stopped matching needs - precisely the case where the
 * normal path captures nothing.
 *
 * <p><b>Capture only.</b> Nothing is clicked, no command is sent, no store is written.
 *
 * <p><b>Developer mode only</b>, like every probe: the command is DEV_ONLY in CommandRegistry and
 * arming checks {@link DevMode} again. A tester who has to capture something needs dev mode on.
 */
public final class ShardDump {

    private static final ShardDump INSTANCE = new ShardDump();

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    /** How often an open menu is re-snapshotted. It only changes when Hypixel refills it. */
    private static final long RESNAPSHOT_MS = 500L;

    /** How many rows go into the chat summary; the file always carries all of them. */
    private static final int CHAT_ROWS = 12;

    /** One slot as the resolver reads it. */
    private record Entry(int slot, String name, String id, String strategy, boolean catalogued,
                         String source) {
    }

    /** One menu as it was snapshotted. */
    private record Capture(String title, String rawTitle, ShardContext context, long at,
                           List<Entry> entries, int filled) {
    }

    /** Whether any container is captured, rather than only the recognised shard menus. */
    private volatile boolean armed;

    private volatile Capture last;

    private Object lastScreen;
    private long lastSnapshotAt;

    private ShardDump() {
    }

    public static ShardDump getInstance() {
        return INSTANCE;
    }

    /** {@code /sbs sharddump [arm|off]}. */
    public void handleCommand(String argument) {
        if (!DevMode.ACTIVE) { // DEV-ONLY: defence in depth behind the command gate
            return;
        }
        String action = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "arm" -> {
                armed = true;
                say("§7Shard dump armed - §fevery§7 container is now snapshotted. Open the menu, "
                        + "close it, then run §f/sbs sharddump§7.");
            }
            case "off" -> {
                armed = false;
                say("§7Shard dump disarmed. Recognised shard menus are still snapshotted.");
            }
            default -> report();
        }
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    /**
     * Client tick. Snapshots the open menu when it is one we would read, or any menu while armed.
     *
     * <p>Costs a title match and a returned {@code null} for every other container in the game.
     */
    public void tick(Minecraft minecraft) {
        try {
            if (minecraft == null) {
                return;
            }
            Screen screen = GuiStateManager.getInstance().getCurrentScreen();
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                lastScreen = null;
                return;
            }
            String rawTitle = container.getTitle() == null ? "" : container.getTitle().getString();
            ShardContext context = ShardContext.fromTitle(rawTitle);
            if (context == null && !armed) {
                return;
            }
            long now = System.currentTimeMillis();
            if (screen == lastScreen && now - lastSnapshotAt < RESNAPSHOT_MS) {
                return;
            }
            lastScreen = screen;
            lastSnapshotAt = now;
            last = snapshot(container, rawTitle, context);
        } catch (Throwable failed) {
            // A diagnostic must never be the thing that breaks the session it is diagnosing.
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][ShardDump] snapshot failed", failed);
        }
    }

    /**
     * Reads every slot of the container through the strategy its title selected.
     *
     * <p>A menu whose title matched nothing is read as {@link ShardContext#INVENTORY} - the NBT
     * strategy - because that is the only reading that can work without knowing which screen this
     * is, and reporting "the NBT says nothing either" is a fact worth having.
     */
    private static Capture snapshot(AbstractContainerScreen<?> container, String rawTitle,
                                    ShardContext context) {
        AbstractContainerMenu menu = container.getMenu();
        ShardContext reading = context == null ? ShardContext.INVENTORY : context;

        List<Entry> entries = new ArrayList<>();
        int filled = 0;
        int slots = Math.max(0, menu.getItems().size() - 36);   // the menu's own slots, not the player's
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = menu.getSlot(slot).getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            filled++;
            ShardResolver.Resolution resolution = ShardResolver.resolve(stack, reading);
            entries.add(new Entry(slot,
                    PlainText.strip(stack.getHoverName().getString()).trim(),
                    resolution.canonicalId(),
                    resolution.strategy().displayName(),
                    resolution.catalogued(),
                    resolution.sourceText()));
        }
        return new Capture(ShardContext.normalise(rawTitle), rawTitle, context,
                System.currentTimeMillis(), entries, filled);
    }

    // ------------------------------------------------------------------
    // Report
    // ------------------------------------------------------------------

    private void report() {
        Capture capture = last;
        if (capture == null) {
            say("§7Nothing captured yet. Open the Attribute Menu, the Hunting Box or a fusion menu "
                    + "and close it, then run this again.");
            say("§7A menu whose title is not recognised needs §f/sbs sharddump arm§7 first.");
            return;
        }
        int resolved = 0;
        int catalogued = 0;
        for (Entry entry : capture.entries()) {
            if (entry.id() != null) {
                resolved++;
            }
            if (entry.catalogued()) {
                catalogued++;
            }
        }

        say("§b--- shard dump ---");
        say("§7title    §f\"" + capture.title() + "\"");
        say("§7context  §f" + (capture.context() == null
                ? "§cnone - the title matched no shard menu" : capture.context().displayName()));
        say("§7slots    §f" + capture.filled() + " filled, " + resolved + " resolved, "
                + catalogued + " in the catalogue");
        say("§7age      §f" + Math.max(0, System.currentTimeMillis() - capture.at()) / 1000 + "s");

        int shown = 0;
        for (Entry entry : capture.entries()) {
            if (shown++ >= CHAT_ROWS) {
                say("§8… " + (capture.entries().size() - CHAT_ROWS) + " more in the file");
                break;
            }
            say("§8" + pad(entry.slot()) + " §f" + entry.name() + " §8-> "
                    + (entry.id() == null ? "§c(none)" : (entry.catalogued() ? "§a" : "§e") + entry.id())
                    + " §8[" + entry.strategy() + "]");
        }

        Path file = write(capture);
        say("§7Full dump §8-> §f" + (file == null ? "(could not be written - see the log)" : file));
    }

    private static Path write(Capture capture) {
        StringBuilder out = new StringBuilder(4096);
        out.append("SBS shard dump\n");
        out.append("captured  : ").append(LocalDateTime.now()).append('\n');
        out.append("raw title : ").append(capture.rawTitle()).append('\n');
        out.append("title     : ").append(capture.title()).append('\n');
        out.append("context   : ")
                .append(capture.context() == null ? "(none matched)" : capture.context().name())
                .append('\n');
        out.append("catalogue : ").append(ShardCatalog.size()).append(" shard(s), ")
                .append(ShardCatalog.unconsumableCount()).append(" unconsumable, source ")
                .append(ShardCatalog.source()).append(" v").append(ShardCatalog.version())
                .append('\n');
        out.append("filled    : ").append(capture.filled()).append('\n');
        out.append('\n');
        out.append(String.format(Locale.ROOT, "%-5s %-34s %-40s %-18s %s%n",
                "slot", "display name", "resolved id", "strategy", "read from"));
        for (Entry entry : capture.entries()) {
            out.append(String.format(Locale.ROOT, "%-5d %-34s %-40s %-18s %s%n",
                    entry.slot(),
                    entry.name(),
                    entry.id() == null ? "(none)" : entry.id() + (entry.catalogued() ? "" : " *"),
                    entry.strategy(),
                    entry.source()));
        }
        out.append("\n* resolved, but the catalogue carries no such shard.\n");

        try {
            Path file = SBSFiles.probeFile("sharddump-" + STAMP.format(LocalDateTime.now()));
            SBSFiles.ensureParent(file);
            Files.writeString(file, out.toString());
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][ShardDump] wrote {}", file);
            return file;
        } catch (Throwable unwritable) {
            SkyblockSimplifiedSBS.LOGGER.error("[SBS][ShardDump] writing the dump failed", unwritable);
            return null;
        }
    }

    private static String pad(int slot) {
        return slot < 10 ? " " + slot : String.valueOf(slot);
    }

    private static void say(String text) {
        SBSChat.send(Component.literal(" " + text));
    }
}
