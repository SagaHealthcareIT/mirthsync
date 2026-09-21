# @saga-it/mirthsync

DevOps tool for Mirth Connect and Open Integration Engine version control and CI/CD automation.

## Installation

```bash
npm install -g @saga-it/mirthsync
```

## Prerequisites

**Java JRE or JDK version 8 or higher is required.**

If Java is not installed, mirthsync will display platform-specific installation instructions when you run it.

### Quick Java Installation

**macOS:**
```bash
brew install openjdk
```

**Linux (Debian/Ubuntu):**
```bash
sudo apt update && sudo apt install default-jre
```

**Linux (Fedora/RHEL):**
```bash
sudo dnf install java-latest-openjdk
```

**Windows:**
```bash
winget install Microsoft.OpenJDK.21
```

## Usage

After installation, mirthsync is available globally:

```bash
# Show help
mirthsync -h

# Pull configuration from a Mirth Connect server
mirthsync -s https://localhost:8443/api -u admin -p admin pull -t ./mirth-config

# Push configuration to a server
mirthsync -s https://localhost:8443/api -u admin -p admin push -t ./mirth-config

# Git operations (no server credentials required)
mirthsync -t ./mirth-config git status
mirthsync -t ./mirth-config git add
mirthsync -t ./mirth-config --commit-message "Updated channels" git commit
```

## Features

- Synchronize channels, code templates, configuration maps, and global scripts
- Built-in Git integration for version control
- Token-based or username/password authentication
- Orphaned file detection and cleanup
- Bulk channel deployment
- Multiple disk modes (backup, groups, items, code)
- CI/CD pipeline integration

## Documentation

Product page: https://saga-it.com/products/mirthsync

For complete documentation and source, visit:
https://github.com/SagaHealthcareIT/mirthsync

## License

Eclipse Public License 1.0
