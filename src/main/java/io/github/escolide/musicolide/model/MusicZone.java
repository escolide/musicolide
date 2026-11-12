package io.github.escolide.musicolide.model;

import java.util.UUID;

/**
 * A two-dimensional X/Z listening zone. Height is unlimited unless explicitly
 * restricted. Region checks are delegated to the optional WorldGuard bridge.
 */
public final class MusicZone {
    public enum Shape { GLOBAL, CIRCLE, SQUARE, REGION }
    public enum AudioMode { NONE, STEREO, FADE }

    private final Shape shape;
    private final UUID worldId;
    private final double centerX;
    private final double centerY;
    private final double centerZ;
    private final double radius;
    private final double width;
    private final double length;
    private final double rotationRadians;
    private final boolean verticalLimited;
    private final double verticalLimit;
    private final boolean fadeEnabled;
    private final double fadeDistance;
    private final String regionId;

    private MusicZone(Shape shape, UUID worldId, double centerX, double centerY, double centerZ,
                      double radius, double width, double length, double rotationRadians,
                      boolean verticalLimited, double verticalLimit, boolean fadeEnabled,
                      double fadeDistance, String regionId) {
        this.shape = shape;
        this.worldId = worldId;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radius = Math.max(0, radius);
        this.width = Math.max(0, width);
        this.length = Math.max(0, length);
        this.rotationRadians = rotationRadians;
        this.verticalLimited = verticalLimited;
        this.verticalLimit = Math.max(0, verticalLimit);
        this.fadeEnabled = fadeEnabled && fadeDistance > 0;
        this.fadeDistance = Math.max(0, fadeDistance);
        this.regionId = regionId;
    }

    public static MusicZone global() {
        return new MusicZone(Shape.GLOBAL, null, 0, 0, 0, 0, 0, 0, 0,
                false, 0, false, 0, null);
    }

    public static MusicZone circle(UUID worldId, double x, double y, double z, double radius,
                                   boolean verticalLimited, double verticalLimit,
                                   boolean fadeEnabled, double fadeDistance) {
        return new MusicZone(Shape.CIRCLE, worldId, x, y, z, radius, 0, 0, 0,
                verticalLimited, verticalLimit, fadeEnabled, fadeDistance, null);
    }

    public static MusicZone square(UUID worldId, double x, double y, double z, double width, double length,
                                   double rotationRadians, boolean verticalLimited, double verticalLimit,
                                   boolean fadeEnabled, double fadeDistance) {
        return new MusicZone(Shape.SQUARE, worldId, x, y, z, 0, width, length, rotationRadians,
                verticalLimited, verticalLimit, fadeEnabled, fadeDistance, null);
    }

    public static MusicZone region(UUID worldId, String regionId) {
        return new MusicZone(Shape.REGION, worldId, 0, 0, 0, 0, 0, 0, 0,
                false, 0, false, 0, regionId);
    }

    public Shape shape() { return shape; }
    public UUID worldId() { return worldId; }
    public String regionId() { return regionId; }
    public boolean fadeEnabled() { return fadeEnabled; }
    public double fadeDistance() { return fadeDistance; }

    public boolean containsBase(UUID candidateWorld, double x, double y, double z) {
        if (shape == Shape.GLOBAL) return true;
        if (worldId == null || !worldId.equals(candidateWorld)) return false;
        if (verticalLimited && Math.abs(y - centerY) > verticalLimit) return false;
        if (shape == Shape.REGION) return false;
        double dx = x - centerX;
        double dz = z - centerZ;
        if (shape == Shape.CIRCLE) {
            return dx * dx + dz * dz <= radius * radius;
        }
        double[] local = toLocal(dx, dz);
        return Math.abs(local[0]) <= width / 2.0 && Math.abs(local[1]) <= length / 2.0;
    }

    public boolean containsActive(UUID candidateWorld, double x, double y, double z) {
        if (containsBase(candidateWorld, x, y, z)) return true;
        if (!fadeEnabled || shape == Shape.GLOBAL || shape == Shape.REGION
                || worldId == null || !worldId.equals(candidateWorld)) return false;
        if (verticalLimited && Math.abs(y - centerY) > verticalLimit) return false;
        return distanceOutsideBase(x, z) <= fadeDistance;
    }

    public AudioMode audioMode(UUID candidateWorld, double x, double y, double z) {
        if (containsBase(candidateWorld, x, y, z)) return AudioMode.STEREO;
        if (fadeEnabled && containsActive(candidateWorld, x, y, z)) return AudioMode.FADE;
        return AudioMode.NONE;
    }

    public double fadeFactor(double x, double z) {
        if (!fadeEnabled || fadeDistance <= 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, 1.0 - distanceOutsideBase(x, z) / fadeDistance));
    }

    public double distanceOutsideBase(double x, double z) {
        if (shape == Shape.CIRCLE) {
            return Math.max(0, Math.hypot(x - centerX, z - centerZ) - radius);
        }
        if (shape == Shape.SQUARE) {
            double[] local = toLocal(x - centerX, z - centerZ);
            double dx = Math.max(0, Math.abs(local[0]) - width / 2.0);
            double dz = Math.max(0, Math.abs(local[1]) - length / 2.0);
            return Math.hypot(dx, dz);
        }
        return Double.POSITIVE_INFINITY;
    }

    private double[] toLocal(double dx, double dz) {
        double c = Math.cos(rotationRadians);
        double s = Math.sin(rotationRadians);
        return new double[]{dx * c - dz * s, dx * s + dz * c};
    }
}
