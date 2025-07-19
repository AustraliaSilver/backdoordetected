package backdoor.detect.backdoordetected;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.FilenameFilter;
import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class BddCommandExecutor implements CommandExecutor {
    private final Backdoordetected plugin;
    private final BlockingQueue<File> pluginQueue = new LinkedBlockingQueue<>();
    private volatile boolean scanning = false;

    public BddCommandExecutor(Backdoordetected plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 && sender instanceof Player player) {
            player.openInventory(plugin.createDetectorGui());
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("track")) {
            if (scanning) {
                sender.sendMessage("§e[BackdoorDetector] A scan is already running. Please wait.");
                return true;
            }

            scanning = true;
            sender.sendMessage("§a[BackdoorDetector] Preparing to scan plugins...");

            File pluginsFolder = new File("plugins");
            File[] pluginJars = pluginsFolder.listFiles((dir, name) ->
                    name.endsWith(".jar") && !name.toLowerCase().contains("backdoordetected"));

            if (pluginJars == null || pluginJars.length == 0) {
                sender.sendMessage("§c[BackdoorDetector] No plugins found to scan.");
                scanning = false;
                return true;
            }

            pluginQueue.clear();
            for (File jar : pluginJars) {
                pluginQueue.offer(jar);
            }

            sender.sendMessage("§a[BackdoorDetector] Added " + pluginQueue.size() + " plugins to the queue. Starting scan...");

            plugin.startParallelScanners(pluginQueue, sender, () -> {
                scanning = false;
                sender.sendMessage("§a[BackdoorDetector] All plugins checked!");
            });
            return true;
        }

        if (args.length > 1 && args[0].equalsIgnoreCase("scan")) {
            if (scanning) {
                sender.sendMessage("§e[BackdoorDetector] The system is busy. Please try again later.");
                return true;
            }

            String pluginName = args[1];
            File pluginFile = new File("plugins", pluginName.endsWith(".jar") ? pluginName : pluginName + ".jar");

            if (!pluginFile.exists() || !pluginFile.getName().endsWith(".jar")) {
                sender.sendMessage("§c[BackdoorDetector] Plugin not found: " + pluginFile.getName());
                return true;
            }

            scanning = true;
            pluginQueue.clear();
            pluginQueue.offer(pluginFile);

            sender.sendMessage("§a[BackdoorDetector] Checking plugin: §e" + pluginFile.getName());

            plugin.startParallelScanners(pluginQueue, sender, () -> {
                scanning = false;
                sender.sendMessage("§a[BackdoorDetector] Plugin checked: §e" + pluginFile.getName());
            });

            return true;
        }

        sender.sendMessage("§cUsage: /bdd, /bdd track or /bdd scan <plugin>");
        return true;
    }
}