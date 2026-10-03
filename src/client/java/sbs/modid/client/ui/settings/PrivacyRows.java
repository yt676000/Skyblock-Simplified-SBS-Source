/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings;

import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import sbs.modid.client.core.api.GuiStateManager;
import sbs.modid.client.core.licence.privacy.ConsentManager;
import sbs.modid.client.core.licence.privacy.ConsentScope;
import sbs.modid.client.core.licence.privacy.ConsentSource;
import sbs.modid.client.core.licence.privacy.PrivacyLinks;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Privacy &amp; data" section of the Licence Token page: one row per {@link ConsentScope}, the
 * two bulk buttons, where the notice lives, and - kept deliberately apart from all of that - the
 * support link.
 *
 * <p>Kept out of {@link ModuleSettings} - which declares every other module's rows - because this
 * section is generated from the scope list rather than written out by hand. That is deliberate:
 * adding a scope to the enum makes a row appear here automatically, so it is not possible to ship a
 * purpose that the client sends data for but never shows the user. A hand-written list would make
 * that omission a one-line mistake.
 *
 * <p><b>Wording lives in the language files</b>, keyed by {@link ConsentScope#id()}. The strings are
 * resolved through {@link I18n#get} rather than {@code Component.translatable} only because
 * {@link SettingRow} works in {@code String}; the lang files are ordinary Minecraft resources and
 * translate normally.
 */
public final class PrivacyRows {

    private PrivacyRows() {
    }

    /** The whole section, in display order. */
    public static List<SettingRow> rows() {
        List<SettingRow> rows = new ArrayList<>();

        rows.add(SettingRow.label("§l" + I18n.get("sbs.privacy.tab.title")));
        rows.add(SettingRow.label(I18n.get("sbs.privacy.tab.intro")));
        rows.add(SettingRow.label("§8" + I18n.get("sbs.privacy.tab.nothing_preticked")));

        // Accept and decline are adjacent, same widget, same size, declining first. Consent that is
        // only meaningful when refusing is as easy as agreeing.
        rows.add(SettingRow.button(I18n.get("sbs.privacy.button.decline_all"),
                        () -> ConsentManager.getInstance().revokeAll(ConsentSource.DECLINE_ALL))
                .anchor("privacy_decline_all")
                .describe(I18n.get("sbs.privacy.scope.licence_validation.decline")));
        rows.add(SettingRow.button(I18n.get("sbs.privacy.button.accept_all"),
                        () -> ConsentManager.getInstance().grantAll(ConsentSource.ACCEPT_ALL))
                .anchor("privacy_accept_all"));

        rows.add(SettingRow.label("§8" + I18n.get("sbs.privacy.label.details_hint")));

        for (ConsentScope scope : ConsentScope.values()) {
            rows.add(row(scope));
        }

        rows.add(SettingRow.label("§l" + I18n.get("sbs.privacy.tab.title") + " §r§7- your rights"));
        // Still a label while the notice is unpublished: a button that 404s reads as "there is no
        // notice" rather than "it is not online yet". PrivacyLinks.PUBLISHED retires this line and
        // repoints the button below in one edit, so the two cannot get out of step.
        if (!PrivacyLinks.PUBLISHED) {
            rows.add(SettingRow.label("§8" + I18n.get("sbs.privacy.label.notice")));
        }
        // No address on this screen on purpose. It would be a fourth copy of a string that also
        // lives on the website and in the Impressum, and the copy that is never the one updated is
        // always the one in the client - it ships in a jar people keep for months.
        rows.add(SettingRow.label("§8" + I18n.get("sbs.privacy.label.contact_website")));
        link(rows, PrivacyLinks.privacyUrl(), "sbs.privacy.button.website",
                "sbs.privacy.button.website.describe", "privacy_website");
        rows.add(SettingRow.label("§8" + I18n.get("sbs.privacy.label.stored")));

        support(rows);
        return rows;
    }

    /**
     * Support and bug reports: a Discord link, under its own heading and after everything above.
     *
     * <p><b>The separation is the point, not decoration.</b> Exercising a data protection right must
     * not require an account with a third party, so this cannot be allowed to read as "the place to
     * ask about your data" - which is exactly how a chat link directly beneath a privacy paragraph
     * would read. Hence its own heading, its own position after the section closes, and a
     * disclaimer that says in words what it is not for. It is also why the removed e-mail address
     * was not simply replaced by this invite.
     */
    private static void support(List<SettingRow> rows) {
        String url = PrivacyLinks.discord();
        if (url.isEmpty()) {
            return;     // nothing to head, so the heading goes too - see link()
        }
        rows.add(SettingRow.label("§l" + I18n.get("sbs.privacy.support.title")));
        link(rows, url, "sbs.privacy.button.discord", "sbs.privacy.button.discord.describe",
                "privacy_discord");
        rows.add(SettingRow.label("§8" + I18n.get("sbs.privacy.label.discord_disclaimer")));
    }

    /**
     * One outbound-link row, or nothing at all when the URL is not configured.
     *
     * <p>The label is built from the URL's own host, so the button names where it is about to send
     * you and cannot go stale when the address changes in {@code fabric.mod.json} and the wording
     * does not. Confirmation is Minecraft's own {@link ConfirmLinkScreen}, the same prompt the rest
     * of the mod's links use - the player sees the full URL and can copy it instead of opening it.
     */
    private static void link(List<SettingRow> rows, String url, String labelKey, String describeKey,
                             String anchorId) {
        if (url.isEmpty()) {
            return;     // PrivacyLinks has already logged why; a dead button would say nothing
        }
        rows.add(SettingRow.button(I18n.get(labelKey, PrivacyLinks.host(url)), () -> open(url))
                .anchor(anchorId)
                .describe(I18n.get(describeKey) + "\n\n" + url));
    }

    /**
     * Opens an external URL through the vanilla confirmation prompt.
     *
     * <p>The parent comes from {@link GuiStateManager}, not from {@code Minecraft}: the current
     * screen is a field on 26.1.2 and a method on 26.2, and that accessor is the one place that
     * difference is handled.
     */
    private static void open(String url) {
        Screen parent = GuiStateManager.getInstance().getCurrentScreen();
        if (parent != null) {
            ConfirmLinkScreen.confirmLinkNow(parent, url);
        }
    }

    /**
     * One scope's row: a toggle when it is a real choice, a disabled row when the scope is declared
     * but nothing sends for it, and a plain label for the contract term that has no choice attached.
     */
    private static SettingRow row(ConsentScope scope) {
        String title = I18n.get(scope.titleKey());

        if (scope.kind() == ConsentScope.Kind.CONTRACT) {
            return SettingRow.label("§7" + title + " §8- " + I18n.get("sbs.privacy.state.contract"))
                    .describe(detail(scope));
        }
        if (scope.isPlanned()) {
            // Rendered as a switch so the row reads as the choice it will become, but inert and
            // greyed: it cannot be turned on, and the tooltip says why in the user's own terms.
            return SettingRow.toggle(title + " §8(" + I18n.get("sbs.privacy.state.planned") + ")",
                            () -> false, () -> { })
                    .anchor("privacy_scope_" + scope.id())
                    .disabled()
                    .describe(I18n.get("sbs.privacy.state.planned.tooltip") + "\n\n" + detail(scope));
        }
        return SettingRow.toggle(title,
                        () -> ConsentManager.isGranted(scope),
                        () -> toggle(scope))
                .anchor("privacy_scope_" + scope.id())
                .describe(detail(scope));
    }

    private static void toggle(ConsentScope scope) {
        ConsentManager manager = ConsentManager.getInstance();
        if (ConsentManager.isGranted(scope)) {
            manager.revoke(scope, ConsentSource.USER);
        } else {
            manager.grant(scope, ConsentSource.USER);
        }
    }

    /**
     * The full disclosure for one scope, in the order a person actually asks: what leaves my
     * machine, why, who ends up with it, for how long, and what I lose by refusing.
     */
    private static String detail(ConsentScope scope) {
        StringBuilder text = new StringBuilder();
        text.append(I18n.get("sbs.privacy.detail.data")).append(' ')
                .append(I18n.get(scope.dataKey())).append("\n\n");
        text.append(I18n.get("sbs.privacy.detail.why")).append(' ')
                .append(I18n.get(scope.whyKey())).append("\n\n");
        text.append(I18n.get("sbs.privacy.detail.recipients")).append(' ')
                .append(I18n.get(scope.recipientsKey())).append("\n\n");
        text.append(I18n.get("sbs.privacy.detail.retention")).append(' ')
                .append(I18n.get(scope.retentionKey())).append("\n\n");
        text.append(I18n.get("sbs.privacy.detail.decline")).append(' ')
                .append(I18n.get(scope.declineKey()));

        // Only shown when it applies: a scope that reset itself needs to explain the reset, or the
        // user is left thinking the toggle failed.
        if (ConsentManager.getInstance().registry().lapsed(scope)) {
            text.append("\n\n§e").append(I18n.get("sbs.privacy.state.lapsed"));
        }
        return text.toString();
    }

}
