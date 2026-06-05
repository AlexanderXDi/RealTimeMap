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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Mod(RealTimeMap.MODID)
public class RealTimeMap {
    public static final String MODID = "realtimemap";
    public static final Logger LOGGER = LogManager.getLogger();
    private static HttpServer server;
    private static final ExecutorService SCAN_EXECUTOR = Executors.newFixedThreadPool(8);
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
                    CompletableFuture.runAsync(() -> {
                        try {
                            for (ServerLevel level : ms.getAllLevels()) {
                                if (level.dimension().location().toString().equals(dimName)) {
                                    var data = MapScanner.getChunkData(level, x, z, mode);
                                    StringBuilder json = new StringBuilder("{");
                                    json.append("\"x\":").append(data.get("x")).append(",");
                                    json.append("\"z\":").append(data.get("z")).append(",");
                                    json.append("\"groups\":{");
                                    
                                    Map<Integer, List<Integer>> groups = (Map<Integer, List<Integer>>) data.get("groups");
                                    int gIdx = 0;
                                    for (Map.Entry<Integer, List<Integer>> entry : groups.entrySet()) {
                                        json.append("\"").append(entry.getKey()).append("\":[");
                                        List<Integer> list = entry.getValue();
                                        for (int i = 0; i < list.size(); i++) {
                                            json.append(list.get(i)).append(i < list.size() - 1 ? "," : "");
                                        }
                                        json.append("]").append(++gIdx < groups.size() ? "," : "");
                                    }
                                    json.append("}}");
                                    future.complete(json.toString());
                                    return;
                                }
                            }
                            future.completeExceptionally(new Exception("Dimension not found"));
                        } catch (Throwable t) {
                            LOGGER.error("[RTM-API] Async Scan Error: {}", t.getMessage());
                            future.completeExceptionally(t);
                        }
                    }, SCAN_EXECUTOR);

                    try {
                        String json = future.get(30, TimeUnit.SECONDS);
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
            return;
        }

        try {
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to send response to client: {}", e.getMessage());
        }
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
