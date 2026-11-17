package io.github.escolide.musicolide.region;

import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/** Optional WorldGuard adapter loaded reflectively so the plugin still starts without WorldGuard. */
public final class WorldGuardBridge {
    static final String BUKKIT_ADAPTER_CLASS = "com.sk89q.worldedit.bukkit.BukkitAdapter";

    private final JavaPlugin plugin;
    private final Method adapt;
    private final Method getRegions;
    private final Method getId;
    private volatile boolean available = true;
    private volatile boolean failureLogged;

    public WorldGuardBridge(JavaPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        Class<?> adapter = Class.forName(BUKKIT_ADAPTER_CLASS);
        this.adapt = adapter.getMethod("adapt", Location.class);
        Class<?> regionQuery = Class.forName("com.sk89q.worldguard.protection.regions.RegionQuery");
        this.getRegions = regionQuery.getMethod("getApplicableRegions",
                Class.forName("com.sk89q.worldedit.util.Location"));
        this.getId = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion").getMethod("getId");
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isInside(Location location, String regionId) {
        if (!available || location == null || regionId == null || regionId.isBlank()) return false;
        try {
            Class<?> worldGuard = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object instance = worldGuard.getMethod("getInstance").invoke(null);
            Object platform = worldGuard.getMethod("getPlatform").invoke(instance);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query = container.getClass().getMethod("createQuery").invoke(container);
            Object adapted = adapt.invoke(null, location);
            Object applicable = getRegions.invoke(query, adapted);
            Object regions = applicable.getClass().getMethod("getRegions").invoke(applicable);
            if (!(regions instanceof Iterable<?> iterable)) return false;
            for (Object region : iterable) {
                if (regionId.equalsIgnoreCase(String.valueOf(getId.invoke(region)))) return true;
            }
        } catch (ReflectiveOperationException | LinkageError error) {
            available = false;
            if (!failureLogged) {
                failureLogged = true;
                plugin.getLogger().warning("WorldGuard region checks have been disabled because the runtime API "
                        + "became unavailable or incompatible: " + error.getClass().getSimpleName()
                        + ": " + String.valueOf(error.getMessage()));
            }
        }
        return false;
    }
}
