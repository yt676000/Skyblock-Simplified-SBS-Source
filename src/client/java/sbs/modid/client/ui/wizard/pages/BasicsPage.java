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
 * First-run page: how bright the world is, and how far you can see.
 *
 * <p>These are the settings people otherwise find by accident weeks later, and the two that change
 * the game most on sight. Dark Mode in particular is three separate controls - the time of day, the
 * flat brightness, and how far blocks themselves are darkened - and picking one without the others
 * gives a result nobody wants, which is why they are offered together rather than one at a time.
 *
 * <p>Every row here already has a working default, so skipping the page changes nothing and switches
 * nothing on that the player did not choose.
 */
public final class BasicsPage implements WizardPage {

    @Override
    public String id() {
        return "basics_display";
    }

    @Override
    public String title() {
        return "Light and distance";
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
        return 200;
    }

    @Override
    public List<PageElement> content() {
        return List.of(
                new PageElement.Text("Leave these alone if you like how the game looks now. All of "
                        + "them live under Visuals afterwards."),
                new PageElement.Heading("Dark Mode"),
                new PageElement.Setting("dark_mode:client_side_time"),
                new PageElement.Setting("dark_mode:time_of_day"),
                new PageElement.Setting("dark_mode:uniform_brightness"),
                new PageElement.Setting("dark_mode:brightness"),
                new PageElement.Setting("dark_mode:darken_blocks"),
                new PageElement.Setting("dark_mode:block_darkness"),
                new PageElement.Heading("View distance"),
                new PageElement.Setting("far_terrain:render_distance"));
    }
}
