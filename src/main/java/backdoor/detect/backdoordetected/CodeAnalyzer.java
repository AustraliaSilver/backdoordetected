package backdoor.detect.backdoordetected;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeAnalyzer {

    private final Backdoordetected pluginInstance;

    public CodeAnalyzer(Backdoordetected pluginInstance) {
        this.pluginInstance = pluginInstance;
    }

    public Map<Path, List<String>> analyze(List<Path> javaFilePaths) {
        Map<Path, List<String>> suspiciousFiles = new HashMap<>();
        List<Path> listenerClasses = new ArrayList<>();
        List<Path> mainClasses = new ArrayList<>();

        for (Path filePath : javaFilePaths) {
            try {
                String fileContent = Files.readString(filePath);
                List<String> reasons = new ArrayList<>();

                runAllChecks(reasons, fileContent, filePath);

                if (fileContent.contains("implements Listener")) listenerClasses.add(filePath);
                if (fileContent.contains("extends JavaPlugin")) mainClasses.add(filePath);

                if (!reasons.isEmpty()) {
                    suspiciousFiles.computeIfAbsent(filePath, k -> new ArrayList<>()).addAll(reasons);
                }

            } catch (IOException e) {
                pluginInstance.getLogger().warning("Error reading Java file for analysis: " + filePath + " - " + e.getMessage());
            }
        }


        if (listenerClasses.size() > 1) {
            for (Path listenerPath : listenerClasses) {
                suspiciousFiles.computeIfAbsent(listenerPath, k -> new ArrayList<>()).add("MODERATE: Multiple listener classes found, possible injected listener.");
            }
        }
        if (mainClasses.size() > 1) {
            for (Path mainClassPath : mainClasses) {
                suspiciousFiles.computeIfAbsent(mainClassPath, k -> new ArrayList<>()).add("MODERATE: Multiple main plugin classes found, possible proxy-class injection.");
            }
        }

        return suspiciousFiles;
    }

    private void runAllChecks(List<String> reasons, String content, Path filePath) {
        if (checkRuntimeExec(content)) reasons.add("CRITICAL: Plugin can execute arbitrary system commands (Runtime.exec).");
        if (checkBukkitConsoleCommands(content)) reasons.add("HIGH: Potential console command execution.");
        if (checkRuntimeLoad(content)) reasons.add("HIGH: Plugin can load native system libraries (Runtime.load).");
        if (checkClassLoaderUsage(content)) reasons.add("HIGH: Plugin can load arbitrary Java code at runtime (ClassLoader).");
        if (checkBytecodeLibraries(content)) reasons.add("HIGH: Contains bytecode manipulation libraries (ASM, Javassist, ByteBuddy).");
        if (checkPluginLoad(content)) reasons.add("MODERATE: Plugin can load other plugins at runtime.");
        if (checkSetOp(content)) reasons.add("MODERATE: Plugin can grant operator status to players.");
        if (checkPermissionAttachment(content)) reasons.add("MODERATE: Plugin can manipulate player permissions.");
        if (checkReflection(content)) reasons.add("LOW: Uses reflection, which can be used to hide malicious code.");
        if (checkClassFileNameMismatch(content, filePath)) reasons.add("LOW: Class name may not match its file name, possible obfuscation.");
    }

    private boolean checkRuntimeExec(String content) {
        return content.contains("Runtime.getRuntime().exec");
    }

    private boolean checkBukkitConsoleCommands(String content) {
        return content.contains("dispatchCommand") && (content.contains("getConsoleSender") || content.contains("ConsoleCommandSender"));
    }

    private boolean checkRuntimeLoad(String content) {
        return content.contains("Runtime.getRuntime().load") || content.contains("System.load");
    }

    private boolean checkClassLoaderUsage(String content) {
        return content.contains("ClassLoader") || content.contains("URLClassLoader");
    }

    private boolean checkBytecodeLibraries(String content) {
        return content.contains("import org.objectweb.asm") || content.contains("import javassist") || content.contains("import net.bytebuddy");
    }

    private boolean checkPluginLoad(String content) {
        return content.contains(".getPluginManager().loadPlugin");
    }

    private boolean checkSetOp(String content) {
        return content.contains(".setOp(true)");
    }

    private boolean checkPermissionAttachment(String content) {
        return content.contains("PermissionAttachment");
    }

    private boolean checkReflection(String content) {
        return content.contains("java.lang.reflect.Method") && content.contains(".invoke");
    }

    private boolean checkClassFileNameMismatch(String content, Path filePath) {
        String fileName = filePath.getFileName().toString().replace(".java", "");
        Pattern classPattern = Pattern.compile("public\\s+(?:final\\s+|abstract\\s+)?class\\s+([\\w$]+)");
        Matcher matcher = classPattern.matcher(content);
        if (matcher.find()) {
            String className = matcher.group(1);
            if (!className.contains("$")) {
                return !className.equals(fileName);
            }
        }
        return false;
    }
}
