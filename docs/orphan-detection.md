# Orphaned File Detection and Cleanup

## Overview

MirthSync can automatically detect and remove orphaned local files during pull operations. Orphaned files are local files that no longer exist on the remote Mirth Connect server, typically created when channels, code templates, or other resources are deleted or renamed on the server.

## Quick Start

```bash
# Pull and show warnings about orphaned files (default behavior)
java -jar target/uberjar/mirthsync-<version>-standalone.jar -s <server-url> -u <username> -p <password> pull -t <target-dir>

# Pull and automatically delete orphaned files
java -jar target/uberjar/mirthsync-<version>-standalone.jar -s <server> -u <user> -p <pass> -t <dir> --delete-orphaned pull

# Pull with interactive confirmation before deleting orphaned files
java -jar target/uberjar/mirthsync-<version>-standalone.jar -s <server> -u <user> -p <pass> -t <dir> --delete-orphaned --interactive pull
```

## How It Works

### Detection Process

1. **Pre-Pull Capture**: Before performing the pull operation, MirthSync captures a snapshot of all existing local files
2. **Pull Operation**: Downloads the latest configuration from the remote server
3. **Path Comparison**: Compares the pre-pull local files against the file paths that would be generated from the remote server content
4. **Orphan Identification**: Any pre-pull local files that don't match expected remote file paths are considered orphaned

### File Path Analysis

The system uses file path comparison rather than XML content comparison to ensure accurate detection:

- **Reliable**: Works even when files are copied, renamed, or modified locally
- **Consistent**: Uses the same path generation logic as the pull operation
- **Safe**: Only considers files that were present before the pull operation

## Command Line Options

### `--delete-orphaned`

Enables automatic deletion of orphaned files during pull operations.

- **Default**: `false` (orphaned files are detected and warnings are shown, but not deleted)
- **When enabled**: Orphaned files are automatically removed after the pull operation

### `--interactive`

When used with `--delete-orphaned`, prompts for user confirmation before deleting files.

- **Non-interactive mode**: Automatically deletes all detected orphaned files
- **Interactive mode**: Shows the list of orphaned files and asks for confirmation before deletion

## Example Scenarios

### Scenario 1: Channel Deleted on Server

```bash
# Initial state
Local:  Channel1.xml, Channel2.xml
Remote: Channel1.xml, Channel2.xml

# Administrator deletes Channel2 on the remote server
# After pull with --delete-orphaned:
Local:  Channel1.xml
Remote: Channel1.xml

# Channel2.xml was detected as orphaned and removed
```

### Scenario 2: Local File Copied/Renamed

```bash
# User creates a copy of Channel1.xml locally
Local:  Channel1.xml, Channel1-backup.xml
Remote: Channel1.xml

# After pull with --delete-orphaned:
Local:  Channel1.xml
Remote: Channel1.xml

# Channel1-backup.xml was detected as orphaned and removed
```

### Scenario 3: Fresh Pull (No Orphans)

```bash
# Pull to empty directory with --delete-orphaned
Local:  (empty directory)
Remote: Channel1.xml, Channel2.xml

# After pull:
Local:  Channel1.xml, Channel2.xml
Remote: Channel1.xml, Channel2.xml

# No orphaned files detected (empty pre-pull state)
```

## Safety Features

### Path Validation
- Uses canonical path resolution to handle symbolic links and relative paths
- Ensures files are within the target directory before deletion
- Prevents path traversal vulnerabilities

### User Control
- Requires explicit `--delete-orphaned` flag to enable deletion
- Interactive mode provides confirmation prompts
- Comprehensive logging of all operations

### Error Handling
- Graceful handling of file deletion failures
- Warning logs for files outside target directory
- Continues operation if individual file deletions fail

## Output Examples

### Warning Mode (Default)
```
INFO  Checking for orphaned files using pre-pull captured files...
INFO  WARNING: Found orphaned files that no longer exist on the remote server:
INFO    /path/to/target/Channels/old-channel.xml
INFO    /path/to/target/CodeTemplates/unused-template.xml
INFO  These 2 orphaned files were not deleted.
INFO  Use the --delete-orphaned flag to automatically delete orphaned files during pull operations.
```

### Deletion Mode (Non-Interactive)
```
INFO  Checking for orphaned files using pre-pull captured files...
INFO  Deleting 2 orphaned files...
INFO  Deleting: /path/to/target/Channels/old-channel.xml
INFO  Deleting: /path/to/target/CodeTemplates/unused-template.xml
INFO  Orphaned file cleanup completed.
```

### Interactive Mode
```
INFO  Checking for orphaned files using pre-pull captured files...
INFO  The following orphaned files will be deleted:
INFO    /path/to/target/Channels/old-channel.xml
INFO    /path/to/target/CodeTemplates/unused-template.xml
Do you want to delete these orphaned files? (y/N): y
INFO  Deleting 2 orphaned files...
INFO  Deleting: /path/to/target/Channels/old-channel.xml
INFO  Deleting: /path/to/target/CodeTemplates/unused-template.xml
INFO  Orphaned file cleanup completed.
```

## Best Practices

### Development Workflow
1. **Regular pulls**: Use regular pulls without `--delete-orphaned` during active development
2. **Review warnings**: Pay attention to orphaned file warnings to understand what files might be outdated
3. **Cleanup pulls**: Periodically use `--delete-orphaned --interactive` to clean up your local workspace
4. **Backup important work**: Always commit important local changes to version control before using `--delete-orphaned`

### Production Deployment
1. **Use version control**: Always track your MirthSync workspace with Git or similar
2. **Interactive mode**: Use `--interactive` flag in production to review changes before deletion
3. **Dry runs**: Consider using warning mode first to see what would be deleted
4. **Automate safely**: Only use non-interactive mode in fully automated environments where you're confident about the setup

## Troubleshooting

### Common Issues

**Q: Orphaned files detected but they're still needed**
A: This typically means the file was modified locally and no longer matches the remote content. Check if the corresponding resource exists on the remote server and consider pulling a fresh copy.

**Q: Files outside target directory reported as orphaned**
A: This shouldn't happen due to path validation, but if it does, it indicates a bug. Please report it with the full log output.

**Q: Deletion failed for some files**
A: Check file permissions and ensure files aren't locked by other applications. The operation will continue and report which files couldn't be deleted.

### Debug Information

For detailed debugging, increase verbosity with multiple `-v` flags:
```bash
java -jar target/uberjar/mirthsync-<version>-standalone.jar -vvv --delete-orphaned pull [other options]
```

This will show detailed information about:
- Pre-pull file capture process
- Expected path generation
- File comparison logic
- Deletion operations