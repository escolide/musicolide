package io.github.escolide.musicolide.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class FfmpegDurationParserTest {
    @Test
    void parsesStandardFfmpegDurationLine() throws Exception {
        String diagnostic = """
                Input #0, matroska,webm, from 'track.webm':
                  Metadata:
                    ENCODER         : Lavf
                  Duration: 00:03:12.45, start: 0.000000, bitrate: 128 kb/s
                  Stream #0:0: Audio: opus, 48000 Hz, stereo
                """;

        assertEquals(192_450L, FfmpegDurationParser.parseMillis(diagnostic));
    }

    @Test
    void parsesLongHourAndMillisecondPrecisionDurations() throws Exception {
        assertEquals(3_723_456L,
                FfmpegDurationParser.parseMillis("Duration: 01:02:03.456, bitrate: 0 kb/s"));
    }

    @Test
    void roundsSubMillisecondPrecision() throws Exception {
        assertEquals(1_235L,
                FfmpegDurationParser.parseMillis("Duration: 00:00:01.2346, bitrate: 0 kb/s"));
    }

    @Test
    void rejectsMissingOrUnknownDuration() {
        assertThrows(IOException.class, () -> FfmpegDurationParser.parseMillis("Duration: N/A"));
        assertThrows(IOException.class, () -> FfmpegDurationParser.parseMillis("Input opened."));
    }

    @Test
    void rejectsInvalidMinuteAndSecondFields() {
        assertThrows(IOException.class,
                () -> FfmpegDurationParser.parseMillis("Duration: 00:60:00.00"));
        assertThrows(IOException.class,
                () -> FfmpegDurationParser.parseMillis("Duration: 00:00:60.00"));
    }
}
