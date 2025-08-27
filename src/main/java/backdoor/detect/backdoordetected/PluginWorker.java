package backdoor.detect.backdoordetected;

import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.*;
import org.benf.cfr.reader.api.CfrDriver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.json.*;

public class PluginWorker implements Runnable {
    private final BlockingQueue<PrioritizedFile> queue;
    private final String currentApiKey;
    private final String currentModelName;
    private final Backdoordetected pluginInstance;
    private final CommandSender sender;
    private final String apiInstanceName;
    private static final int MAX_PROMPT_LENGTH = 30000;
    private final ExecutorService executorService;
    private final Map<String, Path> tempDirs;

    public PluginWorker(BlockingQueue<PrioritizedFile> queue, String apiKey, String modelName, Backdoordetected pluginInstance, CommandSender sender, String apiInstanceName) {
        this.queue = queue;
        this.currentApiKey = apiKey;
        this.currentModelName = modelName;
        this.pluginInstance = pluginInstance;
        this.sender = sender;
        this.apiInstanceName = apiInstanceName;
        this.executorService = Executors.newFixedThreadPool(2);
        this.tempDirs = new HashMap<>();
    }

    @Override
    public void run() {
        this.pluginInstance.getLogger().info("[" + this.apiInstanceName + "] Worker STARTED.");
        Map<String, Integer> retryCount = new HashMap<>();

        Path tempBaseDir = pluginInstance.getDataFolder().toPath().resolve("temp");
        try {
            Files.createDirectories(tempBaseDir);
        } catch (IOException e) {
            this.pluginInstance.getLogger().severe("[" + this.apiInstanceName + "] Failed to create temp directory: " + e.getMessage());
        }

        while (!queue.isEmpty()) {
            List<PrioritizedFile> filesToProcess = new ArrayList<>();
            for (int i = 0; i < 2 && !queue.isEmpty(); i++) {
                PrioritizedFile pFile = queue.poll();
                if (pFile != null) {
                    filesToProcess.add(pFile);
                }
            }

            if (filesToProcess.isEmpty()) {
                break;
            }

            List<Callable<ProcessResult>> tasks = new ArrayList<>();
            for (PrioritizedFile pFile : filesToProcess) {
                final PrioritizedFile finalPFile = pFile;
                tasks.add(() -> processFile(finalPFile.file, finalPFile.depth, retryCount, tempBaseDir));
            }

            try {
                List<Future<ProcessResult>> futures = executorService.invokeAll(tasks);
                for (Future<ProcessResult> future : futures) {
                    try {
                        ProcessResult result = future.get();
                        if (result.retry) {
                            String pluginKey = result.pluginFile.getAbsolutePath();
                            int currentRetry = retryCount.getOrDefault(pluginKey, 0);
                            if (currentRetry < 2) {
                                retryCount.put(pluginKey, currentRetry + 1);
                                queue.offer(new PrioritizedFile(result.pluginFile, result.depth));
                                runTaskOnMainThread(() -> sender.sendMessage(
                                        "§e[" + apiInstanceName + "] §7Retrying (" + (currentRetry + 2) + "/3) (Depth " + result.depth + "): §e" + result.pluginName));
                            } else {
                                writeLog(result.pluginName, result.depth, "ERROR (TRIED 3 TIMES)");
                                runTaskOnMainThread(() -> sender.sendMessage(
                                        "§c[" + apiInstanceName + "] §e" + result.pluginName + " (Depth " + result.depth + ") §7=> §cSKIPPING AFTER 3 TRIES"));
                            }
                        }
                    } catch (Exception e) {
                        this.pluginInstance.getLogger().severe("[" + this.apiInstanceName + "] Error processing task: " + e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Worker thread interrupted.");
                break;
            }

            try {
                Thread.sleep(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Worker thread interrupted.");
                break;
            }
        }

        executorService.shutdown();

        synchronized (tempDirs) {
            for (Path tempDir : tempDirs.values()) {
                if (Files.exists(tempDir)) {
                    try {
                        Files.walk(tempDir)
                                .sorted(Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(File::delete);
                    } catch (IOException e) {
                        this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Failed to delete temp directory: " + tempDir + " - " + e.getMessage());
                    }
                }
            }
            tempDirs.clear();
        }

        runTaskOnMainThread(() -> {
            try {
                Field field = Backdoordetected.class.getDeclaredField("activeScannerThreads");
                field.setAccessible(true);
                ((AtomicInteger) field.get(pluginInstance)).decrementAndGet();
            } catch (Exception e) {
                pluginInstance.getLogger().warning("Could not update activeScannerThreads: " + e.getMessage());
            }
        });

        this.pluginInstance.getLogger().info("[" + this.apiInstanceName + "] Thread finished.");
    }

    private ProcessResult processFile(File pluginFile, int currentDepth, Map<String, Integer> retryCount, Path tempBaseDir) {
        String pluginName = pluginFile.getName();
        String pluginKey = pluginFile.getAbsolutePath();
        this.pluginInstance.getLogger().info("[" + this.apiInstanceName + "] Processing (Depth " + currentDepth + "): " + pluginName);

        try {
            if (currentDepth >= 3) {
                writeLog(pluginName, currentDepth, "SKIPPED (MAX NESTED DEPTH REACHED)");
                runTaskOnMainThread(() -> sender.sendMessage(
                        "§c[" + apiInstanceName + "] §e" + pluginName + " (Depth " + currentDepth + ") §7=> §cSKIPPED (MAX NESTED DEPTH REACHED)"));
                return new ProcessResult(pluginFile, pluginName, currentDepth, false);
            }

            if (!pluginFile.exists() || !pluginFile.canRead()) {
                this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Cannot access file: " + pluginFile.getAbsolutePath());
                writeLog(pluginName, currentDepth, "ERROR (FILE INACCESSIBLE)");
                runTaskOnMainThread(() -> sender.sendMessage(
                        "§c[" + apiInstanceName + "] §e" + pluginName + " (Depth " + currentDepth + ") §7=> §cFILE INACCESSIBLE"));
                return new ProcessResult(pluginFile, pluginName, currentDepth, false);
            }

            String safePluginName = pluginName.replace(".jar", "").replaceAll("[^a-zA-Z0-9_-]", "_");
            Path tempDir = tempBaseDir.resolve(safePluginName + "_" + System.nanoTime());
            Files.createDirectories(tempDir);
            synchronized (tempDirs) {
                tempDirs.put(pluginKey, tempDir);
            }

            boolean hasClassFiles = hasClassFiles(pluginFile);
            if (!hasClassFiles) {
                writeLog(pluginName, currentDepth, "NO CLASS FILES FOUND");
                runTaskOnMainThread(() -> sender.sendMessage(
                        "§b[" + apiInstanceName + "] §fScanned (Depth " + currentDepth + "): §e" + pluginName + " §7=> §6NO CLASS FILES FOUND"));
                return new ProcessResult(pluginFile, pluginName, currentDepth, false);
            }

            List<String> javaFiles = decompilePlugin(pluginFile, tempDir);
            if (javaFiles.isEmpty()) {
                writeLog(pluginName, currentDepth, "NO JAVA CODE (DECOMPILE FAILED)");
                runTaskOnMainThread(() -> sender.sendMessage(
                        "§b[" + apiInstanceName + "] §fScanned (Depth " + currentDepth + "): §e" + pluginName + " §7=> §6NO JAVA CODE (DECOMPILE FAILED)"));
                return new ProcessResult(pluginFile, pluginName, currentDepth, false);
            }

            String prompt = buildInputs(javaFiles);
            if (prompt.length() <= 1) {
                writeLog(pluginName, currentDepth, "SOURCE CODE TOO SHORT/ERROR");
                runTaskOnMainThread(() -> sender.sendMessage(
                        "§b[" + apiInstanceName + "] §fScanned (Depth " + currentDepth + "): §e" + pluginName + " §7=> §6SOURCE CODE TOO SHORT/ERROR"));
                return new ProcessResult(pluginFile, pluginName, currentDepth, false);
            }

            JSONObject result = sendToGemini(prompt, currentApiKey, currentModelName);
            String answer = "ERROR";
            if (result != null) {
                answer = result.optString("text", "NO RESPONSE").trim().toUpperCase();
                if (!answer.equals("YES") && !answer.equals("NO")) {
                    answer = "(" + answer + ")";
                }
            }

            if ("ERROR".equalsIgnoreCase(answer)) {
                return new ProcessResult(pluginFile, pluginName, currentDepth, true);
            }

            writeLog(pluginName, currentDepth, answer);
            String messageColor = answer.contains("YES") ? "§c" : "§a";
            String finalAnswer = answer;
            runTaskOnMainThread(() -> sender.sendMessage(
                    "§b[" + apiInstanceName + "] §fScanned (Depth " + currentDepth + "): §e" + pluginName + " §7=> " + messageColor + finalAnswer));

            List<File> nestedJars = extractNestedJars(pluginFile, tempDir);
            for (File nestedJar : nestedJars) {
                this.pluginInstance.getLogger().info("[" + this.apiInstanceName + "] Found nested JAR (Depth " + (currentDepth + 1) + "): " + nestedJar.getName());
                queue.offer(new PrioritizedFile(nestedJar, currentDepth + 1));
            }

            return new ProcessResult(pluginFile, pluginName, currentDepth, false);

        } catch (IOException e) {
            this.pluginInstance.getLogger().severe("[" + this.apiInstanceName + "] IO Error processing " + pluginName + ": " + e.getMessage());
            return new ProcessResult(pluginFile, pluginName, currentDepth, true);
        } catch (Exception e) {
            this.pluginInstance.getLogger().severe("[" + this.apiInstanceName + "] Unexpected error processing " + pluginName + ": " + e.getMessage());
            return new ProcessResult(pluginFile, pluginName, currentDepth, false);
        } finally {
            synchronized (tempDirs) {
                tempDirs.remove(pluginKey);
            }
        }
    }

    private boolean hasClassFiles(File pluginFile) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(pluginFile.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    return true;
                }
                zis.closeEntry();
            }
        } catch (ZipException e) {
            this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Invalid JAR file: " + pluginFile.getName() + " - " + e.getMessage());
        }
        return false;
    }

    private List<File> extractNestedJars(File pluginFile, Path tempDir) throws IOException {
        List<File> nestedJars = new ArrayList<>();
        Path nestedJarDir = tempDir.resolve("nested_jars");
        Files.createDirectories(nestedJarDir);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(pluginFile.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".jar")) {
                    Path nestedJarPath = nestedJarDir.resolve(entry.getName().substring(entry.getName().lastIndexOf('/') + 1));
                    try {
                        if (entry.getSize() > 100_000_000) {
                            this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Nested JAR too large: " + entry.getName());
                            continue;
                        }
                        this.pluginInstance.getLogger().info("[" + this.apiInstanceName + "] Extracting nested JAR: " + entry.getName());
                        Files.copy(zis, nestedJarPath, StandardCopyOption.REPLACE_EXISTING);
                        File nestedJarFile = nestedJarPath.toFile();
                        if (!nestedJarFile.exists() || !nestedJarFile.canRead()) {
                            this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Cannot access extracted nested JAR: " + nestedJarPath);
                            continue;
                        }
                        if (nestedJarFile.length() == 0) {
                            this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Empty nested JAR: " + nestedJarPath.getFileName());
                            continue;
                        }
                        try (ZipInputStream validateZis = new ZipInputStream(Files.newInputStream(nestedJarPath))) {
                            if (validateZis.getNextEntry() != null) {
                                nestedJars.add(nestedJarFile);
                            } else {
                                this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Invalid nested JAR: " + nestedJarPath.getFileName());
                            }
                        } catch (ZipException e) {
                            this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Invalid nested JAR format: " + nestedJarPath.getFileName() + " - " + e.getMessage());
                        }
                    } catch (IOException e) {
                        this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Failed to extract nested JAR: " + entry.getName() + " - " + e.getMessage());
                    }
                }
                zis.closeEntry();
            }
        } catch (ZipException e) {
            this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Invalid JAR file: " + pluginFile.getName() + " - " + e.getMessage());
        }
        return nestedJars;
    }

    private List<String> decompilePlugin(File pluginFile, Path tempDir) throws Exception {
        List<String> javaFiles = new ArrayList<>();
        if (!pluginFile.exists() || !pluginFile.canRead()) {
            this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Cannot access file for decompilation: " + pluginFile.getAbsolutePath());
            return javaFiles;
        }
        if (pluginFile.length() == 0) {
            this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Empty file: " + pluginFile.getName());
            return javaFiles;
        }

        Map<String, String> options = new HashMap<>();
        options.put("outputdir", tempDir.toString());
        options.put("comments", "false");
        options.put("decodestrings", "true");
        CfrDriver driver = new CfrDriver.Builder().withOptions(options).build();
        try {
            this.pluginInstance.getLogger().info("[" + this.apiInstanceName + "] Decompiling: " + pluginFile.getName());
            driver.analyse(Collections.singletonList(pluginFile.getAbsolutePath()));
        } catch (Exception e) {
            this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Failed to decompile " + pluginFile.getName() + ": " + e.getMessage());
            return javaFiles;
        }

        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> javaFiles.add(p.toString()));
        }
        return javaFiles;
    }

    private String buildInputs(List<String> javaFiles) throws IOException {
        StringBuilder sb = new StringBuilder("Bạn là một chuyên gia bảo mật được yêu cầu phân tích mã nguồn Java sau đây. Nhiệm vụ của bạn là kiểm tra kỹ lưỡng mã để tìm bất kỳ dấu hiệu rõ ràng nào của backdoor. Hãy đặc biệt chú ý đến các kỹ thuật sau:\n");
        sb.append("- Sử dụng Java Reflection và MethodHandles để thực hiện các hành vi bất thường hoặc ẩn giấu.\n");
        sb.append("- Mã hóa phức tạp và che giấu (Obfuscation) không cần thiết, đặc biệt là các chuỗi hoặc logic quan trọng.\n");
        sb.append("- Kiểm tra các điều kiện vô nghĩa hoặc luôn đúng/sai nhằm che giấu luồng thực thi độc hại.\n");
        sb.append("- Điều khiển từ xa bằng cách phân tích chuỗi điều kiện hoặc dữ liệu nhận được từ bên ngoài.\n");
        sb.append("- Thực hiện các lệnh đặc biệt hoặc bỏ qua các điều kiện bảo mật thông thường.\n\n");
        sb.append("Sau khi phân tích, bạn phải đưa ra câu trả lời duy nhất: \"YES\" nếu bạn nghi ngờ có backdoor hoặc bạn phát hiện ra một backdoor dựa trên các tiêu chí trên, hoặc \"NO\" nếu không có dấu hiệu nào của backdoor. Không cung cấp bất kỳ giải thích, phân tích chi tiết, hoặc văn bản bổ sung nào ngoài từ \"YES\" hoặc \"NO\".\nJava Source Code:\n");

        for (String path : javaFiles) {
            String content = Files.readString(Paths.get(path), StandardCharsets.UTF_8);
            if (sb.length() + content.length() > MAX_PROMPT_LENGTH) {
                break;
            }
            sb.append("\n// --- ").append(Paths.get(path).getFileName()).append(" ---\n");
            sb.append(content).append("\n");
        }
        return sb.toString();
    }

    private JSONObject sendToGemini(String prompt, String apiKey, String modelName) {
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");

            JSONObject body = new JSONObject();
            JSONArray contents = new JSONArray();
            JSONObject part = new JSONObject();
            part.put("parts", new JSONArray().put(new JSONObject().put("text", prompt)));
            contents.put(part);
            body.put("contents", contents);

            try (OutputStream os = con.getOutputStream()) {
                byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            if (con.getResponseCode() != 200) {
                this.pluginInstance.getLogger().warning("API returned status: " + con.getResponseCode());
                return null;
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String response = br.lines().collect(Collectors.joining());
                JSONObject jsonResponse = new JSONObject(response);
                JSONArray candidates = jsonResponse.optJSONArray("candidates");
                if (candidates == null || candidates.isEmpty()) {
                    return null;
                }
                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                if (content == null) {
                    return null;
                }
                return content.optJSONArray("parts").getJSONObject(0);
            }
        } catch (IOException e) {
            this.pluginInstance.getLogger().warning("IO Error sending to Gemini: " + e.getMessage());
            return null;
        } catch (Exception e) {
            this.pluginInstance.getLogger().warning("Unexpected error sending to Gemini: " + e.getMessage());
            return null;
        }
    }

    private void writeLog(String pluginName, int depth, String result) {
        String log = "----- Plugin: " + pluginName + " (Depth: " + depth + ") -----\nTime: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\nResult: " + result + "\n------------------------------------------\n";
        runTaskOnMainThread(() -> pluginInstance.appendToLog(log));
    }

    private void runTaskOnMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask((Plugin) pluginInstance, task);
        }
    }

    public static class PrioritizedFile implements Comparable<PrioritizedFile> {
        final File file;
        final int depth;

        PrioritizedFile(File file, int depth) {
            this.file = file;
            this.depth = depth;
        }

        @Override
        public int compareTo(PrioritizedFile other) {
            return Integer.compare(this.depth, other.depth);
        }
    }

    private static class ProcessResult {
        final File pluginFile;
        final String pluginName;
        final int depth;
        final boolean retry;

        ProcessResult(File pluginFile, String pluginName, int depth, boolean retry) {
            this.pluginFile = pluginFile;
            this.pluginName = pluginName;
            this.depth = depth;
            this.retry = retry;
        }
    }
}