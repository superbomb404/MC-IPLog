package ljsure.cn;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;

public class IPLog extends JavaPlugin implements Listener {

    private FileConfiguration config;
    private StorageHandler storageHandler;

    private static final String API_URL = "https://api.ipplus360.com/ip/geo/v1/street/biz/";

    @Override
    public void onEnable() {
        // 注册可序列化类
        ConfigurationSerialization.registerClass(PlayerData.class, "PlayerData");
        ConfigurationSerialization.registerClass(IPRecord.class, "IPRecord");

        saveDefaultConfig();
        config = getConfig();

        // 初始化存储系统
        if (!initializeStorage()) {
            getLogger().severe("存储系统初始化失败，插件将禁用");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("iplog")).setExecutor(new IPLogCommand(this));
        getLogger().info("IPLog插件已启用！存储类型: " + config.getString("storage.type", "yaml"));
    }

    @Override
    public void onDisable() {
        if (storageHandler != null) {
            storageHandler.shutdown();
        }
        getLogger().info("IPLog插件已禁用！");
    }

    private boolean initializeStorage() {
        String storageType = config.getString("storage.type", "yaml").toLowerCase();

        try {
            switch (storageType) {
                case "mysql":
                    storageHandler = new MySQLStorageHandler(this);
                    break;
                case "yaml":
                default:
                    storageHandler = new YamlStorageHandler(this);
                    break;
            }

            storageHandler.initialize();
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "初始化存储系统失败: " + e.getMessage(), e);
            return false;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!config.getBoolean("features.auto-log-on-join", true)) {
            return;
        }

        Player player = event.getPlayer();
        String ip = Objects.requireNonNull(player.getAddress()).getAddress().getHostAddress();
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                logPlayerIP(uuid, playerName, ip);
            } catch (Exception e) {
                getLogger().warning("记录玩家IP时出错: " + e.getMessage());
            }
        });
    }

    private void logPlayerIP(UUID uuid, String playerName, String ip) {
        PlayerData playerData = storageHandler.loadPlayerData(uuid);
        if (playerData == null) {
            playerData = new PlayerData(uuid.toString(), playerName);
        } else {
            playerData.setName(playerName); // 更新名字
        }

        playerData.setCurrentIP(ip);
        playerData.setLastSeen(getCurrentTimestamp());

        // 检查是否已有此IP记录
        boolean ipAlreadyRecorded = false;
        if (config.getBoolean("features.check-duplicate-ip", true)) {
            IPRecord existingRecord = playerData.findExistingRecord(ip);
            if (existingRecord != null) {
                // 更新现有记录
                existingRecord.setLastSeen(getCurrentTimestamp());
                ipAlreadyRecorded = true;
                getLogger().info("[DEBUG] IP " + ip + " 已存在记录中，跳过API查询");
            }
        }

        // 如果没有记录过此IP，或者需要强制查询，则查询IP信息
        if (!ipAlreadyRecorded) {
            // 创建新记录
            IPRecord newRecord = new IPRecord(ip, getCurrentTimestamp(), getCurrentTimestamp());

            // 查询IP信息
            if (config.getBoolean("features.query-ip-location", true)) {
                Map<String, String> ipInfo = queryIPInfo(ip);
                if (ipInfo != null) {
                    newRecord.setLocation(ipInfo.get("location"));
                    newRecord.setIsp(ipInfo.get("isp"));
                    playerData.setCurrentLocation(ipInfo.get("location"));
                    playerData.setCurrentISP(ipInfo.get("isp"));

                    getLogger().info("[DEBUG] 成功查询IP信息: " + ip + " -> " + ipInfo.get("location"));
                } else {
                    getLogger().warning("查询IP信息失败: " + ip);
                }
            }

            playerData.addIPRecord(newRecord);

            // 限制历史记录数量
            int maxSize = config.getInt("data.max-history-size", 100);
            if (playerData.getIpHistory().size() > maxSize) {
                List<IPRecord> history = playerData.getIpHistory();
                playerData.getIpHistory().subList(maxSize, history.size()).clear();
            }
        }

        // 保存玩家数据
        storageHandler.savePlayerData(playerData);
        getLogger().info("[DEBUG] 玩家 " + playerName + " 的IP记录已保存");
    }

    public PlayerData getPlayerData(UUID uuid) {
        return storageHandler.loadPlayerData(uuid);
    }

    public PlayerData findPlayerDataByName(String playerName) {
        return storageHandler.findPlayerDataByName(playerName);
    }

    private Map<String, String> queryIPInfo(String ip) {
        String apiKey = config.getString("api.key");
        String apiUrl = config.getString("api.url", API_URL);

        // 调试信息：显示API配置
        getLogger().info("[DEBUG] 开始查询IP信息: " + ip);
        getLogger().info("[DEBUG] API URL: " + apiUrl);
        getLogger().info("[DEBUG] API Key: " + (apiKey != null ?
                apiKey.substring(0, Math.min(5, apiKey.length())) + "..." : "null"));

        try {
            // 构建完整的请求URL
            String fullUrl = apiUrl + "?key=" + apiKey + "&ip=" + ip + "&coordsys=WGS84&area=multi";
            getLogger().info("[DEBUG] 完整请求URL: " + fullUrl.replace(apiKey, "***")); // 隐藏完整API密钥

            URL url = new URL(fullUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(config.getInt("api.timeout", 5000));
            connection.setReadTimeout(config.getInt("api.timeout", 5000));

            // 添加请求头信息
            connection.setRequestProperty("User-Agent", "Minecraft-IPLog-Plugin/1.0");
            connection.setRequestProperty("Accept", "application/json");

            getLogger().info("[DEBUG] 发送HTTP请求...");

            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            getLogger().info("[DEBUG] HTTP响应码: " + responseCode);
            getLogger().info("[DEBUG] HTTP响应消息: " + responseMessage);

            if (responseCode == 200) {
                // 成功响应
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String responseBody = response.toString();
                getLogger().info("[DEBUG] API响应体: " + responseBody);

                return parseAPIResponse(responseBody);
            } else {
                // 错误响应 - 读取错误流
                getLogger().warning("[ERROR] API返回错误代码: " + responseCode);

                try {
                    InputStream errorStream = connection.getErrorStream();
                    if (errorStream != null) {
                        BufferedReader errorReader = new BufferedReader(
                                new InputStreamReader(errorStream, "UTF-8"));
                        StringBuilder errorResponse = new StringBuilder();
                        String errorLine;
                        while ((errorLine = errorReader.readLine()) != null) {
                            errorResponse.append(errorLine);
                        }
                        errorReader.close();

                        String errorBody = errorResponse.toString();
                        getLogger().warning("[ERROR] API错误响应体: " + errorBody);
                    } else {
                        getLogger().warning("[ERROR] 无错误响应体");
                    }
                } catch (Exception e) {
                    getLogger().warning("[ERROR] 读取错误流时出错: " + e.getMessage());
                }

                // 根据不同的HTTP状态码提供具体的错误信息
                switch (responseCode) {
                    case 400:
                        getLogger().warning("[ERROR] 400 Bad Request - 请求参数错误");
                        break;
                    case 401:
                        getLogger().warning("[ERROR] 401 Unauthorized - 认证失败");
                        break;
                    case 403:
                        getLogger().warning("[ERROR] 403 Forbidden - 访问被拒绝，可能的原因:");
                        getLogger().warning("[ERROR]   - API密钥无效或已过期");
                        getLogger().warning("[ERROR]   - 账户余额不足");
                        getLogger().warning("[ERROR]   - IP地址不在白名单中");
                        getLogger().warning("[ERROR]   - 请求频率超限");
                        break;
                    case 404:
                        getLogger().warning("[ERROR] 404 Not Found - API端点不存在");
                        break;
                    case 429:
                        getLogger().warning("[ERROR] 429 Too Many Requests - 请求频率超限");
                        break;
                    case 500:
                        getLogger().warning("[ERROR] 500 Internal Server Error - 服务器内部错误");
                        break;
                    case 503:
                        getLogger().warning("[ERROR] 503 Service Unavailable - 服务不可用");
                        break;
                    default:
                        getLogger().warning("[ERROR] 未知HTTP错误: " + responseCode);
                        break;
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            getLogger().warning("[ERROR] 连接API超时: " + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            getLogger().warning("[ERROR] 无法解析API主机名: " + e.getMessage());
        } catch (IOException e) {
            getLogger().warning("[ERROR] 网络IO错误: " + e.getMessage());
        } catch (Exception e) {
            getLogger().warning("[ERROR] 查询IP信息时发生未知错误: " + e.getMessage());
            e.printStackTrace();
        }

        return null;
    }

    private Map<String, String> parseAPIResponse(String response) {
        Map<String, String> ipInfo = new HashMap<>();
        try {
            getLogger().info("[DEBUG] 开始解析API响应");

            // 检查响应是否包含错误信息
            if (response.contains("\"code\"") && !response.contains("\"code\":\"Success\"")) {
                // 提取错误代码和消息
                int codeStart = response.indexOf("\"code\":\"") + 8;
                int codeEnd = response.indexOf("\"", codeStart);
                if (codeStart > 7 && codeEnd > codeStart) {
                    String errorCode = response.substring(codeStart, codeEnd);
                    getLogger().warning("[ERROR] API业务错误代码: " + errorCode);
                }

                int msgStart = response.indexOf("\"msg\":\"") + 7;
                int msgEnd = response.indexOf("\"", msgStart);
                if (msgStart > 6 && msgEnd > msgStart) {
                    String errorMsg = response.substring(msgStart, msgEnd);
                    getLogger().warning("[ERROR] API业务错误消息: " + errorMsg);
                }

                return null;
            }

            // 解析成功响应
            if (response.contains("\"country\"")) {
                int countryStart = response.indexOf("\"country\":\"") + 11;
                int countryEnd = response.indexOf("\"", countryStart);
                if (countryStart > 10 && countryEnd > countryStart) {
                    String country = response.substring(countryStart, countryEnd);
                    ipInfo.put("location", country);
                    getLogger().info("[DEBUG] 解析到国家: " + country);
                }
            }

            if (response.contains("\"prov\"")) {
                int provStart = response.indexOf("\"prov\":\"") + 8;
                int provEnd = response.indexOf("\"", provStart);
                if (provStart > 7 && provEnd > provStart) {
                    String province = response.substring(provStart, provEnd);
                    String currentLocation = ipInfo.getOrDefault("location", "");
                    ipInfo.put("location", currentLocation + " " + province);
                    getLogger().info("[DEBUG] 解析到省份: " + province);
                }
            }

            if (response.contains("\"city\"")) {
                int cityStart = response.indexOf("\"city\":\"") + 8;
                int cityEnd = response.indexOf("\"", cityStart);
                if (cityStart > 7 && cityEnd > cityStart) {
                    String city = response.substring(cityStart, cityEnd);
                    String currentLocation = ipInfo.getOrDefault("location", "");
                    ipInfo.put("location", currentLocation + " " + city);
                    getLogger().info("[DEBUG] 解析到城市: " + city);
                }
            }

            if (response.contains("\"isp\"")) {
                int ispStart = response.indexOf("\"isp\":\"") + 7;
                int ispEnd = response.indexOf("\"", ispStart);
                if (ispStart > 6 && ispEnd > ispStart) {
                    String isp = response.substring(ispStart, ispEnd);
                    ipInfo.put("isp", isp);
                    getLogger().info("[DEBUG] 解析到ISP: " + isp);
                }
            }

            getLogger().info("[DEBUG] API响应解析完成，获取到 " + ipInfo.size() + " 个字段");

        } catch (Exception e) {
            getLogger().warning("[ERROR] 解析API响应时出错: " + e.getMessage());
            e.printStackTrace();
        }
        return ipInfo;
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(new Date());
    }

    public FileConfiguration getPluginConfig() {
        return config;
    }

    public StorageHandler getStorageHandler() {
        return storageHandler;
    }
}