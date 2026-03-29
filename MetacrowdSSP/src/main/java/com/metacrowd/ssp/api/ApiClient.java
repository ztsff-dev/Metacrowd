package com.metacrowd.ssp.api;

import com.metacrowd.ssp.MetacrowdSSPPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Lightweight HTTP client for Google Apps Script API.
 * Uses standard HttpURLConnection - no external dependencies.
 * Manual JSON parsing to avoid Gson/Jackson overhead.
 */
public class ApiClient {

    private final MetacrowdSSPPlugin plugin;
    private final String apiUrl;
    private final String instanceId;

    public ApiClient(MetacrowdSSPPlugin plugin) {
        this.plugin = plugin;
        
        ConfigurationSection apiConfig = plugin.getConfig().getConfigurationSection("api");
        this.apiUrl = apiConfig != null ? apiConfig.getString("url", "") : "";
        
        ConfigurationSection instanceConfig = plugin.getConfig().getConfigurationSection("instance");
        String id = instanceConfig != null ? instanceConfig.getString("id", "") : "";
        
        if (id == null || id.isEmpty()) {
            id = "server_" + System.currentTimeMillis();
            plugin.getLogger().warning("Instance ID not configured, generated: " + id);
        }
        this.instanceId = id;
    }

    /**
     * Fetch rotation data from API.
     * Returns parsed RotationData or null on error.
     */
    public RotationData getRotation(String placementId) {
        if (apiUrl.isEmpty()) {
            plugin.getLogger().warning("API URL not configured");
            return null;
        }

        try {
            String encodedPlacementId = URLEncoder.encode(placementId, StandardCharsets.UTF_8.name());
            String encodedInstanceId = URLEncoder.encode(instanceId, StandardCharsets.UTF_8.name());
            
            String urlString = apiUrl + "?action=getRotation&placementId=" + encodedPlacementId + "&instanceId=" + encodedInstanceId;
            
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                plugin.getLogger().log(Level.WARNING, "API error: HTTP " + responseCode);
                connection.disconnect();
                return null;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            
            connection.disconnect();
            
            return parseRotationResponse(response.toString(), placementId);
            
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "API request failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Send analytics data in batch.
     * @param impressions JSON array of impression objects
     * @return true if successful
     */
    public boolean reportImpressions(String jsonPayload) {
        if (apiUrl.isEmpty()) {
            return false;
        }

        try {
            String urlString = apiUrl + "?action=reportImpression";
            
            HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            
            // Send payload
            byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
            connection.getOutputStream().write(payloadBytes);
            connection.getOutputStream().flush();
            
            int responseCode = connection.getResponseCode();
            boolean success = (responseCode == HttpURLConnection.HTTP_OK);
            
            if (!success) {
                plugin.getLogger().log(Level.WARNING, "Analytics report failed: HTTP " + responseCode);
            }
            
            connection.disconnect();
            return success;
            
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Analytics send failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Manual JSON parsing - lightweight alternative to Gson/Jackson.
     * Parses the rotation response format expected from Google Apps Script.
     */
    private RotationData parseRotationResponse(String json, String placementId) {
        // Simple manual parsing for expected format:
        // {"status":"ok","config":{"rotationIntervalSec":15,"fallbackUrl":"..."},"cycle":[{...},...]}
        
        if (json == null || json.isEmpty()) {
            return null;
        }

        // Check status
        if (!json.contains("\"status\":\"ok\"") && !json.contains("\"status\": \"ok\"")) {
            plugin.getLogger().warning("API returned non-OK status");
            return null;
        }

        RotationData data = new RotationData();
        data.placementId = placementId;
        data.rotationIntervalSec = 15; // default
        
        // Extract rotationIntervalSec
        int intervalStart = json.indexOf("\"rotationIntervalSec\"");
        if (intervalStart > 0) {
            int colonPos = json.indexOf(':', intervalStart);
            if (colonPos > 0) {
                int numStart = colonPos + 1;
                while (numStart < json.length() && !Character.isDigit(json.charAt(numStart))) {
                    numStart++;
                }
                int numEnd = numStart;
                while (numEnd < json.length() && Character.isDigit(json.charAt(numEnd))) {
                    numEnd++;
                }
                if (numEnd > numStart) {
                    try {
                        data.rotationIntervalSec = Integer.parseInt(json.substring(numStart, numEnd));
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Failed to parse rotation interval");
                    }
                }
            }
        }

        // Extract fallbackUrl
        int fallbackStart = json.indexOf("\"fallbackUrl\"");
        if (fallbackStart > 0) {
            data.fallbackUrl = extractStringValue(json, fallbackStart);
        }

        // Extract cycle array
        int cycleStart = json.indexOf("\"cycle\"");
        if (cycleStart > 0) {
            int arrayStart = json.indexOf('[', cycleStart);
            int arrayEnd = json.indexOf(']', arrayStart);
            
            if (arrayStart > 0 && arrayEnd > arrayStart) {
                String cycleArray = json.substring(arrayStart + 1, arrayEnd);
                data.creatives = parseCreatives(cycleArray);
            }
        }

        return data;
    }

    /**
     * Parse creative entries from cycle array.
     */
    private List<Creative> parseCreatives(String cycleJson) {
        List<Creative> creatives = new ArrayList<>();
        
        // Split by },{ pattern
        String[] parts = cycleJson.split("\\},\\s*\\{");
        
        for (String part : parts) {
            // Clean up braces
            part = part.replace("{", "").replace("}", "").trim();
            
            Creative creative = new Creative();
            
            // Extract creativeId
            int idPos = part.indexOf("\"creativeId\"");
            if (idPos >= 0) {
                creative.id = extractStringValue(part, idPos);
            }
            
            // Extract imageUrl
            int urlPos = part.indexOf("\"imageUrl\"");
            if (urlPos >= 0) {
                creative.imageUrl = extractStringValue(part, urlPos);
            }
            
            if (creative.id != null && creative.imageUrl != null) {
                creatives.add(creative);
            }
        }
        
        return creatives;
    }

    /**
     * Extract string value after a key in JSON.
     */
    private String extractStringValue(String json, int keyPos) {
        int colonPos = json.indexOf(':', keyPos);
        if (colonPos < 0) return null;
        
        int quoteStart = json.indexOf('"', colonPos + 1);
        if (quoteStart < 0) return null;
        
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        
        return json.substring(quoteStart + 1, quoteEnd);
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Data class for rotation response.
     */
    public static class RotationData {
        public String placementId;
        public int rotationIntervalSec = 15;
        public String fallbackUrl;
        public List<Creative> creatives = new ArrayList<>();
    }

    /**
     * Data class for a single creative.
     */
    public static class Creative {
        public String id;
        public String imageUrl;
    }
}
