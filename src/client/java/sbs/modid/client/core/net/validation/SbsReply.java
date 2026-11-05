/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.net.validation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sbs.modid.client.core.net.RemoteText;

/**
 * An SBS server reply: either the validated message of the expected type, or the server's
 * validated error object ({@link MessageTypes#ERROR}). Anything else is a {@link ValidationException}.
 *
 * @param data  the validated message, or the validated error object for an error reply
 * @param error the sanitized error code or text, or {@code null} for a message
 */
public record SbsReply(JsonObject data, String error) {

    /** Validates {@code body} as {@code typeId}, or as the shared error object when it is one. */
    public static SbsReply parse(String typeId, String body) throws ValidationException {
        MessageSchemas.Entry entry = MessageSchemas.entry(typeId);
        if (entry == null) {
            throw new ValidationException(typeId, ValidationException.Kind.UNKNOWN_TYPE, "");
        }
        JsonElement tree;
        try {
            tree = GuardedJson.parse(body, entry.limits());
        } catch (ValidationException invalid) {
            throw MessageSchemas.rejectLogged(invalid.withType(typeId));
        }
        if (!tree.isJsonObject()) {
            throw MessageSchemas.rejectLogged(new ValidationException(typeId, ValidationException.Kind.WRONG_TYPE, "$"));
        }
        JsonObject object = tree.getAsJsonObject();
        JsonElement error = object.get("error");
        if (error != null && !error.isJsonNull()) {
            try {
                MessageSchemas.entry(MessageTypes.ERROR).schema().validate(object);
            } catch (ValidationException invalid) {
                throw MessageSchemas.rejectLogged(invalid.withType(MessageTypes.ERROR));
            }
            return new SbsReply(object, RemoteText.sanitize(error.getAsString(), 200));
        }
        try {
            entry.schema().validate(object);
        } catch (ValidationException invalid) {
            throw MessageSchemas.rejectLogged(invalid.withType(typeId));
        }
        return new SbsReply(object, null);
    }

    public boolean isError() {
        return error != null;
    }
}
