package backdoor.detect.backdoordetected;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
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
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class GuiManager implements Listener {
    private final Backdoordetected plugin;
    private final Map<UUID, Integer> historyPageMap = new HashMap<>();

    public GuiManager(Backdoordetected plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public Inventory createMainGui() {
        Inventory gui = Bukkit.createInventory(null, 9, "Backdoor Detector");
        gui.setItem(2, createItem(Material.BOOK, "§eView Scan History"));
        gui.setItem(4, createItem(Material.COMMAND_BLOCK, "§aStart Full Scan"));
        return gui;
    }

    public Inventory createHistoryGui(int page) {
        List<String> entries = plugin.readLogEntries();
        int perPage = 7 * 5;
        int maxPage = Math.max(1, (int) Math.ceil(entries.size() / (double) perPage));
        page = Math.max(1, Math.min(page, maxPage));

        Inventory gui = Bukkit.createInventory(null, 54, "§bScan History - Page " + page);

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, entries.size());

        for (int i = start; i < end; i++) {
            int row = (i - start) / 7;
            int col = (i - start) % 7;
            int slot = row * 9 + col;
            gui.setItem(slot, createItem(Material.PAPER, entries.get(i)));
        }

        gui.setItem(5 * 9 + 3, createItem(Material.ARROW, "§aPrevious Page"));
        gui.setItem(5 * 9 + 4, createItem(Material.BARRIER, "§cBack"));
        gui.setItem(5 * 9 + 5, createItem(Material.ARROW, "§aNext Page"));

        return gui;
    }

    private ItemStack createItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        Player player = (Player) e.getWhoClicked();
        ItemStack item = e.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String title = e.getView().getTitle();
        String display = item.getItemMeta().getDisplayName();
        e.setCancelled(true);

        if (title.equals("Backdoor Detector")) {
            if (item.getType() == Material.COMMAND_BLOCK) {
                player.performCommand("bdd track");
                player.closeInventory();
            } else if (item.getType() == Material.BOOK) {
                historyPageMap.put(player.getUniqueId(), 1);
                player.openInventory(createHistoryGui(1));
            }
        } else if (title.startsWith("§bScan History")) {
            int currentPage = historyPageMap.getOrDefault(player.getUniqueId(), 1);
            if (display.contains("Previous Page")) {
                player.openInventory(createHistoryGui(--currentPage));
                historyPageMap.put(player.getUniqueId(), currentPage);
            } else if (display.contains("Next Page")) {
                player.openInventory(createHistoryGui(++currentPage));
                historyPageMap.put(player.getUniqueId(), currentPage);
            } else if (display.contains("Back")) {
                player.openInventory(createMainGui());
            }
        }
    }
}