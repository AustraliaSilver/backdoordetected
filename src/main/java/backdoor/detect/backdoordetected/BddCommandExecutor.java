package backdoor.detect.backdoordetected;

import backdoor.detect.backdoordetected.Backdoordetected;
import backdoor.detect.backdoordetected.PluginWorker.PrioritizedFile;
import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BddCommandExecutor implements CommandExecutor {
    private final Backdoordetected plugin;
    private final BlockingQueue<PluginWorker.PrioritizedFile> pluginQueue = new LinkedBlockingQueue<>();
    private volatile boolean scanning = false;

    public BddCommandExecutor(Backdoordetected plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase();

        switch (commandName) {
            case "bdd":
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    player.openInventory(this.plugin.createDetectorGui());
                } else {
                    sender.sendMessage("This command can only be run by a player.");
                }
                return true;

            case "bddtrack":
                if (this.scanning) {
                    sender.sendMessage("§e[BackdoorDetector] A scan is already running. Please wait.");
                    return true;
                }
                this.scanning = true;
                sender.sendMessage("§a[BackdoorDetector] Preparing to scan plugins...");
                File pluginsFolder = new File("plugins");
                File[] pluginJars = pluginsFolder.listFiles((dir, name) -> name.endsWith(".jar") && !name.toLowerCase().contains("backdoordetected"));
                if (pluginJars == null || pluginJars.length == 0) {
                    sender.sendMessage("§c[BackdoorDetector] No plugins found to scan.");
                    this.scanning = false;
                    return true;
                }
                this.pluginQueue.clear();
                for (File jar : pluginJars) {
                    this.pluginQueue.offer(new PluginWorker.PrioritizedFile(jar, 0));
                }
                sender.sendMessage("§a[BackdoorDetector] Added " + this.pluginQueue.size() + " plugins to the queue. Starting scan...");
                this.plugin.startParallelScanners(this.pluginQueue, sender, () -> {
                    this.scanning = false;
                    sender.sendMessage("§a[BackdoorDetector] All plugins checked!");
                });
                return true;

            case "bddscan":
                if (args.length < 1) {
                    return false;
                }
                if (this.scanning) {
                    sender.sendMessage("§e[BackdoorDetector] The system is busy. Please try again later.");
                    return true;
                }
                String pluginName = args[0];
                File pluginFile = new File("plugins", (pluginName.endsWith(".jar") ? pluginName : pluginName + ".jar"));
                if (!pluginFile.exists() || !pluginFile.getName().endsWith(".jar")) {
                    sender.sendMessage("§c[BackdoorDetector] Plugin not found: " + pluginFile.getName());
                    return true;
                }
                this.scanning = true;
                this.pluginQueue.clear();
                this.pluginQueue.offer(new PluginWorker.PrioritizedFile(pluginFile, 0));
                sender.sendMessage("§a[BackdoorDetector] Checking plugin: §e" + pluginFile.getName());
                this.plugin.startParallelScanners(this.pluginQueue, sender, () -> {
                    this.scanning = false;
                    sender.sendMessage("§a[BackdoorDetector] Plugin checked: §e" + pluginFile.getName());
                });
                return true;
        }
        return false;
    }
}
