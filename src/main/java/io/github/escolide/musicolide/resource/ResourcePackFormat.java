package io.github.escolide.musicolide.resource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The resource-pack metadata schema changed in Minecraft 1.21.9.
 * Legacy releases use a single pack_format integer; newer releases use
 * min_format and max_format.
 */
public record ResourcePackFormat(int major, int minor, boolean modern) {
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*$");

    public ResourcePackFormat {
        if (major < 1 || minor < 0) {
            throw new IllegalArgumentException("Invalid resource-pack format.");
        }
    }

    public static ResourcePackFormat forMinecraftVersion(String serverVersion) {
        if (serverVersion == null) {
            throw new IllegalArgumentException("Server version is unavailable.");
        }
        Matcher matcher = VERSION.matcher(serverVersion.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Cannot parse Minecraft server version: " + serverVersion);
        }

        int releaseMajor = Integer.parseInt(matcher.group(1));
        int releaseMinor = Integer.parseInt(matcher.group(2));
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));

        if (releaseMajor == 1 && releaseMinor == 21) {
            if (patch <= 1) return legacy(34);
            if (patch <= 3) return legacy(42);
            if (patch == 4) return legacy(46);
            if (patch == 5) return legacy(55);
            if (patch == 6) return legacy(63);
            if (patch <= 8) return legacy(64);
            if (patch <= 10) return modern(69, 0);
            if (patch == 11) return modern(75, 0);
        }

        if (releaseMajor == 26) {
            if (releaseMinor == 1) return modern(84, 0);
            if (releaseMinor == 2) return modern(88, 0);
            if (releaseMinor == 3) return modern(97, 1);
        }

        throw new IllegalArgumentException("Unsupported Minecraft version " + serverVersion
                + ". Musicolide supports Paper 1.21.1 through 26.3.");
    }

    private static ResourcePackFormat legacy(int format) {
        return new ResourcePackFormat(format, 0, false);
    }

    private static ResourcePackFormat modern(int major, int minor) {
        return new ResourcePackFormat(major, minor, true);
    }

    public String displayName() {
        return modern ? major + "." + minor : Integer.toString(major);
    }

    public String toPackMcmeta() {
        String fields = modern
                ? "\"min_format\":[" + major + "," + minor + "],\"max_format\":" + major
                : "\"pack_format\":" + major;
        return "{\"pack\":{" + fields + ",\"description\":\"Musicolide track pack\"}}";
    }
}
