package com.alexanderxdi.realtimemap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
import java.util.List;
import java.util.concurrent.Executors;

@Mod(RealTimeMap.MODID)
public class RealTimeMap {
    public static final String MODID = "realtimemap";
    public static final Logger LOGGER = LogManager.getLogger();
    private HttpServer server;
    private MinecraftServer minecraftServer;

    public RealTimeMap(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("RealTimeMap Mod: Initializing...");

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("RealTimeMap Mod: Common Setup - Starting Web Server");
        startWebServer();
    }

    private void startWebServer() {
        try {
            int port = Config.port > 0 ? Config.port : 8080;
            LOGGER.info("RealTimeMap Mod: Attempting to start server on port {}", port);
            
            server = HttpServer.create(new InetSocketAddress(port), 0);
            
            // API Status
            server.createContext("/api/status", (exchange) -> {
                sendResponse(exchange, "{\"status\": \"online\", \"version\": \"0.0.1\"}", "application/json");
            });

            // API Players
            server.createContext("/api/players", (exchange) -> {
                StringBuilder json = new StringBuilder("[");
                if (minecraftServer != null) {
                    List<ServerPlayer> players = minecraftServer.getPlayerList().getPlayers();
                    for (int i = 0; i < players.size(); i++) {
                        ServerPlayer p = players.get(i);
                        json.append(String.format(
                            "{\"uuid\":\"%s\",\"name\":\"%s\",\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"dimension\":\"%s\",\"yaw\":%.2f}",
                            p.getUUID(), p.getName().getString(), p.getX(), p.getY(), p.getZ(), 
                            p.level().dimension().location(), p.getYRot()
                        ));
                        if (i < players.size() - 1) json.append(",");
                    }
                }
                json.append("]");
                sendResponse(exchange, json.toString(), "application/json");
            });

            // Map Data API
            server.createContext("/api/map/chunk", (exchange) -> {
                if (minecraftServer == null) {
                    sendResponse(exchange, "{\"error\": \"Server not ready\"}", "application/json", 503);
                    return;
                }

                String path = exchange.getRequestURI().getPath();
                // Expected: /api/map/chunk/minecraft:overworld/0/0
                String[] parts = path.split("/");
                if (parts.length < 7) {
                    sendResponse(exchange, "{\"error\": \"Invalid path format\"}", "application/json", 400);
                    return;
                }

                String dimName = parts[4];
                int x = Integer.parseInt(parts[5]);
                int z = Integer.parseInt(parts[6]);

                for (ServerLevel level : minecraftServer.getAllLevels()) {
                    if (level.dimension().location().toString().equals(dimName)) {
                        // Using a simple JSON generator instead of Map/Jackson
                        var data = MapScanner.getChunkData(level, x, z);
                        StringBuilder json = new StringBuilder("{");
                        json.append("\"x\":").append(data.get("x")).append(",");
                        json.append("\"z\":").append(data.get("z")).append(",");
                        json.append("\"heights\":[");
                        int[] h = (int[]) data.get("heights");
                        for(int i=0; i<h.length; i++) {
                            json.append(h[i]).append(i < h.length-1 ? "," : "");
                        }
                        json.append("],\"blocks\":[");
                        String[] b = (String[]) data.get("blocks");
                        for(int i=0; i<b.length; i++) {
                            json.append("\"").append(b[i]).append("\"").append(i < b.length-1 ? "," : "");
                        }
                        json.append("]}");
                        sendResponse(exchange, json.toString(), "application/json");
                        return;
                    }
                }
                sendResponse(exchange, "{\"error\": \"Dimension not found\"}", "application/json", 404);
            });

            // Internal Web Server (Static Files)
            if (Config.enableInternalServer) {
                server.createContext("/", (exchange) -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.equals("/")) path = "/index.html";
                    
                    try (InputStream is = getClass().getResourceAsStream("/web" + path)) {
                        if (is == null) {
                            exchange.sendResponseHeaders(404, 0);
                            exchange.close();
                            return;
                        }
                        byte[] bytes = is.readAllBytes();
                        String contentType = "text/html";
                        if (path.endsWith(".css")) contentType = "text/css";
                        if (path.endsWith(".js")) contentType = "application/javascript";
                        
                        sendResponse(exchange, bytes, contentType);
                    }
                });
            }

            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();

            LOGGER.info("RealTimeMap Mod: Web Server started successfully on port {}", port);
        } catch (Exception e) {
            LOGGER.error("RealTimeMap Mod: Failed to start Web Server: ", e);
        }
    }

    private void sendResponse(HttpExchange exchange, String response, String contentType) throws IOException {
        sendResponse(exchange, response.getBytes(StandardCharsets.UTF_8), contentType, 200);
    }

    private void sendResponse(HttpExchange exchange, String response, String contentType, int code) throws IOException {
        sendResponse(exchange, response.getBytes(StandardCharsets.UTF_8), contentType, code);
    }

    private void sendResponse(HttpExchange exchange, byte[] bytes, String contentType, int code) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
        exchange.getResponseHeaders().add("Content-Type", contentType);

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(24, -1);
            return;
        }

        // Security Check
        if (exchange.getRequestURI().getPath().startsWith("/api/")) {
            String providedKey = exchange.getRequestHeaders().getFirst("Authorization");
            if (Config.apiKey != null && !Config.apiKey.isEmpty() && !"changeme".equals(Config.apiKey)) {
                if (providedKey == null || !providedKey.equals(Config.apiKey)) {
                    byte[] error = "Unauthorized: Invalid API Key".getBytes();
                    exchange.sendResponseHeaders(401, error.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(error); }
                    return;
                }
            }
        }

        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("RealTimeMap Mod: Server instance captured.");
        this.minecraftServer = event.getServer();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        this.minecraftServer = null;
        if (server != null) {
            server.stop(0);
        }
    }
}
