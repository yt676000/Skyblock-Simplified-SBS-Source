/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.dungeons.bossbar.logic;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.data.VersionedDataStore;
import sbs.modid.client.core.entity.EntityIndex;
import sbs.modid.client.core.entity.EntityKind;
import sbs.modid.client.core.entity.NameClass;
import sbs.modid.client.core.entity.Tracked;
import sbs.modid.client.core.mixin.BossHealthOverlayAccessor;
import sbs.modid.client.core.util.NumberDisplay;
import sbs.modid.client.dungeons.bossbar.logic.BossBarText.Boss;
import sbs.modid.client.dungeons.bossbar.logic.BossBarText.Hp;
import sbs.modid.client.dungeons.bossbar.logic.BossBarText.Source;
import sbs.modid.client.dungeons.bossbar.model.BossHpTable;
import sbs.modid.client.dungeons.run.logic.DungeonStateManager;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Boss Bar Health: per boss bar, the name with the boss's health appended, prepared on the client
 * tick and handed to the overlay by {@code BossHealthOverlayMixin}.
 *
 * <p>Sources, best first: the boss's own nametag (exact - read through {@link EntityIndex}, matched
 * to the bar by the boss's name); the bar's progress times the maximum in {@link BossHpTable}
 * (shown with "≈"); otherwise the percentage alone. Hypixel sends only progress on the bar, so a
 * number never comes from the bar by itself.
 *
 * <p>Cost: WARM. The tick gate is the Catacombs; the nametag walk runs every
 * {@link #SCAN_INTERVAL} ticks and only while a bar names a known boss. The component is rebuilt
 * only when the bar's name, its progress (to a tenth of a percent), the reading or a setting
 * changed. The per-frame path is one map lookup and a reference compare.
 */
public final class BossHealthModel {

    private static final BossHealthModel INSTANCE = new BossHealthModel();

    private static final String RESOURCE =
            "/assets/" + SkyblockSimplifiedSBS.MOD_ID + "/dungeons/boss_hp.json";

    private static final VersionedDataStore<BossHpTable> TABLE = new VersionedDataStore<>(
            "BossHp", RESOURCE, SBSFiles.root().resolve("data").resolve("boss-hp.json"),
            null, BossHpTable.class, 1);

    /** Ticks between nametag walks. */
    private static final int SCAN_INTERVAL = 10;

    /** How far a boss nametag may be from the player and still count - the arenas fit in this. */
    private static final double TAG_RANGE_SQ = 96.0 * 96.0;

    /** Probe: how many matching tags one log line lists. */
    private static final int PROBE_TAGS = 5;

    /** The buckets a boss nametag can be in: a separate stand, or the boss entity's own name. */
    private static final EntityKind[] TAG_KINDS = {EntityKind.ARMOR_STAND, EntityKind.LIVING};

    /** One bar's prepared state. Client thread only. */
    private static final class Line {
        Component source;
        Boss boss;
        int progressKey = -1;
        Hp tagHp;
        int settingsKey = -1;
        Component display;
    }

    private final Map<UUID, Line> lines = new HashMap<>();
    private final Set<Boss> probed = EnumSet.noneOf(Boss.class);
    private int tickCounter;

    private BossHealthModel() {
    }

    public static BossHealthModel getInstance() {
        return INSTANCE;
    }

    /** Loads the bundled maximum-health table. Call on client init. */
    public static void load() {
        TABLE.load();
        BossHpTable table = TABLE.get();
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][BossBar] {} boss maximum(s), data v{} ({})",
                table == null || table.bosses == null ? 0 : table.bosses.size(), TABLE.version(),
                TABLE.source());
    }

    private static SBSConfig.DungeonsSettings cfg() {
        return ConfigManager.getInstance().get().dungeons;
    }

    /**
     * Render thread, per bar per frame: the prepared name for this bar, or {@code original} when
     * there is none or it was built from a different name. Allocates nothing.
     */
    public Component displayName(UUID id, Component original) {
        if (lines.isEmpty()) {
            return original;
        }
        Line line = lines.get(id);
        if (line == null || line.display == null || line.source != original) {
            return original;
        }
        return line.display;
    }

    /** Clears every bar and the probe memory; registered with the world transitions. */
    public void onWorldChange() {
        lines.clear();
        probed.clear();
        tickCounter = 0;
    }

    /** Called every client tick inside the Catacombs (TickTasks gate). */
    public void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        SBSConfig.DungeonsSettings cfg = cfg();
        if (!cfg.bossBarHealth || minecraft.player == null || minecraft.gui == null
                || !DungeonStateManager.getInstance().inDungeon()) {
            lines.clear();
            return;
        }
        Map<UUID, LerpingBossEvent> events =
                ((BossHealthOverlayAccessor) minecraft.gui.hud.getBossOverlay()).sbs$events();
        if (events.isEmpty()) {
            lines.clear();
            return;
        }
        boolean scan = ++tickCounter % SCAN_INTERVAL == 0;
        boolean master = DungeonStateManager.getInstance().floorType() == 'M';
        int settingsKey = (cfg.bossBarHealthMax ? 1 : 0) | (cfg.bossBarHealthPercent ? 2 : 0)
                | (cfg.bossBarHealthColour ? 4 : 0) | (NumberDisplay.enabled() ? 8 : 0) | (master ? 16 : 0);

        for (Iterator<Map.Entry<UUID, Line>> it = lines.entrySet().iterator(); it.hasNext(); ) {
            if (!events.containsKey(it.next().getKey())) {
                it.remove();
            }
        }
        for (LerpingBossEvent event : events.values()) {
            update(event, minecraft.player, scan, master, settingsKey, cfg);
        }
    }

    private void update(LerpingBossEvent event, LocalPlayer player, boolean scan, boolean master,
                        int settingsKey, SBSConfig.DungeonsSettings cfg) {
        Line line = lines.computeIfAbsent(event.getId(), id -> new Line());
        Component name = event.getName();
        boolean dirty = false;
        if (line.source != name) {
            Boss boss = BossBarText.identify(name.getString());
            dirty = true; // the prepared component embeds the old name
            line.source = name;
            line.boss = boss;
            line.tagHp = null;
        }
        if (line.boss == null) {
            line.display = null;
            return;
        }
        float progress = event.getProgress();
        int progressKey = Math.round(progress * 1000);
        if (progressKey != line.progressKey) {
            line.progressKey = progressKey;
            dirty = true;
        }
        if (scan) {
            Hp tag = scanNametag(line.boss, player, name.getString(), progress);
            if (!sameHp(tag, line.tagHp)) {
                line.tagHp = tag;
                dirty = true;
            }
        }
        if (settingsKey != line.settingsKey) {
            line.settingsKey = settingsKey;
            dirty = true;
        }
        if (dirty) {
            line.display = build(name, line.boss, progress, line.tagHp, master, cfg);
        }
    }

    private static boolean sameHp(Hp a, Hp b) {
        return a == null ? b == null : a.equals(b);
    }

    private static Component build(Component name, Boss boss, float progress, Hp tagHp, boolean master,
                                   SBSConfig.DungeonsSettings cfg) {
        Source source;
        Hp hp;
        if (tagHp != null) {
            source = Source.NAMETAG;
            hp = tagHp;
        } else {
            BossHpTable table = TABLE.get();
            BossHpTable.Entry entry = table == null ? null : table.find(boss.name(), master);
            if (entry != null) {
                source = Source.ESTIMATE;
                hp = BossBarText.estimate(progress, entry.maxHp);
            } else {
                source = Source.PERCENT;
                hp = null;
            }
        }
        String suffix = BossBarText.suffix(source, hp, progress, cfg.bossBarHealthMax,
                cfg.bossBarHealthPercent, NumberDisplay::format);
        ChatFormatting colour = cfg.bossBarHealthColour
                ? ChatFormatting.getByCode(BossBarText.colourCode(BossBarText.fraction(hp, progress)))
                : ChatFormatting.WHITE;
        MutableComponent out = Component.empty().append(name);
        out.append(Component.literal(suffix).withStyle(colour == null ? ChatFormatting.WHITE : colour));
        return out;
    }

    /**
     * The boss's nametag reading: the nearest health tag within range that names this boss. Logs
     * the bar and the candidate tags once per boss per world, so the tag format can be confirmed.
     */
    private Hp scanNametag(Boss boss, LocalPlayer player, String barName, float progress) {
        Hp best = null;
        double bestDistance = Double.MAX_VALUE;
        boolean probe = probed.add(boss);
        List<String> seen = probe ? new ArrayList<>(PROBE_TAGS) : null;
        for (EntityKind kind : TAG_KINDS) {
            List<Tracked> bucket = EntityIndex.bucket(kind);
            for (int i = 0; i < bucket.size(); i++) {
                Tracked tracked = bucket.get(i);
                double distance = tracked.entity.distanceToSqr(player);
                if (distance > TAG_RANGE_SQ || !tracked.entity.isAlive()) {
                    continue;
                }
                NameClass cls = tracked.nameClass();
                if (cls == NameClass.NONE || cls == NameClass.DAMAGE_TAG) {
                    continue;
                }
                String plain = tracked.plain();
                if (BossBarText.identify(plain) != boss) {
                    continue;
                }
                if (seen != null && seen.size() < PROBE_TAGS) {
                    seen.add(plain);
                }
                if (cls != NameClass.HEALTH_TAG) {
                    continue;
                }
                Hp hp = BossBarText.parseNametag(plain);
                if (hp != null && distance < bestDistance) {
                    bestDistance = distance;
                    best = hp;
                }
            }
        }
        if (seen != null) {
            SkyblockSimplifiedSBS.LOGGER.info("[SBS][BossBar] event='{}' progress={} tags={}",
                    barName, progress, seen);
        }
        return best;
    }
}
