package ljsure.cn;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.UUID;
import java.util.logging.Level;

public class MySQLStorageHandler implements StorageHandler {
    private final JavaPlugin plugin;
    private Connection connection;
    private String tablePrefix;
    private SimpleDateFormat dateFormat;

    public MySQLStorageHandler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        this.dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @Override
    public void initialize() throws Exception {
        String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
        int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
        String database = plugin.getConfig().getString("storage.mysql.database", "minecraft");
        String username = plugin.getConfig().getString("storage.mysql.username", "minecraft");
        String password = plugin.getConfig().getString("storage.mysql.password", "password");
        tablePrefix = plugin.getConfig().getString("storage.mysql.table-prefix", "iplog_");
        boolean ssl = plugin.getConfig().getBoolean("storage.mysql.ssl", false);

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=" + ssl + "&useUnicode=true&characterEncoding=UTF-8";

        plugin.getLogger().info("连接MySQL数据库: " + host + ":" + port + "/" + database);

        try {
            connection = DriverManager.getConnection(url, username, password);
            createTables();
            plugin.getLogger().info("MySQL存储系统已初始化");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "连接MySQL数据库失败: " + e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void shutdown() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("MySQL连接已关闭");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "关闭MySQL连接时出错: " + e.getMessage(), e);
            }
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // 创建玩家表
            String playersTable = tablePrefix + "players";
            String createPlayersTable = "CREATE TABLE IF NOT EXISTS " + playersTable + " (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "name VARCHAR(16) NOT NULL, " +
                    "current_ip VARCHAR(45), " +
                    "current_location VARCHAR(100), " +
                    "current_isp VARCHAR(100), " +
                    "last_seen DATETIME, " +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "INDEX idx_name (name)" +
                    ")";
            stmt.execute(createPlayersTable);

            // 创建IP历史表
            String ipHistoryTable = tablePrefix + "ip_history";
            String createIPHistoryTable = "CREATE TABLE IF NOT EXISTS " + ipHistoryTable + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_uuid VARCHAR(36) NOT NULL, " +
                    "ip VARCHAR(45) NOT NULL, " +
                    "location VARCHAR(100), " +
                    "isp VARCHAR(100), " +
                    "first_seen DATETIME, " +
                    "last_seen DATETIME, " +
                    "INDEX idx_player_uuid (player_uuid), " +
                    "INDEX idx_ip (ip), " +
                    "INDEX idx_last_seen (last_seen), " +
                    "UNIQUE KEY unique_player_ip (player_uuid, ip)" +
                    ")";
            stmt.execute(createIPHistoryTable);

            plugin.getLogger().info("数据库表创建完成");
        }
    }

    @Override
    public void savePlayerData(PlayerData playerData) {
        String playersTable = tablePrefix + "players";
        String ipHistoryTable = tablePrefix + "ip_history";

        try {
            // 开始事务
            connection.setAutoCommit(false);

            // 插入或更新玩家基本信息
            String playerSql = "INSERT INTO " + playersTable + " (uuid, name, current_ip, current_location, current_isp, last_seen) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE name = VALUES(name), current_ip = VALUES(current_ip), " +
                    "current_location = VALUES(current_location), current_isp = VALUES(current_isp), last_seen = VALUES(last_seen)";

            try (PreparedStatement playerStmt = connection.prepareStatement(playerSql)) {
                playerStmt.setString(1, playerData.getUuid());
                playerStmt.setString(2, playerData.getName());
                playerStmt.setString(3, playerData.getCurrentIP());
                playerStmt.setString(4, playerData.getCurrentLocation());
                playerStmt.setString(5, playerData.getCurrentISP());

                // 正确转换时间戳
                Timestamp lastSeen = convertToTimestamp(playerData.getLastSeen());
                playerStmt.setTimestamp(6, lastSeen);

                playerStmt.executeUpdate();
            }

            // 保存IP历史记录
            for (IPRecord record : playerData.getIpHistory()) {
                String ipSql = "INSERT INTO " + ipHistoryTable + " (player_uuid, ip, location, isp, first_seen, last_seen) " +
                        "VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE location = VALUES(location), isp = VALUES(isp), last_seen = VALUES(last_seen)";

                try (PreparedStatement ipStmt = connection.prepareStatement(ipSql)) {
                    ipStmt.setString(1, playerData.getUuid());
                    ipStmt.setString(2, record.getIp());
                    ipStmt.setString(3, record.getLocation());
                    ipStmt.setString(4, record.getIsp());

                    // 正确转换时间戳
                    Timestamp firstSeen = convertToTimestamp(record.getFirstSeen());
                    Timestamp lastSeen = convertToTimestamp(record.getLastSeen());

                    ipStmt.setTimestamp(5, firstSeen);
                    ipStmt.setTimestamp(6, lastSeen);

                    ipStmt.executeUpdate();
                }
            }

            // 限制历史记录数量
            int maxSize = plugin.getConfig().getInt("data.max-history-size", 100);
            String deleteOldSql = "DELETE FROM " + ipHistoryTable + " WHERE player_uuid = ? AND id NOT IN (" +
                    "SELECT id FROM (" +
                    "SELECT id FROM " + ipHistoryTable + " WHERE player_uuid = ? ORDER BY last_seen DESC LIMIT ?" +
                    ") AS temp)";

            try (PreparedStatement deleteStmt = connection.prepareStatement(deleteOldSql)) {
                deleteStmt.setString(1, playerData.getUuid());
                deleteStmt.setString(2, playerData.getUuid());
                deleteStmt.setInt(3, maxSize);
                deleteStmt.executeUpdate();
            }

            // 提交事务
            connection.commit();
            connection.setAutoCommit(true);

        } catch (SQLException e) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "回滚事务失败: " + ex.getMessage(), ex);
            }
            plugin.getLogger().log(Level.SEVERE, "保存玩家数据到MySQL失败: " + e.getMessage(), e);
        } catch (ParseException e) {
            plugin.getLogger().log(Level.SEVERE, "时间戳格式错误: " + e.getMessage(), e);
        }
    }

    @Override
    public PlayerData loadPlayerData(UUID uuid) {
        String playersTable = tablePrefix + "players";
        String ipHistoryTable = tablePrefix + "ip_history";

        try {
            // 加载玩家基本信息
            String playerSql = "SELECT * FROM " + playersTable + " WHERE uuid = ?";
            try (PreparedStatement playerStmt = connection.prepareStatement(playerSql)) {
                playerStmt.setString(1, uuid.toString());

                try (ResultSet rs = playerStmt.executeQuery()) {
                    if (rs.next()) {
                        PlayerData playerData = new PlayerData(uuid.toString(), rs.getString("name"));
                        playerData.setCurrentIP(rs.getString("current_ip"));
                        playerData.setCurrentLocation(rs.getString("current_location"));
                        playerData.setCurrentISP(rs.getString("current_isp"));

                        // 正确转换时间戳
                        Timestamp lastSeen = rs.getTimestamp("last_seen");
                        if (lastSeen != null) {
                            playerData.setLastSeen(dateFormat.format(lastSeen));
                        }

                        // 加载IP历史记录
                        String ipSql = "SELECT * FROM " + ipHistoryTable + " WHERE player_uuid = ? ORDER BY last_seen DESC";
                        try (PreparedStatement ipStmt = connection.prepareStatement(ipSql)) {
                            ipStmt.setString(1, uuid.toString());

                            try (ResultSet ipRs = ipStmt.executeQuery()) {
                                while (ipRs.next()) {
                                    IPRecord record = new IPRecord(
                                            ipRs.getString("ip"),
                                            formatTimestamp(ipRs.getTimestamp("first_seen")),
                                            formatTimestamp(ipRs.getTimestamp("last_seen"))
                                    );
                                    record.setLocation(ipRs.getString("location"));
                                    record.setIsp(ipRs.getString("isp"));
                                    playerData.addIPRecord(record);
                                }
                            }
                        }

                        return playerData;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "从MySQL加载玩家数据失败: " + e.getMessage(), e);
        }

        return null;
    }

    @Override
    public PlayerData findPlayerDataByName(String playerName) {
        String playersTable = tablePrefix + "players";

        try {
            String sql = "SELECT uuid FROM " + playersTable + " WHERE name = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, playerName);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return loadPlayerData(UUID.fromString(rs.getString("uuid")));
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "按名称查找玩家数据失败: " + e.getMessage(), e);
        }

        return null;
    }

    @Override
    public IPRecord getLastIPRecord(UUID uuid) {
        String ipHistoryTable = tablePrefix + "ip_history";

        try {
            String sql = "SELECT * FROM " + ipHistoryTable + " WHERE player_uuid = ? ORDER BY last_seen DESC LIMIT 1";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        IPRecord record = new IPRecord(
                                rs.getString("ip"),
                                formatTimestamp(rs.getTimestamp("first_seen")),
                                formatTimestamp(rs.getTimestamp("last_seen"))
                        );
                        record.setLocation(rs.getString("location"));
                        record.setIsp(rs.getString("isp"));
                        return record;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "获取最后IP记录失败: " + e.getMessage(), e);
        }

        return null;
    }

    @Override
    public boolean isIPRecorded(UUID uuid, String ip) {
        String ipHistoryTable = tablePrefix + "ip_history";

        try {
            String sql = "SELECT COUNT(*) FROM " + ipHistoryTable + " WHERE player_uuid = ? AND ip = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, ip);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "检查IP记录失败: " + e.getMessage(), e);
        }

        return false;
    }

    /**
     * 将字符串时间戳转换为java.sql.Timestamp
     */
    private Timestamp convertToTimestamp(String dateString) throws ParseException {
        if (dateString == null) {
            return new Timestamp(System.currentTimeMillis());
        }
        return new Timestamp(dateFormat.parse(dateString).getTime());
    }

    /**
     * 将java.sql.Timestamp转换为字符串时间戳
     */
    private String formatTimestamp(Timestamp timestamp) {
        if (timestamp == null) {
            return dateFormat.format(new java.util.Date());
        }
        return dateFormat.format(new java.util.Date(timestamp.getTime()));
    }
}