package backdoor.detect.backdoordetected;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Backdoordetected extends JavaPlugin implements Listener {

    private File logFile;
    private String apiKey1, apiKey2, model1, model2;
    private boolean enableGemini2;
    private final AtomicInteger activeScannerThreads = new AtomicInteger(0);
    private final Map<UUID, Integer> historyPageMap = new HashMap<>();
    private final Map<UUID, Integer> pluginSelectPageMap = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        apiKey1 = config.getString("gemini_api_key_1", "");
        model1 = config.getString("gemini_model_1", "gemini-1.5-flash");
        enableGemini2 = config.getBoolean("enable_gemini_2", false);
        apiKey2 = config.getString("gemini_api_key_2", "");
        model2 = config.getString("gemini_model_2", "gemini-1.5-flash");

        if (apiKey1 == null || apiKey1.isEmpty()) {
            getLogger().severe("API key 1 is missing!");
            return;
        }
        if (enableGemini2 && (apiKey2 == null || apiKey2.isEmpty())) {
            getLogger().warning("Gemini 2 is enabled but key is missing => disabling Gemini 2.");
            enableGemini2 = false;
        }

        logFile = new File(getDataFolder(), "scan-log.txt");
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("bdd").setExecutor(new BddCommandExecutor(this));
        getLogger().info("BackdoorDetector is enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("BackdoorDetector is disabled.");
    }

    public Inventory createDetectorGui() {
        Inventory gui = Bukkit.createInventory(null, 27, "Backdoor Detector");

        gui.setItem(11, createItem(Material.COMMAND_BLOCK, "§a§lStart Full Scan",
                "§7Click to start scanning all plugins",
                "§7on your server for backdoors."));

        gui.setItem(13, createItem(Material.DIAMOND_PICKAXE, "§b§lSelect Plugin to Scan",
                "§7Click to select a specific plugin",
                "§7and scan only that plugin."));

        gui.setItem(15, createItem(Material.BOOK, "§e§nView Scan History",
                "§7Review previous plugin scan reports.",
                "§7Entries are sorted from newest to oldest."));

        ItemStack fillerItem = createItem(Material.BLACK_STAINED_GLASS_PANE, "§r");
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, fillerItem);
            }
        }

        return gui;
    }

    public Inventory createHistoryGui(int page) {
        List<String> entries = readLogEntries();
        int perPage = 7 * 5;
        int maxPage = (int) Math.ceil(entries.size() / (double) perPage);

        page = Math.max(1, Math.min(page, maxPage));
        Inventory gui = Bukkit.createInventory(null, 9 * 6, "§bScan History - Page " + page);

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, entries.size());

        for (int i = start; i < end; i++) {
            int relativeIndex = i - start;
            int row = relativeIndex / 7;
            int col = relativeIndex % 7;
            int slot = row * 9 + col;

            if (slot >= gui.getSize()) continue;

            String logEntry = entries.get(i);
            String pluginName = "Unknown";
            String scanTime = "Unknown";
            String result = "Unknown";

            String[] lines = logEntry.split("\n");
            for (String line : lines) {
                if (line.startsWith("----- Plugin: ")) {
                    pluginName = line.replace("----- Plugin: ", "").replace("-----", "").trim();
                } else if (line.startsWith("Time: ")) {
                    scanTime = line.replace("Time: ", "").trim();
                } else if (line.startsWith("Result: ")) {
                    result = line.replace("Result: ", "").trim();
                }
            }

            String displayName = "§f" + pluginName;
            List<String> itemLore = new ArrayList<>();
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

            gui.setItem(slot, createItem(Material.PAPER, displayName, itemLore.toArray(new String[0])));
        }

        gui.setItem(5 * 9 + 2, createItem(Material.ARROW, "§aPrevious Page", "§7Click to view older reports."));
        gui.setItem(5 * 9 + 4, createItem(Material.BARRIER, "§cBack", "§7Click to return to the main menu."));
        gui.setItem(5 * 9 + 6, createItem(Material.ARROW, "§aNext Page", "§7Click to view newer reports."));

        ItemStack fillerItem = createItem(Material.GRAY_STAINED_GLASS_PANE, "§r");
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, fillerItem);
            }
        }

        return gui;
    }

    public Inventory createPluginSelectionGui(int page) {
        List<File> pluginFiles = new ArrayList<>();
        File pluginsDir = getDataFolder().getParentFile();
        if (pluginsDir != null) {
            File[] files = pluginsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
            if (files != null) {
                for (File file : files) {
                    if (!file.getName().equalsIgnoreCase(getName() + ".jar")) {
                        pluginFiles.add(file);
                    }
                }
            }
        }
        pluginFiles.sort(Comparator.comparing(File::getName));

        int perPage = 7 * 5;
        int maxPage = Math.max(1, (int) Math.ceil(pluginFiles.size() / (double) perPage));
        page = Math.max(1, Math.min(page, maxPage));

        Inventory gui = Bukkit.createInventory(null, 54, "§9Select Plugin - Page " + page);

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, pluginFiles.size());

        List<String> allLogEntries = readLogEntries();

        for (int i = start; i < end; i++) {
            int relativeIndex = i - start;
            int row = relativeIndex / 7;
            int col = relativeIndex % 7;
            int slot = row * 9 + col;

            File file = pluginFiles.get(i);
            String pluginName = file.getName();

            String latestScanResult = "§7Never scanned";
            String latestScanTime = "";

            for(String logEntry : allLogEntries) {
                String entryPluginName = "N/A";
                String entryScanTime = "N/A";
                String entryResult = "N/A";

                String[] lines = logEntry.split("\n");
                for (String line : lines) {
                    if (line.startsWith("----- Plugin: ")) {
                        entryPluginName = line.replace("----- Plugin: ", "").replace("-----", "").trim();
                    } else if (line.startsWith("Time: ")) {
                        entryScanTime = line.replace("Time: ", "").trim();
                    } else if (line.startsWith("Result: ")) {
                        entryResult = line.replace("Result: ", "").trim();
                    }
                }
                if (entryPluginName.equals(pluginName)) {
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
            }

            List<String> lore = new ArrayList<>();
            lore.add("§7Path: §fplugins/" + pluginName);
            if (!latestScanTime.isEmpty()) {
                lore.add("§7Last Scanned: §f" + latestScanTime);
                lore.add("§7Last Result: " + latestScanResult);
            } else {
                lore.add(latestScanResult);
            }
            lore.add("§7Click to scan this plugin.");

            gui.setItem(slot, createItem(Material.LIME_STAINED_GLASS_PANE, "§e" + pluginName, lore.toArray(new String[0])));
        }

        gui.setItem(5 * 9 + 2, createItem(Material.ARROW, "§aPrevious Page", "§7Click to view previous plugins."));
        gui.setItem(5 * 9 + 4, createItem(Material.BARRIER, "§cBack", "§7Click to return to the main menu."));
        gui.setItem(5 * 9 + 6, createItem(Material.ARROW, "§aNext Page", "§7Click to view next plugins."));

        ItemStack fillerItem = createItem(Material.GRAY_STAINED_GLASS_PANE, "§r");
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, fillerItem);
            }
        }
        return gui;
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
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
        Player p = (Player) e.getWhoClicked();
        String title = e.getView().getTitle();
        ItemStack item = e.getCurrentItem();
        e.setCancelled(true);
        if (item == null || !item.hasItemMeta()) return;

        String display = item.getItemMeta().getDisplayName();

        if (title.equals("Backdoor Detector")) {
            if (item.getType() == Material.COMMAND_BLOCK) {
                p.closeInventory();
                p.performCommand("bdd track");
            } else if (item.getType() == Material.BOOK) {
                historyPageMap.put(p.getUniqueId(), 1);
                p.openInventory(createHistoryGui(1));
            } else if (item.getType() == Material.DIAMOND_PICKAXE) {
                pluginSelectPageMap.put(p.getUniqueId(), 1);
                p.openInventory(createPluginSelectionGui(1));
            }
        } else if (title.startsWith("§bScan History")) {
            int page = historyPageMap.getOrDefault(p.getUniqueId(), 1);
            if (display.contains("Previous Page")) {
                page--;
                historyPageMap.put(p.getUniqueId(), page);
                p.openInventory(createHistoryGui(page));
            } else if (display.contains("Next Page")) {
                page++;
                historyPageMap.put(p.getUniqueId(), page);
                p.openInventory(createHistoryGui(page));
            } else if (display.contains("Back")) {
                p.openInventory(createDetectorGui());
            }
        } else if (title.startsWith("§9Select Plugin")) {
            int currentPage = pluginSelectPageMap.getOrDefault(p.getUniqueId(), 1);
            if (display.contains("Previous Page")) {
                currentPage--;
                pluginSelectPageMap.put(p.getUniqueId(), currentPage);
                p.openInventory(createPluginSelectionGui(currentPage));
            } else if (display.contains("Next Page")) {
                currentPage++;
                pluginSelectPageMap.put(p.getUniqueId(), currentPage);
                p.openInventory(createPluginSelectionGui(currentPage));
            } else if (display.contains("Back")) {
                p.openInventory(createDetectorGui());
            } else {
                String pluginName = item.getItemMeta().getDisplayName().replace("§e", "").trim();
                p.closeInventory();
                p.performCommand("bdd scan " + pluginName);
            }
        }
    }

    public void appendToLog(String text) {
        try {
            if (!logFile.exists()) logFile.createNewFile();
            Files.write(logFile.toPath(), text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().warning("Could not write log: " + e.getMessage());
        }
    }

    public boolean isPreviouslyScanned(File file) {
        if (!logFile.exists()) return false;
        String hash = getSHA256(file);
        try {
            List<String> lines = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
            return lines.stream().anyMatch(l -> l.contains(hash));
        } catch (IOException e) {
            return false;
        }
    }

    public String getSHA256(File file) {
        try (InputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int n;
            while ((n = fis.read(buf)) > 0) digest.update(buf, 0, n);
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public void startParallelScanners(BlockingQueue<File> pluginQueue, CommandSender sender, Runnable onComplete) {
        if (pluginQueue.isEmpty()) {
            File pluginsDir = getDataFolder().getParentFile();
            if (pluginsDir != null) {
                File[] pluginFiles = pluginsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
                if (pluginFiles != null) {
                    for (File file : pluginFiles) {
                        if (file.getName().equalsIgnoreCase(getName() + ".jar")) continue;
                        pluginQueue.offer(file);
                    }
                }
            }
        }

        activeScannerThreads.incrementAndGet();
        new Thread(new PluginWorker(pluginQueue, apiKey1, model1, this, sender, "Gemini_1")).start();

        if (enableGemini2) {
            activeScannerThreads.incrementAndGet();
            new Thread(new PluginWorker(pluginQueue, apiKey2, model2, this, sender, "Gemini_2")).start();
        }

        new Thread(() -> {
            while (activeScannerThreads.get() > 0 || !pluginQueue.isEmpty()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Bukkit.getScheduler().runTask(this, onComplete);
        }).start();
    }

    public List<String> readLogEntries() {
        List<String> entries = new ArrayList<>();
        if (!logFile.exists()) return entries;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(logFile), StandardCharsets.UTF_8))) {
            StringBuilder entry = new StringBuilder();
            String line;
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
            getLogger().warning("Could not read scan-log.txt: " + e.getMessage());
        }

        Collections.reverse(entries);
        return entries;
    }
}