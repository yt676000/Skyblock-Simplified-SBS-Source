/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.perf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.async.SbsExecutors;
import sbs.modid.client.core.config.ConfigManager;
import sbs.modid.client.core.config.SBSConfig;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.memory.MemoryGuard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The global KPIs and the per-feature table, as text for the overlay and as JSON + CSV for
 * {@code /sbs perf dump} ({@code Development_Stuff/perf/}), so builds can be compared.
 */
public final class PerfReport {

    private static long countersSince = System.currentTimeMillis();
    private static long networkAtReset = 0;
    private static long diskAtReset = 0;

    private PerfReport() {
    }

    /** Whether a row is over its KPI target. */
    public static boolean overTarget(Perf.Row row) {
        SBSConfig.PerformanceSettings t = ConfigManager.getInstance().get().performance;
        if (row.allocMbPerSec() > t.allocTargetMbPerSec) {
            return true;
        }
        return row.kind() == Perf.Kind.FRAME
                ? row.last5().avgMs() > t.featureTargetMs
                : row.last5().avgMs() > t.tickBudgetMs;
    }

    /** The global KPIs as label/value pairs. */
    public static List<String[]> globals() {
        Minecraft mc = Minecraft.getInstance();
        SBSConfig.PerformanceSettings t = ConfigManager.getInstance().get().performance;
        RollingStats.Summary frame = Perf.frameTotal(5);
        RollingStats.Summary frameTime = Perf.frameTime(5);
        RollingStats.Summary tick = Perf.tickTotal(5);
        Runtime rt = Runtime.getRuntime();
        double minutes = Math.max(1 / 60.0, (System.currentTimeMillis() - countersSince) / 60_000.0);
        long floor = MemoryGuard.latestFloorBytes();
        double share = frameTime.avgMs() > 0 ? frame.avgMs() / frameTime.avgMs() * 100 : 0;
        return List.of(
                new String[] {"FPS / frame", mc.getFps() + " / " + ms(frameTime.avgMs()) + " (p95 " + ms(frameTime.p95Ms()) + ")"},
                new String[] {"SBS per frame", ms(frame.avgMs()) + " (p95 " + ms(frame.p95Ms()) + ", "
                        + String.format(Locale.ROOT, "%.1f", share) + "% of frame; target "
                        + t.frameTargetMs + " / p95 " + t.frameP95TargetMs + ")"},
                new String[] {"SBS per tick", ms(tick.avgMs()) + " (p95 " + ms(tick.p95Ms()) + ", budget "
                        + t.tickBudgetMs + (Perf.shedding() ? ", SHEDDING" : "") + ")"},
                new String[] {"Heap", mb(rt.totalMemory() - rt.freeMemory()) + " used, floor "
                        + (floor < 0 ? "?" : mb(floor)) + ", max " + mb(rt.maxMemory())},
                new String[] {"Worker queue", String.valueOf(SbsExecutors.queueDepth())},
                new String[] {"Network / disk", String.format(Locale.ROOT, "%.1f / %.1f per min",
                        (Perf.networkCalls() - networkAtReset) / minutes, (Perf.diskWrites() - diskAtReset) / minutes)});
    }

    /** Restarts the per-minute counters with {@code /sbs perf reset}. */
    public static void resetCounters() {
        countersSince = System.currentTimeMillis();
        networkAtReset = Perf.networkCalls();
        diskAtReset = Perf.diskWrites();
    }

    /** Writes {@code perf/<time>[-label].json} and {@code .csv}; returns the base path. */
    public static Path dump(String label) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String safe = label == null || label.isBlank() ? "" : "-" + label.replaceAll("[^A-Za-z0-9_-]", "_");
        Path base = SBSFiles.developmentDir().resolve("perf").resolve(stamp + safe);

        JsonObject root = new JsonObject();
        root.addProperty("time", stamp);
        root.addProperty("label", label == null ? "" : label);
        root.addProperty("cores", Runtime.getRuntime().availableProcessors());
        JsonObject global = new JsonObject();
        for (String[] pair : globals()) {
            global.addProperty(pair[0], pair[1]);
        }
        root.add("globals", global);
        JsonArray features = new JsonArray();
        StringBuilder csv = new StringBuilder(
                "name,kind,tier,avg5_ms,p95_5_ms,max5_ms,calls_per_s,avg60_ms,p95_60_ms,max60_ms,alloc_mb_s,over_target\n");
        for (Perf.Row row : Perf.rows()) {
            JsonObject f = new JsonObject();
            f.addProperty("name", row.name());
            f.addProperty("kind", row.kind().name());
            f.addProperty("tier", row.tier().name());
            f.addProperty("avg5Ms", row.last5().avgMs());
            f.addProperty("p95_5Ms", row.last5().p95Ms());
            f.addProperty("max5Ms", row.last5().maxMs());
            f.addProperty("callsPerSec", row.last5().callsPerSec());
            f.addProperty("avg60Ms", row.last60().avgMs());
            f.addProperty("p95_60Ms", row.last60().p95Ms());
            f.addProperty("max60Ms", row.last60().maxMs());
            f.addProperty("allocMbPerSec", row.allocMbPerSec());
            f.addProperty("overTarget", overTarget(row));
            features.add(f);
            csv.append(String.join(",", row.name(), row.kind().name(), row.tier().name(),
                    d(row.last5().avgMs()), d(row.last5().p95Ms()), d(row.last5().maxMs()),
                    d(row.last5().callsPerSec()), d(row.last60().avgMs()), d(row.last60().p95Ms()),
                    d(row.last60().maxMs()), d(row.allocMbPerSec()), String.valueOf(overTarget(row)))).append('\n');
        }
        root.add("features", features);
        String json = SBSFiles.GSON.toJson(root);
        String text = csv.toString();
        SbsExecutors.io().execute(() -> {
            try {
                Files.createDirectories(base.getParent());
                Files.writeString(base.resolveSibling(base.getFileName() + ".json"), json, StandardCharsets.UTF_8);
                Files.writeString(base.resolveSibling(base.getFileName() + ".csv"), text, StandardCharsets.UTF_8);
            } catch (Exception e) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Perf] could not write the dump: {}", e.toString());
            }
        });
        return base;
    }

    private static String ms(double v) {
        return String.format(Locale.ROOT, "%.2f ms", v);
    }

    private static String d(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String mb(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }
}
