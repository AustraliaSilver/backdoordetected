package backdoor.detect.backdoordetected;

import org.benf.cfr.reader.api.CfrDriver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PluginWorker implements Runnable {
    private final BlockingQueue<File> queue;
    private final String currentApiKey;
    private final String currentModelName;
    private final Backdoordetected pluginInstance;
    private final CommandSender sender;
    private final String apiInstanceName;
    private static final int MAX_PROMPT_LENGTH = 30000;

    public PluginWorker(BlockingQueue<File> queue, String apiKey, String modelName,
                        Backdoordetected pluginInstance, CommandSender sender, String apiInstanceName) {
        this.queue = queue;
        this.currentApiKey = apiKey;
        this.currentModelName = modelName;
        this.pluginInstance = pluginInstance;
        this.sender = sender;
        this.apiInstanceName = apiInstanceName;
    }

    @Override
    public void run() {
        pluginInstance.getLogger().info("[" + apiInstanceName + "] Worker STARTED.");
        Map<String, Integer> retryCount = new HashMap<>();

        try {
            File pluginFile;
            while ((pluginFile = queue.poll()) != null) {
                Path tempDir = null;
                boolean retry = false;
                String pluginName = pluginFile.getName();

                try {
                    pluginInstance.getLogger().info("[" + apiInstanceName + "] Processing: " + pluginName);
                    tempDir = Files.createTempDirectory("bdd_" + pluginName.replace(".jar", "")
                            .substring(0, Math.min(8, pluginName.replace(".jar", "").length())));
                    List<String> javaFiles = decompilePlugin(pluginFile, tempDir);
                    if (javaFiles.isEmpty()) {
                        writeLog(pluginName, "NO JAVA CODE");
                        sender.sendMessage("§b[" + apiInstanceName + "] §fScanned: §e" + pluginName + " §7=> §6NO JAVA CODE");
                        continue;
                    }

                    String prompt = buildInputs(javaFiles);
                    if (prompt.length() <= 100) {
                        writeLog(pluginName, "SOURCE CODE TOO SHORT/ERROR");
                        sender.sendMessage("§b[" + apiInstanceName + "] §fScanned: §e" + pluginName + " §7=> §6SOURCE CODE TOO SHORT/ERROR");
                        continue;
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
                        retry = true;
                    } else {
                        writeLog(pluginName, answer);
                        String messageColor = answer.contains("YES") ? "§c" : "§a";
                        sender.sendMessage("§b[" + apiInstanceName + "] §fScanned: §e" + pluginName + " §7=> " + messageColor + answer);
                    }

                } catch (Exception e) {
                    pluginInstance.getLogger().severe("[" + apiInstanceName + "] Error processing " + pluginName + ": " + e.getMessage());
                    retry = true;
                } finally {
                    if (tempDir != null && Files.exists(tempDir)) {
                        try {
                            Files.walk(tempDir)
                                    .sorted(Comparator.reverseOrder())
                                    .map(Path::toFile)
                                    .forEach(File::delete);
                        } catch (IOException ignored) {}
                    }
                }

                if (retry) {
                    int currentRetry = retryCount.getOrDefault(pluginName, 0);
                    if (currentRetry < 2) {
                        retryCount.put(pluginName, currentRetry + 1);
                        queue.offer(pluginFile);
                        sender.sendMessage("§e[" + apiInstanceName + "] §7Retrying (" + (currentRetry + 2) + "/3): §e" + pluginName);
                        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                        continue;
                    } else {
                        writeLog(pluginName, "ERROR (TRIED 3 TIMES)");
                        sender.sendMessage("§c[" + apiInstanceName + "] §e" + pluginName + " §7=> §cSKIPPING AFTER 3 TRIES");
                    }
                }

                try { Thread.sleep(5000);
                } catch (InterruptedException ignored) {}
            }
        } catch (Exception ex) {
            pluginInstance.getLogger().severe("[" + apiInstanceName + "] Error in worker: " + ex.getMessage());
        } finally {
            Bukkit.getScheduler().runTask(pluginInstance, () -> {
                try {
                    java.lang.reflect.Field field = Backdoordetected.class.getDeclaredField("activeScannerThreads");
                    field.setAccessible(true);
                    ((java.util.concurrent.atomic.AtomicInteger) field.get(pluginInstance)).decrementAndGet();
                } catch (Exception e) {
                    pluginInstance.getLogger().warning("Could not update activeScannerThreads: " + e.getMessage());
                }
            });
            pluginInstance.getLogger().info("[" + apiInstanceName + "] Thread finished.");
        }
    }

    private List<String> decompilePlugin(File pluginFile, Path tempDir) throws Exception {
        List<String> javaFiles = new ArrayList<>();
        Map<String, String> options = new HashMap<>();
        options.put("outputdir", tempDir.toString());
        options.put("comments", "false");
        options.put("decodestrings", "true");

        CfrDriver driver = new CfrDriver.Builder().withOptions(options).build();
        driver.analyse(Collections.singletonList(pluginFile.getAbsolutePath()));
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> javaFiles.add(p.toString()));
        }
        return javaFiles;
    }

    private String buildInputs(List<String> javaFiles) throws IOException {
        StringBuilder sb = new StringBuilder("Given the following files, check if there's a backdoor. Answer only \"YES\" or \"NO\"\nJava Source Code:\n");
        for (String path : javaFiles) {
            String content = Files.readString(Paths.get(path), StandardCharsets.UTF_8);
            if (sb.length() + content.length() > MAX_PROMPT_LENGTH) break;
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

            if (con.getResponseCode() == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    String response = br.lines().collect(Collectors.joining());
                    JSONArray candidates = new JSONObject(response).optJSONArray("candidates");
                    if (candidates != null && !candidates.isEmpty()) {
                        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                        if (content != null) return content.optJSONArray("parts").getJSONObject(0);
                    }
                }
            }
        } catch (Exception e) {
            pluginInstance.getLogger().warning("Send Error to Gemini: " + e.getMessage());
        }
        return null;
    }

    private void writeLog(String pluginName, String result) {
        String log = "----- Plugin: " + pluginName + " -----\n"
                + "Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n"
                + "Result: " + result + "\n"
                + "------------------------------------------\n";
        pluginInstance.appendToLog(log);
    }
}