package org.bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.scheduler.BukkitScheduler;
/** STUB for compiling only. */
public interface Server {
    BukkitScheduler getScheduler();
    boolean dispatchCommand(CommandSender sender, String commandLine);
    ConsoleCommandSender getConsoleSender();
    java.util.Collection<? extends org.bukkit.entity.Player> getOnlinePlayers();
}
