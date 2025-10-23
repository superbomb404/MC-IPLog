package ljsure.cn;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.SerializableAs;

import java.util.HashMap;
import java.util.Map;

@SerializableAs("IPRecord")
public class IPRecord implements ConfigurationSerializable {
    private String ip;
    private String firstSeen;
    private String lastSeen;
    private String location;
    private String isp;

    public IPRecord(String ip, String firstSeen, String lastSeen) {
        this.ip = ip;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
    }

    // Getters and Setters
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public String getFirstSeen() { return firstSeen; }
    public void setFirstSeen(String firstSeen) { this.firstSeen = firstSeen; }

    public String getLastSeen() { return lastSeen; }
    public void setLastSeen(String lastSeen) { this.lastSeen = lastSeen; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getIsp() { return isp; }
    public void setIsp(String isp) { this.isp = isp; }

    // ConfigurationSerializable 实现
    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("ip", ip);
        map.put("firstSeen", firstSeen);
        map.put("lastSeen", lastSeen);
        if (location != null) map.put("location", location);
        if (isp != null) map.put("isp", isp);
        return map;
    }

    // 静态反序列化方法
    public static IPRecord deserialize(Map<String, Object> map) {
        IPRecord record = new IPRecord(
                (String) map.get("ip"),
                (String) map.get("firstSeen"),
                (String) map.get("lastSeen")
        );
        record.setLocation((String) map.get("location"));
        record.setIsp((String) map.get("isp"));
        return record;
    }

    // 为了向后兼容的fromMap方法
    public static IPRecord fromMap(Map<String, String> map) {
        IPRecord record = new IPRecord(
                map.get("ip"),
                map.get("firstSeen"),
                map.get("lastSeen")
        );
        record.setLocation(map.get("location"));
        record.setIsp(map.get("isp"));
        return record;
    }
}