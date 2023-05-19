package de.hthoene.mcrankings;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * McRankings core API
 *
 * @author      Hannes Thöne
 * @version     1.0.0
 * @since       1.0.0
 */
public class McRankings {

    private final JavaPlugin javaPlugin;
    private String pluginName;
    private final File configurationFile;
    private YamlConfiguration yamlConfiguration;

    private static final String API_URL = "https://mc-rankings.com/api/v1/";

    /**
     * Returns a McRankings instance to access the API
     *
     * @param  javaPlugin your plugin instance
     * @see         McRankings
     */
    public McRankings(JavaPlugin javaPlugin) {
        this.javaPlugin = javaPlugin;
        pluginName = javaPlugin.getName();
        configurationFile = new File("plugins/mc-rankings", "mc-rankings.yml");
        createConfiguration();
        loadServer();
    }

    /**
     * Returns a leaderboard instance to access features
     *
     * @param  pluginName the id of the leaderboard, has to be unique
     * @return      the McRankings instance
     * @see         McRankings
     */
    public McRankings withPluginName(String pluginName) {
        if(pluginName.contains(" ")) {
            throw new IllegalArgumentException("Please do not use white-spaces in your plugin name!");
        }
        this.pluginName = pluginName;
        return this;
    }

    /**
     * Returns a leaderboard instance to access features
     *
     * @param  leaderboardId the id of the leaderboard, has to be unique
     * @param  title the title of the leaderboard, will be displayed on the website
     * @param  metric the metric of the leaderboard, this is a value that is shown behind the score, e.g. 'wins'
     * @param  higherIsBetter set this to true if a higher score is better than a lower score
     * @return      a leaderboard instance
     * @see         Leaderboard
     */
    public Leaderboard getLeaderboard(int leaderboardId, String title, String metric, boolean higherIsBetter) {
        String leaderboardConfigPath = "leaderboards." + pluginName + "." + leaderboardId;

        if (yamlConfiguration.isSet(leaderboardConfigPath + ".secret-key")) {
            Leaderboard leaderboard = new Leaderboard(
                    leaderboardId,
                    yamlConfiguration.getString(leaderboardConfigPath + ".title"),
                    yamlConfiguration.getString(leaderboardConfigPath + ".metric"),
                    yamlConfiguration.getBoolean(leaderboardConfigPath + ".higherIsBetter"),
                    javaPlugin
            );

            leaderboard.secretKey = yamlConfiguration.getString(leaderboardConfigPath + ".secret-key");

            registerLeaderboard(leaderboard);

            return leaderboard;
        } else {
            yamlConfiguration.set(leaderboardConfigPath + ".title", title);
            yamlConfiguration.set(leaderboardConfigPath + ".metric", metric);
            yamlConfiguration.set(leaderboardConfigPath + ".higherIsBetter", higherIsBetter);
            yamlConfiguration.set(leaderboardConfigPath + ".secret-key", generateKey());

            saveConfig();
        }

        return getLeaderboard(leaderboardId, title, metric, higherIsBetter);
    }

    private void registerServer() {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("serverName", getServerName());
        requestBody.addProperty("serverKey", getServerKey());
        requestBody.addProperty("license", getLicense());

        log(Level.INFO, "Connecting with mc-rankings.com...");
        sendRequest("server/register", requestBody, RequestType.SERVER);
    }

    private void registerLeaderboard(Leaderboard leaderboard) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("serverKey", getServerKey());
        requestBody.addProperty("secretKey", leaderboard.secretKey);
        requestBody.addProperty("title", leaderboard.title);
        requestBody.addProperty("pluginName", pluginName);
        requestBody.addProperty("metric", leaderboard.metric);
        requestBody.addProperty("leaderboardId", leaderboard.leaderboardId);
        requestBody.addProperty("higherIsBetter", leaderboard.higherIsBetter);

        sendRequest("leaderboard/register", requestBody, RequestType.LEADERBOARD);
    }

    private void sendRequest(String endpoint, JsonObject requestBody, RequestType requestType) {
        Bukkit.getScheduler().runTaskAsynchronously(javaPlugin, new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(API_URL + endpoint);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setDoOutput(true);

                    try (OutputStream outputStream = connection.getOutputStream();
                         BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream))) {
                        writer.write(requestBody.toString());
                    }

                    int responseCode = connection.getResponseCode();

                    if (responseCode < 400) {
                        if(requestType == RequestType.SERVER) {
                            log(Level.INFO, "Successfully connected to mc-rankings.com");
                        }
                        return;
                    }

                    StringBuilder response = new StringBuilder();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));

                    String inputLine;
                    while ((inputLine = reader.readLine()) != null) {
                        response.append(inputLine);
                    }
                    reader.close();

                    log(Level.WARNING, response.toString());

                } catch (IOException e) {
                    log(Level.WARNING, e.getMessage());
                }
            }
        });
    }

    private void log(Level level, String message) {
        javaPlugin.getLogger().log(level, "[mc-rankings.com] " + message);
    }

    private void createConfiguration() {
        try {
            Files.createDirectories(Path.of("plugins/mc-rankings"));
            if (configurationFile.createNewFile()) {
                yamlConfiguration = YamlConfiguration.loadConfiguration(configurationFile);
                yamlConfiguration.options().copyDefaults(true);
                yamlConfiguration.addDefault("license-key", generateKey());
                yamlConfiguration.addDefault("server-key", generateKey());
                yamlConfiguration.addDefault("server-name", UUID.randomUUID().toString());
                saveConfig();
            } else {
                yamlConfiguration = YamlConfiguration.loadConfiguration(configurationFile);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String generateKey() {
        String allowedCharacters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder key = new StringBuilder();

        for (int i = 0; i < 12; i++) {
            int randomIndex = random.nextInt(allowedCharacters.length());
            char randomChar = allowedCharacters.charAt(randomIndex);
            key.append(randomChar);
        }

        return key.toString();
    }

    private void saveConfig() {
        try {
            yamlConfiguration.save(configurationFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadServer() {
        Bukkit.getScheduler().runTaskAsynchronously(javaPlugin, this::registerServer);
    }

    private String getServerName() {
        return Objects.requireNonNull(yamlConfiguration.getString("server-name")).replace(" ", "-");
    }

    private String getLicense() {
        return yamlConfiguration.getString("license-key");
    }

    private String getServerKey() {
        return yamlConfiguration.getString("server-key");
    }

    private enum RequestType {
        SERVER, LEADERBOARD, SCORE
    }

    /**
     * Leaderboard class to access leaderboard features
     */
    public class Leaderboard {
        private final int leaderboardId;
        private final String title;
        private final String metric;
        private final boolean higherIsBetter;
        private String secretKey;

        private final JavaPlugin javaPlugin;

        /**
         * Returns a leaderboard instance to access features
         *
         * @param  leaderboardId the id of the leaderboard, has to be unique
         * @param  title the title of the leaderboard, will be displayed on the website
         * @param  metric the metric of the leaderboard, this is a value that is shown behind the score, e.g. 'wins'
         * @param  higherIsBetter set this to true if a higher score is better than a lower score
         * @param  javaPlugin the instance of the JavaPlugin
         * @see         Leaderboard
         */
        public Leaderboard(int leaderboardId, String title, String metric, boolean higherIsBetter, JavaPlugin javaPlugin) {
            this.leaderboardId = leaderboardId;
            this.title = title;
            this.metric = metric;
            this.higherIsBetter = higherIsBetter;
            this.javaPlugin = javaPlugin;
        }

        /**
         * Returns the url to access the leaderboard with your browser
         *
         * @return      the url to access the leaderboard
         * @see         String
         */
        public String getUrl() {
            return "https://mc-rankings.com/" + getServerName() + "/" + pluginName + "/" + leaderboardId;
        }

        /**
         * Sets a new score to a specific player
         *
         * @param player the player to set a new score
         * @param score the new score of the player
         * @see         String
         */
        public void setScore(Player player, long score) {
            Bukkit.getScheduler().runTaskAsynchronously(javaPlugin, () -> {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("secretKey", secretKey);
                requestBody.addProperty("uuid", player.getUniqueId().toString());
                requestBody.addProperty("username", player.getName());
                requestBody.addProperty("score", score);

                sendRequest("leaderboard/score", requestBody, RequestType.SCORE);
            });
        }
    }
}