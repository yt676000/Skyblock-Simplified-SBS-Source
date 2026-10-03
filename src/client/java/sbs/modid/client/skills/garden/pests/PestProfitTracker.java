/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.garden.pests;

import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.ProfileContext;
import sbs.modid.client.core.config.ProfileScopedStore;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.economy.prices.BazaarPriceCache;
import sbs.modid.client.economy.prices.ItemPriceKey;
import sbs.modid.client.economy.prices.LbinCache;
import sbs.modid.client.economy.recipe.logic.SkyBlockItemCatalog;
import sbs.modid.client.skills.garden.logic.PestTracker;
import sbs.modid.client.ui.hud.edit.logic.HudLayout;
import sbs.modid.client.ui.hud.edit.model.HudElement;
import sbs.modid.client.ui.theme.SBSTheme;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pest Profit: pests killed this session and on this profile, what they dropped, and what that is
 * worth at the Bazaar (instasell, lowest BIN second, unpriced items marked with "+"). The pure
 * bookkeeping is {@link PestLedger}, the chat shapes {@link PestChat}; this class feeds them, stores
 * the profile total ({@code pestprofit.json} in the profile folder) and draws the card
 * ({@link HudElement#PEST_PROFIT}).
 *
 * <p><b>Capture.</b> The kill and drop wording is unverified, so while the feature is on every chat
 * line within {@value #CAPTURE_MS} ms after a kill is written to the log under
 * {@code [SBS][PestDrops]} (at most {@value #CAPTURE_LIMIT} per session).
 *
 * <p><b>Pest Traps.</b> A trap's catch is paid out when it is emptied, with no kill line, so its loot
 * needs its own source. Nothing about trap collection has been seen in a log yet, so on the Garden
 * this logs under {@code [SBS][PestTraps]}: every change of the widget's trap rows, every block or
 * entity right-click, the title and items of a menu whose title names a trap, and every chat line
 * within {@value #TRAP_CAPTURE_MS} ms after any of those. Collection is taken (ESTIMATED) from a trap
 * menu being open or just closed, a collection line, or the "Full Traps" count falling; drops in the
 * {@link PestLedger#TRAP_MS} window after that are trap loot.
 */
public final class PestProfitTracker implements ProfileScopedStore {

    private static final PestProfitTracker INSTANCE = new PestProfitTracker();

    private static final String FILE = "pestprofit.json";
    private static final long CAPTURE_MS = 2_000L;
    private static final int CAPTURE_LIMIT = 300;
    private static final long TRAP_CAPTURE_MS = 5_000L;
    private static final int TRAP_CAPTURE_LIMIT = 400;
    private static final int PAD = 4;
    private static final int ROW = 10;
    private static final int MAX_DROP_ROWS = 6;

    private final PestLedger session = new PestLedger();
    private PestLedger total = new PestLedger();
    private boolean loaded;
    private boolean unreadable;
    private long lastKillAt;
    private int captured;
    private long trapCaptureUntil;
    private int trapCaptured;
    private String lastTrapRows = "";
    private int lastFullTraps = -1;
    /** The trap menu open right now, or null. Identity only. */
    private Screen trapMenu;
    private boolean trapMenuDumped;
    private java.util.Map<String, Integer> trapMenuPests = java.util.Map.of();

    private PestProfitTracker() {
        ProfileContext.getInstance().register(this);
    }

    public static PestProfitTracker getInstance() {
        return INSTANCE;
    }

    private static SBSConfig.GardenHelpersSettings cfg() {
        return ConfigManager.getInstance().get().gardenHelpers;
    }

    // ------------------------------------------------------------------ inputs

    /**
     * From {@code PestTracker}'s tab scan: the alive count fell by {@code count}. When a trap filled
     * in the same scan ({@code trapFilled}), the pests went into the trap - ESTIMATED - and are
     * booked as caught by a trap, not as kills, so they are never counted twice.
     */
    public void onCountDrop(int count, long now, boolean trapFilled) {
        if (!cfg().pestProfit || count <= 0) {
            return;
        }
        ensureLoaded();
        if (trapFilled) {
            session.onTrapCaught(null, count, now);
            total.onTrapCaught(null, count, now);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestTraps] alive count fell by {} as a trap filled - caught, not killed", count);
        } else {
            session.onCountDrop(count, now);
            total.onCountDrop(count, now);
            lastKillAt = now;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestDrops] alive count fell by {}", count);
        }
        save();
    }

    /**
     * From {@code PestTracker}'s tab scan: the widget's trap rows (null when absent). Logs every
     * change; a falling "Full Traps" count is a collection.
     *
     * @return whether a trap filled since the last scan (its count rose)
     */
    public boolean onTrapRows(String traps, String full, String noBait, long now) {
        if (!cfg().pestProfit) {
            return false;
        }
        String rows = "Pest Traps: " + traps + " | Full Traps: " + full + " | No Bait: " + noBait;
        if (!rows.equals(lastTrapRows)) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestTraps] widget: {} (was {})", rows,
                    lastTrapRows.isEmpty() ? "-" : lastTrapRows);
            lastTrapRows = rows;
        }
        int fullNow = PestChat.fullTrapCount(full);
        int before = lastFullTraps;
        if (fullNow >= 0) {
            lastFullTraps = fullNow;
        }
        if (before >= 0 && fullNow >= 0 && fullNow < before) {
            trapCollected(now, "Full Traps fell " + before + " -> " + fullNow);
        }
        return before >= 0 && fullNow > before;
    }

    /** The local player right-clicked a block (from the interaction mixin). */
    public void onBlockInteract(BlockPos pos) {
        if (onGardenWithTracker() && pos != null) {
            armTrapCapture("block right-click " + pos.toShortString() + " "
                    + Minecraft.getInstance().level.getBlockState(pos));
        }
    }

    /** The local player right-clicked an entity (from the interaction mixin). */
    public void onEntityInteract(Entity target) {
        if (onGardenWithTracker() && target != null) {
            armTrapCapture("entity right-click " + target.getType() + " name=" + target.getName().getString()
                    + " at " + target.blockPosition().toShortString());
        }
    }

    private boolean onGardenWithTracker() {
        return cfg().pestProfit && Minecraft.getInstance().level != null && PestTracker.getInstance().onGarden();
    }

    private void armTrapCapture(String why) {
        long now = System.currentTimeMillis();
        trapCaptureUntil = now + TRAP_CAPTURE_MS;
        if (trapCaptured < TRAP_CAPTURE_LIMIT) {
            trapCaptured++;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestTraps] {}", why);
        }
    }

    private void trapCollected(long now, String why) {
        ensureLoaded();
        boolean fresh = session.onTrapCollected(now);
        total.onTrapCollected(now);
        armTrapCapture("collection (" + why + ")" + (fresh ? "" : " - same collection"));
        save();
    }

    /** Every client tick, from {@code PestTracker}: follows a trap menu opening and closing. */
    public void onClientTick() {
        Screen screen = GuiStateManager.getInstance().getCurrentScreen();
        if (screen == trapMenu) {
            if (trapMenu != null) {
                long now = System.currentTimeMillis();
                session.onTrapCollected(now);   // keeps the window open while the menu is
                total.onTrapCollected(now);
                trapCaptureUntil = now + TRAP_CAPTURE_MS;
                if (!trapMenuDumped && trapMenu instanceof AbstractContainerScreen<?> c) {
                    dumpTrapMenu(c, false);
                }
            }
            return;
        }
        if (trapMenu != null) {
            // Closed (or replaced): the loot lines can still follow.
            long now = System.currentTimeMillis();
            if (trapMenu instanceof AbstractContainerScreen<?> c) {
                dumpTrapMenu(c, true);
            }
            for (java.util.Map.Entry<String, Integer> e : trapMenuPests.entrySet()) {
                session.onTrapCaught(e.getKey(), e.getValue(), now);
                total.onTrapCaught(e.getKey(), e.getValue(), now);
            }
            trapCollected(now, "trap menu closed");
            trapMenu = null;
            trapMenuPests = java.util.Map.of();
        }
        if (screen instanceof AbstractContainerScreen<?> c && onGardenWithTracker()
                && PestChat.isTrapMenu(c.getTitle().getString())) {
            trapMenu = screen;
            trapMenuDumped = false;
            trapCollected(System.currentTimeMillis(), "trap menu opened: " + c.getTitle().getString());
        }
    }

    /** Logs a trap menu's title and items, once when it has filled and once as it closes. */
    private void dumpTrapMenu(AbstractContainerScreen<?> screen, boolean closing) {
        AbstractContainerMenu menu = screen.getMenu();
        int own = Math.max(0, menu.slots.size() - 36);
        List<PestChat.Caught> items = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < own; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name = stack.getHoverName().getString();
            items.add(new PestChat.Caught(name, stack.getCount()));
            out.append("\n  #").append(i).append(' ').append(stack.getCount()).append("x ").append(name)
                    .append(" | ").append(String.join(" / ", ItemPriceKey.lore(stack)));
        }
        if (items.isEmpty() && !closing) {
            return;   // contents not sent yet
        }
        trapMenuDumped = true;
        trapMenuPests = PestChat.menuPests(items);
        if (trapCaptured < TRAP_CAPTURE_LIMIT) {
            trapCaptured++;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestTraps] menu {} '{}' pests={}{}",
                    closing ? "closing" : "open", screen.getTitle().getString(), trapMenuPests, out);
        }
    }

    /** Every chat line, before any display rewrite. */
    public void onChat(String text, Component message) {
        if (!cfg().pestProfit || text == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String plain = text.replaceAll("§.", "").trim();
        capture(plain, message, now);
        captureTrap(plain, message, now);

        PestChat.Caught caught = PestChat.trapCaught(plain);
        if (caught != null && PestTracker.getInstance().onGarden()) {
            ensureLoaded();
            session.onTrapCaught(caught.pest(), caught.count(), now);
            total.onTrapCaught(caught.pest(), caught.count(), now);
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestTraps] caught line: {} -> {}", plain, caught);
            if (PestChat.trapCollected(plain)) {
                trapCollected(now, "line: " + plain);
            }
            save();
            return;
        }
        if (PestChat.trapCollected(plain) && PestTracker.getInstance().onGarden()) {
            trapCollected(now, "line: " + plain);
            return;
        }

        String pest = PestChat.killedPest(plain);
        if (pest != null) {
            ensureLoaded();
            session.onNamedKill(pest, now);
            total.onNamedKill(pest, now);
            lastKillAt = now;
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestDrops] kill line: {} -> {}", plain, pest);
            save();
            return;
        }
        PestChat.Drop rare = PestChat.rareDrop(plain);
        if (rare != null) {
            PestLedger.Source source = session.attribute(now, 0);
            if (source != PestLedger.Source.NONE) {
                book(resolveItemId(rare.name()), rare.amount(), now, source);
                save();
            }
            return;
        }
        int period = PestChat.sackPeriod(plain);
        PestLedger.Source source = period > 0 ? session.attribute(now, period) : PestLedger.Source.NONE;
        if (source != PestLedger.Source.NONE && message != null) {
            StringBuilder hover = new StringBuilder();
            collectHoverText(message, hover);
            for (Map.Entry<String, Long> gain : PestChat.sackGains(hover.toString()).entrySet()) {
                String id = resolveItemId(gain.getKey());
                // A trap pays out no crops you broke, but while you farm the batch holds them too.
                if (id != null && !PestChat.FARMED_ITEMS.contains(id)) {
                    book(id, gain.getValue(), now, source);
                }
            }
            save();
        }
    }

    private void book(String itemId, long amount, long now, PestLedger.Source source) {
        ensureLoaded();
        session.addDrop(itemId, amount, now, source);
        total.addDrop(itemId, amount, now, source);
        if (source == PestLedger.Source.TRAP) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestTraps] trap loot: {} x{}", itemId, amount);
        }
    }

    /** Logs the lines right after a trap interaction, menu or collection. */
    private void captureTrap(String plain, Component message, long now) {
        if (now > trapCaptureUntil || trapCaptured >= TRAP_CAPTURE_LIMIT || plain.isEmpty()) {
            return;
        }
        trapCaptured++;
        StringBuilder hover = new StringBuilder();
        if (message != null) {
            collectHoverText(message, hover);
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestTraps] line: {}{}", plain,
                hover.isEmpty() ? "" : "  | hover: " + hover.toString().replace('\n', '/'));
    }

    /** Logs the lines right after a kill, so the real kill and drop wording can be read off a log. */
    private void capture(String plain, Component message, long now) {
        if (lastKillAt == 0 || now - lastKillAt > CAPTURE_MS || captured >= CAPTURE_LIMIT
                || plain.isEmpty()) {
            return;
        }
        captured++;
        StringBuilder hover = new StringBuilder();
        if (message != null) {
            collectHoverText(message, hover);
        }
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestDrops] +{}ms: {}{}", now - lastKillAt, plain,
                hover.isEmpty() ? "" : "  | hover: " + hover.toString().replace('\n', '/'));
    }

    // ------------------------------------------------------------------ read model

    public PestLedger session() {
        return session;
    }

    public PestLedger total() {
        ensureLoaded();
        return total;
    }

    /** Instasell first, lowest BIN second, 0 when unknown. */
    public static double price(String itemId) {
        BazaarPriceCache.BzPrice value = BazaarPriceCache.getInstance().get(itemId);
        if (value != null && value.sell() > 0) {
            return value.sell();
        }
        Long lbin = LbinCache.getInstance().getLbin(itemId);
        return lbin == null ? 0 : lbin;
    }

    private static boolean anyUnpriced(PestLedger ledger, boolean includeTraps) {
        return anyUnpriced(ledger.drops) || includeTraps && anyUnpriced(ledger.trapDrops);
    }

    private static boolean anyUnpriced(Map<String, Long> items) {
        for (String id : items.keySet()) {
            if (price(id) <= 0) {
                return true;
            }
        }
        return false;
    }

    /** "Rat ×2, Mite ×1, unnamed ×3". */
    private static String trapCaughtText(PestLedger ledger) {
        StringBuilder out = new StringBuilder();
        ledger.trapPests.forEach((pest, n) -> out.append(out.isEmpty() ? "" : ", ").append(pest).append(" ×").append(n));
        if (ledger.trapCaughtUnknown > 0) {
            out.append(out.isEmpty() ? "" : ", ").append("unnamed ×").append(ledger.trapCaughtUnknown);
        }
        return out.toString();
    }

    public void resetSession() {
        session.reset();
        captured = 0;
        trapCaptured = 0;
    }

    public void resetTotal() {
        ensureLoaded();
        total.reset();
        save();
    }

    // ------------------------------------------------------------------ card

    /** Called from the HUD render hook once per frame. */
    public void render(GuiGraphicsExtractor g) {
        SBSConfig.GardenHelpersSettings cfg = cfg();
        if (!cfg.pestProfit || HudLayout.isHidden(HudElement.PEST_PROFIT)
                || Minecraft.getInstance().player == null || !PestTracker.getInstance().onGarden()
                || session.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        boolean traps = cfg.pestProfitTrapLoot;
        String plus = anyUnpriced(session, traps) ? "+" : "";
        List<String> lines = new ArrayList<>();
        lines.add("Pests: " + session.totalKills() + "   "
                + NumberDisplay.format(session.value(PestProfitTracker::price, traps)) + plus);
        double perHour = session.perHour(PestProfitTracker::price, traps);
        lines.add((perHour > 0 ? NumberDisplay.format(perHour) + plus + "/h   " : "")
                + NumberDisplay.format(session.perPest(PestProfitTracker::price)) + plus + " per pest");
        if (cfg.pestProfitBreakdown) {
            List<Map.Entry<String, Integer>> kills = new ArrayList<>(session.kills.entrySet());
            kills.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            StringBuilder row = new StringBuilder("§7");
            for (Map.Entry<String, Integer> kill : kills) {
                if (row.length() > 2) {
                    row.append(", ");
                }
                row.append(kill.getKey()).append(" ×").append(kill.getValue());
            }
            lines.add(row.toString());
        }
        if (session.trapCollections > 0 || !session.trapDrops.isEmpty() || session.totalTrapCaught() > 0) {
            String tp = anyUnpriced(session.trapDrops) ? "+" : "";
            lines.add("§7from traps: " + NumberDisplay.format(session.trapItemCount()) + " items · "
                    + NumberDisplay.format(session.trapValue(PestProfitTracker::price)) + tp + " coins"
                    + (traps ? "" : " §8(not in total)"));
            lines.add("§7pests caught: " + (session.trapPests.isEmpty() && session.trapCaughtUnknown == 0
                    ? "unknown" : trapCaughtText(session)));
        }
        Map<String, Long> shown = new java.util.LinkedHashMap<>(session.drops);
        if (traps) {
            session.trapDrops.forEach((id, n) -> shown.merge(id, n, Long::sum));
        }
        List<Map.Entry<String, Long>> drops = new ArrayList<>(shown.entrySet());
        drops.sort(Comparator.comparingDouble((Map.Entry<String, Long> e) -> -price(e.getKey()) * e.getValue()));
        for (int i = 0; i < Math.min(MAX_DROP_ROWS, drops.size()); i++) {
            Map.Entry<String, Long> d = drops.get(i);
            double value = price(d.getKey()) * d.getValue();
            lines.add(name(d.getKey()) + " ×" + NumberDisplay.format(d.getValue()) + "  §7"
                    + (value > 0 ? NumberDisplay.format(value) : "?"));
        }
        if (drops.isEmpty()) {
            lines.add("§8No drops seen yet");
        }
        PestLedger all = total();
        lines.add("§8Profile: " + all.totalKills() + " pests, "
                + NumberDisplay.format(all.value(PestProfitTracker::price, traps)) + (anyUnpriced(all, traps) ? "+" : "")
                + (all.trapCollections > 0 ? ", traps " + NumberDisplay.format(all.trapValue(PestProfitTracker::price)) : ""));

        String title = "Pest Profit";
        int w = PAD * 2 + font.width(title);
        for (String line : lines) {
            w = Math.max(w, PAD * 2 + font.width(line));
        }
        int h = PAD * 2 + ROW * (lines.size() + 1);
        HudElement.Bounds b = HudElement.PEST_PROFIT.defaultBounds(g.guiWidth(), g.guiHeight());
        int x = Math.round(b.x());
        int y = Math.round(b.y());
        HudLayout.measure(HudElement.PEST_PROFIT, x, y, w, h);
        HudLayout.begin(g, HudElement.PEST_PROFIT);
        g.fill(x, y, x + w, y + h, SBSTheme.HUD_CARD_BG);
        g.outline(x, y, w, h, SBSTheme.HUD_CARD_BORDER);
        g.text(font, title, x + PAD, y + PAD, SBSTheme.ACCENT, true);
        int cy = y + PAD + ROW;
        for (String line : lines) {
            g.text(font, line, x + PAD, cy, SBSTheme.TEXT, false);
            cy += ROW;
        }
        HudLayout.end(g);
    }

    private static String name(String itemId) {
        SkyBlockItemCatalog.Entry entry = SkyBlockItemCatalog.getInstance().byId(itemId);
        return entry != null && entry.name != null ? entry.name : itemId;
    }

    // ------------------------------------------------------------------ persistence

    private void ensureLoaded() {
        if (!loaded) {
            reloadProfile();
        }
    }

    private Path file() {
        return ProfileContext.getInstance().file(FILE);
    }

    @Override
    public synchronized void reloadProfile() {
        total = new PestLedger();
        unreadable = false;
        loaded = false;
        if (!ProfileContext.getInstance().known()) {
            return;
        }
        Path path = file();
        if (!Files.isRegularFile(path)) {
            loaded = true;
            return;
        }
        try {
            PestLedger read = SBSFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8),
                    PestLedger.class);
            if (read != null) {
                read.fillMissing();
                total = read;
            }
            loaded = true;
        } catch (JsonSyntaxException corrupt) {
            unreadable = true;
            loaded = true;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PestDrops] {} is unreadable ({}) - left as it is",
                    FILE, corrupt.toString());
        } catch (Exception transientFailure) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][PestDrops] could not read {} yet ({})", FILE,
                    transientFailure.toString());
        }
    }

    @Override
    public synchronized void flushProfile() {
        if (loaded) {
            save();
        }
    }

    private synchronized void save() {
        if (!loaded || unreadable || !ProfileContext.getInstance().known()) {
            return;
        }
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, SBSFiles.GSON.toJson(total), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][PestDrops] could not write {}: {}", FILE, e.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Display name -> SkyBlock id via the item catalogue, else the normalised name itself. */
    static String resolveItemId(String displayName) {
        if (displayName == null) {
            return null;
        }
        String name = displayName.replaceAll("§.", "").trim();
        if (name.isEmpty()) {
            return null;
        }
        SkyBlockItemCatalog catalog = SkyBlockItemCatalog.getInstance();
        SkyBlockItemCatalog.Entry entry = catalog.byName(name);
        if (entry == null) {
            entry = catalog.byNormalizedName(name);
        }
        if (entry != null) {
            return entry.id;
        }
        String constructed = name.toUpperCase(Locale.ROOT).replace(' ', '_').replaceAll("[^A-Z0-9_]", "");
        return constructed.isEmpty() ? null : constructed;
    }

    private static void collectHoverText(Component component, StringBuilder out) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component text)) {
            out.append(text.getString()).append('\n');
        }
        for (Component sibling : component.getSiblings()) {
            collectHoverText(sibling, out);
        }
    }
}
