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
    private static final Pattern IP_PATTERN = Pattern.compile("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b");
    private static final Pattern BASE64_PATTERN = Pattern.compile("\"[A-Za-z0-9+/]{20,}=*\"");

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
        // Existing and modified checks
        if (checkRuntimeExec(content)) reasons.add("CRITICAL: Executes system commands (Runtime.exec/ProcessBuilder).");
        if (checkUnsafeApis(content)) reasons.add("CRITICAL: Uses unsafe APIs like JNDI, RMI, or Unsafe, potential for RCE.");
        if (checkUnsafeClassLoading(content)) reasons.add("CRITICAL: Uses advanced class loading/defining, can bypass security.");
        if (checkScriptExecution(content)) reasons.add("CRITICAL: Executes dynamic scripts (ScriptEngine/JavaCompiler).");
        if (checkDynamicCommands(content)) reasons.add("CRITICAL: Registers dynamic commands, can create hidden commands.");
        if (checkSystemAccess(content)) reasons.add("CRITICAL: Accesses system environment variables or properties.");

        if (checkNetworkUsage(content)) reasons.add("HIGH: Creates network connections (URL/HttpURLConnection).");
        if (checkRawSocketUsage(content)) reasons.add("HIGH: Uses raw sockets (Socket/Netty), can be for covert communication.");
        if (checkClassLoaderUsage(content)) reasons.add("HIGH: Can load arbitrary Java code at runtime (URLClassLoader).");
        if (checkBytecodeLibraries(content)) reasons.add("HIGH: Contains bytecode manipulation libraries (ASM, Javassist, ByteBuddy).");
        if (checkReflection(content)) reasons.add("HIGH: Uses reflection, which can be used to hide malicious code.");
        if (checkSetOp(content)) reasons.add("HIGH: Can grant operator status to players.");
        if (checkPermissionAttachment(content)) reasons.add("HIGH: Can manipulate player permissions.");
        if (checkPlayerDataManipulation(content)) reasons.add("HIGH: Manipulates sensitive player data (ban/kick/whitelist).");
        if (checkBase64Usage(content)) reasons.add("HIGH: Decodes Base64 strings, may hide malicious data.");
        if (checkEncryptionApis(content)) reasons.add("HIGH: Uses encryption APIs, could hide malicious payloads.");
        if (checkFileIO(content)) reasons.add("HIGH: Performs file operations (read/write/delete).");
        if (checkThreadingAbuse(content)) reasons.add("HIGH: Creates new Threads/Timers, can be used to hide tasks.");
    }

    // --- CRITICAL CHECKS ---
    private boolean checkRuntimeExec(String content) {
        return content.contains("Runtime.getRuntime().exec") || content.contains("new ProcessBuilder");
    }
    private boolean checkUnsafeApis(String content) {
        return content.contains("javax.naming.InitialContext.lookup") || content.contains("sun.misc.Unsafe");
    }
    private boolean checkUnsafeClassLoading(String content) {
        return content.contains(".defineClass") || content.contains(".defineAnonymousClass");
    }
    private boolean checkScriptExecution(String content) {
        return content.contains("javax.script.ScriptEngine") || content.contains("javax.tools.JavaCompiler");
    }
    private boolean checkDynamicCommands(String content) {
        return content.contains(".getCommandMap().register") || content.contains(".dispatchCommand");
    }

    // --- HIGH CHECKS ---
    private boolean checkNetworkUsage(String content) {
        if (content.contains("new URL(") || content.contains(".openConnection()")) {
            Matcher ipMatcher = IP_PATTERN.matcher(content);
            if (ipMatcher.find()) {
                String ip = ipMatcher.group();
                if (!ip.startsWith("127.") && !ip.startsWith("192.168.") && !ip.startsWith("10.") && !ip.equals("0.0.0.0")) {
                    return true;
                }
            }
            if(content.contains("\"http://") || content.contains("\"https://")){
                return true;
            }
        }
        return false;
    }
    private boolean checkRawSocketUsage(String content) {
        return content.contains("new Socket(") || content.contains("new ServerSocket(") || content.contains("DatagramSocket") || content.contains("SocketChannel");
    }
    private boolean checkClassLoaderUsage(String content) {
        return content.contains("new URLClassLoader");
    }
    private boolean checkBytecodeLibraries(String content) {
        return content.contains("import org.objectweb.asm") || content.contains("import javassist") || content.contains("import net.bytebuddy");
    }
    private boolean checkReflection(String content) {
        return content.contains("java.lang.reflect.Method") && content.contains(".invoke");
    }
    private boolean checkSetOp(String content) {
        return content.contains(".setOp(true)");
    }
    private boolean checkPermissionAttachment(String content) {
        return content.contains("PermissionAttachment");
    }
    private boolean checkPlayerDataManipulation(String content) {
        return content.contains(".setBanned(true)") || content.contains(".kickPlayer(") || content.contains(".setWhitelisted(");
    }
    private boolean checkBase64Usage(String content) {
        if (content.contains("Base64.getDecoder().decode")) {
            Matcher base64Matcher = BASE64_PATTERN.matcher(content);
            return base64Matcher.find();
        }
        return false;
    }

    // --- MODERATE CHECKS ---
    private boolean checkEncryptionApis(String content) {
        return content.contains("javax.crypto.Cipher") || content.contains("SecretKeySpec") || content.contains("MessageDigest") || content.contains("KeyGenerator");
    }
    private boolean checkSystemAccess(String content) {
        return content.contains("System.getenv") || content.contains("System.getProperty") || content.contains("ManagementFactory");
    }

    // --- LOW CHECKS ---
    private boolean checkFileIO(String content) {
        return content.contains("java.io.File") || content.contains("java.nio.file");
    }
    private boolean checkThreadingAbuse(String content) {
        return content.contains("new Thread") || content.contains("new Timer") || content.contains("Executor");
    }
}
