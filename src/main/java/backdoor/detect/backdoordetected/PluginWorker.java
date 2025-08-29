package backdoor.detect.backdoordetected;

import org.benf.cfr.reader.api.CfrDriver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PluginWorker implements Runnable {
    private final BlockingQueue<PrioritizedFile> queue;
    private final String currentApiKey;
    private final String currentModelName;
    private final Backdoordetected pluginInstance;
    private final CommandSender sender;
    private final String apiInstanceName;
    private static final int MAX_PROMPT_LENGTH = 262144;
    private final CodeAnalyzer codeAnalyzer;

    private static class GeminiResponse {
        final JSONObject json;
        final int statusCode;

        GeminiResponse(JSONObject json, int statusCode) {
            this.json = json;
            this.statusCode = statusCode;
        }
    }

    public PluginWorker(BlockingQueue<PrioritizedFile> queue, String apiKey, String modelName, Backdoordetected pluginInstance, CommandSender sender, String apiInstanceName) {
        this.queue = queue;
        this.currentApiKey = apiKey;
        this.currentModelName = modelName;
        this.pluginInstance = pluginInstance;
        this.sender = sender;
        this.apiInstanceName = apiInstanceName;
        this.codeAnalyzer = new CodeAnalyzer(pluginInstance);
    }

    @Override
    public void run() {
        this.pluginInstance.getLogger().info("[" + this.apiInstanceName + "] Worker STARTED.");
        Path tempBaseDir = pluginInstance.getDataFolder().toPath().resolve("temp");
        try {
            Files.createDirectories(tempBaseDir);
        } catch (IOException e) {
            this.pluginInstance.getLogger().severe("[" + this.apiInstanceName + "] Failed to create temp directory: " + e.getMessage());
        }

        while (!queue.isEmpty()) {
            PrioritizedFile pFile = queue.poll();
            if (pFile == null) continue;

            try {
                processFile(pFile.file, pFile.depth, tempBaseDir);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Processing of " + pFile.file.getName() + " was interrupted.");
            } catch (Exception e) {
                this.pluginInstance.getLogger().severe("[" + this.apiInstanceName + "] Unhandled exception while processing " + pFile.file.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        this.pluginInstance.getLogger().info("[" + this.apiInstanceName + "] Worker FINISHED.");
        runTaskOnMainThread(() -> {
            try {
                Field field = Backdoordetected.class.getDeclaredField("activeScannerThreads");
                field.setAccessible(true);
                ((AtomicInteger) field.get(pluginInstance)).decrementAndGet();
            } catch (Exception e) {
                pluginInstance.getLogger().warning("Could not update activeScannerThreads: " + e.getMessage());
            }
        });
    }

    private void processFile(File pluginFile, int currentDepth, Path tempBaseDir) throws InterruptedException {
        String pluginName = pluginFile.getName();
        Path tempDir = null;
        try {
            runTaskOnMainThread(() -> sender.sendMessage("§b[" + apiInstanceName + "] §f[1/3] Decompiling: §e" + pluginName));
            String safePluginName = pluginName.replace(".jar", "").replaceAll("[^a-zA-Z0-9_-]", "_");
            tempDir = tempBaseDir.resolve(safePluginName + "_" + System.nanoTime());
            Files.createDirectories(tempDir);
            List<Path> allJavaFiles = decompilePlugin(pluginFile, tempDir);

            if (allJavaFiles.isEmpty()) {
                writeLog(pluginName, currentDepth, "Decompile Fail");
                runTaskOnMainThread(() -> sender.sendMessage("§b[" + apiInstanceName + "] §fScanned: §e" + pluginName + " §7=> §eDecompile Fail"));
                return;
            }

            runTaskOnMainThread(() -> sender.sendMessage("§b[" + apiInstanceName + "] §f[2/3] Performing internal code analysis..."));
            Map<Path, List<String>> analysisResults = codeAnalyzer.analyze(allJavaFiles);
            Map<Path, List<String>> criticalFindings = analysisResults.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry ->
                    entry.getValue().stream()
                         .filter(reason -> reason.startsWith("CRITICAL") || reason.startsWith("HIGH"))
                         .collect(Collectors.toList())))
                .entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            if (criticalFindings.isEmpty()) {
                writeLog(pluginName, currentDepth, "NO (Fast Scan)");
                runTaskOnMainThread(() -> sender.sendMessage("§b[" + apiInstanceName + "] §fScanned: §e" + pluginName + " §7=> §aNO (Fast Scan)"));
                return;
            }

            String finalVerdict = processBatchesInChunks(criticalFindings, pluginName);
            writeLog(pluginName, currentDepth, "Gemini Verdict: " + finalVerdict);
            final String messageColor = finalVerdict.contains("YES") ? "§c" : "§a";
            runTaskOnMainThread(() -> sender.sendMessage("§b[" + apiInstanceName + "] §fScanned: §e" + pluginName + " §7=> " + messageColor + ": " + finalVerdict));

        } catch (Exception e) {
            this.pluginInstance.getLogger().severe("[" + this.apiInstanceName + "] Error processing " + pluginName + ": " + e.getMessage());
            e.printStackTrace();
            writeLog(pluginName, currentDepth, "ERROR (Internal Plugin Error)");
        } finally {
            if (tempDir != null) {
                try {
                    List<File> filesToDelete = Files.walk(tempDir).sorted(Comparator.reverseOrder()).map(Path::toFile).collect(Collectors.toList());
                    for (File file : filesToDelete) {
                        file.delete();
                    }
                } catch (IOException e) {
                    this.pluginInstance.getLogger().warning("[" + this.apiInstanceName + "] Failed to delete temp directory: " + tempDir);
                }
            }
        }
    }

    private String processBatchesInChunks(Map<Path, List<String>> criticalFindings, String pluginName) throws IOException, InterruptedException {
        List<Map<Path, List<String>>> batches = createBatches(criticalFindings);
        List<String> allResults = new ArrayList<>();
        int batchNum = 1;

        this.pluginInstance.getLogger().info("----------------------------------------------------");
        this.pluginInstance.getLogger().info("Internal analysis found " + criticalFindings.size() + " critical file(s) in " + pluginName + ". Splitting into " + batches.size() + " batches.");

        for (Map<Path, List<String>> batch : batches) {
            final int currentBatchNum = batchNum++;
            final int totalBatches = batches.size();

            this.pluginInstance.getLogger().info("--> Preparing Batch " + currentBatchNum + "/" + totalBatches + " for Gemini with " + batch.size() + " file(s):");
            for (Path filePath : batch.keySet()) {
                this.pluginInstance.getLogger().info("    - " + filePath.getFileName());
            }

            String prompt = buildGeminiPrompt(batch);
            JSONObject result = sendToGeminiWithRetries(prompt, currentApiKey, currentModelName, currentBatchNum, totalBatches);

            String answer = "ERROR";
            if (result != null) {
                answer = result.optString("text", "NO RESPONSE").trim().toUpperCase();
                if (!answer.equals("YES") && !answer.equals("NO")) {
                    answer = "UNKNOWN(" + answer + ")";
                }
            }
            allResults.add(answer);
            this.pluginInstance.getLogger().info("<-- Batch " + currentBatchNum + " of " + totalBatches + " result: " + answer);

            if (answer.equals("YES")) {
                this.pluginInstance.getLogger().info("Immediate 'YES' verdict from batch " + currentBatchNum + ". Halting further analysis.");
                break;
            }
        }
        this.pluginInstance.getLogger().info("----------------------------------------------------");

        if (allResults.stream().anyMatch("YES"::equals)) {
            return "YES";
        } else if (allResults.stream().allMatch("NO"::equals)) {
            return "NO";
        } else {
            long errorCount = allResults.stream().filter(r -> !r.equals("NO") && !r.equals("YES")).count();
            return "ERROR (" + errorCount + "/" + allResults.size() + " batches failed)";
        }
    }

    private List<Map<Path, List<String>>> createBatches(Map<Path, List<String>> allFindings) throws IOException {
        List<Map<Path, List<String>>> batches = new ArrayList<>();
        Map<Path, List<String>> currentBatch = new HashMap<>();
        long currentSize = getBasePromptSize();

        for (Map.Entry<Path, List<String>> entry : allFindings.entrySet()) {
            long entrySize = calculateEntrySize(entry.getKey(), entry.getValue());

            if (currentSize + entrySize > MAX_PROMPT_LENGTH && !currentBatch.isEmpty()) {
                batches.add(currentBatch);
                currentBatch = new HashMap<>();
                currentSize = getBasePromptSize();
            }

            currentBatch.put(entry.getKey(), entry.getValue());
            currentSize += entrySize;
        }

        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }

    private long getBasePromptSize() {
        return ("Bạn là một chuyên gia bảo mật. Phân tích mã nguồn Java sau đây để tìm backdoor. Các công cụ quét tự động của tôi đã gắn cờ các tệp này với những lý do sau:\n\nDựa trên các cảnh báo này và phân tích của riêng bạn về mã nguồn, hãy đưa ra câu trả lời duy nhất: \"YES\" nếu bạn chắc chắn có backdoor, hoặc \"NO\" nếu không. Không cung cấp bất kỳ giải thích nào khác.\n\n--- Mã nguồn đáng ngờ ---\n").length();
    }

    private long calculateEntrySize(Path path, List<String> reasons) throws IOException {
        long size = ("--- File: " + path.getFileName() + " ---\n\n").length();
        for (String reason : reasons) {
            size += ("- " + reason + "\n").length();
        }
        size += Files.size(path);
        size += ("\n// --- File: " + path.getFileName() + " ---\n\n").length();
        return size;
    }

    private String buildGeminiPrompt(Map<Path, List<String>> analysisResults) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Bạn là một chuyên gia bảo mật. Phân tích mã nguồn Java sau đây để tìm backdoor. Các công cụ quét tự động của tôi đã gắn cờ các tệp này với những lý do sau:\n\n");

        for (Map.Entry<Path, List<String>> entry : analysisResults.entrySet()) {
            sb.append("--- File: ").append(entry.getKey().getFileName()).append(" ---\n");
            for (String reason : entry.getValue()) {
                sb.append("- ").append(reason).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Dựa trên các cảnh báo này và phân tích của riêng bạn về mã nguồn, hãy đưa ra câu trả lời duy nhất: \"YES\" nếu bạn chắc chắn có backdoor, hoặc \"NO\" nếu không. Không cung cấp bất kỳ giải thích nào khác.\n\n");
        sb.append("--- Mã nguồn đáng ngờ ---\n");

        for (Path path : analysisResults.keySet()) {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (sb.length() + content.length() > MAX_PROMPT_LENGTH) {
                this.pluginInstance.getLogger().warning("Prompt limit reached while building batch, sending partial code for analysis.");
                break;
            }
            sb.append("\n// --- File: ").append(path.getFileName()).append(" ---\n");
            sb.append(content).append("\n");
        }
        return sb.toString();
    }

    private List<Path> decompilePlugin(File pluginFile, Path tempDir) throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("outputdir", tempDir.toString());
        options.put("comments", "false");
        options.put("decodestrings", "true");
        CfrDriver driver = new CfrDriver.Builder().withOptions(options).build();
        this.pluginInstance.getLogger().info("[" + this.apiInstanceName + "] Decompiling: " + pluginFile.getName());
        driver.analyse(Collections.singletonList(pluginFile.getAbsolutePath()));

        try (Stream<Path> walk = Files.walk(tempDir)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    private JSONObject sendToGeminiWithRetries(String prompt, String apiKey, String modelName, int currentBatchNum, int totalBatches) throws InterruptedException {
        final int MAX_ATTEMPTS = 3;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            final int currentAttempt = attempt;
            runTaskOnMainThread(() -> sender.sendMessage("§b[" + apiInstanceName + "] §f[3/3] Sending batch " + currentBatchNum + " of " + totalBatches + " to Gemini... (Attempt " + currentAttempt + "/" + MAX_ATTEMPTS + ")"));

            GeminiResponse response = sendToGemini(prompt, apiKey, modelName);
            if (response.json != null) {
                return response.json;
            }


            this.pluginInstance.getLogger().warning("<-- Batch " + currentBatchNum + " attempt " + currentAttempt + " failed with status code " + response.statusCode);

            if (attempt < MAX_ATTEMPTS) {
                long delay;
                String reason;
                if (response.statusCode == 429) {
                    delay = 50000;
                    reason = "API rate limit hit";
                } else if (response.statusCode == 503) {
                    delay = 50000;
                    reason = "API service unavailable (503)";
                } else {
                    delay = 5000;
                    reason = "API error";
                }
                final long finalDelaySec = delay / 1000;
                final String finalReason = reason;
                runTaskOnMainThread(() -> sender.sendMessage("§b[" + apiInstanceName + "] §e" + finalReason + ". Waiting " + finalDelaySec + "s before retry..."));
                Thread.sleep(delay);
            } else {
                this.pluginInstance.getLogger().severe("<-- Batch " + currentBatchNum + " failed after " + MAX_ATTEMPTS + " attempts.");
                runTaskOnMainThread(() -> sender.sendMessage("§c[" + apiInstanceName + "] Failed to analyze batch " + currentBatchNum + ". Please check server logs."));
            }
        }
        return null;
    }


    private GeminiResponse sendToGemini(String prompt, String apiKey, String modelName) {
        HttpURLConnection con = null;
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + apiKey);
            con = (HttpURLConnection) url.openConnection();
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

            int statusCode = con.getResponseCode();
            if (statusCode != 200) {
                this.pluginInstance.getLogger().warning("API returned status: " + statusCode);
                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(con.getErrorStream(), StandardCharsets.UTF_8))) {
                    String errorResponse = errorReader.lines().collect(Collectors.joining());
                    this.pluginInstance.getLogger().warning("API Error Response: " + errorResponse);
                } catch (Exception ex) {
                }
                return new GeminiResponse(null, statusCode);
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String response = br.lines().collect(Collectors.joining());
                JSONObject jsonResponse = new JSONObject(response);
                JSONArray candidates = jsonResponse.optJSONArray("candidates");
                if (candidates == null || candidates.isEmpty()) {
                    this.pluginInstance.getLogger().warning("API response contained no candidates. Full response: " + response);
                    return new GeminiResponse(null, statusCode);
                }
                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                if (content == null) {
                    this.pluginInstance.getLogger().warning("API response candidate had no content. Full response: " + response);
                    return new GeminiResponse(null, statusCode);
                }
                return new GeminiResponse(content.optJSONArray("parts").getJSONObject(0), 200);
            }
        } catch (Exception e) {
            this.pluginInstance.getLogger().warning("Error sending to Gemini: " + e.getMessage());
            return new GeminiResponse(null, -1);
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    private void writeLog(String pluginName, int depth, String result) {
        String log = String.format("----- Plugin: %s (Depth: %d) -----\nTime: %s\nResult: %s\n------------------------------------------\n",
                pluginName, depth, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()), result);
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
}
