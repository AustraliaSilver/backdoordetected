package backdoor.detect.backdoordetected;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.Arrays;

public class GuiManager {

    public Inventory createDetectorGui() {
        Inventory gui = Bukkit.createInventory(null, 27, "Backdoor Detector");

        gui.setItem(11, createItem(Material.COMMAND_BLOCK, "§a§lStart Full Scan", "§7Click to start scanning all plugins", "§7on your server for backdoors."));
        gui.setItem(13, createItem(Material.DIAMOND_PICKAXE, "§b§lSelect Plugin to Scan", "§7Click to select a specific plugin", "§7and scan only that plugin."));
        gui.setItem(15, createItem(Material.BOOK, "§e§nView Scan History", "§7Review previous plugin scan reports.", "§7Entries are sorted from newest to oldest."));

        ItemStack fillerItem = createItem(Material.BLACK_STAINED_GLASS_PANE, "§r");
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, fillerItem);
            }
        }

        return gui;
    }

    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}
