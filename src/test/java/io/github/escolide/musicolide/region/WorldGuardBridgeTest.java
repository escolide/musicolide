package io.github.escolide.musicolide.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldGuardBridgeTest {
    @Test
    void usesWorldEditsBukkitAdapterPackage() {
        assertEquals("com.sk89q.worldedit.bukkit.BukkitAdapter",
                WorldGuardBridge.BUKKIT_ADAPTER_CLASS);
    }
}
