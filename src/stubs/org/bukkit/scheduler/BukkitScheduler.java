package org.bukkit.scheduler;
import org.bukkit.plugin.Plugin;
/** STUB for compiling only. */
public interface BukkitScheduler {
    BukkitTask runTask(Plugin plugin, Runnable task);
}
