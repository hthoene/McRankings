package de.hthoene.mcrankings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * McRankings is a library to implement web-based leaderboards
 * to your plugin. Please do not modify this code yourself
 * if you do not know what you are doing - it may break the
 * system and disables mc-rankings for the complete server.
 * A full guide can be found <a href="https://mc-rankings.com/guide">here</a>
 *
 * @author Hannes Thoene
 * @version 1.2.9
 * @since 20.05.2023
 */
public class McRankings {

    private final JavaPlugin javaPlugin;
    private String pluginName;
    private final File configurationFile;
    private YamlConfiguration yamlConfiguration;
    private static String API_URL = "https://mc-rankings.com/api/v1/";
    private static String FRONTEND_URL = "https://mc-rankings.com/<serverName>/<pluginName>/<id>";
    private boolean logInfos = true;
    private boolean libraryEnabled = true;
    private boolean connected = false;
    private long delay = 0;

    public McRankings(JavaPlugin javaPlugin) {
        this.javaPlugin = javaPlugin;
        pluginName = javaPlugin.getName();
        configurationFile = new File("plugins/mc-rankings", "mc-rankings.yml");
        log(Level.INFO, "Plugin is using mc-rankings.com - You will find a settings file in the mc-rankings folder.");
        createConfiguration();
        loadServer();
    }

    public McRankings withPluginName(String pluginName) {
        if(pluginName.contains(" ")) {
            throw new IllegalArgumentException("Please do not use white-spaces in your plugin name!");
        }
        this.pluginName = pluginName;
        return this;
    }

    public McRankings withoutLogging() {
        this.logInfos = false;
        return this;
    }

    public Leaderboard getLeaderboard(int leaderboardId, String title, String metric, boolean higherIsBetter) {
        String leaderboardConfigPath = "leaderboards." + pluginName + "." + leaderboardId;

        if (yamlConfiguration.isSet(leaderboardConfigPath + ".secret-key")) {
            Leaderboard leaderboard = new Leaderboard(
                    leaderboardId,
                    yamlConfiguration.getString(leaderboardConfigPath + ".title"),
                    yamlConfiguration.getString(leaderboardConfigPath + ".metric"),
                    yamlConfiguration.getBoolean(leaderboardConfigPath + ".higherIsBetter"),
                    yamlConfiguration.getBoolean(leaderboardConfigPath + ".enabled", true)
            );

            leaderboard.secretKey = yamlConfiguration.getString(leaderboardConfigPath + ".secret-key");

            registerLeaderboard(leaderboard);
            return leaderboard;

        } else {
            yamlConfiguration.set(leaderboardConfigPath + ".title", title);
            yamlConfiguration.set(leaderboardConfigPath + ".metric", metric);
            yamlConfiguration.set(leaderboardConfigPath + ".higherIsBetter", higherIsBetter);
            yamlConfiguration.set(leaderboardConfigPath + ".secret-key", generateKey());
            yamlConfiguration.set(leaderboardConfigPath + ".enabled", true);

            saveConfig();
        }

        return getLeaderboard(leaderboardId, title, metric, higherIsBetter);
    }

    private void registerServer() {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("serverName", getServerName());
        requestBody.addProperty("serverKey", getServerKey());
        requestBody.addProperty("license", getLicense());

        log(Level.FINE, "Connecting to mc-rankings.com...");
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
        requestBody.addProperty("enabled", leaderboard.enabled);

        sendRequest("leaderboard/register", requestBody, RequestType.LEADERBOARD);
        setDelay(2000);
    }

    private void setDelay(long millis) {
        this.delay = millis;
    }

    private void runDelay() {
        try {
            Thread.sleep(delay);
            delay = 0;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendRequest(String endpoint, JsonObject requestBody, RequestType requestType) {
        if(!libraryEnabled) return;
        Bukkit.getScheduler().runTaskAsynchronously(javaPlugin, new Runnable() {
            @Override
            public void run() {
                try {
                    if(requestType != RequestType.SERVER && !connected) {
                        switch (requestType) {
                            case LEADERBOARD:
                                Thread.sleep(1000);
                                break;
                            case SCORE:
                            case BULK:
                                Thread.sleep(2000);
                                break;
                        }
                    }

                    if(requestType != RequestType.SERVER && delay > 0)
                        runDelay();

                    URL url = new URL(API_URL + endpoint);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setRequestProperty("Accept", "*/*");
                    connection.setRequestProperty("User-Agent", "PostmanRuntime/7.28.4");
                    connection.setRequestProperty("Connection", "keep-alive");
                    connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
                    connection.setDoOutput(true);

                    try (OutputStream outputStream = connection.getOutputStream();
                         BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream))) {
                        writer.write(requestBody.toString());
                    }

                    int responseCode = connection.getResponseCode();

                    if (responseCode < 400) {
                        if(requestType == RequestType.SERVER) {
                            log(Level.INFO, "Successfully connected to mc-rankings.com");
                            connected = true;
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

                    switch (requestType) {
                        case SERVER:
                            log(Level.WARNING, "Could not connect to mc-rankings.com");
                            break;
                        case SCORE:
                            log(Level.WARNING, "Could not update score");
                            break;
                        case BULK:
                            log(Level.WARNING, "Could not execute bulk task");
                            break;
                        case LEADERBOARD:
                            log(Level.WARNING, "Could not update leaderboard");
                            break;
                    }

                    log(Level.WARNING, response.toString());

                } catch (IOException e) {
                    log(Level.WARNING, e.getMessage());
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private void log(Level level, String message) {
        if(level == Level.INFO && !logInfos) return;
        javaPlugin.getLogger().log(level, "(mc-rankings.com) > " + message);
    }

    private void createConfiguration() {
        try {
            Files.createDirectories(Paths.get("plugins/mc-rankings"));
            if (configurationFile.createNewFile()) {
                yamlConfiguration = YamlConfiguration.loadConfiguration(configurationFile);
                yamlConfiguration.options().copyDefaults(true);
                yamlConfiguration.addDefault("license-key", generateKey());
                yamlConfiguration.addDefault("server-key", generateKey());
                yamlConfiguration.addDefault("server-name", UUID.randomUUID().toString());
                yamlConfiguration.addDefault("api-endpoint", API_URL);
                yamlConfiguration.addDefault("frontend-url", FRONTEND_URL);
                yamlConfiguration.addDefault("enabled", true);
                log(Level.WARNING, "mc-rankings.com requires one more server reload for the initial setup to take effect.");
                saveConfig();
            } else {
                yamlConfiguration = YamlConfiguration.loadConfiguration(configurationFile);
                API_URL = yamlConfiguration.getString("api-endpoint", API_URL);
                FRONTEND_URL = yamlConfiguration.getString("frontend-url", FRONTEND_URL);
                connected = true;
                libraryEnabled = yamlConfiguration.getBoolean("enabled", true);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String generateKey() {
        String allowedCharacters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder key = new StringBuilder();

        for (int i = 0; i < 14; i++) {
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
        if(!libraryEnabled) return;
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
        SERVER, LEADERBOARD, SCORE, BULK
    }

    private class BulkScoreRequest {
        private String serverKey;
        private String secretKey;
        private final List<PlayerScore> scores = new ArrayList<>();
    }

    public static class PlayerScore {
        private final UUID uuid;
        private final String username;
        private final long score;

        public PlayerScore(UUID uuid, String username, long score) {
            this.uuid = uuid;
            this.username = username;
            this.score = score;
        }
    }

    public class Leaderboard {
        private final int leaderboardId;
        private final String title;
        private final String metric;
        private final boolean higherIsBetter;
        private final boolean enabled;
        private String secretKey;


        public Leaderboard(int leaderboardId, String title, String metric, boolean higherIsBetter, boolean enabled) {
            this.leaderboardId = leaderboardId;
            this.title = title;
            this.metric = metric;
            this.higherIsBetter = higherIsBetter;
            this.enabled = enabled;
        }

        public String getUrl() {
            return FRONTEND_URL.replace("<serverName>", getServerName()).replace("<pluginName>", pluginName).replace("<id>", String.valueOf(leaderboardId));
        }

        public void setScore(OfflinePlayer offlinePlayer, long score) {
            publishScore(new PlayerScore(offlinePlayer.getUniqueId(), offlinePlayer.getName(), score));
        }

        public void setScore(Player player, long score) {
            publishScore(new PlayerScore(player.getUniqueId(), player.getName(), score));
        }

        public void setScore(UUID uuid, String playerName, long score) {
            publishScore(new PlayerScore(uuid, playerName, score));
        }

        public void deleteEntry(UUID uuid) {
            if(!enabled) return;
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("secretKey", secretKey);
            jsonObject.addProperty("uuid", uuid.toString());
            sendRequest("score/delete", jsonObject, RequestType.SCORE);
        }

        public void clear() {
            if(!enabled) return;
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("secretKey", secretKey);
            sendRequest("score/deleteAll", jsonObject, RequestType.SCORE);
        }

        public void setScore(PlayerScore playerScore) {
            publishScore(playerScore);
        }

        public void setScores(List<PlayerScore> playerScores) {
            if(!enabled) return;
            Gson gson = new Gson();
            BulkScoreRequest request = new BulkScoreRequest();
            request.serverKey = getServerKey();
            request.secretKey = secretKey;
            request.scores.addAll(playerScores);

            sendRequest("leaderboard/scores", gson.fromJson(gson.toJson(request), JsonObject.class), RequestType.BULK);
        }

        private void publishScore(PlayerScore playerScore) {
            if(!enabled) return;
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("secretKey", secretKey);
            requestBody.addProperty("uuid", playerScore.uuid.toString());
            requestBody.addProperty("username", playerScore.username);
            requestBody.addProperty("score", playerScore.score);
            requestBody.addProperty("serverKey", getServerKey());
            sendRequest("leaderboard/score", requestBody, RequestType.SCORE);
        }
    }
}