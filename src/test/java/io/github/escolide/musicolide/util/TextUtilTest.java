package io.github.escolide.musicolide.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextUtilTest {
    @Test
    void truncatesByUnicodeCodePointWithoutSplittingSurrogatePairs() {
        String result = TextUtil.truncate("A😀B🎵C", 4);
        assertEquals("A...", result);
        assertEquals(4, result.codePointCount(0, result.length()));
    }

    @Test
    void keepsShortMultilingualTitlesUnchanged() {
        assertEquals("夜に駆ける", TextUtil.truncate("夜に駆ける", 20));
        assertEquals("مرحبا", TextUtil.truncate("مرحبا", 20));
    }

    @Test
    void handlesSmallLimitsAndNullValues() {
        assertEquals("..", TextUtil.truncate("long", 2));
        assertEquals("", TextUtil.truncate(null, 20));
        assertEquals("", TextUtil.truncate("text", 0));
    }
}
