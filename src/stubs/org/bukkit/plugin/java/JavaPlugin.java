package org.bukkit.plugin.java;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
/** STUB for compiling only (functional so tests can drive the plugin; never packaged in the jar). */
public abstract class JavaPlugin implements Plugin {
    public static File TEST_DATA;
    public static Server TEST_SERVER;
    public static FileConfiguration TEST_CONFIG = new FileConfiguration();
    public static final Map<String, PluginCommand> TEST_COMMANDS = new HashMap<>();

    public void onEnable() { }
    public void onDisable() { }
    public Logger getLogger() { return Logger.getLogger("McBackupTest"); }
    public FileConfiguration getConfig() { return TEST_CONFIG; }
    public void saveDefaultConfig() { }
    public void reloadConfig() { }
    public File getDataFolder() { return TEST_DATA; }
    public Server getServer() { return TEST_SERVER; }
    public PluginCommand getCommand(String name) { return TEST_COMMANDS.computeIfAbsent(name, k -> new PluginCommand()); }
}
