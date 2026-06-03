package com.alexanderxdi.realtimemap;

import io.javalin.Javalin;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(RealTimeMap.MODID)
public class RealTimeMap {
    public static final String MODID = "realtimemap";
    public static final Logger LOGGER = LogManager.getLogger();
    private Javalin server;

    public RealTimeMap(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("RealTimeMap Mod is initializing!");

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        modEventBus.addListener(this::commonSetup);
        
        // Start server after config is potentially loaded
        startWebServer();
    }

    private void startWebServer() {
        try {
            server = Javalin.create(config -> {
                config.showJavalinBanner = false;
                config.bundledPlugins.enableCors(cors -> {
                    cors.addRule(it -> it.anyHost());
                });
            });

            // Security Middleware
            server.before(ctx -> {
                String providedKey = ctx.header("Authorization");
                if (Config.apiKey != null && !Config.apiKey.isEmpty() && !"changeme".equals(Config.apiKey)) {
                    if (providedKey == null || !providedKey.equals(Config.apiKey)) {
                        ctx.status(401).result("Unauthorized: Invalid API Key");
                    }
                }
            });

            server.get("/api/status", ctx -> {
                ctx.result("{\"status\": \"online\", \"version\": \"0.0.1\"}");
            });

            int port = Config.port > 0 ? Config.port : 8080;
            server.start(port);

            LOGGER.info("RealTimeMap Web Server started on port {}", port);
        } catch (Exception e) {
            LOGGER.error("Failed to start RealTimeMap Web Server: ", e);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");
    }
}
