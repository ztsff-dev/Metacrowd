package com.metacrowd.ssp.geometry;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;

/**
 * Immutable data class representing a billboard placement.
 * Stores pre-calculated geometry for efficient viewability checks.
 * All values are primitives to minimize GC pressure.
 */
public class Placement {

    private final String id;
    private final int width;
    private final int height;
    
    // Center location coordinates (primitives for performance)
    private final double centerX;
    private final double centerY;
    private final double centerZ;
    
    // Normal vector (direction the billboard faces)
    private final double normalX;
    private final double normalY;
    private final double normalZ;
    
    // Bounding box for quick distance checks
    private final double minX, maxX;
    private final double minY, maxY;
    private final double minZ, maxZ;
    
    // Frame locations for rendering
    private final Location[] frameLocations;
    private final BlockFace[] frameFaces;

    public Placement(String id, int width, int height, Location corner, BlockFace facing) {
        this.id = id;
        this.width = width;
        this.height = height;
        
        // Calculate center
        double faceOffset = 0.5; // Slightly in front of the glowstone
        
        if (facing == BlockFace.NORTH) {
            this.centerX = corner.getX() + width / 2.0;
            this.centerY = corner.getY() + height / 2.0;
            this.centerZ = corner.getZ() - faceOffset;
            this.normalX = 0;
            this.normalY = 0;
            this.normalZ = -1;
        } else if (facing == BlockFace.SOUTH) {
            this.centerX = corner.getX() + width / 2.0;
            this.centerY = corner.getY() + height / 2.0;
            this.centerZ = corner.getZ() + 1 + faceOffset;
            this.normalX = 0;
            this.normalY = 0;
            this.normalZ = 1;
        } else if (facing == BlockFace.EAST) {
            this.centerX = corner.getX() + 1 + faceOffset;
            this.centerY = corner.getY() + height / 2.0;
            this.centerZ = corner.getZ() + height / 2.0;
            this.normalX = 1;
            this.normalY = 0;
            this.normalZ = 0;
        } else if (facing == BlockFace.WEST) {
            this.centerX = corner.getX() - faceOffset;
            this.centerY = corner.getY() + height / 2.0;
            this.centerZ = corner.getZ() + height / 2.0;
            this.normalX = -1;
            this.normalY = 0;
            this.normalZ = 0;
        } else {
            // Default to south
            this.centerX = corner.getX() + width / 2.0;
            this.centerY = corner.getY() + height / 2.0;
            this.centerZ = corner.getZ() + 1 + faceOffset;
            this.normalX = 0;
            this.normalY = 0;
            this.normalZ = 1;
        }
        
        // Calculate bounding box
        this.minX = corner.getX();
        this.maxX = corner.getX() + width;
        this.minY = corner.getY();
        this.maxY = corner.getY() + height;
        this.minZ = corner.getZ();
        this.maxZ = corner.getZ() + (facing == BlockFace.NORTH || facing == BlockFace.SOUTH ? 1 : height);
        
        // Pre-calculate frame locations
        this.frameLocations = new Location[width * height];
        this.frameFaces = new BlockFace[width * height];
        
        int index = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Location loc = corner.clone().add(x, y, 0);
                frameLocations[index] = loc;
                frameFaces[index] = facing;
                index++;
            }
        }
    }

    /**
     * Quick distance squared check (avoids sqrt).
     */
    public double distanceSquaredToCenter(Location loc) {
        double dx = loc.getX() - centerX;
        double dy = loc.getY() - centerY;
        double dz = loc.getZ() - centerZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Calculate dot product between view direction and billboard normal.
     * Returns value between -1 (looking away) and 1 (looking directly at).
     */
    public double getDotProduct(double lookX, double lookY, double lookZ) {
        return normalX * lookX + normalY * lookY + normalZ * lookZ;
    }

    /**
     * Get angle between view direction and billboard normal in degrees.
     */
    public double getAngleDegrees(double lookX, double lookY, double lookZ) {
        double dot = getDotProduct(lookX, lookY, lookZ);
        double magLook = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        double magNormal = 1.0; // Normal is already unit length
        
        if (magLook == 0) return 90.0;
        
        double cosAngle = dot / (magLook * magNormal);
        // Clamp to [-1, 1] to avoid NaN from acos
        cosAngle = Math.max(-1.0, Math.min(1.0, cosAngle));
        
        return Math.toDegrees(Math.acos(cosAngle));
    }

    /**
     * Check if point is within viewing frustum of billboard.
     */
    public boolean isInFront(Location loc) {
        double dx = loc.getX() - centerX;
        double dy = loc.getY() - centerY;
        double dz = loc.getZ() - centerZ;
        
        // Dot product with normal should be positive if in front
        return normalX * dx + normalY * dy + normalZ * dz > 0;
    }

    // Getters
    public String getId() { return id; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public double getCenterX() { return centerX; }
    public double getCenterY() { return centerY; }
    public double getCenterZ() { return centerZ; }
    public double getNormalX() { return normalX; }
    public double getNormalY() { return normalY; }
    public double getNormalZ() { return normalZ; }
    public Location[] getFrameLocations() { return frameLocations; }
    public BlockFace[] getFrameFaces() { return frameFaces; }
}
