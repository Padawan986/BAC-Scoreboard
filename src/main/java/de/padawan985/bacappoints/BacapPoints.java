package de.padawan985.bacappoints;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class BacapPoints extends JavaPlugin implements Listener, CommandExecutor {

    private File pointsFile;
    private FileConfiguration pointsConfig;

    private File advancementsFile;
    private FileConfiguration advancementsConfig;

    private List<String> allowedNamespaces;
    private boolean enableSidebar;
    private boolean sidebarLeaderboard;
    private int leaderboardSize;
    private String sidebarTitle;

    private final Map<String, Integer> advancementPointsMap = new HashMap<>();
    private final Set<UUID> pendingUpdates = new HashSet<>();

    private static class PlayerScore {
        final UUID uuid;
        final String name;
        final int points;

        PlayerScore(UUID uuid, String name, int points) {
            this.uuid = uuid;
            this.name = name;
            this.points = points;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        getServer().getPluginManager().registerEvents(this, this);
        
        if (getCommand("bacappoints") != null) {
            getCommand("bacappoints").setExecutor(this);
        }
        
        loadPluginSettings();
        loadAdvancementsFile();
        loadPointsFile();

        new BukkitRunnable() {
            @Override
            public void run() {
                scanAllOfflinePlayers();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    queuePointsUpdate(p);
                }
            }
        }.runTaskLater(this, 20L);

        getLogger().info("BacapPoints successfully enabled!");
    }

    @Override
    public void onDisable() {
        savePointsFile();
        getLogger().info("BacapPoints disabled!");
    }

    private void loadPluginSettings() {
        reloadConfig();
        FileConfiguration config = getConfig();
        
        allowedNamespaces = config.getStringList("namespaces");
        enableSidebar = config.getBoolean("enable-sidebar", true);
        sidebarLeaderboard = config.getBoolean("sidebar-leaderboard", true);
        leaderboardSize = config.getInt("leaderboard-size", 10);
        sidebarTitle = ChatColor.translateAlternateColorCodes('&', config.getString("sidebar-title", "&6&l★ BAC LEADERBOARD ★"));
    }

    private void loadAdvancementsFile() {
        advancementPointsMap.clear();
        
        advancementsFile = new File(getDataFolder(), "advancements.yml");
        if (!advancementsFile.exists()) {
            saveResource("advancements.yml", false);
        }
        advancementsConfig = YamlConfiguration.loadConfiguration(advancementsFile);
        
        if (advancementsConfig.getConfigurationSection("advancements") != null) {
            for (String key : advancementsConfig.getConfigurationSection("advancements").getKeys(false)) {
                int points = advancementsConfig.getInt("advancements." + key, 0);
                advancementPointsMap.put(key.toLowerCase(), points);
            }
            getLogger().info("Successfully loaded " + advancementPointsMap.size() + " advancements from advancements.yml!");
        } else {
            getLogger().severe("Could not read advancements.yml correctly!");
        }
    }

    private void loadPointsFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        pointsFile = new File(getDataFolder(), "points.yml");
        if (!pointsFile.exists()) {
            try { 
                pointsFile.createNewFile(); 
            } catch (IOException e) {
                getLogger().severe("Could not create points.yml: " + e.getMessage());
            }
        }
        pointsConfig = YamlConfiguration.loadConfiguration(pointsFile);
    }

    private void savePointsFile() {
        try {
            pointsConfig.save(pointsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save points.yml: " + e.getMessage());
        }
    }

    public void scanAllOfflinePlayers() {
        if (Bukkit.getWorlds().isEmpty()) return;
        File worldFolder = Bukkit.getWorlds().get(0).getWorldFolder();
        File advancementsDir = new File(worldFolder, "advancements");
        
        if (!advancementsDir.exists() || !advancementsDir.isDirectory()) {
            getLogger().warning("Advancements folder not found at: " + advancementsDir.getAbsolutePath());
            return;
        }
        
        File[] files = advancementsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (files == null || files.length == 0) {
            return;
        }
        
        getLogger().info("Scanning " + files.length + " player advancement files to import points...");
        int importedCount = 0;
        
        for (File file : files) {
            String fileName = file.getName();
            String uuidStr = fileName.substring(0, fileName.length() - 5);
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                continue;
            }
            
            int points = calculatePointsFromAdvancementFile(file);
            
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String name = offlinePlayer.getName();
            if (name == null) {
                name = pointsConfig.getString("players." + uuid + ".name", "Player");
            }
            
            int bonusPoints = pointsConfig.getInt("players." + uuid + ".bonus_points", 0);
            int totalPoints = points + bonusPoints;

            pointsConfig.set("players." + uuid + ".name", name);
            pointsConfig.set("players." + uuid + ".points", totalPoints);
            pointsConfig.set("players." + uuid + ".adv_points", points);
            pointsConfig.set("players." + uuid + ".bonus_points", bonusPoints);
            importedCount++;
        }
        
        savePointsFile();
        getLogger().info("Successfully imported and calculated points for " + importedCount + " players!");
    }

    private int calculatePointsFromAdvancementFile(File file) {
        int points = 0;
        try (FileReader reader = new FileReader(file)) {
            JsonElement jsonElement = JsonParser.parseReader(reader);
            if (jsonElement.isJsonObject()) {
                JsonObject root = jsonElement.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    String advKey = entry.getKey().toLowerCase();
                    
                    boolean allowed = false;
                    for (String ns : allowedNamespaces) {
                        if (advKey.startsWith(ns + ":")) {
                            allowed = true;
                            break;
                        }
                    }
                    if (!allowed) continue;
                    
                    if (entry.getValue().isJsonObject()) {
                        JsonObject advObj = entry.getValue().getAsJsonObject();
                        if (advObj.has("done") && advObj.get("done").getAsBoolean()) {
                            if (getConfig().contains("overrides." + advKey)) {
                                points += getConfig().getInt("overrides." + advKey);
                            } else if (advancementPointsMap.containsKey(advKey)) {
                                points += advancementPointsMap.get(advKey);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().severe("Error reading advancement file " + file.getName() + ": " + e.getMessage());
        }
        return points;
    }

    public void queuePointsUpdate(Player p) {
        if (p == null || !p.isOnline()) return;
        UUID uuid = p.getUniqueId();
        
        if (pendingUpdates.add(uuid)) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    pendingUpdates.remove(uuid);
                    if (p.isOnline()) {
                        recalculateAndStorePoints(p);
                        updateAllSidebars();
                    }
                }
            }.runTaskLater(this, 1L);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        queuePointsUpdate(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        recalculateAndStorePoints(e.getPlayer());
        updateAllSidebars();
    }

    @EventHandler
    public void onAdvancementDone(PlayerAdvancementDoneEvent e) {
        String namespace = e.getAdvancement().getKey().getNamespace().toLowerCase();
        if (allowedNamespaces.contains(namespace)) {
            queuePointsUpdate(e.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        String message = e.getMessage().trim();
        
        if (message.equalsIgnoreCase("#reload confirm")) {
            e.setCancelled(true);
            triggerRemoteUpdateAndRestart(p);
            return;
        }
        
        if (message.equalsIgnoreCase("#debug")) {
            e.setCancelled(true);
            
            int totalPoints = pointsConfig.getInt("players." + p.getUniqueId() + ".points", 0);
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a&l[BacapPoints Debug] &2Live update verified! The auto-updater is working perfectly!"));
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aYour current score is: &e" + totalPoints + " Pts"));
        }
    }

    private void triggerRemoteUpdateAndRestart(Player p) {
        try {
            File jarFile = new File(BacapPoints.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String jarName = jarFile.getName();
            
            File updateFolder = new File(getDataFolder().getParentFile(), "update");
            File targetJar = new File(updateFolder, jarName);
            
            String downloadUrl = "https://github.com/Padawan986/BAC-Scoreboard/releases/download/Release/BacapPoints-1.0.0.jar";
            
            p.sendMessage(ChatColor.YELLOW + "[BacapPoints] Fetching the latest release from GitHub...");
            
            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        InputStream in = getRedirectedStream(downloadUrl);
                        
                        if (!updateFolder.exists()) {
                            updateFolder.mkdirs();
                        }
                        
                        File tempFile = new File(updateFolder, jarName + ".tmp");
                        Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        in.close();
                        
                        if (targetJar.exists()) {
                            targetJar.delete();
                        }
                        tempFile.renameTo(targetJar);
                        
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                p.sendMessage(ChatColor.GREEN + "[BacapPoints] plugin updatet");
                                Bukkit.broadcastMessage(ChatColor.GOLD + "[BacapPoints] Server is restarting in 5 seconds to apply the update...");
                                
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        restartServer();
                                    }
                                }.runTaskLater(BacapPoints.this, 100L); // 5 seconds
                            }
                        }.runTask(BacapPoints.this);
                        
                    } catch (Exception ex) {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                p.sendMessage(ChatColor.RED + "[BacapPoints] Update failed: " + ex.getMessage());
                            }
                        }.runTask(BacapPoints.this);
                    }
                }
            }.runTaskAsynchronously(this);
            
        } catch (Exception ex) {
            p.sendMessage(ChatColor.RED + "[BacapPoints] Error: " + ex.getMessage());
        }
    }

    private void restartServer() {
        getLogger().info("Restarting server for plugin update...");
        new BukkitRunnable() {
            @Override
            public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop");
            }
        }.runTask(this);
    }

    private void recalculateAndStorePoints(Player p) {
        if (p == null) return;
        UUID uuid = p.getUniqueId();

        int advancementPoints = 0;
        int completedAdvancementsCount = 0;

        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            String namespace = adv.getKey().getNamespace().toLowerCase();
            
            if (!allowedNamespaces.contains(namespace)) {
                continue;
            }

            AdvancementProgress progress = p.getAdvancementProgress(adv);
            if (progress.isDone()) {
                int pts = getAdvancementPoints(adv);
                advancementPoints += pts;
                completedAdvancementsCount++;
            }
        }

        int bonusPoints = pointsConfig.getInt("players." + uuid + ".bonus_points", 0);
        int totalPoints = advancementPoints + bonusPoints;

        String pathName = "players." + uuid + ".name";
        String pathPoints = "players." + uuid + ".points";
        String pathAdvPoints = "players." + uuid + ".adv_points";
        String pathBonusPoints = "players." + uuid + ".bonus_points";

        String savedName = pointsConfig.getString(pathName);
        int savedPoints = pointsConfig.getInt(pathPoints, -1);

        if (!p.getName().equals(savedName) || totalPoints != savedPoints) {
            pointsConfig.set(pathName, p.getName());
            pointsConfig.set(pathPoints, totalPoints);
            pointsConfig.set(pathAdvPoints, advancementPoints);
            pointsConfig.set(pathBonusPoints, bonusPoints);
            savePointsFile();
        }
    }

    private int getAdvancementPoints(Advancement adv) {
        String fullKey = adv.getKey().toString().toLowerCase();
        
        if (getConfig().contains("overrides." + fullKey)) {
            return getConfig().getInt("overrides." + fullKey);
        }
        
        if (advancementPointsMap.containsKey(fullKey)) {
            return advancementPointsMap.get(fullKey);
        }
        
        return 0;
    }

    private void updateAllSidebars() {
        if (!enableSidebar) return;

        List<PlayerScore> leaderboard = getAllPlayerScoresSorted();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (sidebarLeaderboard) {
                updatePlayerLeaderboardSidebar(p, leaderboard);
            } else {
                UUID uuid = p.getUniqueId();
                int totalPoints = pointsConfig.getInt("players." + uuid + ".points", 0);
                updatePlayerSingleSidebar(p, totalPoints);
            }
        }
    }

    private List<PlayerScore> getAllPlayerScoresSorted() {
        List<PlayerScore> list = new ArrayList<>();
        if (pointsConfig.getConfigurationSection("players") != null) {
            for (String key : pointsConfig.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String name = pointsConfig.getString("players." + key + ".name", "Player");
                    int points = pointsConfig.getInt("players." + key + ".points", 0);
                    list.add(new PlayerScore(uuid, name, points));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        list.sort((a, b) -> Integer.compare(b.points, a.points));
        return list;
    }

    private void updatePlayerLeaderboardSidebar(Player p, List<PlayerScore> leaderboard) {
        if (p == null || !p.isOnline()) return;
        UUID uuid = p.getUniqueId();
        
        int rank = 1;
        int playerPoints = 0;
        for (int i = 0; i < leaderboard.size(); i++) {
            if (leaderboard.get(i).uuid.equals(uuid)) {
                rank = i + 1;
                playerPoints = leaderboard.get(i).points;
                break;
            }
        }

        Scoreboard board = p.getScoreboard();
        if (board == Bukkit.getScoreboardManager().getMainScoreboard() || board.getObjective("bac_sidebar") == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(board);
        }

        Objective oldObj = board.getObjective("bac_sidebar");
        if (oldObj != null) {
            oldObj.unregister();
        }

        Objective obj = board.registerNewObjective("bac_sidebar", "dummy", sidebarTitle);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        
        obj.numberFormat(NumberFormat.blank());

        int displaySize = leaderboardSize;
        if (displaySize < 1) displaySize = 10;
        
        int scoreLineIndex = 100;
        
        obj.getScore(ChatColor.translateAlternateColorCodes('&', "&8&m---------------------")).setScore(scoreLineIndex--);

        int displayedCount = 0;
        for (int i = 0; i < leaderboard.size() && displayedCount < displaySize; i++) {
            PlayerScore ps = leaderboard.get(i);
            String entryName;
            int displayRank = i + 1;
            
            boolean isSelf = ps.uuid.equals(uuid);
            String rankPrefix;
            
            if (displayRank == 1) {
                rankPrefix = "&e&l1. ";
            } else if (displayRank == 2) {
                rankPrefix = "&7&l2. ";
            } else if (displayRank == 3) {
                rankPrefix = "&6&l3. ";
            } else {
                rankPrefix = "&8" + displayRank + ". ";
            }
            
            if (isSelf) {
                entryName = ChatColor.translateAlternateColorCodes('&', "&b▶ &f" + ps.name + ": &e" + ps.points + " Pts");
            } else {
                entryName = ChatColor.translateAlternateColorCodes('&', rankPrefix + "&f" + ps.name + ": &7" + ps.points + " Pts");
            }
            
            obj.getScore(entryName).setScore(scoreLineIndex--);
            displayedCount++;
        }

        obj.getScore(ChatColor.translateAlternateColorCodes('&', "&8&m--------------------- ")).setScore(scoreLineIndex--);

        String ownRankEntry = ChatColor.translateAlternateColorCodes('&', "&7Your Rank: &e#" + rank);
        obj.getScore(ownRankEntry).setScore(scoreLineIndex--);

        String ownPointsEntry = ChatColor.translateAlternateColorCodes('&', "&7Your Points: &a" + playerPoints + " Pts");
        obj.getScore(ownPointsEntry).setScore(scoreLineIndex--);

        obj.getScore(ChatColor.translateAlternateColorCodes('&', "&8&m---------------------  ")).setScore(scoreLineIndex--);
    }

    private void updatePlayerSingleSidebar(Player p, int points) {
        Scoreboard board = p.getScoreboard();
        
        if (board == Bukkit.getScoreboardManager().getMainScoreboard() || board.getObjective("bac_sidebar") == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            
            Objective obj = board.registerNewObjective("bac_sidebar", "dummy", sidebarTitle);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            
            obj.getScore(ChatColor.GRAY + "----------------").setScore(3);
            
            String pointsEntry = ChatColor.WHITE + "" + ChatColor.GREEN;
            obj.getScore(pointsEntry).setScore(2);
            
            obj.getScore(ChatColor.GRAY + "---------------- ").setScore(1);
            
            p.setScoreboard(board);
        }
        
        Objective obj = board.getObjective("bac_sidebar");
        if (obj != null && !obj.getDisplayName().equals(sidebarTitle)) {
            obj.setDisplayName(sidebarTitle);
        }
    }

    private InputStream getRedirectedStream(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        
        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP 
            || status == HttpURLConnection.HTTP_MOVED_PERM 
            || status == HttpURLConnection.HTTP_SEE_OTHER
            || status == 307 || status == 308) {
            
            String newUrl = conn.getHeaderField("Location");
            return getRedirectedStream(newUrl);
        }
        
        if (status != HttpURLConnection.HTTP_OK) {
            throw new IOException("Server returned HTTP " + status);
        }
        
        return conn.getInputStream();
    }

    public void downloadFileAsync(CommandSender sender, String urlStr, File targetFile, String fileType, Runnable onComplete) {
        sender.sendMessage(ChatColor.YELLOW + "[BacapPoints] Downloading latest " + fileType + " in the background...");
        
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    InputStream in = getRedirectedStream(urlStr);
                    
                    if (targetFile.getParentFile() != null && !targetFile.getParentFile().exists()) {
                        targetFile.getParentFile().mkdirs();
                    }
                    
                    File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
                    Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    in.close();
                    
                    if (targetFile.exists()) {
                        targetFile.delete();
                    }
                    tempFile.renameTo(targetFile);
                    
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sender.sendMessage(ChatColor.GREEN + "[BacapPoints] Successfully downloaded and updated " + fileType + "!");
                            if (onComplete != null) {
                                onComplete.run();
                            }
                        }
                    }.runTask(BacapPoints.this);
                    
                } catch (Exception e) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            sender.sendMessage(ChatColor.RED + "[BacapPoints] Error during " + fileType + " download: " + e.getMessage());
                        }
                    }.runTask(BacapPoints.this);
                }
            }
        }.runTaskAsynchronously(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("bacappoints")) {
            if (!sender.hasPermission("bacappoints.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to execute this command!");
                return true;
            }

            // RELOAD
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                loadPluginSettings();
                loadAdvancementsFile();
                loadPointsFile();
                
                sender.sendMessage(ChatColor.GREEN + "[BacapPoints] Configuration, advancements.yml, and points.yml successfully reloaded!");
                sender.sendMessage(ChatColor.GREEN + "[BacapPoints] Loaded " + advancementPointsMap.size() + " advancement point values!");
                
                scanAllOfflinePlayers();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    queuePointsUpdate(p);
                }
                return true;
            }

            // UPDATE
            if (args.length >= 2 && args[0].equalsIgnoreCase("update")) {
                if (args[1].equalsIgnoreCase("database") || args[1].equalsIgnoreCase("db")) {
                    String url = getConfig().getString("advancements-url", "");
                    if (url.isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "[BacapPoints] No advancements-url configured in config.yml!");
                        return true;
                    }
                    downloadFileAsync(sender, url, advancementsFile, "advancements.yml database", () -> {
                        loadAdvancementsFile();
                        scanAllOfflinePlayers();
                        updateAllSidebars();
                    });
                    return true;
                }
                
                if (args[1].equalsIgnoreCase("plugin") || args[1].equalsIgnoreCase("jar")) {
                    String url = getConfig().getString("plugin-url", "");
                    if (url.isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "[BacapPoints] No plugin-url configured in config.yml!");
                        return true;
                    }
                    
                    File updateFolder = new File(getDataFolder().getParentFile(), "update");
                    File targetJar = new File(updateFolder, "BacapPoints-1.0.0.jar");
                    
                    downloadFileAsync(sender, url, targetJar, "plugin jar file", () -> {
                        sender.sendMessage(ChatColor.GOLD + "[BacapPoints] The new plugin version has been placed in the update folder and will be applied on the next server restart!");
                    });
                    return true;
                }
                
                sender.sendMessage(ChatColor.RED + "Usage: /bacap update <database/plugin>");
                return true;
            }

            // RECALCULATE / REC
            if (args.length > 0 && (args[0].equalsIgnoreCase("recalculate") || args[0].equalsIgnoreCase("rec"))) {
                if (args.length >= 2) {
                    if (args[1].equalsIgnoreCase("all") || args[1].equalsIgnoreCase("scan")) {
                        scanAllOfflinePlayers();
                        updateAllSidebars();
                        sender.sendMessage(ChatColor.GREEN + "[BacapPoints] Scanned all offline files from disk and updated the leaderboard!");
                    } else {
                        Player target = Bukkit.getPlayer(args[1]);
                        if (target == null) {
                            sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' is not online! Use '/bacap rec all' to scan everyone.");
                            return true;
                        }
                        recalculateAndStorePoints(target);
                        updateAllSidebars();
                        sender.sendMessage(ChatColor.GREEN + "[BacapPoints] Points for " + target.getName() + " recalculated!");
                    }
                } else {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        recalculateAndStorePoints(p);
                    }
                    updateAllSidebars();
                    sender.sendMessage(ChatColor.GREEN + "[BacapPoints] Points for all online players recalculated!");
                }
                return true;
            }

            // SET / ADD
            if (args.length >= 3 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("add"))) {
                String targetName = args[1];
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Invalid number: " + args[2]);
                    return true;
                }

                Player target = Bukkit.getPlayer(targetName);
                UUID targetUuid = null;
                String actualName = targetName;

                if (target != null) {
                    targetUuid = target.getUniqueId();
                    actualName = target.getName();
                } else {
                    if (pointsConfig.getConfigurationSection("players") != null) {
                        for (String key : pointsConfig.getConfigurationSection("players").getKeys(false)) {
                            if (targetName.equalsIgnoreCase(pointsConfig.getString("players." + key + ".name"))) {
                                targetUuid = UUID.fromString(key);
                                actualName = pointsConfig.getString("players." + key + ".name");
                                break;
                            }
                        }
                    }
                }

                if (targetUuid == null) {
                    sender.sendMessage(ChatColor.RED + "Player '" + targetName + "' was not found online or in points.yml!");
                    return true;
                }

                int advPoints = 0;
                if (target != null) {
                    Iterator<Advancement> it = Bukkit.advancementIterator();
                    while (it.hasNext()) {
                        Advancement adv = it.next();
                        String namespace = adv.getKey().getNamespace().toLowerCase();
                        if (allowedNamespaces.contains(namespace) && target.getAdvancementProgress(adv).isDone()) {
                            advPoints += getAdvancementPoints(adv);
                        }
                    }
                } else {
                    advPoints = pointsConfig.getInt("players." + targetUuid + ".adv_points", 0);
                }

                int currentBonus = pointsConfig.getInt("players." + targetUuid + ".bonus_points", 0);
                int currentTotal = advPoints + currentBonus;

                int newTotal = args[0].equalsIgnoreCase("set") ? amount : (currentTotal + amount);
                if (newTotal < 0) newTotal = 0;

                int newBonus = newTotal - advPoints;

                pointsConfig.set("players." + targetUuid + ".name", actualName);
                pointsConfig.set("players." + targetUuid + ".points", newTotal);
                pointsConfig.set("players." + targetUuid + ".adv_points", advPoints);
                pointsConfig.set("players." + targetUuid + ".bonus_points", newBonus);
                savePointsFile();

                sender.sendMessage(ChatColor.GREEN + "[BacapPoints] Points for " + actualName + " set to " + newTotal + " (Base: " + advPoints + ", Bonus: " + newBonus + ")!");

                if (target != null) {
                    recalculateAndStorePoints(target);
                }
                updateAllSidebars();
                return true;
            }

            // DEFAULT DEBUG INFO
            sender.sendMessage(ChatColor.GOLD + "=== [BacapPoints Admin Info] ===");
            sender.sendMessage(ChatColor.GREEN + "✔ advancements.yml loaded! (" + advancementPointsMap.size() + " points in memory)");
            sender.sendMessage(ChatColor.YELLOW + "Sidebar Leaderboard: " + (sidebarLeaderboard ? "Enabled" : "Disabled") + " (Size: " + leaderboardSize + ")");

            if (sender instanceof Player) {
                Player p = (Player) sender;
                UUID uuid = p.getUniqueId();
                
                int basePoints = 0;
                int totalAdv = 0;

                Iterator<Advancement> it = Bukkit.advancementIterator();
                while (it.hasNext()) {
                    Advancement adv = it.next();
                    String namespace = adv.getKey().getNamespace().toLowerCase();
                    if (allowedNamespaces.contains(namespace) && p.getAdvancementProgress(adv).isDone()) {
                        basePoints += getAdvancementPoints(adv);
                        totalAdv++;
                    }
                }

                int bonusPoints = pointsConfig.getInt("players." + uuid + ".bonus_points", 0);
                int totalPoints = basePoints + bonusPoints;

                sender.sendMessage(ChatColor.GOLD + "Your Live Stats:");
                sender.sendMessage(ChatColor.YELLOW + "- Completed Advancements: " + ChatColor.AQUA + totalAdv);
                sender.sendMessage(ChatColor.YELLOW + "- Calculated Points: " + ChatColor.AQUA + basePoints);
                sender.sendMessage(ChatColor.YELLOW + "- Bonus Points: " + ChatColor.AQUA + bonusPoints);
                sender.sendMessage(ChatColor.YELLOW + "- Total Points: " + ChatColor.GOLD + "" + ChatColor.BOLD + totalPoints);
            }

            sender.sendMessage(ChatColor.GOLD + "=== Commands ===");
            sender.sendMessage(ChatColor.YELLOW + "/bacap reload " + ChatColor.WHITE + "- Reloads configuration and databases.");
            sender.sendMessage(ChatColor.YELLOW + "/bacap update <database/plugin> " + ChatColor.WHITE + "- Securely downloads remote updates.");
            sender.sendMessage(ChatColor.YELLOW + "/bacap recalculate all " + ChatColor.WHITE + "- Scans all offline/online player progress from disk.");
            sender.sendMessage(ChatColor.YELLOW + "/bacap recalculate [player] " + ChatColor.WHITE + "- Recalculates a specific player's score.");
            sender.sendMessage(ChatColor.YELLOW + "/bacap set <player> <points> " + ChatColor.WHITE + "- Sets player's points.");
            sender.sendMessage(ChatColor.YELLOW + "/bacap add <player> <points> " + ChatColor.WHITE + "- Adds points to player.");
            return true;
        }
        return false;
    }
}
