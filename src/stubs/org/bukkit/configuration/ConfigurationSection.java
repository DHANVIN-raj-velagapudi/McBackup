package org.bukkit.configuration;
import java.util.List;
import java.util.Set;
/** STUB for compiling only - the real interface comes from the Paper server at runtime. */
public interface ConfigurationSection {
    Set<String> getKeys(boolean deep);
    boolean contains(String path);
    boolean isConfigurationSection(String path);
    Object get(String path);
    void set(String path, Object value);
    ConfigurationSection createSection(String path);
    String getString(String path, String def);
    List<String> getComments(String path);
    void setComments(String path, List<String> comments);
    List<String> getInlineComments(String path);
    void setInlineComments(String path, List<String> comments);
}
