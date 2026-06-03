package com.alexanderxdi.realtimemap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
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
import java.util.stream.Collectors;

@Mod(RealTimeMap.MODID)
public class RealTimeMap {
    public static final String MODID = "realtimemap";
    public static final Logger LOGGER = LogManager.getLogger();
    private HttpServer server;
    private MinecraftServer minecraftServer;

    public RealTimeMap(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("RealTimeMap Mod is initializing (Zero-Dep mode)!");

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        
        startWebServer();
    }

    private void startWebServer() {
        try {
            int port = Config.port > 0 ? Config.port : 8080;
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

            LOGGER.info("RealTimeMap Web Server started on port {}", port);
        } catch (Exception e) {
            LOGGER.error("Failed to start RealTimeMap Web Server: ", e);
        }
    }

    private void sendResponse(HttpExchange exchange, String response, String contentType) throws IOException {
        sendResponse(exchange, response.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private void sendResponse(HttpExchange exchange, byte[] bytes, String contentType) throws IOException {
        // Add CORS headers
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
        exchange.getResponseHeaders().add("Content-Type", contentType);

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
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

        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        this.minecraftServer = event.getServer();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        this.minecraftServer = null;
        if (server != null) {
            server.stop(0);
        }
    }
}
