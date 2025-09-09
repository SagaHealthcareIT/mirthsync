# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

mirthSync is a Clojure-based command-line tool for synchronizing Mirth Connect code between servers. It allows pushing/pulling channels, code templates, configuration maps, and global scripts from Mirth Connect instances via REST API.

## Build and Development Commands

### Core Build Commands
- `lein uberjar` - Build standalone JAR file
- `lein test` - Run test suite  
- `lein clean` - Clean build artifacts

### Running the Application
- `java -jar mirthsync.jar -h` - Show help
- `java -jar mirthsync.jar -s <server-url> -u <username> -p <password> pull -t <target-dir>` - Pull from server
- `java -jar mirthsync.jar -s <server-url> -u <username> -p <password> push -t <target-dir>` - Push to server
- `java -jar mirthsync.jar -t <target-dir> git init` - Initialize git repository
- `java -jar mirthsync.jar -t <target-dir> git status` - Check git status
- `java -jar mirthsync.jar -t <target-dir> --commit-message "msg" git commit` - Commit changes
- `java -jar mirthsync.jar -s <server> -u <user> -p <pass> -t <dir> --auto-commit pull` - Auto-commit after pull
- `java -jar mirthsync.jar -s <server> -u <user> -p <pass> -t <dir> --git-init --auto-commit push` - Auto-commit with repo init

### Testing
- Tests are organized by Mirth version in `test/mirthsync/mirth_*_test.clj`
- Git functionality tests are in `test/mirthsync/git_test.clj`
- CLI tests are in `test/mirthsync/cli_test.clj`
- `lein test` runs all tests
- `lein test mirthsync.git-test` runs only git tests
- `lein test mirthsync.cli-test` runs only CLI tests
- Test data is extracted from `dev-resources/test-data.tar.gz` during prep tasks

### Release Process
- `make release` - Full release process (version updates, build, package, sign)
- Version is managed in multiple files and updated via Makefile

## Code Architecture

### Core Namespaces
- `mirthsync.core` - Main entry point and application orchestration
- `mirthsync.cli` - Command-line argument parsing and configuration
- `mirthsync.actions` - Core push/pull operations (upload/download)
- `mirthsync.apis` - API definitions and server interactions
- `mirthsync.http-client` - HTTP client wrapper for Mirth API calls
- `mirthsync.files` - File system operations and directory management
- `mirthsync.xml` - XML parsing and manipulation utilities
- `mirthsync.interfaces` - Protocol definitions for API implementations
- `mirthsync.git` - Git integration functions (init, status, commit)

### Application Flow
1. CLI parsing in `mirthsync.cli/config`
2. Authentication via `mirthsync.http-client/with-authentication`
3. API iteration through `mirthsync.apis/iterate-apis`
4. Action execution (push/pull) via `mirthsync.actions`
5. File operations through `mirthsync.files`

### Key Concepts
- **Disk Modes**: Controls granularity of file extraction (backup, groups, items, code)
- **API Protocols**: Each Mirth entity type (channels, code templates, etc.) implements specific protocols
- **Multimethods**: Used for dispatch based on entity types and operations
- **XML Processing**: Extensive use of clojure.data.xml and zipper operations

### Dependencies
- Requires Java JRE/JDK version 8 or higher
- Built with Leiningen
- Key libraries: clj-http, clojure.data.xml, tools.cli, slingshot, clj-jgit

### Environment Variables
- `MIRTHSYNC_PASSWORD` - Alternative to --password command line option