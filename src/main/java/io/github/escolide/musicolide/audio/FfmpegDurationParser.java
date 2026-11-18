package io.github.escolide.musicolide.audio;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the container-duration line emitted by FFmpeg while opening an input. */
final class FfmpegDurationParser {
    private static final Pattern DURATION = Pattern.compile(
            "Duration:\\s*(\\d+):(\\d{2}):(\\d{2})(?:\\.(\\d+))?");

    private FfmpegDurationParser() {}

    static long parseMillis(String diagnosticOutput) throws IOException {
        if (diagnosticOutput == null || diagnosticOutput.isBlank()) {
            throw new IOException("FFmpeg did not return input metadata.");
        }

        Matcher matcher = DURATION.matcher(diagnosticOutput);
        if (!matcher.find()) {
            throw new IOException("FFmpeg could not determine the input duration.");
        }

        try {
            long hours = Long.parseLong(matcher.group(1));
            long minutes = Long.parseLong(matcher.group(2));
            long seconds = Long.parseLong(matcher.group(3));
            String fraction = matcher.group(4);
            if (minutes > 59 || seconds > 59) {
                throw new IOException("FFmpeg returned an invalid duration.");
            }
            double fractionalSeconds = fraction == null ? 0.0 : Double.parseDouble("0." + fraction);
            double totalSeconds = hours * 3600.0 + minutes * 60.0 + seconds + fractionalSeconds;
            if (!Double.isFinite(totalSeconds) || totalSeconds <= 0.0
                    || totalSeconds > Long.MAX_VALUE / 1000.0) {
                throw new IOException("FFmpeg returned an invalid duration.");
            }
            return Math.round(totalSeconds * 1000.0);
        } catch (NumberFormatException ex) {
            throw new IOException("FFmpeg returned an invalid duration.", ex);
        }
    }
}
