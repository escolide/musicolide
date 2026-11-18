package io.github.escolide.musicolide.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the admin-only permanent URL import command.
 *
 * Supported forms:
 *   /music import-url <url>
 *   /music import-url <url> <title...>
 *   /music import-url <url> <title...> id:<custom_id>
 *   /music import-url <url> id:<custom_id>
 *
 * The old confirmation-word position is still accepted for compatibility
 * with existing command scripts, but it is no longer required.
 */
record UrlImportArguments(String sourceUrl, String title, String customId) {
    private static final String USAGE =
            "Usage: /music import-url <url> [title...] [id:custom_name]";

    static UrlImportArguments parse(String[] args) {
        if (args == null || args.length < 2 || args[1] == null || args[1].isBlank()) {
            throw new IllegalArgumentException(USAGE);
        }

        int start = 2;
        if (args.length > start && isLegacyConfirmation(args[start])) {
            start++;
        }

        String customId = null;
        List<String> titleParts = new ArrayList<>();
        for (int i = start; i < args.length; i++) {
            String token = args[i] == null ? "" : args[i].trim();
            if (token.equalsIgnoreCase("--id")) {
                if (i + 1 >= args.length || args[i + 1] == null
                        || args[i + 1].isBlank() || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--id requires a custom ID value.");
                }
                if (customId != null) {
                    throw new IllegalArgumentException("Specify a custom ID only once.");
                }
                customId = args[++i].trim();
            } else if (token.toLowerCase(Locale.ROOT).startsWith("id:")) {
                String value = token.substring(3).trim();
                if (value.isBlank()) {
                    throw new IllegalArgumentException("id: requires a custom ID value.");
                }
                if (customId != null) {
                    throw new IllegalArgumentException("Specify a custom ID only once.");
                }
                customId = value;
            } else if (!token.isBlank()) {
                titleParts.add(token);
            }
        }

        String title = String.join(" ", titleParts).trim();
        return new UrlImportArguments(args[1].trim(), title.isBlank() ? null : title, customId);
    }

    private static boolean isLegacyConfirmation(String token) {
        if (token == null) return false;
        return token.equalsIgnoreCase("rights")
                || token.equalsIgnoreCase("rights-confirmed")
                || token.equalsIgnoreCase("--rights-confirmed");
    }
}
