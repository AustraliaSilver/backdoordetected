package backdoor.detect.backdoordetected;

import backdoor.detect.backdoordetected.BddCommandExecutor;
import backdoor.detect.backdoordetected.PluginWorker;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final Map<UUID, Integer> historyPageMap = new HashMap<UUID, Integer>();
    private final Map<UUID, Integer> pluginSelectPageMap = new HashMap<UUID, Integer>();

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
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)this);
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
        Inventory gui = Bukkit.createInventory(null, (int)27, (String)"Backdoor Detector");
        gui.setItem(11, this.createItem(Material.COMMAND_BLOCK, "\u00a7a\u00a7lStart Full Scan", "\u00a77Click to start scanning all plugins", "\u00a77on your server for backdoors."));
        gui.setItem(13, this.createItem(Material.DIAMOND_PICKAXE, "\u00a7b\u00a7lSelect Plugin to Scan", "\u00a77Click to select a specific plugin", "\u00a77and scan only that plugin."));
        gui.setItem(15, this.createItem(Material.BOOK, "\u00a7e\u00a7nView Scan History", "\u00a77Review previous plugin scan reports.", "\u00a77Entries are sorted from newest to oldest."));
        ItemStack fillerItem = this.createItem(Material.BLACK_STAINED_GLASS_PANE, "\u00a7r", new String[0]);
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
        Inventory gui = Bukkit.createInventory(null, (int)54, (String)("\u00a7bScan History - Page " + page));
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
            String displayName = "\u00a7f" + pluginName;
            ArrayList<String> itemLore = new ArrayList<String>();
            itemLore.add("\u00a77Scan Date: \u00a7f" + scanTime);
            String resultColor = "\u00a77";
            if (result.equalsIgnoreCase("YES")) {
                resultColor = "\u00a7c";
            } else if (result.equalsIgnoreCase("NO")) {
                resultColor = "\u00a7a";
            } else if (result.contains("ERROR")) {
                resultColor = "\u00a7e";
            } else if (result.contains("UNKNOWN")) {
                resultColor = "\u00a76";
            }
            itemLore.add("\u00a77Result: " + resultColor + result);
            gui.setItem(slot, this.createItem(Material.PAPER, displayName, itemLore.toArray(new String[0])));
        }
        gui.setItem(47, this.createItem(Material.ARROW, "\u00a7aPrevious Page", "\u00a77Click to view older reports."));
        gui.setItem(49, this.createItem(Material.BARRIER, "\u00a7cBack", "\u00a77Click to return to the main menu."));
        gui.setItem(51, this.createItem(Material.ARROW, "\u00a7aNext Page", "\u00a77Click to view newer reports."));
        ItemStack fillerItem = this.createItem(Material.GRAY_STAINED_GLASS_PANE, "\u00a7r", new String[0]);
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
        Inventory gui = Bukkit.createInventory(null, (int)54, (String)("\u00a79Select Plugin - Page " + page));
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
            String latestScanResult = "\u00a77Never scanned";
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
                String resultColor = "\u00a77";
                if (entryResult.equalsIgnoreCase("YES")) {
                    resultColor = "\u00a7c";
                } else if (entryResult.equalsIgnoreCase("NO")) {
                    resultColor = "\u00a7a";
                } else if (entryResult.contains("ERROR")) {
                    resultColor = "\u00a7e";
                } else if (entryResult.contains("UNKNOWN")) {
                    resultColor = "\u00a76";
                }
                latestScanResult = resultColor + entryResult;
                break;
            }
            ArrayList<String> lore = new ArrayList<String>();
            lore.add("\u00a77Path: \u00a7fplugins/" + pluginName);
            if (!latestScanTime.isEmpty()) {
                lore.add("\u00a77Last Scanned: \u00a7f" + latestScanTime);
                lore.add("\u00a77Last Result: " + latestScanResult);
            } else {
                lore.add(latestScanResult);
            }
            lore.add("\u00a77Click to scan this plugin.");
            gui.setItem(slot, this.createItem(Material.LIME_STAINED_GLASS_PANE, "\u00a7e" + pluginName, lore.toArray(new String[0])));
        }
        gui.setItem(47, this.createItem(Material.ARROW, "\u00a7aPrevious Page", "\u00a77Click to view previous plugins."));
        gui.setItem(49, this.createItem(Material.BARRIER, "\u00a7cBack", "\u00a77Click to return to the main menu."));
        gui.setItem(51, this.createItem(Material.ARROW, "\u00a7aNext Page", "\u00a77Click to view next plugins."));
        ItemStack fillerItem = this.createItem(Material.GRAY_STAINED_GLASS_PANE, "\u00a7r", new String[0]);
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
        Player p = (Player)e.getWhoClicked();
        String title = e.getView().getTitle();
        ItemStack item = e.getCurrentItem();
        e.setCancelled(true);
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
        } else if (title.startsWith("\u00a7bScan History")) {
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
        } else if (title.startsWith("\u00a79Select Plugin")) {
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
                String pluginName = item.getItemMeta().getDisplayName().replace("\u00a7e", "").trim();
                p.closeInventory();
                p.performCommand("bddscan " + pluginName);
            }
        }
    }

    public void appendToLog(String text) {
        try {
            if (!this.logFile.exists()) {
                this.logFile.createNewFile();
            }
            Files.write(this.logFile.toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            this.getLogger().warning("Could not write log: " + e.getMessage());
        }
    }

    public boolean isPreviouslyScanned(File file) {
        if (!this.logFile.exists()) {
            return false;
        }
        String hash = this.getSHA256(file);
        try {
            List<String> lines = Files.readAllLines(this.logFile.toPath(), StandardCharsets.UTF_8);
            return lines.stream().anyMatch(l -> l.contains(hash));
        } catch (IOException e) {
            return false;
        }
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
        File[] pluginFiles;
        File pluginsDir;
        if (pluginQueue.isEmpty() && (pluginsDir = this.getDataFolder().getParentFile()) != null && (pluginFiles = pluginsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"))) != null) {
            for (File file : pluginFiles) {
                if (file.getName().equalsIgnoreCase(this.getName() + ".jar")) continue;
                pluginQueue.offer(new PluginWorker.PrioritizedFile(file, 0));
            }
        }
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
            Bukkit.getScheduler().runTask((Plugin)this, onComplete);
        }).start();
    }

    public List<String> readLogEntries() {
        ArrayList<String> entries = new ArrayList<String>();
        if (!this.logFile.exists()) {
            return entries;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader((InputStream)new FileInputStream(this.logFile), StandardCharsets.UTF_8))) {
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
}