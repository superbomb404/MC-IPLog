package ljsure.cn;

import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class MySQLStorageHandler implements StorageHandler {
    private final JavaPlugin plugin;
    private final BlockingQueue<Connection> connectionPool;
    private final int poolSize = 5;
    private final String tablePrefix;
    private final SimpleDateFormat dateFormat;

    // 数据库连接参数
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final boolean ssl;
    private final String connectionUrl;

    public MySQLStorageHandler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.connectionPool = new ArrayBlockingQueue<>(poolSize);
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        this.dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        // 读取配置
        this.host = plugin.getConfig().getString("storage.mysql.host", "localhost");
        this.port = plugin.getConfig().getInt("storage.mysql.port", 3306);
        this.database = plugin.getConfig().getString("storage.mysql.database", "minecraft");
        this.username = plugin.getConfig().getString("storage.mysql.username", "minecraft");
        this.password = plugin.getConfig().getString("storage.mysql.password", "password");
        this.tablePrefix = plugin.getConfig().getString("storage.mysql.table-prefix", "iplog_");
        this.ssl = plugin.getConfig().getBoolean("storage.mysql.ssl", false);

        // 构建连接URL
        this.connectionUrl = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=" + ssl +
                "&useUnicode=true" +
                "&characterEncoding=UTF-8" +
                "&autoReconnect=true" +
                "&failOverReadOnly=false" +
                "&maxReconnects=10" +
                "&initialTimeout=5" +
                "&connectTimeout=30000" +
                "&socketTimeout=30000";
    }

    @Override
    public void initialize() throws Exception {
        plugin.getLogger().info("初始化MySQL连接池: " + host + ":" + port + "/" + database);

        // 初始化连接池
        for (int i = 0; i < poolSize; i++) {
            Connection connection = createConnection();
            if (connection != null) {
                connectionPool.offer(connection);
            }
        }

        if (connectionPool.size() == 0) {
            throw new Exception("无法创建任何数据库连接");
        }

        // 创建表结构
        try (Connection connection = getConnection()) {
            createTables(connection);
            plugin.getLogger().info("MySQL存储系统已初始化，连接池大小: " + connectionPool.size());
        }
    }

    @Override
    public void shutdown() {
        plugin.getLogger().info("关闭MySQL连接池...");
        while (!connectionPool.isEmpty()) {
            try {
                Connection connection = connectionPool.take();
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "关闭数据库连接时出错: " + e.getMessage(), e);
            }
        }
        plugin.getLogger().info("MySQL连接池已关闭");
    }

    private Connection createConnection() throws SQLException {
        try {
            Properties props = new Properties();
            props.setProperty("user", username);
            props.setProperty("password", password);
            props.setProperty("autoReconnect", "true");
            props.setProperty("maxReconnects", "10");
            props.setProperty("initialTimeout", "5");

            Connection connection = DriverManager.getConnection(connectionUrl, props);

            // 测试连接是否有效
            try (Statement stmt = connection.createStatement()) {
                stmt.executeQuery("SELECT 1");
            }

            return connection;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "创建数据库连接失败: " + e.getMessage(), e);
            throw e;
        }
    }

    private Connection getConnection() throws SQLException {
        try {
            // 尝试从连接池获取连接，最多等待5秒
            Connection connection = connectionPool.poll(5, TimeUnit.SECONDS);

            if (connection == null) {
                // 连接池为空，创建新连接
                plugin.getLogger().warning("连接池为空，创建新连接");
                return createConnection();
            }

            // 检查连接是否有效
            if (connection.isClosed() || !connection.isValid(2)) {
                plugin.getLogger().info("连接无效，创建新连接");
                try {
                    connection.close();
                } catch (SQLException e) {
                    // 忽略关闭异常
                }
                return createConnection();
            }

            return connection;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("获取数据库连接时被中断", e);
        }
    }

    private void returnConnection(Connection connection) {
        if (connection != null) {
            // 如果连接池已满，直接关闭连接
            if (!connectionPool.offer(connection)) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "关闭数据库连接时出错: " + e.getMessage(), e);
                }
            }
        }
    }

    private void createTables(Connection connection) throws SQLException {
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
        Connection connection = null;
        try {
            connection = getConnection();
            savePlayerDataInternal(connection, playerData);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "保存玩家数据到MySQL失败: " + e.getMessage(), e);
        } finally {
            returnConnection(connection);
        }
    }

    private void savePlayerDataInternal(Connection connection, PlayerData playerData) {
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

        } catch (SQLException e) {
            try {
                if (connection != null) {
                    connection.rollback();
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.SEVERE, "回滚事务失败: " + ex.getMessage(), ex);
            }
            throw new RuntimeException("保存玩家数据失败", e);
        } catch (ParseException e) {
            plugin.getLogger().log(Level.SEVERE, "时间戳格式错误: " + e.getMessage(), e);
        } finally {
            try {
                if (connection != null) {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "恢复自动提交模式失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public PlayerData loadPlayerData(UUID uuid) {
        Connection connection = null;
        try {
            connection = getConnection();
            return loadPlayerDataInternal(connection, uuid);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "从MySQL加载玩家数据失败: " + e.getMessage(), e);
            return null;
        } finally {
            returnConnection(connection);
        }
    }

    private PlayerData loadPlayerDataInternal(Connection connection, UUID uuid) {
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
        Connection connection = null;
        try {
            connection = getConnection();
            return findPlayerDataByNameInternal(connection, playerName);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "按名称查找玩家数据失败: " + e.getMessage(), e);
            return null;
        } finally {
            returnConnection(connection);
        }
    }

    private PlayerData findPlayerDataByNameInternal(Connection connection, String playerName) {
        String playersTable = tablePrefix + "players";

        try {
            String sql = "SELECT uuid FROM " + playersTable + " WHERE name = ?";
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, playerName);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return loadPlayerDataInternal(connection, UUID.fromString(rs.getString("uuid")));
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
        Connection connection = null;
        try {
            connection = getConnection();
            return getLastIPRecordInternal(connection, uuid);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "获取最后IP记录失败: " + e.getMessage(), e);
            return null;
        } finally {
            returnConnection(connection);
        }
    }

    private IPRecord getLastIPRecordInternal(Connection connection, UUID uuid) {
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
        Connection connection = null;
        try {
            connection = getConnection();
            return isIPRecordedInternal(connection, uuid, ip);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "检查IP记录失败: " + e.getMessage(), e);
            return false;
        } finally {
            returnConnection(connection);
        }
    }

    private boolean isIPRecordedInternal(Connection connection, UUID uuid, String ip) {
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