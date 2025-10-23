package ljsure.cn;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class IPLogCommand implements CommandExecutor {

    private final IPLog plugin;

    public IPLogCommand(IPLog plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "用法: /iplog <玩家名>");
            return false;
        }

        String targetName = args[0];

        // 异步查找玩家数据
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerData playerData = plugin.findPlayerDataByName(targetName);

            if (playerData == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(ChatColor.RED + "未找到玩家 " + targetName + " 的记录")
                );
                return;
            }

            // 同步回主线程发送结果
            Bukkit.getScheduler().runTask(plugin, () -> {
                displayPlayerInfo(sender, playerData);
            });
        });

        return true;
    }

    private void displayPlayerInfo(CommandSender sender, PlayerData playerData) {
        sender.sendMessage(ChatColor.GOLD + "=== " + playerData.getName() + " 的IP信息 ===");
        sender.sendMessage(ChatColor.YELLOW + "当前IP: " + ChatColor.WHITE + playerData.getCurrentIP());
        sender.sendMessage(ChatColor.YELLOW + "位置: " + ChatColor.WHITE +
                (playerData.getCurrentLocation() != null ? playerData.getCurrentLocation() : "未知"));
        sender.sendMessage(ChatColor.YELLOW + "ISP: " + ChatColor.WHITE +
                (playerData.getCurrentISP() != null ? playerData.getCurrentISP() : "未知"));
        sender.sendMessage(ChatColor.YELLOW + "最后上线: " + ChatColor.WHITE + playerData.getLastSeen());

        List<IPRecord> ipHistory = playerData.getIpHistory();
        if (ipHistory != null && !ipHistory.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "IP历史记录:");
            for (int i = 0; i < Math.min(ipHistory.size(), 10); i++) {
                IPRecord record = ipHistory.get(i);
                String locationInfo = record.getLocation() != null ?
                        " (" + record.getLocation() + ")" : "";
                sender.sendMessage(ChatColor.GRAY + "  " + (i + 1) + ". " +
                        record.getIp() + " - " +
                        record.getFirstSeen() + locationInfo);
            }
            if (ipHistory.size() > 10) {
                sender.sendMessage(ChatColor.GRAY + "  ... 还有 " +
                        (ipHistory.size() - 10) + " 条记录");
            }
        } else {
            sender.sendMessage(ChatColor.YELLOW + "IP历史记录: " + ChatColor.GRAY + "无");
        }
    }
}