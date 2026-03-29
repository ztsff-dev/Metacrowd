package com.metacrowd.ssp.geometry;

import com.metacrowd.ssp.MetacrowdSSPPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages billboard placements and their physical representation in the world.
 * Handles creation, removal, and persistence of placements to state.yml.
 */
public class PlacementManager {

    private final MetacrowdSSPPlugin plugin;
    private final Map<String, Placement> placements;
    private final Map<String, List<ItemFrame>> placementFrames;
    private final File stateFile;
    private FileConfiguration stateConfig;

    public PlacementManager(MetacrowdSSPPlugin plugin) {
        this.plugin = plugin;
        this.placements = new HashMap<>();
        this.placementFrames = new HashMap<>();
        
        // Initialize state file
        File dataFolder = new File(plugin.getDataFolder(), "state.yml");
        this.stateFile = dataFolder;
        loadState();
    }

    /**
     * Create a new billboard placement.
     * 
     * @param id Unique placement ID (matches Google Sheets)
     * @param playerLocation Location where player is standing
     * @param width Width in maps (e.g., 4)
     * @param height Height in maps (e.g., 3)
     * @return true if successful
     */
    public boolean createPlacement(String id, Location playerLocation, int width, int height) {
        if (placements.containsKey(id)) {
            plugin.getLogger().warning("Placement already exists: " + id);
            return false;
        }

        // Calculate placement location (2 blocks in front of player)
        Location playerLoc = playerLocation.clone();
        double yaw = playerLoc.getYaw();
        
        // Get direction vector
        double rad = Math.toRadians(-yaw);
        double dirX = -Math.sin(rad);
        double dirZ = Math.cos(rad);
        
        // Place 2 blocks in front
        int placeX = (int) Math.round(playerLoc.getX() + dirX * 2);
        int placeY = (int) playerLoc.getY();
        int placeZ = (int) Math.round(playerLoc.getZ() + dirZ * 2);
        
        Location corner = new Location(playerLoc.getWorld(), placeX, placeY, placeZ);
        
        // Determine facing direction
        BlockFace facing;
        if (Math.abs(dirX) > Math.abs(dirZ)) {
            facing = dirX > 0 ? BlockFace.EAST : BlockFace.WEST;
        } else {
            facing = dirZ > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
        }

        // Build structure
        if (!buildStructure(corner, width, height, facing)) {
            return false;
        }

        // Create placement object
        Placement placement = new Placement(id, width, height, corner, facing);
        placements.put(id, placement);
        placementFrames.put(id, new ArrayList<>());

        // Spawn item frames
        spawnItemFrames(placement);

        // Save state
        saveState();

        plugin.getLogger().info("Created placement: " + id + " (" + width + "x" + height + ") at " + 
            corner.getBlockX() + "," + corner.getBlockY() + "," + corner.getBlockZ());
        
        return true;
    }

    /**
     * Build the glowstone structure for the billboard.
     */
    private boolean buildStructure(Location corner, int width, int height, BlockFace facing) {
        WorldGuardChecker wgChecker = new WorldGuardChecker(plugin);
        
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Location loc = corner.clone().add(x, y, 0);
                Block block = loc.getBlock();
                
                // Check if we can build here
                if (!wgChecker.canBuild(loc)) {
                    plugin.getLogger().warning("Cannot build at " + loc + " (protected by WorldGuard?)");
                    // Cleanup on failure
                    cleanupStructure(corner, width, height);
                    return false;
                }
                
                // Place glowstone
                block.setType(Material.GLOWSTONE);
            }
        }
        
        return true;
    }

    /**
     * Spawn item frames on the glowstone structure.
     */
    private void spawnItemFrames(Placement placement) {
        List<ItemFrame> frames = new ArrayList<>();
        
        Location[] locations = placement.getFrameLocations();
        BlockFace[] faces = placement.getFrameFaces();
        
        for (int i = 0; i < locations.length; i++) {
            Location loc = locations[i];
            BlockFace face = faces[i];
            
            // Get the block behind the frame location
            Block block = loc.getBlock().getRelative(face.getOppositeFace());
            
            // Spawn item frame
            ItemFrame frame = (ItemFrame) block.getWorld().spawnEntity(
                loc.clone().add(0.5, 0.5, 0.5),
                org.bukkit.entity.EntityType.ITEM_FRAME
            );
            
            // Set facing direction
            frame.setFacingDirection(face);
            frame.setVisible(false); // Hide the frame itself, only show map
            frame.setFixed(false);
            frame.setGlowing(false);
            
            // Create empty map item
            ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) mapItem.getItemMeta();
            if (meta != null) {
                meta.setMapId(1); // Default, will be replaced
                mapItem.setItemMeta(meta);
            }
            frame.setItem(mapItem);
            
            frames.add(frame);
        }
        
        placementFrames.put(placement.getId(), frames);
    }

    /**
     * Remove a placement and clean up the world.
     */
    public boolean removePlacement(String id) {
        Placement placement = placements.remove(id);
        if (placement == null) {
            plugin.getLogger().warning("Placement not found: " + id);
            return false;
        }

        // Remove item frames
        List<ItemFrame> frames = placementFrames.remove(id);
        if (frames != null) {
            for (ItemFrame frame : frames) {
                if (frame.isValid()) {
                    frame.remove();
                }
            }
        }

        // Remove glowstone structure
        cleanupStructure(
            placement.getFrameLocations()[0],
            placement.getWidth(),
            placement.getHeight()
        );

        // Save state
        saveState();

        plugin.getLogger().info("Removed placement: " + id);
        return true;
    }

    /**
     * Clean up glowstone blocks.
     */
    private void cleanupStructure(Location corner, int width, int height) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                Location loc = corner.clone().add(x, y, 0);
                Block block = loc.getBlock();
                
                if (block.getType() == Material.GLOWSTONE) {
                    block.setType(Material.AIR);
                }
            }
        }
    }

    /**
     * Update item frames with new MapView.
     */
    public void updateFrames(String id, MapView mapView) {
        List<ItemFrame> frames = placementFrames.get(id);
        if (frames == null || frames.isEmpty()) {
            return;
        }

        // Create map item with the view
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setMapView(mapView);
            mapItem.setItemMeta(meta);
        }

        // Update all frames
        for (ItemFrame frame : frames) {
            if (frame.isValid()) {
                frame.setItem(mapItem);
            }
        }
    }

    /**
     * Get placement by ID.
     */
    public Placement getPlacement(String id) {
        return placements.get(id);
    }

    /**
     * Get all placements.
     */
    public Collection<Placement> getAllPlacements() {
        return placements.values();
    }

    /**
     * Load state from YAML file.
     */
    private void loadState() {
        if (!stateFile.exists()) {
            plugin.getLogger().info("No existing state file found, starting fresh");
            stateConfig = new YamlConfiguration();
            return;
        }

        try {
            stateConfig = YamlConfiguration.loadConfiguration(stateFile);
            
            ConfigurationSection placementsSection = stateConfig.getConfigurationSection("placements");
            if (placementsSection == null) {
                return;
            }

            for (String key : placementsSection.getKeys(false)) {
                ConfigurationSection pSection = placementsSection.getConfigurationSection(key);
                if (pSection == null) continue;

                String id = pSection.getString("id");
                int width = pSection.getInt("width");
                int height = pSection.getInt("height");
                String worldName = pSection.getString("world");
                int x = pSection.getInt("x");
                int y = pSection.getInt("y");
                int z = pSection.getInt("z");
                String facingStr = pSection.getString("facing", "SOUTH");

                if (id == null || worldName == null) continue;

                org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("World not found for placement " + id + ": " + worldName);
                    continue;
                }

                Location corner = new Location(world, x, y, z);
                BlockFace facing = BlockFace.valueOf(facingStr);

                Placement placement = new Placement(id, width, height, corner, facing);
                placements.put(id, placement);
                placementFrames.put(id, new ArrayList<>());

                // Rebuild structure and frames
                buildStructure(corner, width, height, facing);
                spawnItemFrames(placement);
            }

            plugin.getLogger().info("Loaded " + placements.size() + " placements from state");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error loading state: " + e.getMessage());
            stateConfig = new YamlConfiguration();
        }
    }

    /**
     * Save state to YAML file.
     */
    public void saveState() {
        stateConfig = new YamlConfiguration();

        for (Placement placement : placements.values()) {
            String path = "placements." + placement.getId();
            
            Location loc = placement.getFrameLocations()[0];
            
            stateConfig.set(path + ".id", placement.getId());
            stateConfig.set(path + ".width", placement.getWidth());
            stateConfig.set(path + ".height", placement.getHeight());
            stateConfig.set(path + ".world", loc.getWorld().getName());
            stateConfig.set(path + ".x", loc.getBlockX());
            stateConfig.set(path + ".y", loc.getBlockY());
            stateConfig.set(path + ".z", loc.getBlockZ());
            stateConfig.set(path + ".facing", placement.getFrameFaces()[0].name());
        }

        try {
            stateConfig.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Error saving state: " + e.getMessage());
        }
    }

    /**
     * Simple WorldGuard compatibility checker.
     * Returns true if building is allowed at location.
     */
    private static class WorldGuardChecker {
        public WorldGuardChecker(MetacrowdSSPPlugin plugin) {
            // Placeholder for WorldGuard integration
            // In production, would use WorldGuard API to check build permissions
        }

        public boolean canBuild(Location loc) {
            // For now, always return true
            // Production version would check WorldGuard regions
            return true;
        }
    }
}
