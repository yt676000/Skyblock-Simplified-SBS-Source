/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard;

import sbs.modid.SkyblockSimplifiedSBS;
import sbs.modid.client.ui.settings.ModuleSettings;
import sbs.modid.client.ui.settings.OptionIndex;
import sbs.modid.client.ui.settings.SettingRow;
import sbs.modid.client.ui.wizard.model.ModVersion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Every registered {@link WizardPage}, and the rules for choosing which ones to show.
 *
 * <p>Built once per session from {@link ServiceLoader}. Declaration problems - a duplicate id, an
 * unparseable {@code introducedIn} - are caught here and logged once, rather than surfacing later as
 * a page that silently never appears.
 */
public final class WizardPages {

    /**
     * How many showcase pages a returning player may be shown at once.
     *
     * <p>Someone several versions behind must get a coherent result rather than an ordeal. Selection
     * is by {@link WizardPage#importance()} so release order does not decide what survives the cap;
     * presentation stays chronological, so the pages still read as a history.
     */
    public static final int SHOWCASE_CAP = 5;

    private static List<WizardPage> all;
    private static Map<String, ModVersion> versions;

    private WizardPages() {
    }

    /** Every page that loaded and declared itself correctly, in no particular order. */
    public static synchronized List<WizardPage> all() {
        ensureBuilt();
        return all;
    }

    /** One page by id, for the dev jump command. */
    public static synchronized Optional<WizardPage> byId(String id) {
        ensureBuilt();
        return all.stream().filter(page -> page.id().equals(id)).findFirst();
    }

    /** The version a page was introduced in. Only ever called for pages that parsed. */
    public static synchronized ModVersion versionOf(WizardPage page) {
        ensureBuilt();
        return versions.getOrDefault(page.id(), ModVersion.ZERO);
    }

    private static void ensureBuilt() {
        if (all != null) {
            return;
        }
        List<WizardPage> loaded = new ArrayList<>();
        Map<String, ModVersion> parsed = new LinkedHashMap<>();

        for (WizardPage page : ServiceLoader.load(WizardPage.class, WizardPages.class.getClassLoader())) {
            String id = page.id();
            if (id == null || id.isBlank()) {
                SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Wizard] {} declares no id - dropped.",
                        page.getClass().getName());
                continue;
            }
            if (parsed.containsKey(id)) {
                // First wins, deterministically, and the collision is named: two pages sharing an id
                // would share a "seen" record, so one of them could never be shown again.
                SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Wizard] Duplicate page id '{}' from {} - dropped, the first one keeps it.",
                        id, page.getClass().getName());
                continue;
            }
            ModVersion version = ModVersion.parse(page.introducedIn()).orElse(null);
            if (version == null) {
                SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Wizard] Page '{}' declares introducedIn='{}', which is not a version - "
                                + "dropped. Use major.minor.patch.", id, page.introducedIn());
                continue;
            }
            loaded.add(page);
            parsed.put(id, version);
        }
        all = List.copyOf(loaded);
        versions = Map.copyOf(parsed);
        SkyblockSimplifiedSBS.LOGGER.info("[SBS][Wizard] {} page(s) registered.", all.size());
    }

    // ------------------------------------------------------------------
    // Choosing
    // ------------------------------------------------------------------

    /**
     * The onboarding pages still due: registered, applicable, not already shown, and with content
     * that still resolves. Ordered by {@link WizardPage#order()}.
     *
     * <p>Never capped. Onboarding is short by construction, and dropping a basics page would leave a
     * setting the player was supposed to be offered silently at its default.
     */
    public static List<WizardPage> onboarding(java.util.function.Predicate<String> alreadySeen) {
        List<WizardPage> due = new ArrayList<>();
        for (WizardPage page : all()) {
            if (page.mode() != WizardMode.ONBOARDING || !safeAvailable(page)) {
                continue;
            }
            if (alreadySeen.test(page.id()) || resolve(page).isEmpty()) {
                continue;
            }
            due.add(page);
        }
        due.sort(byOrder());
        return due;
    }

    /**
     * Every showcase page newer than {@code baseline}, in display order.
     *
     * <p>The full list, before the cap - {@link #capShowcase} applies that, so the caller can tell
     * how many were dropped without recomputing anything.
     */
    public static List<WizardPage> showcaseDue(ModVersion baseline, ModVersion current) {
        List<WizardPage> due = new ArrayList<>();
        for (WizardPage page : all()) {
            if (page.mode() != WizardMode.SHOWCASE || !safeAvailable(page)) {
                continue;
            }
            ModVersion introduced = versionOf(page);
            // Strictly after what they have seen, and never ahead of what they are running: a page
            // for a version this build does not contain describes a feature that is not here.
            if (!introduced.isAfter(baseline) || introduced.isAfter(current)) {
                continue;
            }
            if (resolve(page).isEmpty()) {
                continue;
            }
            due.add(page);
        }
        due.sort(byOrder());
        return due;
    }

    /**
     * Narrows a showcase run to {@link #SHOWCASE_CAP} pages, keeping the most important.
     *
     * <p>Selection is by importance, then by version, then by id - all three so the result is
     * deterministic and two players on the same versions see the same pages. The survivors are
     * returned in display order, not in selection order, because the run is presented as a history.
     */
    public static List<WizardPage> capShowcase(List<WizardPage> due) {
        if (due.size() <= SHOWCASE_CAP) {
            return due;
        }
        List<WizardPage> ranked = new ArrayList<>(due);
        ranked.sort(Comparator
                .comparingInt(WizardPage::importance).reversed()
                .thenComparing((WizardPage page) -> versionOf(page)).reversed()
                .thenComparing(WizardPage::id));
        List<WizardPage> kept = new ArrayList<>(ranked.subList(0, SHOWCASE_CAP));
        kept.sort(byOrder());
        return kept;
    }

    private static Comparator<WizardPage> byOrder() {
        return Comparator.comparingInt(WizardPage::order).thenComparing(WizardPage::id);
    }

    // ------------------------------------------------------------------
    // Resolving content
    // ------------------------------------------------------------------

    /** A page's content with every element resolved, or empty when the page must be skipped. */
    public record Resolved(WizardPage page, List<Element> elements) {

        /** One resolved element: either static content, or a settings row that still exists. */
        public record Element(PageElement source, SettingRow row) {
        }

        public boolean isEmpty() {
            return elements.isEmpty();
        }
    }

    /**
     * Resolves a page's content against the live config.
     *
     * <p>An element naming an option that no longer exists is <b>dropped and logged</b>. The page as
     * a whole is skipped only when it declared settings and none of them survived - which for a page
     * about a single setting is the same thing, and for a page about five is the difference between
     * hiding one row and hiding four working ones.
     */
    public static Resolved resolve(WizardPage page) {
        List<PageElement> content;
        try {
            content = page.content();
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Wizard] Page '{}' threw while listing its content - skipped.", page.id(), t);
            return new Resolved(page, List.of());
        }
        if (content == null || content.isEmpty()) {
            return new Resolved(page, List.of());
        }

        List<Resolved.Element> out = new ArrayList<>();
        int declaredSettings = 0;
        int resolvedSettings = 0;
        for (PageElement element : content) {
            if (!(element instanceof PageElement.Setting setting)) {
                out.add(new Resolved.Element(element, null));
                continue;
            }
            declaredSettings++;
            SettingRow row = rowFor(setting.optionId());
            if (row == null) {
                SkyblockSimplifiedSBS.LOGGER.warn(
                        "[SBS][Wizard] Page '{}' references option '{}', which no longer exists - "
                                + "that row is dropped.", page.id(), setting.optionId());
                continue;
            }
            resolvedSettings++;
            out.add(new Resolved.Element(element, row));
        }

        if (declaredSettings > 0 && resolvedSettings == 0) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Wizard] Page '{}' has no surviving settings - page skipped.", page.id());
            return new Resolved(page, List.of());
        }
        return new Resolved(page, List.copyOf(out));
    }

    /** The live row behind an option id, or {@code null} when this build no longer has it. */
    private static SettingRow rowFor(String optionId) {
        if (optionId == null || optionId.isBlank()) {
            return null;
        }
        OptionIndex.Entry entry = OptionIndex.byId(optionId);
        if (entry == null) {
            return null;
        }
        try {
            List<SettingRow> rows = ModuleSettings.rowsFor(entry.moduleId());
            // The index records where the row sat when it was built; the list is rebuilt fresh here,
            // so the position is re-checked rather than trusted.
            if (entry.rowIndex() >= 0 && entry.rowIndex() < rows.size()) {
                SettingRow row = rows.get(entry.rowIndex());
                if (row.id().equals(entry.optionId().substring(entry.moduleId().length() + 1))) {
                    return row;
                }
            }
            for (SettingRow row : rows) {
                if (optionId.equals(entry.moduleId() + ":" + row.id())) {
                    return row;
                }
            }
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn("[SBS][Wizard] Could not list settings for '{}'.",
                    entry.moduleId(), t);
        }
        return null;
    }

    private static boolean safeAvailable(WizardPage page) {
        try {
            return page.available();
        } catch (Throwable t) {
            SkyblockSimplifiedSBS.LOGGER.warn(
                    "[SBS][Wizard] Page '{}' threw from available() - treated as unavailable.",
                    page.id(), t);
            return false;
        }
    }
}
