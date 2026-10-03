/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.wizard.pages;

import sbs.modid.client.ui.wizard.PageElement;
import sbs.modid.client.ui.wizard.WizardMode;
import sbs.modid.client.ui.wizard.WizardPage;

import java.util.List;

/**
 * First-run page: which look the mod wears.
 *
 * <p>Shown as a grid of tiles, each drawn in the style it offers, because eight names in a dropdown
 * tell a first-time player nothing. Clicking one applies it immediately, so the screen they are
 * standing on becomes the preview.
 *
 * <p>First page of the flow on purpose. Everything after it is drawn in whatever they picked, which
 * makes the rest of the wizard the second half of the preview.
 */
public final class ThemePage implements WizardPage {

    @Override
    public String id() {
        return "basics_theme";
    }

    @Override
    public String title() {
        return "Pick a look";
    }

    @Override
    public WizardMode mode() {
        return WizardMode.ONBOARDING;
    }

    @Override
    public String introducedIn() {
        return "1.0.0";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public List<PageElement> content() {
        return List.of(
                new PageElement.Text("Click one to try it - the whole mod changes straight away, so "
                        + "keep clicking until you like what you see. You can change it later under "
                        + "Interface & Theme."),
                new PageElement.StylePicker());
    }
}
