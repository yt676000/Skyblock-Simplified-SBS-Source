/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.ui.settings;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import sbs.modid.client.ui.component.SciFiButton;
import sbs.modid.client.ui.component.SciFiCycleButton;
import sbs.modid.client.ui.component.SciFiRangeSlider;
import sbs.modid.client.ui.component.SciFiToggleButton;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One row of a module's settings page in the two-column config screen: a searchable label plus a
 * factory that creates the matching SBS widget (toggle / cycle / slider / button / plain label) at
 * the position the settings panel assigns. Each module's options are declared once in
 * {@link ModuleSettings} and reused for both rendering and the real-time search.
 */
public final class SettingRow {

    /** Creates the widget at the given bounds. */
    public interface WidgetFactory {
        AbstractWidget create(int x, int y, int width, int height);
    }

    private final String label;
    private final WidgetFactory factory;
    /** True for a plain explanatory {@link #label} row (not an interactive setting). */
    private final boolean isLabel;
    /** The hover description; a row without one simply shows no tooltip. */
    private String description;
    /** Greyed out and unclickable – see {@link #disabled()}. */
    private boolean disabled;

    /** Needs an SBS licence token – see {@link #licenced(String)}. Still fully usable. */
    private boolean licenced;

    /** What the feature still does with no token; {@code ""} when nothing does. */
    private String licenceNote = "";

    /** Unfinished and able to be wrong – see {@link #inDevelopment()}. */
    private boolean inDevelopment;

    /** Explicit stable id, or {@code null} to derive one from the label. See {@link #anchor}. */
    private String anchorId;

    /** Ids this row used to be known by, so a stored reference to an old one still resolves. */
    private List<String> aliases = List.of();

    /** Set only when the row is shown outside its own module's page. See {@link #owner}. */
    private String ownerModuleId;

    private SettingRow(String label, WidgetFactory factory) {
        this(label, factory, false);
    }

    private SettingRow(String label, WidgetFactory factory, boolean isLabel) {
        this.label = label;
        this.factory = factory;
        this.isLabel = isLabel;
    }

    public String label() {
        return label;
    }

    /** Whether this row is a plain explanatory label rather than an interactive setting. */
    public boolean isLabel() {
        return isLabel;
    }

    /** The beginner-friendly hover description, or {@code null} when the row has none. */
    public String description() {
        return description;
    }

    /**
     * Attaches the hover description (fluent). Write it for someone seeing the setting for the first
     * time: what it does in the game, where it shows up, and – when it is not obvious – why one
     * would want it on or off. One to three plain sentences.
     */
    public SettingRow describe(String description) {
        this.description = description;
        return this;
    }

    /**
     * Renders the row greyed out and ignores clicks on it, while still showing its label and
     * description.
     *
     * <p>For a setting that exists but cannot be used yet. Hiding such a row instead would be
     * easier, but the privacy screen in particular has to be able to list a purpose and say
     * truthfully "nothing is sent for this yet" - a switch quietly missing from the list is
     * indistinguishable from a switch we forgot to tell you about.
     */
    public SettingRow disabled() {
        this.disabled = true;
        return this;
    }

    /**
     * {@link #disabled()} when {@code condition} holds, for a row whose availability is decided at
     * build time - the operating system, a missing capability, a feature switched off above it.
     *
     * <p>Exists so a conditional row stays one readable chain inside the page's {@code List.of(...)}
     * rather than being lifted out into a local and re-assigned, which is where "and pair it with a
     * label saying why" stops happening.
     */
    public SettingRow disabledIf(boolean condition) {
        return condition ? disabled() : this;
    }

    /** Whether {@link #disabled()} was applied. */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Marks this row as needing an SBS licence token, so the config screen can paint it red while
     * none is set. See {@code core/licence/LicenceMarks}.
     *
     * <p>Deliberately <b>not</b> a disable: the row stays fully usable. A player without a token is
     * allowed to turn a feature on and have it waiting for the day they get one, and greying it out
     * would also hide the setting from anyone deciding whether a licence is worth it.
     *
     * @param withoutToken what the feature still does with no token - "" when the answer is nothing.
     *                     Shown in the tooltip, because a red row that quietly works fine teaches
     *                     players to ignore the colour.
     */
    public SettingRow licenced(String withoutToken) {
        this.licenced = true;
        this.licenceNote = withoutToken == null ? "" : withoutToken;
        return this;
    }

    /** {@link #licenced(String)} for a feature that does nothing at all without a token. */
    public SettingRow licenced() {
        return licenced("");
    }

    /** Whether {@link #licenced} was applied. */
    public boolean isLicenced() {
        return licenced;
    }

    /** What still works without a token, or {@code ""} when nothing does. Never {@code null}. */
    public String licenceNote() {
        return licenceNote;
    }

    /**
     * Marks this row as a feature that is still being built and can be wrong, so its hover text
     * carries the same warning its screen does ({@code ui/render/DevNotice}).
     *
     * <p>For the features that answer "what is this worth" - the calculators, the flip rankings, the
     * appraisal. The row is where a player meets one of those <i>before</i> switching it on, and a
     * warning that only appears on the screen afterwards has arrived after the decision it was meant
     * to inform.
     *
     * <p>Independent of {@link #licenced(String)}: a token makes the numbers better sourced, not
     * finished. Rows carrying both say both, in that order.
     */
    public SettingRow inDevelopment() {
        this.inDevelopment = true;
        return this;
    }

    /** Whether {@link #inDevelopment()} was applied. */
    public boolean isInDevelopment() {
        return inDevelopment;
    }

    /**
     * Pins this row's stable id, plus any ids it used to have.
     *
     * <p><b>When you need it.</b> A row without an anchor derives its id from its label, which is
     * stable against reordering but not against renaming – rename the label and anything stored
     * against the old id (a favourite, a jump target) no longer resolves. Anchoring a row makes its
     * id independent of what it is called.
     *
     * <p><b>The aliases are the insurance.</b> "Anchor it before you rename it" is a rule people
     * forget, and the cost of forgetting is silent. Passing the previous ids – the old anchor, or
     * the slug of the old label – keeps stored references working through the rename, and they can
     * be dropped again a few releases later.
     *
     * <p>Required on rows whose label is not unique within their module; {@code OptionIndex} audits
     * that on startup.
     */
    public SettingRow anchor(String id, String... previousIds) {
        this.anchorId = id;
        this.aliases = previousIds.length == 0 ? List.of() : List.of(previousIds);
        return this;
    }

    /**
     * This row's stable id within its module: the {@link #anchor} if it has one, else a slug of the
     * label. Not globally unique on its own – the module id is what scopes it.
     */
    public String id() {
        return anchorId != null ? anchorId : slug(label);
    }

    /** Whether the id is pinned rather than derived from the label. */
    public boolean hasAnchor() {
        return anchorId != null;
    }

    /**
     * Marks which module this row really belongs to, for a row rendered on someone else's page.
     *
     * <p>The favourites page shows rows owned by other modules, and a row's full id is only
     * meaningful together with its module – without this, a favourite shown there would compute its
     * id against the page it is displayed on and the star next to it would unfavourite nothing.
     */
    public SettingRow owner(String moduleId) {
        this.ownerModuleId = moduleId;
        return this;
    }

    /** The module this row belongs to, or {@code null} when it is on its own module's page. */
    public String ownerModuleId() {
        return ownerModuleId;
    }

    /** Ids this row previously answered to; empty for almost every row. */
    public List<String> aliases() {
        return aliases;
    }

    /**
     * A label reduced to a stable key: lowercase, runs of anything but letters and digits collapsed
     * to a single underscore.
     *
     * <p>Deliberately not the raw label. Labels carry punctuation, casing and the odd unit that all
     * change without anyone thinking of it as a rename ("Repeat Every" → "Repeat every (min)"), and
     * a key that survives those is worth more than one that reads exactly like the label.
     */
    public static String slug(String label) {
        StringBuilder out = new StringBuilder(label.length());
        boolean pendingSeparator = false;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSeparator && !out.isEmpty()) {
                    out.append('_');
                }
                pendingSeparator = false;
                out.append(Character.toLowerCase(c));
            } else {
                pendingSeparator = true;
            }
        }
        return out.toString();
    }

    public AbstractWidget create(int x, int y, int width, int height) {
        AbstractWidget widget = factory.create(x, y, width, height);
        if (disabled) {
            widget.active = false;
        }
        return widget;
    }

    /**
     * Case-insensitive search match on the row's label <b>and its description</b>.
     *
     * <p>The description has to count, and it is the half that does the work. A label is the name we
     * chose for a setting; the description is the sentence saying what it does in the words somebody
     * would use who does not already know that name. Searching labels alone means the search only
     * finds a feature for a player who could already have found it by scrolling - "hide my name"
     * turns up nothing, because the row is called Streamer Mode.
     *
     * <p>This is what {@code ui/AGENTS.md} has always said the search does. It did not, and the gap
     * was invisible from the outside: a search that finds nothing looks exactly like a mod that has
     * no such feature.
     */
    public boolean matches(String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return label.toLowerCase(Locale.ROOT).contains(needle)
                || (description != null && description.toLowerCase(Locale.ROOT).contains(needle));
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------

    public static SettingRow toggle(String label, BooleanSupplier state, Runnable onToggle) {
        return new SettingRow(label, (x, y, w, h) ->
                new SciFiToggleButton(x, y, w, h, Component.literal(label), state, onToggle));
    }

    /**
     * A multi-option row that ADVANCES on click. Prefer {@link #options} or {@link #enumOptions}:
     * clicking a value forward one step at a time is fine for two states and miserable for eight,
     * and a dropdown also shows what the other choices are before you commit to one.
     *
     * <p>Kept for the handful of rows whose choices cannot be enumerated up front.
     */
    public static SettingRow cycle(String label, Supplier<String> value, Runnable onCycle) {
        return new SettingRow(label, (x, y, w, h) ->
                new SciFiCycleButton(x, y, w, h, Component.literal(label),
                        () -> Component.literal(value.get()), onCycle));
    }

    /**
     * A multi-option row that opens a list of every choice and jumps straight to the one picked.
     *
     * @param options every selectable value, in display order
     * @param value   the current value; must be one of {@code options}
     * @param onPick  applies the chosen value (and saves)
     */
    public static SettingRow options(String label, Supplier<List<String>> options,
                                     Supplier<String> value, java.util.function.Consumer<String> onPick) {
        return new SettingRow(label, (x, y, w, h) ->
                new sbs.modid.client.ui.component.SciFiDropdown(x, y, w, h, label,
                        options, value, onPick));
    }

    /**
     * A choice of <b>two to five</b> named modes, every one of them visible and one click away.
     *
     * <p>This is the row {@code ui/AGENTS.md} requires at that option count, and it is not
     * interchangeable with {@link #options}: a dropdown hides the choices behind a click, which is
     * the right trade at fifteen options and the wrong one at two. Nor is it a {@link #toggle} - that
     * is for values which are genuinely on and off, and a pair of named modes squeezed into ON/OFF
     * makes the row say something other than what it means.
     *
     * @param options the mode labels, left to right; their indices are what {@code selected} returns
     *                and {@code onSelect} receives
     */
    public static SettingRow segmented(String label, List<String> options, IntSupplier selected,
                                       IntConsumer onSelect) {
        return new SettingRow(label, (x, y, w, h) ->
                new sbs.modid.client.ui.component.SciFiSegmentedRow(x, y, w, h,
                        Component.literal(label), options, selected, onSelect));
    }

    /**
     * The enum form of {@link #options}, which is what nearly every multi-choice setting really is:
     * the option list comes from the enum's own constants, so adding a constant adds an option and
     * no call site has to list them twice.
     *
     * @param name how a constant is displayed (usually {@code Enum::displayName})
     */
    public static <E extends Enum<E>> SettingRow enumOptions(String label, Supplier<E> value,
                                                             java.util.function.Consumer<E> onPick,
                                                             java.util.function.Function<E, String> name) {
        Supplier<List<String>> options = () -> {
            E current = value.get();
            if (current == null) {
                return List.of();
            }
            List<String> out = new java.util.ArrayList<>();
            for (E constant : current.getDeclaringClass().getEnumConstants()) {
                out.add(name.apply(constant));
            }
            return out;
        };
        return options(label, options,
                () -> {
                    E current = value.get();
                    return current == null ? "" : name.apply(current);
                },
                picked -> {
                    E current = value.get();
                    if (current == null) {
                        return;
                    }
                    for (E constant : current.getDeclaringClass().getEnumConstants()) {
                        if (name.apply(constant).equals(picked)) {
                            onPick.accept(constant);
                            return;
                        }
                    }
                });
    }

    /**
     * The row for a bounded number - a percentage, an opacity, a radius, a delay, a count: label
     * left, a bounded track with a handle on the right, the live value in a box at the far edge.
     * {@code unit} is printed straight after the number ({@code "%"}, {@code "s"}, {@code ""}).
     *
     * <p>This is the only slider factory, and deliberately so. The full-width variant it replaced
     * treated the whole row as its track, so clicking a row to read its tooltip moved the value -
     * see {@code ui/AGENTS.md}, "Numeric rows", for the anatomy every such row now follows.
     */
    public static SettingRow rangeSlider(String label, int min, int max, IntSupplier value,
                                         IntConsumer onChange, String unit) {
        return new SettingRow(label, (x, y, w, h) ->
                new SciFiRangeSlider(x, y, w, h,
                        Component.literal(label), min, max, value.getAsInt(), unit, onChange));
    }

    public static SettingRow button(String label, Runnable action) {
        return new SettingRow(label, (x, y, w, h) ->
                new SciFiButton(x, y, w, h, Component.literal(label), action));
    }

    public static SettingRow label(String text) {
        return new SettingRow(text, (x, y, w, h) ->
                new sbs.modid.client.ui.component.SciFiLabel(x, y, w, h, Component.literal(text)), true);
    }

    /**
     * A colour row: the label, a swatch of the colour currently in effect, and {@code onPress} -
     * normally opening the colour picker. {@code fallback} is the colour used while the config value
     * is empty, so the swatch shows the real thing instead of black.
     */
    public static SettingRow color(String label, Supplier<String> hex, IntSupplier fallback,
                                   Runnable onPress) {
        return new SettingRow(label, (x, y, w, h) ->
                new sbs.modid.client.ui.component.SciFiColorButton(x, y, w, h,
                        Component.literal(label), hex, fallback, onPress));
    }

    /**
     * A key-capture row for a plain int bind-code config field ({@code 0} = unbound).
     *
     * <p>Takes a key, any of the eight mouse buttons or a wheel direction – see
     * {@code core/keybind/Keys}, which owns the encoding.
     */
    public static SettingRow keybind(String label, IntSupplier key, IntConsumer onAssign) {
        return new SettingRow(label, (x, y, w, h) ->
                new sbs.modid.client.ui.component.SciFiKeyCaptureButton(x, y, w, h, label, key, onAssign));
    }

    /**
     * A key-capture row for a feature that asks "is this held right now" rather than reacting to a
     * press – Zoom, Slot Lock, the Far Terrain toggles.
     *
     * <p>Same row, minus the wheel: a wheel notch has no held state, so a wheel bind here would be
     * stored and then never fire. The field refuses it and says so instead.
     */
    public static SettingRow holdKeybind(String label, IntSupplier key, IntConsumer onAssign) {
        return new SettingRow(label, (x, y, w, h) ->
                new sbs.modid.client.ui.component.SciFiKeyCaptureButton(x, y, w, h, label, key, onAssign,
                        true));
    }

    /**
     * A single-line text-input row bound to a String config value (persisted on every edit).
     *
     * <p>The label stays visible beside the input, like every other row type. The alternative -
     * a bare box carrying only a hint - reads fine while empty and becomes anonymous the moment
     * it holds a value, which is no way to label a setting.
     *
     * <p>The input is sized to the value rather than the row: long values still fit, because the
     * box scrolls its text, and a short one does not leave a field stretching across the page.
     */
    public static SettingRow text(String label, String hint, int maxLength,
                                  Supplier<String> getter, java.util.function.Consumer<String> setter) {
        int columns = Math.max(4, Math.min(14, maxLength));
        return new SettingRow(label, (x, y, w, h) ->
                sbs.modid.client.ui.component.SciFiTextField.forValueRow(x, y, w, h, label, hint,
                        maxLength, columns, getter, setter));
    }

    /**
     * Like {@link #text} but the value is masked ({@code ****}) while {@code reveal} is false – a
     * password field. Editing / pasting / persisting are unaffected; only the drawn glyphs hide.
     */
    public static SettingRow maskedText(String label, String hint, int maxLength,
                                        Supplier<String> getter, java.util.function.Consumer<String> setter,
                                        java.util.function.BooleanSupplier reveal) {
        return new SettingRow(label, (x, y, w, h) ->
                sbs.modid.client.ui.component.SciFiTextField.forMaskedRow(x, y, w, h, label, hint,
                        maxLength, getter, setter, reveal));
    }

    /**
     * A labelled text-input row: the label on the left, a {@code columns}-wide input on the right.
     * Preferred over {@link #text} whenever several such rows sit together, since that one draws the
     * whole row as one unlabelled box.
     */
    public static SettingRow valueField(String label, String hint, int maxLength, int columns,
                                        Supplier<String> getter,
                                        java.util.function.Consumer<String> setter) {
        return new SettingRow(label, (x, y, w, h) ->
                sbs.modid.client.ui.component.SciFiTextField.forValueRow(x, y, w, h, label, hint,
                        maxLength, columns, getter, setter));
    }

    /**
     * A numeric-input row bound to an int config value: digits-only field with a unit suffix
     * ("%") behind it, clamped to {@code [min, max]} on every edit.
     */
    public static SettingRow intField(String label, int min, int max, IntSupplier value,
                                      IntConsumer onChange, String unit) {
        return new SettingRow(label, (x, y, w, h) ->
                sbs.modid.client.ui.component.SciFiTextField.forIntRow(x, y, w, h, label, min, max,
                        value, onChange, unit));
    }
}
