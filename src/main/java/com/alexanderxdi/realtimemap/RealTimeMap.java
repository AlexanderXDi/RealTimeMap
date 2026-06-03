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
import java.util.List;
import java.util.concurrent.Executors;

@Mod(RealTimeMap.MODID)
public class RealTimeMap {
    public static final String MODID = "realtimemap";
    public static final Logger LOGGER = LogManager.getLogger();
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
                sendResponse(exchange, "{\"status\": \"online\", \"version\": \"0.0.1\"}", "application/json", 200);
            });

            server.createContext("/api/players", (exchange) -> {
                StringBuilder json = new StringBuilder("[");
                MinecraftServer ms = minecraftServer;
                if (ms != null) {
                    List<ServerPlayer> players = ms.getPlayerList().getPlayers();
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
                sendResponse(exchange, json.toString(), "application/json", 200);
            });

            server.createContext("/api/map/chunk", (exchange) -> {
                try {
                    MinecraftServer ms = minecraftServer;
                    if (ms == null) {
                        sendResponse(exchange, "{\"error\": \"World not loaded\"}", "application/json", 503);
                        return;
                    }
                    String path = exchange.getRequestURI().getPath();
                    String[] parts = path.split("/");
                    if (parts.length < 6) {
                        sendResponse(exchange, "{\"error\": \"Invalid path\"}", "application/json", 400);
                        return;
                    }
                    String dimName = parts[4];
                    int x = Integer.parseInt(parts[5]);
                    int z = Integer.parseInt(parts[6]);

                    for (ServerLevel level : ms.getAllLevels()) {
                        if (level.dimension().location().toString().equals(dimName)) {
                            var data = MapScanner.getChunkData(level, x, z);
                            StringBuilder json = new StringBuilder("{");
                            json.append("\"x\":").append(data.get("x")).append(",");
                            json.append("\"z\":").append(data.get("z")).append(",");
                            json.append("\"topY\":[");
                            int[] tY = (int[]) data.get("topY");
                            for(int i=0; i<tY.length; i++) {
                                json.append(tY[i]).append(i < tY.length-1 ? "," : "");
                            }
                            json.append("],\"columns\":[");
                            List<List<String>> columns = (List<List<String>>) data.get("columns");
                            for(int i=0; i<columns.size(); i++) {
                                json.append("[");
                                List<String> col = columns.get(i);
                                for(int j=0; j<col.size(); j++) {
                                    json.append("\"").append(col.get(j)).append("\"").append(j < col.size()-1 ? "," : "");
                                }
                                json.append("]").append(i < columns.size()-1 ? "," : "");
                            }
                            json.append("]}");
                            sendResponse(exchange, json.toString(), "application/json", 200);
                            return;
                        }
                    }
                    sendResponse(exchange, "{\"error\": \"Dimension not found\"}", "application/json", 404);
                } catch (Exception e) {
                    sendResponse(exchange, "{\"error\": \"Internal server error\"}", "application/json", 500);
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

            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            LOGGER.info("RealTimeMap Web Server started on port {}", port);
        } catch (Exception e) {
            LOGGER.error("Failed to start Web Server: ", e);
        }
    }

    private void sendResponse(HttpExchange exchange, String response, String contentType, int code) throws IOException {
        // Force UTF-8 conversion for the response body
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        sendResponse(exchange, bytes, contentType, code);
    }

    private void sendResponse(HttpExchange exchange, byte[] bytes, String contentType, int code) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS, POST");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
        
        if (!contentType.contains("charset=")) {
            contentType += "; charset=UTF-8";
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        minecraftServer = event.getServer();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        minecraftServer = null;
    }
}
