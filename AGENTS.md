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
- `java -jar target/uberjar/mirthsync-<version>-standalone.jar -h` - Show help
- `java -jar target/uberjar/mirthsync-<version>-standalone.jar -s <server-url> -u <username> -p <password> pull -t <target-dir>` - Pull from server
- `java -jar target/uberjar/mirthsync-<version>-standalone.jar -s <server-url> -u <username> -p <password> push -t <target-dir>` - Push to server
- `java -jar target/uberjar/mirthsync-<version>-standalone.jar -s <server> -u <user> -p <pass> -t <dir> --delete-orphaned pull` - Pull with orphaned file cleanup
- `java -jar target/uberjar/mirthsync-<version>-standalone.jar -s <server> -u <user> -p <pass> -t <dir> --delete-orphaned --interactive pull` - Pull with interactive orphan confirmation
- `java -jar target/uberjar/mirthsync-<version>-standalone.jar -t <target-dir> git init` - Initialize git repository
- `java -jar target/uberjar/mirthsync-<version>-standalone.jar -t <target-dir> git status` - Check git status
- `java -jar target/uberjar/mirthsync-<version>-standalone.jar -t <target-dir> --commit-message "msg" git commit` - Commit changes
- `java -jar target/uberjar/mirthsync-<version>-standalone.jar -s <server> -u <user> -p <pass> -t <dir> --auto-commit pull` - Auto-commit after pull
- `java -jar target/uberjar/mirthsync-<version>-standalone.jar -s <server> -u <user> -p <pass> -t <dir> --git-init --auto-commit push` - Auto-commit with repo init
- `lein run` - this is the quickest way to run the application for quick tests (be sure to add appropriate command line args). This is preferable to rebuilding the uberjar and running that.

### Testing
- Tests are organized by Mirth version in `test/mirthsync/mirth_*_test.clj`
- Git functionality tests are in `test/mirthsync/git_test.clj`
- CLI tests are in `test/mirthsync/cli_test.clj`
- Orphan detection tests are in `test/mirthsync/delete_orphaned_test.clj`
- `lein test` runs all tests
- `lein test mirthsync.git-test` runs only git tests
- `lein test mirthsync.cli-test` runs only CLI tests
- `lein test mirthsync.delete-orphaned-test` runs only orphan detection tests
- Test data is extracted from `dev-resources/test-data.tar.gz` during prep tasks
- ** NOTE ** - `lein test` will run the full test suite including integration tests. This spins up a real Mirth instance with our test-data to run full tests against. This will fail if you're already running your own mcserver or oieserver in the background. You will need to kill that process before running this. This full test suite should be run after completing any major functionality and before committing changes.

### Release Process
- `make release` - Full release process (version updates, build, package, sign)
- Version is managed in multiple files and updated via Makefile

## Code Architecture

### Core Namespaces
- `mirthsync.core` - Main entry point and application orchestration
- `mirthsync.cli` - Command-line argument parsing and configuration
- `mirthsync.actions` - Core push/pull operations (upload/download) and orphaned file cleanup
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
4. For pull operations: Pre-pull local file capture for orphan detection
5. Action execution (push/pull) via `mirthsync.actions`
6. For pull operations: Post-pull orphan detection and cleanup/warning
7. File operations through `mirthsync.files`

### Key Concepts
- **Disk Modes**: Controls granularity of file extraction (backup, groups, items, code)
- **API Protocols**: Each Mirth entity type (channels, code templates, etc.) implements specific protocols
- **Multimethods**: Used for dispatch based on entity types and operations
- **XML Processing**: Extensive use of clojure.data.xml and zipper operations
- **Orphan Detection**: Automatic detection of local files that no longer exist on remote server during pull operations
- **Pre-pull State Capture**: Local files are captured before pull operations to enable accurate orphan detection

### Code Quality
- When implementing similar logic across multiple functions, extract shared helper functions to maintain DRY principles.
- Remove dead code promptly when refactoring to avoid confusion and maintenance overhead.
- Always verify that removed functions are not referenced elsewhere before deletion.
- Use private helper functions (defn-) for internal implementation details that shouldn't be part of the public API.

### Feature Development Best Practices
- **Orphan Detection**: Always detect orphaned files during pull operations and warn users about them, even when automatic deletion is disabled.
- **User Experience**: Provide clear warnings with file lists and actionable instructions when potentially destructive operations are available but not enabled.
- **Safety First**: Implement confirmation prompts for destructive operations like file deletion, especially in interactive mode.
- **Path Handling**: Use canonical paths when comparing file locations to handle symbolic links and path normalization correctly.
- **Pre-operation State Capture**: For operations that need to compare before/after state, capture the initial state before making changes.

### Debugging and Troubleshooting Best Practices
- **Integration Tests for API Issues**: When troubleshooting API-related problems, prefer integration tests that spin up real Mirth instances over unit tests. Integration tests catch actual API behavior that unit tests may miss.
- **API Investigation Process**: Always check OpenAPI/Swagger specifications when API calls aren't working as expected. Compare what the code sends vs what the API documentation requires - query parameters and request structure are often critical.
- **API Documentation Access**: When the Mirth server (oieserver) is running, the OpenAPI specification is available at `https://localhost:8443/api/openapi.json` for reviewing API endpoints and request/response formats.
- **Mirth Configuration Map Structure**: Configuration map entries require specific XML structure with ConfigurationProperty objects containing `<value>` and `<comment>` elements. Simple string values will not work.
- **Server Process Management**: Integration tests require clean server state. Kill any manually running Mirth servers (mcserver/oieserver) before running the full test suite to avoid conflicts.
- **CLI Flag Consistency**: Ensure CLI arguments are respected across all disk modes. For example, backup mode should still honor user preferences like `--include-configuration-map`.
- **Complex Conditional Logic**: In boolean filtering logic, add comments to clarify intent and review conditions for logical redundancy. Complex chains of `and`/`or` conditions can become hard to read and may contain unnecessary clauses.

### Dependencies
- Requires Java JRE/JDK version 8 or higher
- Built with Leiningen
- Key libraries: clj-http, clojure.data.xml, tools.cli, slingshot, clj-jgit

### Committing changes
- Do not include Claude attributions - it's rude and noisy

### Environment Variables
- `MIRTHSYNC_PASSWORD` - Alternative to --password command line option
