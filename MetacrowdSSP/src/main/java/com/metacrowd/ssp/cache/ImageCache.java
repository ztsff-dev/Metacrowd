package com.metacrowd.ssp.cache;

import com.metacrowd.ssp.MetacrowdSSPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.imageio.ImageIO;

/**
 * RAM-only image cache.
 * No disk I/O - all images stored as byte[] in ConcurrentHashMap.
 * Automatically cleared on rotation change or plugin disable.
 */
public class ImageCache {

    private final MetacrowdSSPPlugin plugin;
    
    // Cache: creativeId -> image bytes
    private final Map<String, byte[]> imageCache;
    
    // Cache: creativeId -> rendered MapView (for quick reuse)
    private final Map<String, MapView> renderedViews;

    public ImageCache(MetacrowdSSPPlugin plugin) {
        this.plugin = plugin;
        this.imageCache = new ConcurrentHashMap<>();
        this.renderedViews = new ConcurrentHashMap<>();
    }

    /**
     * Download and cache image from URL.
     * Runs asynchronously to avoid blocking main thread.
     * 
     * @param creativeId Unique identifier for the creative
     * @param imageUrl URL to download from
     * @param callback Called when download completes (success or failure)
     */
    public void loadImageAsync(String creativeId, String imageUrl, ImageLoadCallback callback) {
        // Check if already cached
        if (imageCache.containsKey(creativeId)) {
            plugin.getLogger().finest("Image already cached: " + creativeId);
            if (callback != null) {
                Bukkit.getScheduler().runTask(plugin, () -> 
                    callback.onComplete(creativeId, imageCache.get(creativeId)));
            }
            return;
        }

        // Download async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                byte[] imageData = downloadImage(imageUrl);
                
                if (imageData != null) {
                    imageCache.put(creativeId, imageData);
                    
                    plugin.getLogger().finest("Image cached: " + creativeId + " (" + imageData.length + " bytes)");
                    
                    // Callback on main thread
                    if (callback != null) {
                        byte[] finalImageData = imageData;
                        Bukkit.getScheduler().runTask(plugin, () -> 
                            callback.onComplete(creativeId, finalImageData));
                    }
                } else {
                    plugin.getLogger().log(Level.WARNING, "Failed to download image: " + imageUrl);
                    if (callback != null) {
                        Bukkit.getScheduler().runTask(plugin, () -> 
                            callback.onComplete(creativeId, null));
                    }
                }
                
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error loading image: " + e.getMessage());
                if (callback != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> 
                        callback.onComplete(creativeId, null));
                }
            }
        });
    }

    /**
     * Download image from URL and return as byte array.
     */
    private byte[] downloadImage(String imageUrl) throws IOException {
        URL url = new URL(imageUrl);
        
        try (var inputStream = url.openStream()) {
            byte[] buffer = new byte[8192];
            var outputStream = new java.io.ByteArrayOutputStream();
            
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            return outputStream.toByteArray();
        }
    }

    /**
     * Get cached image bytes.
     */
    public byte[] getImageBytes(String creativeId) {
        return imageCache.get(creativeId);
    }

    /**
     * Render image bytes to a MapView.
     * Creates a custom renderer that draws the image on the map.
     */
    public MapView renderToMap(String creativeId, int width, int height) {
        byte[] imageData = imageCache.get(creativeId);
        if (imageData == null) {
            return null;
        }

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                plugin.getLogger().warning("Failed to decode image: " + creativeId);
                return null;
            }

            // Create map view
            MapView mapView = Bukkit.createMap(Bukkit.getWorlds().get(0));
            mapView.setScale(MapView.Scale.CLOSEST);
            
            // Remove default renderer and add custom one
            mapView.getRenderers().clear();
            mapView.addRenderer(new ImageMapRenderer(image, width, height));
            
            renderedViews.put(creativeId, mapView);
            return mapView;
            
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Error rendering image to map: " + e.getMessage());
            return null;
        }
    }

    /**
     * Custom MapRenderer that draws a BufferedImage onto the Minecraft map.
     */
    private static class ImageMapRenderer extends MapRenderer {
        
        private final BufferedImage sourceImage;
        private final int targetWidth;
        private final int targetHeight;
        
        public ImageMapRenderer(BufferedImage image, int width, int height) {
            this.sourceImage = image;
            this.targetWidth = width;
            this.targetHeight = height;
        }
        
        @Override
        public void render(MapView map, MapCanvas canvas, org.bukkit.entity.Player player) {
            // Calculate scaling
            int mapPixelWidth = targetWidth * 128;
            int mapPixelHeight = targetHeight * 128;
            
            // Draw image scaled to fit the multi-map grid
            for (int x = 0; x < targetWidth; x++) {
                for (int y = 0; y < targetHeight; y++) {
                    // Extract 128x128 portion for this map cell
                    int srcX = (x * sourceImage.getWidth()) / targetWidth;
                    int srcY = (y * sourceImage.getHeight()) / targetHeight;
                    int srcWidth = sourceImage.getWidth() / targetWidth;
                    int srcHeight = sourceImage.getHeight() / targetHeight;
                    
                    // Scale down to 128x128
                    BufferedImage cellImage = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2d = cellImage.createGraphics();
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2d.drawImage(sourceImage, 
                        0, 0, 128, 128,
                        srcX, srcY, srcX + srcWidth, srcY + srcHeight,
                        null);
                    g2d.dispose();
                    
                    // Draw to canvas at offset
                    drawImageToCanvas(canvas, cellImage, x * 128, y * 128);
                }
            }
        }
        
        private void drawImageToCanvas(MapCanvas canvas, BufferedImage image, int offsetX, int offsetY) {
            int width = image.getWidth();
            int height = image.getHeight();
            
            for (int x = 0; x < width && offsetX + x < 128 * targetWidth; x++) {
                for (int y = 0; y < height && offsetY + y < 128 * targetHeight; y++) {
                    int rgb = image.getRGB(x, y);
                    
                    // Convert ARGB to Minecraft map color format
                    if ((rgb >> 24 & 0xFF) > 0) { // If not transparent
                        byte r = (byte) ((rgb >> 16) & 0xFF);
                        byte g = (byte) ((rgb >> 8) & 0xFF);
                        byte b = (byte) (rgb & 0xFF);
                        
                        // Simple color quantization for Minecraft map colors
                        canvas.setPixel(offsetX + x, offsetY + y, matchMapColor(r, g, b));
                    }
                }
            }
        }
        
        private byte matchMapColor(byte r, byte g, byte b) {
            // Simplified color matching - Minecraft has 128 predefined colors
            // This is a basic implementation; production would use full palette matching
            int brightness = (r + g + b) / 3;
            return (byte) (brightness / 2);
        }
    }

    /**
     * Check if creative is cached.
     */
    public boolean isCached(String creativeId) {
        return imageCache.containsKey(creativeId);
    }

    /**
     * Remove specific creative from cache.
     */
    public void remove(String creativeId) {
        imageCache.remove(creativeId);
        MapView view = renderedViews.remove(creativeId);
        if (view != null) {
            view.getRenderers().clear();
        }
    }

    /**
     * Clear all cached images.
     * Called on rotation change or plugin disable.
     */
    public void clear() {
        int cachedCount = imageCache.size();
        long totalBytes = imageCache.values().stream().mapToInt(b -> b.length).sum();
        
        imageCache.clear();
        
        for (MapView view : renderedViews.values()) {
            view.getRenderers().clear();
        }
        renderedViews.clear();
        
        plugin.getLogger().info("Image cache cleared: " + cachedCount + " images, ~" + (totalBytes / 1024) + " KB freed");
    }

    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        int count = imageCache.size();
        long totalBytes = imageCache.values().stream().mapToInt(b -> b.length).sum();
        
        return new CacheStats(count, totalBytes);
    }

    /**
     * Callback interface for async image loading.
     */
    public interface ImageLoadCallback {
        void onComplete(String creativeId, byte[] imageData);
    }

    /**
     * Cache statistics data class.
     */
    public static class CacheStats {
        public final int imageCount;
        public final long totalBytes;
        
        public CacheStats(int count, long bytes) {
            this.imageCount = count;
            this.totalBytes = bytes;
        }
        
        public long getKilobytes() {
            return totalBytes / 1024;
        }
        
        public long getMegabytes() {
            return totalBytes / (1024 * 1024);
        }
    }
}
