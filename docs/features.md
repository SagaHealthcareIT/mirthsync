# Features

## Orphaned File Detection and Cleanup

MirthSync automatically detects orphaned local files during pull operations and can optionally clean them up.

### What are Orphaned Files?

Orphaned files are local files that no longer exist on the remote Mirth Connect server. This commonly happens when:
- Channels, code templates, or other resources are deleted on the server
- Resources are renamed on the server
- Local files are copied or modified manually

### Basic Usage

By default, MirthSync will detect orphaned files and show warnings:
```bash
java -jar target/uberjar/mirthsync-<version>-standalone.jar pull [options]
# Shows warnings about orphaned files but doesn't delete them
```

To automatically delete orphaned files:
```bash
java -jar target/uberjar/mirthsync-<version>-standalone.jar pull --delete-orphaned [options]
# Automatically deletes detected orphaned files
```

For interactive confirmation:
```bash
java -jar target/uberjar/mirthsync-<version>-standalone.jar pull --delete-orphaned --interactive [options]
# Prompts for confirmation before deleting
```

### Safety Features

- **Pre-pull analysis**: Captures local files before pulling to ensure accurate detection
- **Path validation**: Prevents deletion of files outside the target directory
- **Interactive mode**: Optional confirmation prompts before deletion
- **Comprehensive logging**: Full transparency of detection and deletion operations

For complete documentation, see [orphan-detection.md](orphan-detection.md).
