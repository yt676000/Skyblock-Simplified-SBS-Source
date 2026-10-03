/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.config.share;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one {@code SBSConfig} field as safe to share between players.
 *
 * <p><b>The allowlist is this annotation, and absence is denial.</b> A field with no
 * {@code @Shareable} is neither exported nor accepted on import - there is no key list anywhere that
 * someone has to remember to update. That puts the failure in the harmless direction: forget the
 * annotation on a secret and the secret is not shared; forget it on a normal toggle and somebody
 * reports "my new setting doesn't travel", which is a bug rather than a leak.
 *
 * <p>It also survives a rename, which a list of key strings does not - a list keyed on
 * {@code "dungeons.terminalPb"} silently stops matching the day the field is renamed, and silently
 * matching nothing is indistinguishable from working.
 *
 * <p><b>Where it may not go.</b> No annotated field may be used as a URL, host, port, file path,
 * filename, command or secret. There is deliberately no {@link Kind} that can express one, and
 * {@code ShareLeakTest} fails the build on a field whose name looks like any of them - for the
 * kinds whose value could carry one; see that test for why it is scoped rather than universal.
 *
 * <p>See {@code docs/CONFIG-SHARING-DESIGN.md} for the exclusions this implements.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Shareable {

    /** What the value is, and therefore how an imported one is validated. */
    Kind value();

    /** Lower bound for {@link Kind#INT}; required for it, taken from the setting's own UI bounds. */
    int min() default 0;

    /** Upper bound for {@link Kind#INT}. */
    int max() default 0;

    /** The enum type for {@link Kind#ENUM}, whose constant <i>names</i> are the accepted values. */
    Class<? extends Enum<?>> enumType() default DefaultNone.class;

    /** Stand-in for "no enum", so the attribute can have a default. Never a real setting's type. */
    enum DefaultNone {
        NONE
    }
}
