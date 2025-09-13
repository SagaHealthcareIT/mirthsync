# Delete Orphaned Files Feature

## Overview

This feature adds support for cleaning up orphaned local files during pull operations from a remote Mirth instance. When a user deletes or renames a channel, code template, destination, transformer, filter, etc. on the remote server, the corresponding local files become orphaned and are no longer needed.

## Implementation

### CLI Flag

Added a new `--delete-orphaned` flag that enables orphaned file cleanup during pull operations:

```bash
mirthsync pull --delete-orphaned --interactive
```

### Features

1. **Orphan Detection**: Compares local files with remote files for each API type (channels, code templates, etc.)
2. **Interactive Confirmation**: When using `--interactive` flag, prompts user for confirmation before deleting files
3. **Safe Deletion**: Only deletes files that no longer exist on the remote server
4. **Comprehensive Logging**: Logs all operations for transparency

### Usage Examples

```bash
# Pull with orphaned file cleanup (non-interactive)
mirthsync pull --delete-orphaned -s https://mirth-server:8443/api -u admin -p password -t ./local-config

# Pull with orphaned file cleanup and interactive confirmation
mirthsync pull --delete-orphaned --interactive -s https://mirth-server:8443/api -u admin -p password -t ./local-config

# Regular pull without orphaned file cleanup (default behavior)
mirthsync pull -s https://mirth-server:8443/api -u admin -p password -t ./local-config
```

### How It Works

1. During a pull operation, after downloading files from the remote server
2. For each API type (channels, code templates, etc.), the system:
   - Gets the list of remote file IDs from the server
   - Gets the list of local file IDs from the filesystem
   - Identifies local files that don't have corresponding remote files
3. If `--delete-orphaned` is specified:
   - In interactive mode: Prompts user for confirmation before deletion
   - In non-interactive mode: Automatically deletes orphaned files
   - Logs all deletion operations

### Safety Features

- Only operates during pull operations (not push)
- Requires explicit `--delete-orphaned` flag to enable
- Interactive mode provides user confirmation
- Comprehensive logging of all operations
- Graceful error handling for file deletion failures

### Files Modified

- `src/mirthsync/cli.clj`: Added `--delete-orphaned` CLI flag
- `src/mirthsync/actions.clj`: Added orphan detection and cleanup functions
- `src/mirthsync/core.clj`: Integrated orphan cleanup into pull workflow
- `test/mirthsync/delete_orphaned_test.clj`: Added tests for the new functionality

### Testing

The feature includes comprehensive tests covering:
- CLI flag parsing
- Orphan detection logic
- Interactive confirmation
- File deletion operations
- Integration with existing pull workflow
