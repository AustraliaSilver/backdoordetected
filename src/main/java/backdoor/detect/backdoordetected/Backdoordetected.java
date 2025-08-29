package backdoor.detect.backdoordetected;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class Backdoordetected extends JavaPlugin implements Listener {
    private File logFile;
    private String apiKey1;
    private String apiKey2;
    private String model1;
    private String model2;
    private boolean enableGemini2;
    private final AtomicInteger activeScannerThreads = new AtomicInteger(0);
    private final Map<UUID, Integer> historyPageMap = new HashMap<>();
    private final Map<UUID, Integer> pluginSelectPageMap = new HashMap<>();

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        FileConfiguration config = this.getConfig();
        this.apiKey1 = config.getString("gemini_api_key", "");
        this.model1 = config.getString("gemini_model", "gemini-1.5-flash");
        this.enableGemini2 = config.getBoolean("enable_gemini_2", false);
        this.apiKey2 = config.getString("gemini_api_key_2", "");
        this.model2 = config.getString("gemini_model_2", "gemini-1.5-flash");

        if (this.apiKey1 == null || this.apiKey1.isEmpty()) {
            this.getLogger().severe("API key 1 is missing! Please check your config.yml.");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (this.enableGemini2 && (this.apiKey2 == null || this.apiKey2.isEmpty())) {
            this.getLogger().warning("Gemini 2 is enabled but key is missing => disabling Gemini 2.");
            this.enableGemini2 = false;
        }

        this.logFile = new File(this.getDataFolder(), "scan-log.txt");
        Bukkit.getPluginManager().registerEvents(this, this);
        BddCommandExecutor executor = new BddCommandExecutor(this);
        this.getCommand("bdd").setExecutor(executor);
        this.getCommand("bddtrack").setExecutor(executor);
        this.getCommand("bddscan").setExecutor(executor);
        this.getLogger().info("BackdoorDetector is enabled!");
    }

    public void onDisable() {
        this.getLogger().info("BackdoorDetector is disabled.");
    }

    public Inventory createDetectorGui() {
        Inventory gui = Bukkit.createInventory(null, 27, "Backdoor Detector");
        gui.setItem(11, this.createItem(Material.COMMAND_BLOCK, "§a§lStart Full Scan", "§7Click to start scanning all plugins", "§7on your server for backdoors."));
        gui.setItem(13, this.createItem(Material.DIAMOND_PICKAXE, "§b§lSelect Plugin to Scan", "§7Click to select a specific plugin", "§7and scan only that plugin."));
        gui.setItem(15, this.createItem(Material.BOOK, "§e§nView Scan History", "§7Review previous plugin scan reports.", "§7Entries are sorted from newest to oldest."));
        ItemStack fillerItem = this.createItem(Material.BLACK_STAINED_GLASS_PANE, "§r");
        for (int i = 0; i < gui.getSize(); ++i) {
            if (gui.getItem(i) != null) continue;
            gui.setItem(i, fillerItem);
        }
        return gui;
    }

    public Inventory createHistoryGui(int page) {
        List<String> entries = this.readLogEntries();
        int perPage = 35;
        int maxPage = (int)Math.ceil((double)entries.size() / (double)perPage);
        page = Math.max(1, Math.min(page, maxPage));
        Inventory gui = Bukkit.createInventory(null, 54, "§bScan History - Page " + page);
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, entries.size());
        for (int i = start; i < end; ++i) {
            String[] lines;
            int relativeIndex = i - start;
            int row = relativeIndex / 7;
            int col = relativeIndex % 7;
            int slot = row * 9 + col;
            if (slot >= gui.getSize()) continue;
            String logEntry = entries.get(i);
            String pluginName = "Unknown";
            String scanTime = "Unknown";
            String result = "Unknown";
            for (String line : lines = logEntry.split("\n")) {
                if (line.startsWith("----- Plugin: ")) {
                    pluginName = line.replace("----- Plugin: ", "").replace("-----", "").trim();
                    continue;
                }
                if (line.startsWith("Time: ")) {
                    scanTime = line.replace("Time: ", "").trim();
                    continue;
                }
                if (!line.startsWith("Result: ")) continue;
                result = line.replace("Result: ", "").trim();
            }
            String displayName = "§f" + pluginName;
            ArrayList<String> itemLore = new ArrayList<String>();
            itemLore.add("§7Scan Date: §f" + scanTime);
            String resultColor = "§7";
            if (result.equalsIgnoreCase("YES")) {
                resultColor = "§c";
            } else if (result.equalsIgnoreCase("NO")) {
                resultColor = "§a";
            } else if (result.contains("ERROR")) {
                resultColor = "§e";
            } else if (result.contains("UNKNOWN")) {
                resultColor = "§6";
            }
            itemLore.add("§7Result: " + resultColor + result);
            gui.setItem(slot, this.createItem(Material.PAPER, displayName, itemLore.toArray(new String[0])));
        }
        gui.setItem(47, this.createItem(Material.ARROW, "§aPrevious Page", "§7Click to view older reports."));
        gui.setItem(49, this.createItem(Material.BARRIER, "§cBack", "§7Click to return to the main menu."));
        gui.setItem(51, this.createItem(Material.ARROW, "§aNext Page", "§7Click to view newer reports."));
        ItemStack fillerItem = this.createItem(Material.GRAY_STAINED_GLASS_PANE, "§r");
        for (int i = 0; i < gui.getSize(); ++i) {
            if (gui.getItem(i) != null) continue;
            gui.setItem(i, fillerItem);
        }
        return gui;
    }

    public Inventory createPluginSelectionGui(int page) {
        File[] files;
        ArrayList<File> pluginFiles = new ArrayList<File>();
        File pluginsDir = this.getDataFolder().getParentFile();
        if (pluginsDir != null && (files = pluginsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"))) != null) {
            for (File file : files) {
                if (file.getName().equalsIgnoreCase(this.getName() + ".jar")) continue;
                pluginFiles.add(file);
            }
        }
        pluginFiles.sort(Comparator.comparing(File::getName));
        int perPage = 35;
        int maxPage = Math.max(1, (int)Math.ceil((double)pluginFiles.size() / (double)perPage));
        page = Math.max(1, Math.min(page, maxPage));
        Inventory gui = Bukkit.createInventory(null, 54, "§9Select Plugin - Page " + page);
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, pluginFiles.size());
        List<String> allLogEntries = this.readLogEntries();
        for (int i = start; i < end; ++i) {
            int relativeIndex = i - start;
            int row = relativeIndex / 7;
            int col = relativeIndex % 7;
            int slot = row * 9 + col;
            File file = (File)pluginFiles.get(i);
            String pluginName = file.getName();
            String latestScanResult = "§7Never scanned";
            String latestScanTime = "";
            for (String logEntry : allLogEntries) {
                String[] lines;
                String entryPluginName = "N/A";
                String entryScanTime = "N/A";
                String entryResult = "N/A";
                for (String line : lines = logEntry.split("\n")) {
                    if (line.startsWith("----- Plugin: ")) {
                        entryPluginName = line.replace("----- Plugin: ", "").replace("-----", "").trim();
                        continue;
                    }
                    if (line.startsWith("Time: ")) {
                        entryScanTime = line.replace("Time: ", "").trim();
                        continue;
                    }
                    if (!line.startsWith("Result: ")) continue;
                    entryResult = line.replace("Result: ", "").trim();
                }
                if (!entryPluginName.equals(pluginName)) continue;
                latestScanTime = entryScanTime;
                String resultColor = "§7";
                if (entryResult.equalsIgnoreCase("YES")) {
                    resultColor = "§c";
                } else if (entryResult.equalsIgnoreCase("NO")) {
                    resultColor = "§a";
                } else if (entryResult.contains("ERROR")) {
                    resultColor = "§e";
                } else if (entryResult.contains("UNKNOWN")) {
                    resultColor = "§6";
                }
                latestScanResult = resultColor + entryResult;
                break;
            }
            ArrayList<String> lore = new ArrayList<String>();
            lore.add("§7Path: §fplugins/" + pluginName);
            if (!latestScanTime.isEmpty()) {
                lore.add("§7Last Scanned: §f" + latestScanTime);
                lore.add("§7Last Result: " + latestScanResult);
            } else {
                lore.add(latestScanResult);
            }
            lore.add("§7Click to scan this plugin.");
            gui.setItem(slot, this.createItem(Material.LIME_STAINED_GLASS_PANE, "§e" + pluginName, lore.toArray(new String[0])));
        }
        gui.setItem(47, this.createItem(Material.ARROW, "§aPrevious Page", "§7Click to view previous plugins."));
        gui.setItem(49, this.createItem(Material.BARRIER, "§cBack", "§7Click to return to the main menu."));
        gui.setItem(51, this.createItem(Material.ARROW, "§aNext Page", "§7Click to view next plugins."));
        ItemStack fillerItem = this.createItem(Material.GRAY_STAINED_GLASS_PANE, "§r");
        for (int i = 0; i < gui.getSize(); ++i) {
            if (gui.getItem(i) != null) continue;
            gui.setItem(i, fillerItem);
        }
        return gui;
    }

    private ItemStack createItem(Material mat, String name, String ... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        String title = e.getView().getTitle();

        if (title.equals("Backdoor Detector") || title.startsWith("§bScan History") || title.startsWith("§9Select Plugin")) {
            e.setCancelled(true);
            Player p = (Player)e.getWhoClicked();
            ItemStack item = e.getCurrentItem();
            if (item == null || !item.hasItemMeta()) {
                return;
            }
            String display = item.getItemMeta().getDisplayName();
            if (title.equals("Backdoor Detector")) {
                if (item.getType() == Material.COMMAND_BLOCK) {
                    p.closeInventory();
                    p.performCommand("bddtrack");
                } else if (item.getType() == Material.BOOK) {
                    this.historyPageMap.put(p.getUniqueId(), 1);
                    p.openInventory(this.createHistoryGui(1));
                } else if (item.getType() == Material.DIAMOND_PICKAXE) {
                    this.pluginSelectPageMap.put(p.getUniqueId(), 1);
                    p.openInventory(this.createPluginSelectionGui(1));
                }
            } else if (title.startsWith("§bScan History")) {
                int page = this.historyPageMap.getOrDefault(p.getUniqueId(), 1);
                if (display.contains("Previous Page")) {
                    this.historyPageMap.put(p.getUniqueId(), --page);
                    p.openInventory(this.createHistoryGui(page));
                } else if (display.contains("Next Page")) {
                    this.historyPageMap.put(p.getUniqueId(), ++page);
                    p.openInventory(this.createHistoryGui(page));
                } else if (display.contains("Back")) {
                    p.openInventory(this.createDetectorGui());
                }
            } else if (title.startsWith("§9Select Plugin")) {
                int currentPage = this.pluginSelectPageMap.getOrDefault(p.getUniqueId(), 1);
                if (display.contains("Previous Page")) {
                    this.pluginSelectPageMap.put(p.getUniqueId(), --currentPage);
                    p.openInventory(this.createPluginSelectionGui(currentPage));
                } else if (display.contains("Next Page")) {
                    this.pluginSelectPageMap.put(p.getUniqueId(), ++currentPage);
                    p.openInventory(this.createPluginSelectionGui(currentPage));
                } else if (display.contains("Back")) {
                    p.openInventory(this.createDetectorGui());
                } else {
                    String pluginName = item.getItemMeta().getDisplayName().replace("§e", "").trim();
                    p.closeInventory();
                    p.performCommand("bddscan " + pluginName);
                }
            }
        }
    }

    public void appendToLog(String text) {
        try {
            if (!this.logFile.exists()) {
                this.logFile.createNewFile();
            }
            Files.write(this.logFile.toPath(), (text + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            this.getLogger().warning("Could not write log: " + e.getMessage());
        }
    }

    public List<String> readLogEntries() {
        ArrayList<String> entries = new ArrayList<String>();
        if (!this.logFile.exists()) {
            return entries;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(this.logFile), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            StringBuilder entry = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("----- Plugin: ") && entry.length() > 0) {
                    entries.add(entry.toString().trim());
                    entry.setLength(0);
                }
                entry.append(line).append("\n");
            }
            if (entry.length() > 0) {
                entries.add(entry.toString().trim());
            }
        } catch (IOException e) {
            this.getLogger().warning("Could not read scan-log.txt: " + e.getMessage());
        }
        Collections.reverse(entries);
        return entries;
    }
    
    public String getSHA256(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int n;
            while ((n = fis.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            this.getLogger().warning("Error calculating SHA-256: " + e.getMessage());
            return "";
        }
    }

    public void startParallelScanners(BlockingQueue<PluginWorker.PrioritizedFile> pluginQueue, CommandSender sender, Runnable onComplete) {
        this.activeScannerThreads.incrementAndGet();
        new Thread(new PluginWorker(pluginQueue, this.apiKey1, this.model1, this, sender, "Gemini_1")).start();
        if (this.enableGemini2) {
            this.activeScannerThreads.incrementAndGet();
            new Thread(new PluginWorker(pluginQueue, this.apiKey2, this.model2, this, sender, "Gemini_2")).start();
        }
        new Thread(() -> {
            while (this.activeScannerThreads.get() > 0 || !pluginQueue.isEmpty()) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Bukkit.getScheduler().runTask(this, onComplete);
        }).start();
    }
}
