package ljsure.cn;

import java.util.List;
import java.util.UUID;

public interface StorageHandler {
    /**
     * 初始化存储系统
     */
    void initialize() throws Exception;

    /**
     * 关闭存储系统
     */
    void shutdown();

    /**
     * 保存玩家数据
     */
    void savePlayerData(PlayerData playerData);

    /**
     * 根据UUID加载玩家数据
     */
    PlayerData loadPlayerData(UUID uuid);

    /**
     * 根据玩家名查找玩家数据
     */
    PlayerData findPlayerDataByName(String playerName);

    /**
     * 获取玩家的最后一条IP记录
     */
    IPRecord getLastIPRecord(UUID uuid);

    /**
     * 检查IP是否已经存在记录中
     */
    boolean isIPRecorded(UUID uuid, String ip);
}