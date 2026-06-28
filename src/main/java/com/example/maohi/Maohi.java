package com.example.maohi;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Maohi 核心类，实现 Fabric Mod 初始化接口
 * <p>
 * 该 Mod 提供虚拟玩家（假人）功能：服务器在没有真实玩家在线时，
 * 由 {@link VirtualPlayerManager} 模拟若干虚拟玩家连接，使服务器的玩家列表保持活跃。
 */
public class Maohi implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Maohi");

    /** 虚拟玩家管理器实例，由服务器启动回调构造 */
    private static VirtualPlayerManager virtualPlayerManager;

    @Override
    public void onInitialize() {
        // 注册服务器生命周期事件：启动时初始化虚拟玩家管理器，关闭时停止它
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // 注册服务器 Tick 事件，用于检测虚拟玩家死亡并自动补位
        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);
    }

    /** 服务器启动完成回调 */
    private void onServerStarted(MinecraftServer server) {
        virtualPlayerManager = new VirtualPlayerManager(server);
        virtualPlayerManager.start();
        LOGGER.info("[Maohi] 虚拟玩家管理器已启动");
    }

    /** 服务器关闭回调 */
    private void onServerStopping(MinecraftServer server) {
        if (virtualPlayerManager != null) {
            virtualPlayerManager.stop();
            virtualPlayerManager = null;
            LOGGER.info("[Maohi] 虚拟玩家管理器已停止");
        }
    }

    /**
     * 服务器 Tick 事件：遍历所有虚拟玩家，若发现死亡或已移除的对象则触发补位逻辑
     */
    private void onServerTick(MinecraftServer server) {
        if (virtualPlayerManager == null) {
            return;
        }

        for (UUID uuid : new ArrayList<>(virtualPlayerManager.getVirtualPlayerUUIDs())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null && (!player.isAlive() || player.isRemoved())) {
                virtualPlayerManager.onVirtualPlayerDeath(uuid);
            }
        }
    }
}
