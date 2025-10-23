package ljsure.cn;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class YamlStorageHandler implements StorageHandler {
    private final JavaPlugin plugin;
    private FileConfiguration dataConfig;
    private File dataFile;

    public YamlStorageHandler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void initialize() throws Exception {
        String dataFileName = plugin.getConfig().getString("storage.data-file", "data.yml");
        dataFile = new File(plugin.getDataFolder(), dataFileName);

        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "创建数据文件失败: " + e.getMessage(), e);
                throw e;
            }
        }

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        plugin.getLogger().info("YAML存储系统已初始化");
    }

    @Override
    public void shutdown() {
        // YAML存储不需要特殊关闭操作
    }

    @Override
    public void savePlayerData(PlayerData playerData) {
        String playerPath = "players." + playerData.getUuid();
        dataConfig.set(playerPath, playerData.serialize());
        saveDataConfig();
    }

    @Override
    public PlayerData loadPlayerData(UUID uuid) {
        String playerPath = "players." + uuid.toString();
        if (!dataConfig.contains(playerPath)) {
            return null;
        }

        Object playerObj = dataConfig.get(playerPath);
        if (playerObj instanceof PlayerData) {
            return (PlayerData) playerObj;
        } else if (playerObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> playerMap = (Map<String, Object>) playerObj;
            return PlayerData.fromMap(uuid.toString(), playerMap);
        }

        return null;
    }

    @Override
    public PlayerData findPlayerDataByName(String playerName) {
        if (!dataConfig.contains("players")) {
            return null;
        }

        for (String uuid : dataConfig.getConfigurationSection("players").getKeys(false)) {
            PlayerData data = loadPlayerData(UUID.fromString(uuid));
            if (data != null && playerName.equalsIgnoreCase(data.getName())) {
                return data;
            }
        }
        return null;
    }

    @Override
    public IPRecord getLastIPRecord(UUID uuid) {
        PlayerData playerData = loadPlayerData(uuid);
        if (playerData != null && !playerData.getIpHistory().isEmpty()) {
            return playerData.getIpHistory().get(0); // 第一条是最新的
        }
        return null;
    }

    @Override
    public boolean isIPRecorded(UUID uuid, String ip) {
        IPRecord lastRecord = getLastIPRecord(uuid);
        return lastRecord != null && ip.equals(lastRecord.getIp());
    }

    private void saveDataConfig() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "保存数据文件时出错: " + e.getMessage(), e);
        }
    }
}