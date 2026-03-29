package com.metacrowd.ssp.commands;

import com.metacrowd.ssp.MetacrowdSSPPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

/**
 * Handles all plugin commands.
 * All commands require metacrowd.admin permission.
 */
public class CommandHandler implements CommandExecutor, TabCompleter {

    private final MetacrowdSSPPlugin plugin;

    public CommandHandler(MetacrowdSSPPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Register command handlers.
     */
    public void register() {
        plugin.getCommand("mcplace").setExecutor(this);
        plugin.getCommand("mcplace").setTabCompleter(this);
        
        plugin.getCommand("mcremove").setExecutor(this);
        plugin.getCommand("mcremove").setTabCompleter(this);
        
        plugin.getCommand("mcreload").setExecutor(this);
        plugin.getCommand("mcreload").setTabCompleter(this);
        
        plugin.getCommand("mcstats").setExecutor(this);
        plugin.getCommand("mcstats").setTabCompleter(this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, 
                             @NotNull String label, @NotNull String[] args) {
        
        if (!sender.hasPermission("metacrowd.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "mcplace":
                return handlePlace(sender, args);
            case "mcremove":
                return handleRemove(sender, args);
            case "mcreload":
                return handleReload(sender);
            case "mcstats":
                return handleStats(sender);
            default:
                return false;
        }
    }

    /**
     * Handle /mcplace <id> <width> <height>
     */
    private boolean handlePlace(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /mcplace <id> <width> <height>");
            sender.sendMessage("§eExample: /mcplace lobby_main 4 3");
            return true;
        }

        String id = args[0];
        int width, height;

        try {
            width = Integer.parseInt(args[1]);
            height = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cWidth and height must be numbers.");
            return true;
        }

        if (width < 1 || width > 8 || height < 1 || height > 6) {
            sender.sendMessage("§cSize must be between 1x1 and 8x6.");
            return true;
        }

        boolean success = plugin.getPlacementManager().createPlacement(
            id, player.getLocation(), width, height);

        if (success) {
            sender.sendMessage("§aCreated placement '" + id + "' (" + width + "x" + height + ")");
            sender.sendMessage("§eThe billboard will start displaying ads once rotation is loaded.");
            
            // Trigger initial rotation load
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                RotationLoader.loadRotationForPlacement(plugin, id);
            });
        } else {
            sender.sendMessage("§cFailed to create placement. Check console for details.");
        }

        return true;
    }

    /**
     * Handle /mcremove <id>
     */
    private boolean handleRemove(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /mcremove <id>");
            return true;
        }

        String id = args[0];
        boolean success = plugin.getPlacementManager().removePlacement(id);

        if (success) {
            sender.sendMessage("§aRemoved placement '" + id + "'");
        } else {
            sender.sendMessage("§cPlacement not found: " + id);
        }

        return true;
    }

    /**
     * Handle /mcreload
     */
    private boolean handleReload(CommandSender sender) {
        sender.sendMessage("§eReloading rotations for all placements...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int count = 0;
            plugin.getPlacementManager().getAllPlacements().forEach(placement -> {
                RotationLoader.loadRotationForPlacement(plugin, placement.getId());
                count++;
            });
            
            int finalCount = count;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage("§aReloaded " + finalCount + " placements.");
            });
        });

        return true;
    }

    /**
     * Handle /mcstats
     */
    private boolean handleStats(CommandSender sender) {
        StringBuilder stats = new StringBuilder();
        stats.append("§6=== MetacrowdSSP Stats ===\n");
        
        int placementCount = plugin.getPlacementManager().getAllPlacements().size();
        stats.append("§fActive placements: §e").append(placementCount).append("\n");
        
        var cacheStats = plugin.getImageCache().getStats();
        stats.append("§fCached images: §e").append(cacheStats.imageCount).append("\n");
        stats.append("§fCache size: §e").append(cacheStats.getKilobytes()).append(" KB\n");
        
        int bufferedImpressions = plugin.getAnalyticsManager().getBufferedCount();
        stats.append("§fBuffered impressions: §e").append(bufferedImpressions).append("\n");
        
        Runtime runtime = Runtime.getRuntime();
        long usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        stats.append("§fPlugin memory usage: §e~").append(usedMem).append(" MB\n");
        
        sender.sendMessage(stats.toString());
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("metacrowd.admin")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            // Suggest placement IDs for remove command
            if (command.getName().equalsIgnoreCase("mcremove")) {
                return new ArrayList<>(plugin.getPlacementManager().getAllPlacements().stream()
                    .map(p -> p.getId())
                    .toList());
            }
        }

        return new ArrayList<>();
    }
}
