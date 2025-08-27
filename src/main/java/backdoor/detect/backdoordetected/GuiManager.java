package backdoor.detect.backdoordetected;

import backdoor.detect.backdoordetected.Backdoordetected;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public class GuiManager
        implements Listener {
    private final Backdoordetected plugin;
    private final Map<UUID, Integer> historyPageMap = new HashMap<UUID, Integer>();

    public GuiManager(Backdoordetected plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)plugin);
    }

    public Inventory createMainGui() {
        Inventory gui = Bukkit.createInventory(null, (int)9, (String)"Backdoor Detector");
        gui.setItem(2, this.createItem(Material.BOOK, "\u00a7eView Scan History"));
        gui.setItem(4, this.createItem(Material.COMMAND_BLOCK, "\u00a7aStart Full Scan"));
        return gui;
    }

    public Inventory createHistoryGui(int page) {
        List<String> entries = this.plugin.readLogEntries();
        int perPage = 35;
        int maxPage = Math.max(1, (int)Math.ceil((double)entries.size() / (double)perPage));
        page = Math.max(1, Math.min(page, maxPage));
        Inventory gui = Bukkit.createInventory(null, (int)54, (String)("\u00a7bScan History - Page " + page));
        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, entries.size());
        for (int i = start; i < end; ++i) {
            int row = (i - start) / 7;
            int col = (i - start) % 7;
            int slot = row * 9 + col;
            gui.setItem(slot, this.createItem(Material.PAPER, entries.get(i)));
        }
        gui.setItem(48, this.createItem(Material.ARROW, "\u00a7aPrevious Page"));
        gui.setItem(49, this.createItem(Material.BARRIER, "\u00a7cBack"));
        gui.setItem(50, this.createItem(Material.ARROW, "\u00a7aNext Page"));
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
        Player player = (Player)e.getWhoClicked();
        ItemStack item = e.getCurrentItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        String title = e.getView().getTitle();
        String display = item.getItemMeta().getDisplayName();
        e.setCancelled(true);
        if (title.equals("Backdoor Detector")) {
            if (item.getType() == Material.COMMAND_BLOCK) {
                player.performCommand("bdd track");
                player.closeInventory();
            } else if (item.getType() == Material.BOOK) {
                this.historyPageMap.put(player.getUniqueId(), 1);
                player.openInventory(this.createHistoryGui(1));
            }
        } else if (title.startsWith("\u00a7bScan History")) {
            int currentPage = this.historyPageMap.getOrDefault(player.getUniqueId(), 1);
            if (display.contains("Previous Page")) {
                player.openInventory(this.createHistoryGui(--currentPage));
                this.historyPageMap.put(player.getUniqueId(), currentPage);
            } else if (display.contains("Next Page")) {
                player.openInventory(this.createHistoryGui(++currentPage));
                this.historyPageMap.put(player.getUniqueId(), currentPage);
            } else if (display.contains("Back")) {
                player.openInventory(this.createMainGui());
            }
        }
    }
}
