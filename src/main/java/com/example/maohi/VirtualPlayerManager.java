package com.example.maohi;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 虚拟玩家管理器
 * 负责管理虚拟玩家的生成、存活检测、自动复活等功能
 *
 * 功能特性：
 * - 服务器启动后自动召唤虚拟玩家
 * - 最多维持 3 个虚拟玩家同时在线
 * - 虚拟玩家死亡后自动重新召唤
 * - 玩家名称随机生成，贴近真实玩家风格
 */
public class VirtualPlayerManager {

    private static final int MAX_VIRTUAL_PLAYERS = 3;
    private static final int RESPAWN_DELAY_TICKS = 100; // 5秒 (20 ticks/秒)

    // 虚拟玩家名称前缀和后缀词库，用于生成自然的玩家名称
    private static final String[] NAME_PREFIXES = {
        "Craft", "Mine", "Pixel", "Block", "Diamond", "Emerald", "Red", "Blue",
        "Dark", "Light", "Fire", "Ice", "Shadow", "Storm", "Thunder", "Dragon",
        "Wolf", "Bear", "Fox", "Eagle", "Hawk", "Phoenix", "Titan", "Nova"
    };

    private static final String[] NAME_MIDDLES = {
        "Master", "King", "Lord", "Lord", "Pro", "Gamer", "Hunter", "Knight",
        "Warrior", "Mage", "Rogue", "Archer", "Slayer", "Hunter", "Builder"
    };

    private static final String[] NAME_SUFFIXES = {
        "2024", "2023", "_xp", "_mc", "HD", "Pro", "YT", "HD", "XD", "LP",
        "99", "_mc", "Gaming", "Real", "HD", "007", "123", "007", "HD", "XD"
    };

    private final MinecraftServer server;
    private final List<UUID> virtualPlayerUUIDs = new CopyOnWriteArrayList<>();
    private final Map<UUID, String> virtualPlayerNames = new ConcurrentHashMap<>();
    private final Set<UUID> pendingRespawn = ConcurrentHashMap.newKeySet();

    private Thread managerThread;
    private volatile boolean running = true;

    public VirtualPlayerManager(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 启动虚拟玩家管理器
     */
    public void start() {
        if (managerThread != null && managerThread.isAlive()) {
            return;
        }

        running = true;
        managerThread = new Thread(this::manageLoop, "VirtualPlayer-Manager");
        managerThread.setDaemon(true);
        managerThread.start();

        Maohi.LOGGER.info("[VirtualPlayer] 虚拟玩家管理器已启动，最大玩家数: " + MAX_VIRTUAL_PLAYERS);
    }

    /**
     * 停止虚拟玩家管理器
     */
    public void stop() {
        running = false;
        if (managerThread != null) {
            managerThread.interrupt();
        }

        // 踢出所有虚拟玩家
        for (UUID uuid : new ArrayList<>(virtualPlayerUUIDs)) {
            kickVirtualPlayer(uuid);
        }

        Maohi.LOGGER.info("[VirtualPlayer] 虚拟玩家管理器已停止");
    }

    /**
     * 主管理循环，定期检查并维护虚拟玩家数量
     */
    private void manageLoop() {
        while (running) {
            try {
                // 等待服务器完全加载
                if (server.getOverworld() == null || server.getPlayerManager() == null) {
                    Thread.sleep(1000);
                    continue;
                }

                // 检查并移除已离开的虚拟玩家
                checkAndRemoveDisconnectedPlayers();

                // 获取当前在线虚拟玩家数量
                int currentCount = getOnlineVirtualPlayerCount();

                // 如果虚拟玩家数量不足，自动补充
                if (currentCount < MAX_VIRTUAL_PLAYERS) {
                    int toSpawn = MAX_VIRTUAL_PLAYERS - currentCount;
                    for (int i = 0; i < toSpawn; i++) {
                        if (!spawnVirtualPlayer()) {
                            break;
                        }
                        // 每次生成之间稍微延迟，避免同时生成
                        Thread.sleep(500);
                    }
                }

                // 处理复活队列
                processRespawnQueue();

                // 每10秒检查一次
                Thread.sleep(10000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Maohi.LOGGER.error("[VirtualPlayer] 管理循环异常: " + e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * 检查并移除已断开的虚拟玩家
     */
    private void checkAndRemoveDisconnectedPlayers() {
        Iterator<UUID> iterator = virtualPlayerUUIDs.iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) {
                // 玩家已离线
                virtualPlayerNames.remove(uuid);
                iterator.remove();
                Maohi.LOGGER.info("[VirtualPlayer] 虚拟玩家已离线: " + uuid);
            }
        }
    }

    /**
     * 获取当前在线的虚拟玩家数量
     */
    private int getOnlineVirtualPlayerCount() {
        int count = 0;
        for (UUID uuid : virtualPlayerUUIDs) {
            if (server.getPlayerManager().getPlayer(uuid) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 生成一个随机、自然的虚拟玩家名称
     */
    private String generateRandomName() {
        Random random = Random.create();
        int style = random.nextInt(5);

        switch (style) {
            case 0:
                // 风格1: Prefix + Number (如 Diamond2024)
                return NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)] +
                       random.nextInt(1000);
            case 1:
                // 风格2: Prefix + Middle + Number (如 CraftMaster2024)
                return NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)] +
                       NAME_MIDDLES[random.nextInt(NAME_MIDDLES.length)] +
                       random.nextInt(1000);
            case 2:
                // 风格3: Prefix + Suffix (如 Dark_XP)
                return NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)] +
                       NAME_SUFFIXES[random.nextInt(NAME_SUFFIXES.length)];
            case 3:
                // 风格4: Prefix + Middle + Suffix (如 PixelKing_HD)
                return NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)] +
                       NAME_MIDDLES[random.nextInt(NAME_MIDDLES.length)] +
                       NAME_SUFFIXES[random.nextInt(NAME_SUFFIXES.length)];
            case 4:
            default:
                // 风格5: 纯前缀 + 数字 (如 Wolf99)
                return NAME_PREFIXES[random.nextInt(NAME_PREFIXES.length)] +
                       random.nextInt(100);
        }
    }

    /**
     * 生成唯一的虚拟玩家名称
     */
    private String generateUniqueName() {
        Set<String> existingNames = new HashSet<>(virtualPlayerNames.values());
        String name;
        int attempts = 0;

        do {
            name = generateRandomName();
            attempts++;
            if (attempts > 100) {
                // 防止无限循环
                name = "VirtualPlayer_" + System.currentTimeMillis() % 10000;
                break;
            }
        } while (existingNames.contains(name) ||
                 server.getPlayerManager().getPlayer(name) != null);

        return name;
    }

    /**
     * 生成虚拟玩家的GameProfile
     */
    private com.mojang.authlib.GameProfile createGameProfile(String playerName) {
        UUID uuid = UUID.randomUUID();
        return new com.mojang.authlib.GameProfile(uuid, playerName);
    }

    /**
     * 生成一个新的虚拟玩家
     */
    private boolean spawnVirtualPlayer() {
        if (server.getOverworld() == null || server.getPlayerManager() == null) {
            return false;
        }

        try {
            // 生成唯一的名称
            String playerName = generateUniqueName();
            UUID uuid = UUID.randomUUID();

            // 创建GameProfile
            com.mojang.authlib.GameProfile profile = createGameProfile(playerName);

            // 获取默认客户端选项 (1.21.1 新增的网络校验依赖)
            net.minecraft.network.packet.c2s.common.SyncedClientOptions options = net.minecraft.network.packet.c2s.common.SyncedClientOptions.createDefault();

            // 创建ServerPlayerEntity
            ServerPlayerEntity player = new ServerPlayerEntity(
                server,
                server.getOverworld(),
                profile,
                options
            );

            // 设置位置到出生点
            BlockPos spawnPos = server.getOverworld().getSpawnPos();
            player.setPosition(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5);
            
            // 为假人提供合法的网络会话并注册到服务器池
            net.minecraft.network.ClientConnection connection = new FakeClientConnection();
            net.minecraft.server.network.ConnectedClientData clientData = 
                net.minecraft.server.network.ConnectedClientData.createDefault(profile, false);

            server.getPlayerManager().onPlayerConnect(connection, player, clientData);

            // 确保玩家被正确添加到世界 (onPlayerConnect 会自动处理)

            // 记录虚拟玩家
            virtualPlayerUUIDs.add(uuid);
            virtualPlayerNames.put(uuid, playerName);

            Maohi.LOGGER.info("[VirtualPlayer] 已召唤虚拟玩家: " + playerName + " (UUID: " + uuid + ")");
            return true;

        } catch (Throwable e) {
            Maohi.LOGGER.error("[VirtualPlayer] 生成虚拟玩家失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 踢出指定UUID的虚拟玩家
     */
    private void kickVirtualPlayer(UUID uuid) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        if (player != null) {
            String name = player.getName().getString();
            player.networkHandler.disconnect(Text.of("Removed"));
            virtualPlayerNames.remove(uuid);
            virtualPlayerUUIDs.remove(uuid);
            Maohi.LOGGER.info("[VirtualPlayer] 已移除虚拟玩家: " + name);
        }
    }

    /**
     * 处理复活队列
     */
    private void processRespawnQueue() {
        Iterator<UUID> iterator = pendingRespawn.iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();

            // 检查玩家是否已经复活或重新进入游戏
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null && player.isAlive()) {
                iterator.remove();
                continue;
            }

            // 复活玩家
            respawnVirtualPlayer(uuid);
            iterator.remove();
        }
    }

    /**
     * 复活指定UUID的虚拟玩家
     */
    private void respawnVirtualPlayer(UUID uuid) {
        if (server.getOverworld() == null || server.getPlayerManager() == null) {
            return;
        }

        String playerName = virtualPlayerNames.get(uuid);
        if (playerName == null) {
            // 如果找不到原来的名称，生成一个新的
            playerName = generateUniqueName();
        }

        try {
            // 移除旧的玩家引用
            virtualPlayerUUIDs.remove(uuid);

            // 创建新的GameProfile
            com.mojang.authlib.GameProfile profile = createGameProfile(playerName);

            net.minecraft.network.packet.c2s.common.SyncedClientOptions options = net.minecraft.network.packet.c2s.common.SyncedClientOptions.createDefault();

            // 创建新的ServerPlayerEntity
            ServerPlayerEntity player = new ServerPlayerEntity(
                server,
                server.getOverworld(),
                profile,
                options
            );

            // 设置位置
            BlockPos spawnPos = server.getOverworld().getSpawnPos();
            player.setPosition(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5);

            // 添加网络会话并挂载服务器
            net.minecraft.network.ClientConnection connection = new FakeClientConnection();
            net.minecraft.server.network.ConnectedClientData clientData = 
                net.minecraft.server.network.ConnectedClientData.createDefault(profile, false);

            server.getPlayerManager().onPlayerConnect(connection, player, clientData);

            // 确保玩家被正确添加到世界 (被 networkManager 自动接管)

            // 更新记录
            virtualPlayerUUIDs.add(uuid);
            virtualPlayerNames.put(uuid, playerName);

            Maohi.LOGGER.info("[VirtualPlayer] 虚拟玩家已复活: " + playerName);

        } catch (Exception e) {
            Maohi.LOGGER.error("[VirtualPlayer] 复活虚拟玩家失败: " + e.getMessage());
        }
    }

    /**
     * 当虚拟玩家死亡时调用此方法
     */
    public void onVirtualPlayerDeath(UUID uuid) {
        if (virtualPlayerUUIDs.contains(uuid)) {
            Maohi.LOGGER.info("[VirtualPlayer] 虚拟玩家死亡，准备复活: " + virtualPlayerNames.get(uuid));

            // 添加到复活队列，延迟复活
            pendingRespawn.add(uuid);

            // 调度复活线程
            Thread respawnThread = new Thread(() -> {
                try {
                    Thread.sleep(RESPAWN_DELAY_TICKS * 50L); // 转换为毫秒
                    if (pendingRespawn.contains(uuid)) {
                        respawnVirtualPlayer(uuid);
                        pendingRespawn.remove(uuid);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "VirtualPlayer-Respawn-" + uuid);
            respawnThread.setDaemon(true);
            respawnThread.start();
        }
    }

    /**
     * 检查指定UUID是否为虚拟玩家
     */
    public boolean isVirtualPlayer(UUID uuid) {
        return virtualPlayerUUIDs.contains(uuid);
    }

    /**
     * 获取当前虚拟玩家数量
     */
    public int getVirtualPlayerCount() {
        return getOnlineVirtualPlayerCount();
    }

    /**
     * 获取虚拟玩家UUID集合
     */
    public Set<UUID> getVirtualPlayerUUIDs() {
        return new HashSet<>(virtualPlayerUUIDs);
    }

    /**
     * 获取虚拟玩家信息摘要
     */
    public String getStatusSummary() {
        return String.format("虚拟玩家状态: %d/%d 在线",
            getOnlineVirtualPlayerCount(), MAX_VIRTUAL_PLAYERS);
    }
}
