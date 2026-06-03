package com.alexanderxdi.realtimemap;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod(RealTimeMap.MODID)
public class RealTimeMap {
    public static final String MODID = "realtimemap";
    public static final Logger LOGGER = LogManager.getLogger();
    private Javalin server;
    private MinecraftServer minecraftServer;

    public RealTimeMap(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("RealTimeMap Mod is initializing!");

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        
        startWebServer();
    }

    private void startWebServer() {
        try {
            server = Javalin.create(config -> {
                config.showJavalinBanner = false;
                config.bundledPlugins.enableCors(cors -> {
                    cors.addRule(it -> it.anyHost());
                });
                // Hosting internal website from resources if enabled
                if (Config.enableInternalServer) {
                    config.staticFiles.add(staticFiles -> {
                        staticFiles.hostedPath = "/";
                        staticFiles.directory = "/web";
                        staticFiles.location = Location.CLASSPATH;
                    });
                }
            });

            // Security Middleware
            server.before(ctx -> {
                // Skip security for static files (the website itself)
                if (ctx.path().startsWith("/api/")) {
                    String providedKey = ctx.header("Authorization");
                    if (Config.apiKey != null && !Config.apiKey.isEmpty() && !"changeme".equals(Config.apiKey)) {
                        if (providedKey == null || !providedKey.equals(Config.apiKey)) {
                            ctx.status(401).result("Unauthorized: Invalid API Key");
                        }
                    }
                }
            });

            server.get("/api/status", ctx -> {
                ctx.result("{\"status\": \"online\", \"version\": \"0.0.1\"}");
            });

            // Players API
            server.get("/api/players", ctx -> {
                List<Map<String, Object>> players = new ArrayList<>();
                if (minecraftServer != null) {
                    for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("uuid", player.getUUID().toString());
                        data.put("name", player.getName().getString());
                        data.put("x", player.getX());
                        data.put("y", player.getY());
                        data.put("z", player.getZ());
                        data.put("dimension", player.level().dimension().location().toString());
                        data.put("yaw", player.getYRot());
                        players.add(data);
                    }
                }
                ctx.json(players);
            });

            // Map Data API
            server.get("/api/map/chunk/{dimension}/{x}/{z}", ctx -> {
                if (minecraftServer == null) {
                    ctx.status(503).result("Server not ready");
                    return;
                }

                String dimName = ctx.pathParam("dimension");
                int x = Integer.parseInt(ctx.pathParam("x"));
                int z = Integer.parseInt(ctx.pathParam("z"));

                for (ServerLevel level : minecraftServer.getAllLevels()) {
                    if (level.dimension().location().toString().equals(dimName)) {
                        ctx.json(MapScanner.getChunkData(level, x, z));
                        return;
                    }
                }
                ctx.status(404).result("Dimension not found");
            });

            int port = Config.port > 0 ? Config.port : 8080;
            server.start(port);

            LOGGER.info("RealTimeMap Web Server started on port {}", port);
        } catch (Exception e) {
            LOGGER.error("Failed to start RealTimeMap Web Server: ", e);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        this.minecraftServer = event.getServer();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        this.minecraftServer = null;
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");
    }
}
