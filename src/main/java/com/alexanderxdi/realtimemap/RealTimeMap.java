package com.alexanderxdi.realtimemap;

import io.javalin.Javalin;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(RealTimeMap.MODID)
public class RealTimeMap {
    public static final String MODID = "realtimemap";
    public static final Logger LOGGER = LogManager.getLogger();
    private Javalin server;

    public RealTimeMap(IEventBus modEventBus) {
        LOGGER.info("RealTimeMap Mod is initializing!");

        modEventBus.addListener(this::commonSetup);
        
        startWebServer();
    }

    private void startWebServer() {
        try {
            server = Javalin.create(config -> {
                config.showJavalinBanner = false;
                config.bundledPlugins.enableCors(cors -> {
                    cors.addRule(it -> it.anyHost());
                });
            }).start(8080);

            server.get("/api/status", ctx -> {
                ctx.result("{\"status\": \"online\", \"version\": \"0.0.1\"}");
            });

            LOGGER.info("RealTimeMap Web Server started on port 8080");
        } catch (Exception e) {
            LOGGER.error("Failed to start RealTimeMap Web Server: ", e);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");
    }
}
