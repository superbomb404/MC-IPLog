package ljsure.cn;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.SerializableAs;

import java.util.*;
import java.util.stream.Collectors;

@SerializableAs("PlayerData")
public class PlayerData implements ConfigurationSerializable {
    private String uuid;
    private String name;
    private String currentIP;
    private String currentLocation;
    private String currentISP;
    private String lastSeen;
    private List<IPRecord> ipHistory;

    public PlayerData(String uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.ipHistory = new ArrayList<>();
    }

    // Getters and Setters
    public String getUuid() { return uuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCurrentIP() { return currentIP; }
    public void setCurrentIP(String currentIP) { this.currentIP = currentIP; }

    public String getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(String currentLocation) { this.currentLocation = currentLocation; }

    public String getCurrentISP() { return currentISP; }
    public void setCurrentISP(String currentISP) { this.currentISP = currentISP; }

    public String getLastSeen() { return lastSeen; }
    public void setLastSeen(String lastSeen) { this.lastSeen = lastSeen; }

    public List<IPRecord> getIpHistory() { return ipHistory; }

    // 添加IP记录
    public void addIPRecord(IPRecord record) {
        ipHistory.add(0, record); // 添加到开头
    }

    // 查找已有的IP记录
    public IPRecord findExistingRecord(String ip) {
        for (IPRecord record : ipHistory) {
            if (ip.equals(record.getIp())) {
                return record;
            }
        }
        return null;
    }

    // ConfigurationSerializable 实现
    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("currentIP", currentIP);
        map.put("currentLocation", currentLocation);
        map.put("currentISP", currentISP);
        map.put("lastSeen", lastSeen);

        // 转换IP历史记录
        List<Map<String, Object>> historyList = ipHistory.stream()
                .map(IPRecord::serialize)
                .collect(Collectors.toList());
        map.put("ipHistory", historyList);

        return map;
    }

    // 从Map创建PlayerData的静态方法
    public static PlayerData deserialize(Map<String, Object> map) {
        String uuid = (String) map.get("uuid");
        String name = (String) map.get("name");
        PlayerData data = new PlayerData(uuid, name);

        data.setCurrentIP((String) map.get("currentIP"));
        data.setCurrentLocation((String) map.get("currentLocation"));
        data.setCurrentISP((String) map.get("currentISP"));
        data.setLastSeen((String) map.get("lastSeen"));

        // 加载IP历史记录
        List<Map<String, Object>> historyList = (List<Map<String, Object>>) map.get("ipHistory");
        if (historyList != null) {
            for (Map<String, Object> recordMap : historyList) {
                data.addIPRecord(IPRecord.deserialize(recordMap));
            }
        }

        return data;
    }

    // 为了向后兼容的fromMap方法
    public static PlayerData fromMap(String uuid, Map<String, Object> map) {
        // 在map中添加uuid以便deserialize方法使用
        map.put("uuid", uuid);
        return deserialize(map);
    }
}