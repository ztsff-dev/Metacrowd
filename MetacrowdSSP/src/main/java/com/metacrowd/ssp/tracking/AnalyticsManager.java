package com.metacrowd.ssp.tracking;

import com.metacrowd.ssp.MetacrowdSSPPlugin;
import com.metacrowd.ssp.api.ApiClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Analytics manager with batched reporting.
 * Accumulates impressions in memory and sends them as a single JSON payload.
 * Uses StringBuilder for efficient JSON construction (no Gson/Jackson).
 */
public class AnalyticsManager {

    private final MetacrowdSSPPlugin plugin;
    private final ApiClient apiClient;
    
    // Thread-safe buffer for impressions
    private final List<Impression> impressionBuffer;
    
    // Configuration
    private final int flushIntervalSeconds;
    private final String salt;
    
    private int taskId = -1;

    public AnalyticsManager(MetacrowdSSPPlugin plugin) {
        this.plugin = plugin;
        this.apiClient = plugin.getApiClient();
        this.impressionBuffer = new CopyOnWriteArrayList<>();
        
        // Load config
        flushIntervalSeconds = plugin.getConfig().getInt("analytics.flush-interval", 30);
        salt = plugin.getConfig().getString("security.salt", "default_salt_change_me");
        
        startFlushScheduler();
    }

    /**
     * Start periodic flush scheduler.
     */
    private void startFlushScheduler() {
        taskId = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::flush,
            flushIntervalSeconds * 20L,
            flushIntervalSeconds * 20L
        ).getTaskId();
        
        plugin.getLogger().info("Analytics flush scheduler started: " + flushIntervalSeconds + "s interval");
    }

    /**
     * Record an impression event.
     */
    public void recordImpression(UUID playerId, String placementId, long durationMs, boolean moved, double distance) {
        Impression impression = new Impression(
            System.currentTimeMillis(),
            hashPlayerId(playerId),
            placementId,
            durationMs,
            moved,
            distance
        );
        
        impressionBuffer.add(impression);
        
        plugin.getLogger().finest("Recorded impression: " + placementId + " by " + impression.pidHash + 
            " (" + durationMs + "ms, dist=" + String.format("%.1f", distance) + ")");
    }

    /**
     * Flush buffered impressions to API.
     */
    public synchronized void flush() {
        if (impressionBuffer.isEmpty()) {
            return;
        }

        // Build JSON array manually using StringBuilder
        StringBuilder jsonBuilder = new StringBuilder();
        jsonBuilder.append("[");
        
        boolean first = true;
        for (Impression imp : impressionBuffer) {
            if (!first) {
                jsonBuilder.append(",");
            }
            first = false;
            
            jsonBuilder.append("{")
                .append("\"ts\":").append(imp.timestamp)
                .append(",\"inst\":\"").append(apiClient.getInstanceId()).append("\"")
                .append(",\"plc\":\"").append(imp.placementId).append("\"")
                .append(",\"camp\":\"").append(imp.placementId).append("\"")
                .append(",\"pid_hash\":\"").append(imp.pidHash).append("\"")
                .append(",\"dur\":").append(imp.durationMs)
                .append(",\"move\":").append(imp.moved)
                .append(",\"dist\":").append(String.format("%.1f", imp.distance))
                .append("}");
        }
        
        jsonBuilder.append("]");
        
        String jsonPayload = jsonBuilder.toString();
        
        // Clear buffer before sending (in case of failure, we lose these - acceptable for analytics)
        int count = impressionBuffer.size();
        impressionBuffer.clear();
        
        // Send to API
        boolean success = apiClient.reportImpressions(jsonPayload);
        
        if (success) {
            plugin.getLogger().fine("Flushed " + count + " impressions to API");
        } else {
            plugin.getLogger().warning("Failed to flush " + count + " impressions");
        }
    }

    /**
     * Hash player UUID with salt for privacy.
     */
    private String hashPlayerId(UUID playerId) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] input = (playerId.toString() + salt).getBytes(StandardCharsets.UTF_8);
            byte[] hash = sha256.digest(input);
            
            // Convert to hex string
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
            
        } catch (NoSuchAlgorithmException e) {
            // Fallback to plain UUID (should never happen)
            plugin.getLogger().log(Level.SEVERE, "SHA-256 not available", e);
            return playerId.toString();
        }
    }

    /**
     * Get buffer size for stats.
     */
    public int getBufferedCount() {
        return impressionBuffer.size();
    }

    /**
     * Impression data class.
     */
    private static class Impression {
        final long timestamp;
        final String pidHash;
        final String placementId;
        final long durationMs;
        final boolean moved;
        final double distance;

        Impression(long ts, String hash, String plc, long dur, boolean move, double dist) {
            this.timestamp = ts;
            this.pidHash = hash;
            this.placementId = plc;
            this.durationMs = dur;
            this.moved = move;
            this.distance = dist;
        }
    }
}
