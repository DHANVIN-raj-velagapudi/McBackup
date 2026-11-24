package org.bukkit.command;
import java.util.List;
/** STUB for compiling only. */
public interface TabCompleter {
    List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args);
}
