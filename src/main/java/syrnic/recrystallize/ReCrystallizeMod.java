package syrnic.recrystallize;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(ReCrystallizeMod.MODID)
public class ReCrystallizeMod {
    public static final String MODID = "recrystallize";
    public static final Logger LOGGER = LogManager.getLogger();

    public ReCrystallizeMod(IEventBus modEventBus) {
        LOGGER.info("ReCrystallize Mod is initializing!");

        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");
    }
}
