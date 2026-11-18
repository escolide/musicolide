package io.github.escolide.musicolide.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlImportArgumentsTest {
    @Test
    void acceptsUrlWithoutTitleOrConfirmationWord() {
        UrlImportArguments parsed = UrlImportArguments.parse(
                new String[]{"import-url", "https://youtu.be/example"});

        assertEquals("https://youtu.be/example", parsed.sourceUrl());
        assertNull(parsed.title());
        assertNull(parsed.customId());
    }

    @Test
    void joinsMultiWordTitle() {
        UrlImportArguments parsed = UrlImportArguments.parse(
                new String[]{"import-url", "https://youtu.be/example", "Summer", "Night", "Mix"});

        assertEquals("Summer Night Mix", parsed.title());
        assertNull(parsed.customId());
    }

    @Test
    void acceptsCustomIdWithOrWithoutTitle() {
        UrlImportArguments titled = UrlImportArguments.parse(
                new String[]{"import-url", "https://youtu.be/example", "Summer", "Mix", "id:summer_mix"});
        UrlImportArguments idOnly = UrlImportArguments.parse(
                new String[]{"import-url", "https://youtu.be/example", "id:summer_mix"});

        assertEquals("Summer Mix", titled.title());
        assertEquals("summer_mix", titled.customId());
        assertNull(idOnly.title());
        assertEquals("summer_mix", idOnly.customId());
    }

    @Test
    void keepsLegacyConfirmationSyntaxWorkingWithoutAdvertisingIt() {
        UrlImportArguments parsed = UrlImportArguments.parse(
                new String[]{"import-url", "https://youtu.be/example", "rights", "Summer", "Mix", "id:summer_mix"});

        assertEquals("Summer Mix", parsed.title());
        assertEquals("summer_mix", parsed.customId());
    }

    @Test
    void keepsLegacyIdOptionWorking() {
        UrlImportArguments parsed = UrlImportArguments.parse(
                new String[]{"import-url", "https://youtu.be/example", "Summer", "Mix", "--id", "summer_mix"});

        assertEquals("Summer Mix", parsed.title());
        assertEquals("summer_mix", parsed.customId());
    }

    @Test
    void rejectsMissingUrlAndIncompleteOrDuplicateIds() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlImportArguments.parse(new String[]{"import-url"}));
        assertThrows(IllegalArgumentException.class,
                () -> UrlImportArguments.parse(new String[]{"import-url", "https://youtu.be/example", "id:"}));
        assertThrows(IllegalArgumentException.class,
                () -> UrlImportArguments.parse(new String[]{"import-url", "https://youtu.be/example", "--id"}));
        assertThrows(IllegalArgumentException.class,
                () -> UrlImportArguments.parse(new String[]{"import-url", "https://youtu.be/example", "id:first", "id:second"}));
    }
}
