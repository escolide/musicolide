package io.github.escolide.musicolide.region;

import org.bukkit.Location;

import java.lang.reflect.Method;

/** Optional WorldGuard adapter loaded reflectively so the plugin still starts without WorldGuard. */
public final class WorldGuardBridge {
    private final Method adapt;
    private final Method getRegions;
    private final Method getId;

    public WorldGuardBridge() throws ReflectiveOperationException {
        Class<?> adapter = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter");
        this.adapt = adapter.getMethod("adapt", Location.class);
        Class<?> regionQuery = Class.forName("com.sk89q.worldguard.protection.regions.RegionQuery");
        this.getRegions = regionQuery.getMethod("getApplicableRegions",
                Class.forName("com.sk89q.worldedit.util.Location"));
        this.getId = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion").getMethod("getId");
    }

    public boolean isInside(Location location, String regionId) {
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
        } catch (ReflectiveOperationException ignored) {
            // An unavailable/incompatible WorldGuard API simply makes the region non-matching.
        }
        return false;
    }
}
