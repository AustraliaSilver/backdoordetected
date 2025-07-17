package backdoor.detect.backdoordetected;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.Inventory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

import org.benf.cfr.reader.api.CfrDriver;
import org.json.JSONArray;
import org.json.JSONObject;

public class Backdoordetected extends JavaPlugin implements Listener {

    private String apiKey;
    private File logFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        apiKey = config.getString("gemini_api_key");

        logFile = new File(getDataFolder(), "scan-log.txt");
        getCommand("bdd").setExecutor(new BackdoorCommand());
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("BackdoorDetector Plugin Enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("BackdoorDetector Plugin Disabled");
    }

    public class BackdoorCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can open the GUI.");
                return true;
            }

            if (args.length == 0) {
                openGui(player);
                return true;
            }

            if (args[0].equalsIgnoreCase("track")) {
                sender.sendMessage("\u23F3 Checking plugins...");

                File pluginDir = new File(getDataFolder().getParent());
                File[] pluginFiles = pluginDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (pluginFiles == null || pluginFiles.length == 0) {
                    sender.sendMessage("\u274C No plugins found to check.");
                    return true;
                }

                new BukkitRunnable() {
                    int index = 0;

                    @Override
                    public void run() {
                        int count = 0;
                        while (index < pluginFiles.length && count < 4) {
                            File pluginFile = pluginFiles[index++];
                            if (pluginFile.getName().equalsIgnoreCase("backdoordetected.jar")) continue;

                            scanSinglePlugin(pluginFile, sender);
                            count++;
                        }

                        if (index >= pluginFiles.length) {
                            cancel();
                            sender.sendMessage("\u2705 All plugins checked.");
                        }
                    }
                }.runTaskTimerAsynchronously(Backdoordetected.this, 0L, 100L);

                return true;
            }
            return false;
        }

        private void openGui(Player player) {
            Inventory gui = Bukkit.createInventory(null, 9, "Backdoor Detector");
            gui.setItem(4, Bukkit.getItemFactory().createItemStack("COMMAND_BLOCK"));
            player.openInventory(gui);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals("Backdoor Detector")) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            player.performCommand("bdd track");
            player.closeInventory();
        }
    }

    private void scanSinglePlugin(File pluginFile, CommandSender sender) {
        try {
            Path tempDir = Files.createTempDirectory("decompiled_plugin_" + pluginFile.getName());
            List<String> javaFiles = decompilePlugin(pluginFile, tempDir);
            String prompt = buildInputs(javaFiles);
            JSONObject response = sendToGemini(prompt);

            String result = response != null ? response.optString("text", "Unknown") : "Unknown";

            String log = "----------------------------" + pluginFile.getName() + "------------------------------\n" +
                    "- Scan Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n" +
                    "- Plugin Version: Unknown\n" +
                    "- Contains Backdoor: " + result + "\n" +
                    "-----------------------------------------------------------------------------\n";

            appendToLog(log);
            sender.sendMessage("\uD83D\uDD22 Checked: " + pluginFile.getName() + " => " + result);

        } catch (Exception e) {
            getLogger().severe("Error processing plugin " + pluginFile.getName() + ": " + e.getMessage());
        }
    }

    private List<String> decompilePlugin(File pluginFile, Path tempDir) throws Exception {
        List<String> javaFiles = new ArrayList<>();
        Map<String, String> options = new HashMap<>();
        options.put("outputdir", tempDir.toString());

        CfrDriver driver = new CfrDriver.Builder()
                .withOptions(options)
                .build();

        driver.analyse(Collections.singletonList(pluginFile.getAbsolutePath()));

        Files.walk(tempDir).filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> javaFiles.add(path.toString()));

        return javaFiles;
    }

    private String buildInputs(List<String> javaFiles) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String file : javaFiles) {
            sb.append(Files.readString(Path.of(file))).append("\n\n");
            if (sb.length() > 3500) break;
        }
        return "In these Java files, is there a backdoor? Only answer 'YES' or 'NO'.':\n" + sb.toString();
    }

    private JSONObject sendToGemini(String prompt) {
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");

            JSONObject requestBody = new JSONObject();
            JSONArray contents = new JSONArray();
            JSONObject contentObj = new JSONObject();
            contentObj.put("parts", new JSONArray().put(new JSONObject().put("text", prompt)));
            contents.put(contentObj);
            requestBody.put("contents", contents);

            try (OutputStream os = con.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int code = con.getResponseCode();
            if (code == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line.trim());
                    }
                    JSONObject responseJson = new JSONObject(response.toString());
                    JSONArray candidates = responseJson.optJSONArray("candidates");
                    if (candidates != null && candidates.length() > 0) {
                        return candidates.getJSONObject(0).optJSONObject("content").optJSONArray("parts").getJSONObject(0);
                    }
                }
            } else {
                getLogger().warning("Gemini API returned error code: " + code);
            }
        } catch (Exception e) {
            getLogger().severe("Failed to call Gemini API: " + e.getMessage());
        }
        return null;
    }

    private void appendToLog(String text) {
        try {
            Files.createDirectories(logFile.getParentFile().toPath());
            Files.write(logFile.toPath(), text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().warning("Failed to write log to file: " + e.getMessage());
        }
    }
}