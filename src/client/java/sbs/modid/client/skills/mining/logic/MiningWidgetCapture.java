/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.skills.mining.logic;

import net.minecraft.client.Minecraft;
import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.core.config.SBSFiles;
import sbs.modid.client.core.dev.DevMode;
import sbs.modid.client.core.location.SkyBlockLocation;
import sbs.modid.client.core.tab.TabWidgets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dev-only capture of the three places a mining number could be published: the tab-list widget, the
 * tab-list footer and the scoreboard sidebar.
 *
 * <p><b>Why this exists.</b> {@link MiningTracker} parses the widget's {@code Powders:} section
 * against line shapes that were written from a guess and have never once been observed - across 55
 * dev-client logs there is not a single {@code [SBS][Mining]} line, which is that logger saying the
 * mining path has never run. Every powder rate, every projection and every "time to afford" figure
 * downstream inherits that guess. So before any of it is built on, one play session should produce a
 * file that says what Hypixel actually sends.
 *
 * <p><b>All three sources, not just the widget.</b> The open question is not only "what shape is the
 * powder line" but "is powder published passively at all, and where". Capturing the sidebar and the
 * footer beside the widget is what turns that into a settled question instead of another guess - if
 * powder appears in none of the three, that is the answer, and it is worth having in writing.
 *
 * <p><b>Capture-only and dev-gated.</b> Nothing here parses, decides or displays; it writes down what
 * arrived and stops. A snapshot is only appended when the text actually changed, so a widget sitting
 * still costs one hash per interval and produces one entry, and {@link #MAX_SNAPSHOTS} caps a
 * forgotten dev session at a file a human can still read.
 */
public final class MiningWidgetCapture {

    private static final MiningWidgetCapture INSTANCE = new MiningWidgetCapture();

    /** Sampling cadence. The widget moves on a server tick; twice a second is already finer. */
    private static final long INTERVAL_MS = 500L;

    /** A forgotten dev session cannot grow the file past something openable. */
    private static final int MAX_SNAPSHOTS = 400;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    /** Content already written, so a static widget produces one entry rather than one per sample. */
    private final Set<String> seen = new HashSet<>();

    private long lastSampleAt;
    private int snapshots;
    private Path file;
    private boolean exhausted;

    private MiningWidgetCapture() {
    }

    public static MiningWidgetCapture getInstance() {
        return INSTANCE;
    }

    /**
     * Called every client tick (throttled internally). Off entirely unless developer mode is on, so
     * a normal player never touches this path.
     */
    public void onClientTick() {
        // DEV-ONLY: capture file read by nothing else
        if (!DevMode.ACTIVE || exhausted || Minecraft.getInstance().player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSampleAt < INTERVAL_MS) {
            return;
        }
        lastSampleAt = now;

        List<String> widget = TabWidgets.lines();
        List<String> footer = TabWidgets.footerLines();
        List<String> sidebar = SkyBlockLocation.sidebarLines();
        if (widget.isEmpty() && footer.isEmpty() && sidebar.isEmpty()) {
            return;
        }
        // The area is part of the key: the same widget text in the Dwarven Mines and in the Crystal
        // Hollows is two observations, and which areas publish which sections is half the question.
        String island = SkyBlockLocation.island();
        String zone = SkyBlockLocation.zone();
        String key = island + "|" + zone + "|" + widget + "|" + footer + "|" + sidebar;
        if (!seen.add(key)) {
            return;
        }
        append(island, zone, widget, footer, sidebar);
    }

    private void append(String island, String zone, List<String> widget, List<String> footer,
                        List<String> sidebar) {
        List<String> out = new ArrayList<>();
        out.add("");
        out.add("=== " + LocalDateTime.now().format(STAMP)
                + "  island=" + blankAsUnknown(island) + "  zone=" + blankAsUnknown(zone) + " ===");
        section(out, "TAB WIDGET (TabWidgets.lines)", widget);
        section(out, "TAB FOOTER (TabWidgets.footerLines)", footer);
        section(out, "SCOREBOARD SIDEBAR (SkyBlockLocation.sidebarLines)", sidebar);

        try {
            Files.createDirectories(SBSFiles.probeDir());
            if (file == null) {
                file = SBSFiles.probeDir().resolve(
                        "mining-widget-" + LocalDateTime.now().format(FILE_STAMP) + ".txt");
                Files.writeString(file, header(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            Files.writeString(file, String.join(System.lineSeparator(), out) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Capture is a diagnostic, so a failed write is reported and gives up rather than
            // retrying every tick against a disk that is not going to start working.
            exhausted = true;
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Mining] widget capture disabled - write failed", e);
            return;
        }

        // Mirrored to the log so the observation survives even if the file is never sent back.
        SkyblockSimplifiedSBS.LOGGER.info(
                "[SBS][Mining] capture #{} island={} zone={} widget={} footer={} sidebar={}",
                ++snapshots, island, zone, widget, footer, sidebar);
        if (snapshots >= MAX_SNAPSHOTS) {
            exhausted = true;
            SkyblockSimplifiedSBS.LOGGER.info(
                    "[SBS][Mining] widget capture stopped at {} snapshots -> {}", snapshots, file);
        }
    }

    private static void section(List<String> out, String title, List<String> lines) {
        out.add("-- " + title + " (" + lines.size() + " lines)");
        if (lines.isEmpty()) {
            out.add("   (empty)");
            return;
        }
        for (String line : lines) {
            // Quoted, because a trailing space or a non-breaking space is exactly the kind of thing
            // that makes a pattern fail and exactly the kind of thing an unquoted dump hides.
            out.add("   \"" + line + "\"");
        }
    }

    private static String blankAsUnknown(String value) {
        return value == null || value.isBlank() ? "(unknown)" : value;
    }

    private static String header() {
        return String.join(System.lineSeparator(), List.of(
                "SkyBlock Simplified - mining widget capture",
                "",
                "Every distinct tab widget / tab footer / scoreboard sidebar seen while developer",
                "mode was on, tagged with the island and zone it was seen in. Text is already",
                "colour-stripped and trimmed, and each line is quoted so trailing or unusual",
                "whitespace is visible.",
                "",
                "Capture only - nothing here was parsed or acted on.",
                ""));
    }
}
