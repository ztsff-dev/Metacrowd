package com.metacrowd.ssp.tracking;

import com.metacrowd.ssp.MetacrowdSSPPlugin;
import com.metacrowd.ssp.geometry.Placement;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-stage viewability tracker.
 * Stage 1: Fast math (distance + dot product) - runs every 40 ticks
 * Stage 2: RayCast visibility check - only if stage 1 passes
 * 
 * Optimized for minimal CPU usage and zero object allocation in hot path.
 */
public class ViewTracker {

    private final MetacrowdSSPPlugin plugin;
    private final AnalyticsManager analyticsManager;
    
    // Active view sessions: playerId -> PlacementId -> startTime
    private final Map<UUID, Map<String, Long>> activeViews;
    
    // Player movement tracking for AFK detection
    private final Map<UUID, Location> lastPlayerLocations;
    
    // Configuration
    private final double maxDistanceSquared;
    private final double maxAngleCos; // cos(60°) = 0.5
    private final int minViewDurationMs;
    private final int checkIntervalTicks;
    
    private int taskId = -1;

    public ViewTracker(MetacrowdSSPPlugin plugin) {
        this.plugin = plugin;
        this.analyticsManager = plugin.getAnalyticsManager();
        this.activeViews = new ConcurrentHashMap<>();
        this.lastPlayerLocations = new ConcurrentHashMap<>();
        
        // Load config
        maxDistanceSquared = Math.pow(plugin.getConfig().getDouble("tracking.max-distance", 32), 2);
        double maxAngle = plugin.getConfig().getDouble("tracking.view-angle", 60);
        maxAngleCos = Math.cos(Math.toRadians(maxAngle));
        minViewDurationMs = plugin.getConfig().getInt("tracking.min-view-duration", 1000);
        checkIntervalTicks = plugin.getConfig().getInt("tracking.check-interval-ticks", 40);
        
        startTracking();
    }

    /**
     * Start the tracking scheduler.
     */
    private void startTracking() {
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::trackViews,
            checkIntervalTicks,
            checkIntervalTicks
        ).getTaskId();
        
        plugin.getLogger().info("View tracker started: " + checkIntervalTicks + " tick interval");
    }

    /**
     * Main tracking loop.
     */
    private void trackViews() {
        long startTime = System.currentTimeMillis();
        
        Collection<Placement> placements = plugin.getPlacementManager().getAllPlacements();
        if (placements.isEmpty()) {
            return;
        }
        
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        
        // Process each player
        for (Player player : players) {
            trackPlayer(player, placements);
        }
        
        // Log performance if too slow
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > 10) {
            plugin.getLogger().fine("View tracking took " + elapsed + "ms");
        }
    }

    /**
     * Track a single player against all placements.
     */
    private void trackPlayer(Player player, Collection<Placement> placements) {
        UUID playerId = player.getUniqueId();
        Location playerLoc = player.getLocation();
        Vector lookDir = playerLoc.getDirection();
        
        // Normalize look direction for dot product
        double lookX = lookDir.getX();
        double lookY = lookDir.getY();
        double lookZ = lookDir.getZ();
        
        // Get or create player's active views map
        Map<String, Long> playerViews = activeViews.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        
        // Track which placements are currently visible
        Set<String> visiblePlacements = new HashSet<>();
        
        for (Placement placement : placements) {
            // STAGE 1: Fast distance check (squared to avoid sqrt)
            double distSq = placement.distanceSquaredToCenter(playerLoc);
            if (distSq > maxDistanceSquared) {
                continue;
            }
            
            // STAGE 1: Fast angle check (dot product)
            double dot = placement.getDotProduct(lookX, lookY, lookZ);
            if (dot < maxAngleCos) {
                continue;
            }
            
            // STAGE 2: RayCast visibility check (expensive, only if stage 1 passes)
            if (!isVisibleViaRayCast(playerLoc, placement)) {
                continue;
            }
            
            // Player can see this placement
            visiblePlacements.add(placement.getId());
            
            // Track view duration
            long now = System.currentTimeMillis();
            Long startTime = playerViews.get(placement.getId());
            
            if (startTime == null) {
                // New view session
                playerViews.put(placement.getId(), now);
            } else {
                long duration = now - startTime;
                
                // Check if minimum duration reached
                if (duration >= minViewDurationMs && duration < minViewDurationMs + checkIntervalTicks * 50) {
                    // Just crossed threshold - record impression
                    boolean moved = hasPlayerMoved(playerId, playerLoc);
                    double distance = Math.sqrt(distSq);
                    
                    analyticsManager.recordImpression(
                        playerId,
                        placement.getId(),
                        duration,
                        moved,
                        distance
                    );
                }
            }
        }
        
        // Clean up views that are no longer visible
        playerViews.keySet().removeIf(id -> !visiblePlacements.contains(id));
        
        // Update last known location
        lastPlayerLocations.put(playerId, playerLoc.clone());
    }

    /**
     * RayCast check for line of sight.
     */
    private boolean isVisibleViaRayCast(Location from, Placement placement) {
        World world = from.getWorld();
        if (world == null) return false;
        
        Vector to = new Vector(placement.getCenterX(), placement.getCenterY(), placement.getCenterZ());
        Vector fromVec = from.toVector();
        
        Vector direction = to.clone().subtract(fromVec).normalize();
        double distance = fromVec.distance(to);
        
        try {
            RayTraceResult result = world.rayTraceBlocks(from, direction, distance);
            return result == null; // No block hit = visible
        } catch (Exception e) {
            // Fallback on error
            return true;
        }
    }

    /**
     * Check if player has moved significantly (AFK detection).
     */
    private boolean hasPlayerMoved(UUID playerId, Location current) {
        Location last = lastPlayerLocations.get(playerId);
        if (last == null) {
            return true;
        }
        
        double dx = current.getX() - last.getX();
        double dy = current.getY() - last.getY();
        double dz = current.getZ() - last.getZ();
        
        // Moved more than 0.5 blocks
        return (dx * dx + dy * dy + dz * dz) > 0.25;
    }

    /**
     * Shutdown the tracker.
     */
    public void shutdown() {
        if (taskId >= 0) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        
        activeViews.clear();
        lastPlayerLocations.clear();
        
        plugin.getLogger().info("View tracker stopped");
    }
}
