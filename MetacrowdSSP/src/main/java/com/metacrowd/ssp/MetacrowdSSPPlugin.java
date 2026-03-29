package com.metacrowd.ssp;

import com.metacrowd.ssp.api.ApiClient;
import com.metacrowd.ssp.cache.ImageCache;
import com.metacrowd.ssp.commands.CommandHandler;
import com.metacrowd.ssp.geometry.PlacementManager;
import com.metacrowd.ssp.tracking.AnalyticsManager;
import com.metacrowd.ssp.tracking.ViewTracker;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * MetacrowdSSPPlugin - Lightweight SSP client for Minecraft advertising network.
 * 
 * Key features:
 * - RAM-only image caching (no disk I/O)
 * - Two-stage viewability tracking (math + raycast)
 * - Batched analytics reporting
 * - Zero external dependencies
 */
public class MetacrowdSSPPlugin extends JavaPlugin {

    private static MetacrowdSSPPlugin instance;
    
    private ApiClient apiClient;
    private ImageCache imageCache;
    private PlacementManager placementManager;
    private ViewTracker viewTracker;
    private AnalyticsManager analyticsManager;
    private CommandHandler commandHandler;

    @Override
    public void onEnable() {
        instance = this;
        
        saveDefaultConfig();
        
        // Initialize components in order of dependency
        this.imageCache = new ImageCache(this);
        this.apiClient = new ApiClient(this);
        this.placementManager = new PlacementManager(this);
        this.analyticsManager = new AnalyticsManager(this);
        this.viewTracker = new ViewTracker(this);
        this.commandHandler = new CommandHandler(this);
        
        // Register events and commands
        this.commandHandler.register();
        
        getLogger().log(Level.INFO, "MetacrowdSSP v{0} enabled successfully", getDescription().getVersion());
        getLogger().info("Memory-optimized mode: RAM-only caching active");
        getLogger().info("Analytics batching: " + getConfig().getInt("analytics.flush-interval") + "s interval");
    }

    @Override
    public void onDisable() {
        // Cleanup resources
        if (viewTracker != null) {
            viewTracker.shutdown();
        }
        
        if (analyticsManager != null) {
            analyticsManager.flush();
        }
        
        if (imageCache != null) {
            imageCache.clear();
        }
        
        if (placementManager != null) {
            placementManager.saveState();
        }
        
        getLogger().info("MetacrowdSSP disabled. Resources cleaned up.");
    }

    public static MetacrowdSSPPlugin getInstance() {
        return instance;
    }

    public ApiClient getApiClient() {
        return apiClient;
    }

    public ImageCache getImageCache() {
        return imageCache;
    }

    public PlacementManager getPlacementManager() {
        return placementManager;
    }

    public ViewTracker getViewTracker() {
        return viewTracker;
    }

    public AnalyticsManager getAnalyticsManager() {
        return analyticsManager;
    }

    public CommandHandler getCommandHandler() {
        return commandHandler;
    }
}
