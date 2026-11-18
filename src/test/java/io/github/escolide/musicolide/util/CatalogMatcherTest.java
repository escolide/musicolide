package io.github.escolide.musicolide.util;

import io.github.escolide.musicolide.model.MusicTrack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogMatcherTest {
    @Test
    void exactTitleRanksAboveUnrelatedTracks() {
        MusicTrack exact = track("night-drive", "Night Drive");
        MusicTrack other = track("rainy-day", "Rainy Day");
        var match = CatalogMatcher.best("Night Drive", "", List.of(other, exact)).orElseThrow();
        assertEquals(exact.id(), match.track().id());
        assertEquals(1.0, match.confidence());
    }

    @Test
    void weakMatchIsRejected() {
        assertTrue(CatalogMatcher.best("Completely Different", "", List.of(
                track("some-song", "Some Song"))).isEmpty());
    }

    @Test
    void strongMetadataMatchRejectsSameTitleByDifferentArtist() {
        MusicTrack wrongArtist = track("night-drive-cover", "Night Drive", "Cover Band");
        assertTrue(CatalogMatcher.bestCompatibleArtistMatch(
                "Night Drive", "Original Artist", List.of(wrongArtist)).isEmpty());
    }

    @Test
    void strongMetadataMatchAcceptsTopicSuffixForSameArtist() {
        MusicTrack saved = track("night-drive-original", "Night Drive", "Original Artist");
        var match = CatalogMatcher.bestCompatibleArtistMatch(
                "Night Drive", "Original Artist - Topic", List.of(saved)).orElseThrow();
        assertEquals(saved.id(), match.track().id());
    }

    private MusicTrack track(String id, String title) {
        return track(id, title, "");
    }

    private MusicTrack track(String id, String title, String artist) {
        return new MusicTrack(id, title, artist, "mc_" + id.replace('-', '_'),
                id + ".zip", "0000000000000000000000000000000000000000",
                60000, 1000, 60, 1024);
    }
}
