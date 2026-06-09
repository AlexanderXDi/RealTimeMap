package com.alexanderxdi.realtimemap;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = RealTimeMap.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.IntValue PORT = BUILDER
            .comment("Port for the RealTimeMap web server")
            .defineInRange("port", 8080, 1024, 65535);

    public static final ModConfigSpec.IntValue MAX_RENDER_RADIUS = BUILDER
            .comment("Maximum chunk radius to load around players on the map")
            .defineInRange("max_render_radius", 8, 1, 32);

    private static final ModConfigSpec.ConfigValue<String> API_KEY = BUILDER
            .comment("API Key for accessing the map data (leave empty to disable security - NOT RECOMMENDED)")
            .define("api_key", "");

    private static final ModConfigSpec.BooleanValue ENABLE_INTERNAL_SERVER = BUILDER
            .comment("Enable the internal web server to host the website")
            .define("enable_internal_server", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static int port;
    public static int maxRenderRadius;
    public static String apiKey;
    public static boolean enableInternalServer;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        port = PORT.get();
        maxRenderRadius = MAX_RENDER_RADIUS.get();
        apiKey = API_KEY.get();
        enableInternalServer = ENABLE_INTERNAL_SERVER.get();
    }
}
