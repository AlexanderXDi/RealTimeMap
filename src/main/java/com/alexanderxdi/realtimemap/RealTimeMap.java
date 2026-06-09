package com.alexanderxdi.realtimemap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Mod(RealTimeMap.MODID)
public class RealTimeMap {
    public static final String MODID = "realtimemap";
    public static final Logger LOGGER = LogManager.getLogger();
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static HttpServer server;
    private static MinecraftServer minecraftServer;

    public RealTimeMap(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("RealTimeMap Mod: Initializing...");
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        if (server == null) {
            startWebServer();
        }
    }

    private void startWebServer() {
        try {
            int port = Config.port > 0 ? Config.port : 8080;
            server = HttpServer.create(new InetSocketAddress(port), 0);
            
            server.createContext("/api/status", (exchange) -> {
                LOGGER.info("API Status Request");
                boolean worldReady = (minecraftServer != null);
                sendResponse(exchange, "{\"status\": \"online\", \"version\": \"0.0.1\", \"world_loaded\": " + worldReady + "}", "application/json", 200);
            });

            server.createContext("/api/logs", (exchange) -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    try (InputStream is = exchange.getRequestBody()) {
                        byte[] bodyBytes = is.readAllBytes();
                        String body = new String(bodyBytes, StandardCharsets.UTF_8);
                        
                        String timestamp = parseJsonField(body, "timestamp");
                        String level = parseJsonField(body, "level").toUpperCase();
                        String message = parseJsonField(body, "message");
                        
                        message = message.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
                        
                        LOGGER.info("[WEBSITE] [{}] {}", level, message);
                        
                        String fileLine = String.format("[%s] [%s] %s", timestamp, level, message);
                        writeLogToFile("website_console.log", fileLine);
                        
                        sendResponse(exchange, "{\"status\":\"ok\"}", "application/json", 200);
                    } catch (Exception e) {
                        LOGGER.error("Failed to parse client log: ", e);
                        sendResponse(exchange, "{\"error\":\"" + e.getMessage() + "\"}", "application/json", 500);
                    }
                } else if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, "", "text/plain", 204);
                } else {
                    sendResponse(exchange, "{\"error\":\"Method Not Allowed\"}", "application/json", 405);
                }
            });

            server.createContext("/api/map/dictionary", (exchange) -> {
                LOGGER.info("API Dictionary Request");
                if (!checkApiKey(exchange)) return;
                List<String> dict = MapScanner.getDictionary();
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < dict.size(); i++) {
                    json.append("\"").append(dict.get(i)).append("\"").append(i < dict.size() - 1 ? "," : "");
                }
                json.append("]");
                sendResponse(exchange, json.toString(), "application/json", 200);
            });

            server.createContext("/api/players", (exchange) -> {
                if (!checkApiKey(exchange)) return;
                
                MinecraftServer ms = minecraftServer;
                if (ms == null) {
                    sendResponse(exchange, "[]", "application/json", 200);
                    return;
                }

                CompletableFuture<String> future = new CompletableFuture<>();
                ms.execute(() -> {
                    try {
                        StringBuilder json = new StringBuilder("[");
                        List<ServerPlayer> players = ms.getPlayerList().getPlayers();
                        for (int i = 0; i < players.size(); i++) {
                            ServerPlayer p = players.get(i);
                            json.append(String.format(Locale.US,
                                "{\"uuid\":\"%s\",\"name\":\"%s\",\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"dimension\":\"%s\",\"yaw\":%.2f}",
                                p.getUUID(), p.getName().getString(), p.getX(), p.getY(), p.getZ(), 
                                p.level().dimension().location(), p.getYRot()
                            ));
                            if (i < players.size() - 1) json.append(",");
                        }
                        json.append("]");
                        future.complete(json.toString());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });

                try {
                    String json = future.get(10, TimeUnit.SECONDS);
                    sendResponse(exchange, json, "application/json", 200);
                } catch (Exception e) {
                    sendResponse(exchange, "[]", "application/json", 200);
                }
            });

            server.createContext("/api/map/chunk", (exchange) -> {
                if (!checkApiKey(exchange)) return;
                LOGGER.info("API Request: {}?{}", exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery());
                try {
                    Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
                    String dimName = params.getOrDefault("dim", "minecraft:overworld");
                    int x = Integer.parseInt(params.getOrDefault("x", "0"));
                    int z = Integer.parseInt(params.getOrDefault("z", "0"));
                    String mode = params.getOrDefault("mode", "2d");

                    MinecraftServer ms = minecraftServer;
                    if (ms == null) {
                        sendResponse(exchange, "{\"error\": \"World not loaded\"}", "application/json", 503);
                        return;
                    }

                    CompletableFuture<String> future = new CompletableFuture<>();
                    ms.execute(() -> {
                        try {
                            for (ServerLevel level : ms.getAllLevels()) {
                                if (level.dimension().location().toString().equals(dimName)) {
                                    var data = MapScanner.getChunkData(level, x, z, mode);
                                    StringBuilder json = new StringBuilder("{");
                                    json.append("\"x\":").append(data.get("x")).append(",");
                                    json.append("\"z\":").append(data.get("z")).append(",");
                                    json.append("\"topY\":[");
                                    int[] tY = (int[]) data.get("topY");
                                    for(int i=0; i<tY.length; i++) {
                                        json.append(tY[i]).append(i < tY.length-1 ? "," : "");
                                    }
                                    json.append("],\"columns\":[");
                                    List<List<Integer>> columns = (List<List<Integer>>) data.get("columns");
                                    for(int i=0; i<columns.size(); i++) {
                                        json.append("[");
                                        List<Integer> col = columns.get(i);
                                        for(int j=0; j<col.size(); j++) {
                                            json.append(col.get(j)).append(j < col.size()-1 ? "," : "");
                                        }
                                        json.append("]").append(i < columns.size()-1 ? "," : "");
                                    }
                                    json.append("]}");
                                    future.complete(json.toString());
                                    return;
                                }
                            }
                            future.completeExceptionally(new Exception("Dimension not found"));
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                        }
                    });

                    try {
                        String json = future.get(15, TimeUnit.SECONDS);
                        sendResponse(exchange, json, "application/json", 200);
                    } catch (Exception e) {
                        sendResponse(exchange, "{\"error\": \"Failed to get chunk data\"}", "application/json", 503);
                    }
                } catch (Exception e) {
                    sendResponse(exchange, "{\"error\": \"Bad request: " + e.getMessage() + "\"}", "application/json", 400);
                }
            });

            server.createContext("/api/map/block", (exchange) -> {
                if (!checkApiKey(exchange)) return;
                try {
                    Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
                    String dimName = params.getOrDefault("dim", "minecraft:overworld");
                    int x = Integer.parseInt(params.getOrDefault("x", "0"));
                    int y = Integer.parseInt(params.getOrDefault("y", "64"));
                    int z = Integer.parseInt(params.getOrDefault("z", "0"));

                    MinecraftServer ms = minecraftServer;
                    if (ms == null) {
                        sendResponse(exchange, "{\"error\": \"World not loaded\"}", "application/json", 503);
                        return;
                    }

                    CompletableFuture<String> future = new CompletableFuture<>();
                    ms.execute(() -> {
                        try {
                            for (ServerLevel level : ms.getAllLevels()) {
                                if (level.dimension().location().toString().equals(dimName)) {
                                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                                    var state = level.getBlockState(pos);
                                    String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                                    future.complete(String.format("{\"x\":%d,\"y\":%d,\"z\":%d,\"id\":\"%s\"}", x, y, z, blockId));
                                    return;
                                }
                            }
                            future.completeExceptionally(new Exception("Dimension not found"));
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                        }
                    });

                    try {
                        String json = future.get(5, TimeUnit.SECONDS);
                        sendResponse(exchange, json, "application/json", 200);
                    } catch (Exception e) {
                        sendResponse(exchange, "{\"error\": \"Failed to get block data\"}", "application/json", 503);
                    }
                } catch (Exception e) {
                    sendResponse(exchange, "{\"error\": \"Bad request\"}", "application/json", 400);
                }
            });

            server.createContext("/", (exchange) -> {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/api/")) {
                    sendResponse(exchange, "{\"error\": \"Not Found\"}", "application/json", 404);
                    return;
                }
                if (!Config.enableInternalServer) {
                    sendResponse(exchange, "Disabled", "text/plain", 403);
                    return;
                }
                if (path.equals("/")) path = "/index.html";
                String resourcePath = "/web" + path;
                try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        sendResponse(exchange, "404", "text/plain", 404);
                        return;
                    }
                    byte[] bytes = is.readAllBytes();
                    String contentType = "text/html; charset=UTF-8";
                    if (path.endsWith(".css")) contentType = "text/css; charset=UTF-8";
                    if (path.endsWith(".js")) contentType = "application/javascript; charset=UTF-8";
                    sendResponse(exchange, bytes, contentType, 200);
                } catch (Exception e) {
                    sendResponse(exchange, "Error", "text/plain", 500);
                }
            });

            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
            LOGGER.info("RealTimeMap Web Server started on port {}", port);
        } catch (Exception e) {
            LOGGER.error("Failed to start Web Server: ", e);
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1) {
                try {
                    params.put(entry[0], java.net.URLDecoder.decode(entry[1], StandardCharsets.UTF_8.toString()));
                } catch (Exception e) {
                    params.put(entry[0], entry[1]);
                }
            }
        }
        return params;
    }

    private boolean checkApiKey(HttpExchange exchange) throws IOException {
        if (Config.apiKey == null || Config.apiKey.isEmpty()) {
            return true; // Security disabled
        }
        
        String providedKey = exchange.getRequestHeaders().getFirst("X-API-Key");
        if (Config.apiKey.equals(providedKey)) {
            return true;
        }
        
        sendResponse(exchange, "{\"error\": \"Unauthorized\"}", "application/json", 401);
        return false;
    }

    private void sendResponse(HttpExchange exchange, String response, String contentType, int code) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        sendResponse(exchange, bytes, contentType, code);
    }

    private void sendResponse(HttpExchange exchange, byte[] bytes, String contentType, int code) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS, POST");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization,X-API-Key");
        
        if (!contentType.contains("charset=")) {
            contentType += "; charset=UTF-8";
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            logHttpRequest(exchange, 204, 0);
            return;
        }

        try {
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            logHttpRequest(exchange, code, bytes.length);
        } catch (IOException e) {
            LOGGER.warn("Failed to send response to client: {}", e.getMessage());
            logHttpRequest(exchange, code, -1);
        }
    }

    private static void logHttpRequest(HttpExchange exchange, int code, int responseLength) {
        String timestamp = LocalDateTime.now().format(ISO_FORMATTER);
        String remoteIp = exchange.getRemoteAddress().toString();
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().toString();
        
        String logLine = String.format("[%s] [%s] %s %s -> Status %d (%d bytes)",
            timestamp, remoteIp, method, path, code, responseLength);
            
        LOGGER.info(logLine);
        writeLogToFile("server_http.log", logLine);
    }

    private static synchronized void writeLogToFile(String fileName, String line) {
        try {
            File geminiDir = new File("../../Gemini");
            File logFile;
            if (geminiDir.exists() && geminiDir.isDirectory()) {
                logFile = new File(geminiDir, fileName);
            } else {
                File logsDir = new File("logs");
                if (!logsDir.exists()) {
                    logsDir.mkdirs();
                }
                logFile = new File(logsDir, fileName);
            }

            try (FileWriter fw = new FileWriter(logFile, true);
                 BufferedWriter bw = new BufferedWriter(fw);
                 PrintWriter out = new PrintWriter(bw)) {
                out.println(line);
            }
        } catch (Exception e) {
            System.err.println("[RealTimeMap Log Error] Failed to write log: " + e.getMessage());
        }
    }

    private static String parseJsonField(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) {
            pattern = "\"" + field + "\":";
            idx = json.indexOf(pattern);
            if (idx == -1) return "";
            int start = idx + pattern.length();
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            if (end == -1) return "";
            return json.substring(start, end).trim().replace("\"", "");
        }
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return "";
        return json.substring(start, end);
    }

    private void onServerStarted(ServerStartedEvent event) {
        minecraftServer = event.getServer();
        LOGGER.info("RealTimeMap: Minecraft Server started, API ready.");
    }

    private void onServerStopped(ServerStoppedEvent event) {
        minecraftServer = null;
        LOGGER.info("RealTimeMap: Minecraft Server stopped, API suspended.");
    }
}
