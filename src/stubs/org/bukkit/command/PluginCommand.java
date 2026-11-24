package org.bukkit.command;
/** STUB for compiling only (functional so tests can drive the plugin). */
public class PluginCommand extends Command {
    public CommandExecutor executor;
    public TabCompleter completer;
    public void setExecutor(CommandExecutor executor) { this.executor = executor; }
    public void setTabCompleter(TabCompleter completer) { this.completer = completer; }
}
