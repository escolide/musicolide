package io.github.escolide.musicolide.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackManagerStatusTest {
    @Test
    void acceptedAndDownloadedRemainInFlight() {
        assertTrue(PackManager.isPendingStatus("ACCEPTED"));
        assertTrue(PackManager.isPendingStatus("DOWNLOADED"));
    }

    @Test
    void terminalStatusesFinishTheRequest() {
        assertFalse(PackManager.isPendingStatus("SUCCESSFULLY_LOADED"));
        assertFalse(PackManager.isPendingStatus("DECLINED"));
        assertFalse(PackManager.isPendingStatus("FAILED_DOWNLOAD"));
        assertFalse(PackManager.isPendingStatus("FAILED_RELOAD"));
        assertFalse(PackManager.isPendingStatus("INVALID_URL"));
        assertFalse(PackManager.isPendingStatus("DISCARDED"));
    }
}
