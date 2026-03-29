package com.metacrowd.ssp.commands;

import com.metacrowd.ssp.MetacrowdSSPPlugin;
import com.metacrowd.ssp.api.ApiClient;
import com.metacrowd.ssp.cache.ImageCache;
import com.metacrowd.ssp.geometry.Placement;
import org.bukkit.map.MapView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Handles loading and rotating ad creatives for placements.
 * Manages the rotation cycle and triggers image loading.
 */
public class RotationLoader {

    // Active rotations: placementId -> current creative index
    private static final Map<String, Integer> rotationIndices = new HashMap<>();
    
    // Rotation tasks: placementId -> task ID
    private static final Map<String, Integer> rotationTasks = new HashMap<>();

    /**
     * Load rotation data for a specific placement.
     */
    public static void loadRotationForPlacement(MetacrowdSSPPlugin plugin, String placementId) {
        Placement placement = plugin.getPlacementManager().getPlacement(placementId);
        if (placement == null) {
            return;
        }

        ApiClient apiClient = plugin.getApiClient();
        ApiClient.RotationData rotationData = apiClient.getRotation(placementId);

        if (rotationData == null || rotationData.creatives.isEmpty()) {
            plugin.getLogger().warning("No rotation data for placement: " + placementId);
            // Try to show fallback if available
            showFallback(plugin, placementId);
            return;
        }

        plugin.getLogger().info("Loaded rotation for " + placementId + ": " + 
            rotationData.creatives.size() + " creatives, interval=" + rotationData.rotationIntervalSec + "s");

        // Cancel existing rotation task
        cancelRotation(placementId);

        // Reset rotation index
        rotationIndices.put(placementId, 0);

        // Start rotation scheduler
        int intervalTicks = rotationData.rotationIntervalSec * 20;
        int taskId = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin,
            () -> rotateCreative(plugin, placementId, rotationData),
            0,
            intervalTicks
        ).getTaskId();

        rotationTasks.put(placementId, taskId);

        // Load first creative immediately
        rotateCreative(plugin, placementId, rotationData);
    }

    /**
     * Rotate to the next creative in the cycle.
     */
    private static void rotateCreative(MetacrowdSSPPlugin plugin, String placementId, 
                                        ApiClient.RotationData rotationData) {
        
        Placement placement = plugin.getPlacementManager().getPlacement(placementId);
        if (placement == null) {
            cancelRotation(placementId);
            return;
        }

        ImageCache cache = plugin.getImageCache();
        List<ApiClient.Creative> creatives = rotationData.creatives;

        // Get current index and advance
        int currentIndex = rotationIndices.computeIfAbsent(placementId, k -> 0);
        ApiClient.Creative creative = creatives.get(currentIndex);

        // Advance index for next rotation
        int nextIndex = (currentIndex + 1) % creatives.size();
        rotationIndices.put(placementId, nextIndex);

        // Check if already cached
        if (cache.isCached(creative.id)) {
            displayCreative(plugin, placement, creative.id);
            return;
        }

        // Load image asynchronously
        cache.loadImageAsync(creative.id, creative.imageUrl, (id, imageData) -> {
            if (imageData != null) {
                displayCreative(plugin, placement, id);
            } else {
                plugin.getLogger().warning("Failed to load creative: " + creative.id);
                // Try next creative on failure
                if (!creatives.isEmpty()) {
                    ApiClient.Creative fallback = creatives.get(nextIndex);
                    cache.loadImageAsync(fallback.id, fallback.imageUrl, (fid, fdata) -> {
                        if (fdata != null) {
                            displayCreative(plugin, placement, fid);
                        }
                    });
                }
            }
        });
    }

    /**
     * Display a creative on the placement's frames.
     */
    private static void displayCreative(MetacrowdSSPPlugin plugin, Placement placement, String creativeId) {
        MapView mapView = plugin.getImageCache().renderToMap(
            creativeId, 
            placement.getWidth(), 
            placement.getHeight()
        );

        if (mapView != null) {
            plugin.getPlacementManager().updateFrames(placement.getId(), mapView);
            plugin.getLogger().finest("Displayed creative " + creativeId + " on " + placement.getId());
        }
    }

    /**
     * Show fallback image when no creatives available.
     */
    private static void showFallback(MetacrowdSSPPlugin plugin, String placementId) {
        Placement placement = plugin.getPlacementManager().getPlacement(placementId);
        if (placement == null) {
            return;
        }

        // For now, just clear the display
        // In production, would load a default "no ads" image
        plugin.getLogger().fine("Showing fallback for " + placementId);
    }

    /**
     * Cancel rotation task for a placement.
     */
    private static void cancelRotation(String placementId) {
        Integer taskId = rotationTasks.remove(placementId);
        if (taskId != null) {
            MetacrowdSSPPlugin.getInstance().getServer().getScheduler().cancelTask(taskId);
        }
        rotationIndices.remove(placementId);
    }

    /**
     * Cancel all rotation tasks.
     */
    public static void cancelAll() {
        for (Integer taskId : rotationTasks.values()) {
            MetacrowdSSPPlugin.getInstance().getServer().getScheduler().cancelTask(taskId);
        }
        rotationTasks.clear();
        rotationIndices.clear();
    }
}
