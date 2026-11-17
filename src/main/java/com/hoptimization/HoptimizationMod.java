package com.hoptimization;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = HoptimizationMod.MODID,
    name = HoptimizationMod.NAME,
    version = HoptimizationMod.VERSION,
    clientSideOnly = true,
    useMetadata = true
)
public class HoptimizationMod {
    public static final String MODID = "hoptimization";
    public static final String NAME = "Hoptimization";
    public static final String VERSION = "0.1.0";
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("Pre-initializing {} {}", NAME, VERSION);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("{} initialized.", NAME);
    }
}
