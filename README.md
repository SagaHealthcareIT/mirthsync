# mirthSync

![](https://github.com/SagaHealthcareIT/mirthsync/workflows/Clojure%20CI/badge.svg)

mirthSync is an open source DevOps tool for Mirth Connect and Open Integration Engine (OIE) version control
and CI/CD automation. This command line tool enables healthcare integration DevOps workflows by synchronizing
code between servers - allowing you to push or pull channels, code templates, configuration maps, and global
scripts. The tool includes built-in Git integration for channel versioning and version control best practices,
automatic orphaned file detection and cleanup, and supports GitOps and infrastructure as code methodologies for
healthcare interface engines including HL7 v2, FHIR, and DICOM integration deployments.

The only requirements are having credentials for the server that is being synced and the server also needs to
support and allow access to Mirth Connect's REST API.

## Open Integration Engine Support

mirthSync now supports **Open Integration Engine (OIE)**, an open-source fork of Mirth Connect that continues to provide the powerful integration capabilities of the original platform. This support allows you to use mirthSync with both traditional Mirth Connect servers and OIE instances seamlessly.

We extend our sincere gratitude to the maintainers and contributors of the [Open Integration Engine project](https://github.com/OpenIntegrationEngine/engine) for their dedication to keeping this powerful integration platform alive and open-source. Their work ensures that the community continues to have access to a robust, actively maintained integration engine.

## Suggestions for use

- Set up Mirth Connect version control with Git using mirthSync's built-in integration
  to automatically track your healthcare integration configurations
- Implement Mirth Connect channel versioning best practices using Git branches for
  dev, test, and prod environments
- Enable automated channel promotion between Mirth Connect servers for multi-environment
  deployments
- Build Mirth Connect CI/CD pipelines with automated deployments:
  - GitLab CI example deployments for interface engine version control
  - Jenkins integration for automated channel deployments
  - GitHub Actions workflows - see our
    [mirthsync-ci](https://github.com/SagaHealthcareIT/mirthsync-ci) repository
    for a complete CI/CD pipeline example
- Implement GitOps for healthcare integration engines with infrastructure as code workflows
- Support continuous integration and continuous deployment in healthcare environments
- Work on Mirth JavaScript locally with your favorite editor without editing XML
- Version control HL7 v2, FHIR, and DICOM routing configurations

## Current version

The latest version of mirthSync is "3.5.1-SNAPSHOT". Note the changes below. Version 3 of
mirthSync changed the layout of the target directory structure. Javascript is
extracted into separate files and top level channels are now placed in a default
group directory.

**Recent Improvements:**
- **Token-based Authentication**: New `--token` flag for authentication using existing HTTP session tokens (alternative to username/password)
- **Simplified Git Operations**: Git commands no longer require server credentials or authentication flags
- **Bulk Channel Deployment**: New `--deploy-all` flag for efficient bulk deployment of multiple channels
- **Enhanced Orphaned File Detection**: Always detects orphaned files during pull operations with clear user warnings and optional automatic deletion
- **Improved User Experience**: Better code organization and elimination of duplicate orphan detection logic
- **Enhanced Git Integration**: Comprehensive subcommand support (init, status, add, commit, diff, log, branch, checkout, remote, pull, push, reset)
- **Improved JGit API**: Better reliability and performance with fixed reflection warnings
- **Security**: Path validation and safety features for file operations

## Changes

### 3.5.1-SNAPSHOT

- **Token-based Authentication**: New `--token` flag for authentication using existing HTTP session tokens
  - Alternative to username/password authentication
  - Useful for automation and CI/CD pipelines with pre-authenticated sessions
  - Mutually exclusive with username/password flags
- **Simplified Git Operations**: Git commands no longer require server or authentication flags
  - Run git operations directly on target directory without Mirth server credentials
  - Streamlined workflow for version control operations
- **Bulk Channel Deployment**: New `--deploy-all` flag for efficient bulk deployment of multiple channels
  - Deploys all channels in a single operation instead of one-by-one
  - Significantly faster when pushing multiple channels
  - Allows Mirth's dependency logic to control deployment order
- **⚠️ SNAPSHOT Release**: This version contains new features that are still being tested. Use with caution in production environments.

### 3.3.0-SNAPSHOT

- **Enhanced Orphaned File Detection**: Always detects orphaned files during pull operations with clear user warnings
- **Improved User Experience**: Better code organization and elimination of duplicate orphan detection logic  
- **Safe Deletion Options**: Optional automatic deletion of orphaned files with interactive confirmation mode
- **Better Documentation**: Comprehensive user guides and development best practices
- **Code Quality**: Refactored orphan detection logic for better maintainability
- **⚠️ SNAPSHOT Release**: This version contains new features that are still being tested. Use with caution in production environments.

### 3.1.0

- **NOTE** that this version respects the --include-configuration-map
         (false by default) parameter during both a "push" and a
         "pull". Previous versions would always "pull" the
         configuration map even if the parameter was not set or was
         false. If you want to include the configuration map now
         during a "pull" please ensure that you set the flag.

- New "--skip-disabled" flag to indicate whether the item (only
  channels currently) being pushed or pulled should be included based
  on its status. The flag defaults to 'false' and all items are pushed
  or pulled no matter what the 'enabled' setting is. NOTE - this
  feature only works on mirth versions >= 3.9.
  
- New "--disk-mode" setting that alters behavior around how granular
  the resulting files are in the target directory. 
  - Mode "backup" pushes and pulls a full backup XML file and doesn't
    produce any other disk artifacts.
  - Mode "groups" pushes and pulls code at the next most granular level
    which means that channel groups and code template library XML
    actually contains the assocated channels and javascript
  - Mode "items" extracts code templates and channels from the respective
    library or group XML.
  - Mode "code" extracts even further pulling all code from XML files
    into individual language specific files.

### 3.0.2

- Support for getting the password from the MIRTHSYNC_PASSWORD
  environment variable if --password is not specified
- Prompt for password on console if no password is set, the console is
  available and the interactive "-I" flag is set
- Fix bug preventing local modifications from being pushed even with
  --force flag specified

### 3.0.1

- New feature to enable optional deployment of channel(s) during a push

### 3.0.0

- Major feature - javascript in channels, code templates, and global scripts is
  now extracted into its own file
- Breaking change - place top level channels beneath a 'Default Group' directory
- restrict-to-path now filters pulls (previously only pushes were filtered)
- code refactoring to use multimethods instead of passing around functions
- code clarity work
- more tests
- support for Mirth Connect 3.12


### 2.1.1

- Support for pushing/pulling Alerts
- Fix bug related to directory/xml ordering and zip/remove

### 2.1.0 (Pre-Release)

- Selective push
- Testing against mirth 3.11

"2.1.x" versions of mirthSync support selectively pushing/pulling arbitrary
paths in the target directory to Mirth. This allows, for instance, the ability
to push/pull only the ConfigurationMap or a single channel or channel group by
specifying a base path within the target directory to use as a starting point
for find the files to push/pull.

**NOTE** 2.1.x versions include new features that break compatibility in minor
ways from the 2.0.x versions.

- There are new command line options and new defaults for the ConfigurationMap.
  You must now add a flag to your command line to allow for pushing the
  ConfigurationMap - without the flag, it will not get included in the push.
- Resources are now properly included in a push.
- Alerts are supported

If you're trying out 2.1.x after using 2.0.x, be careful to test thoroughly in a
dev environment first.

### 2.0.10

Small change that shouldn't impact the current directory layout. This change
allows for forward and backward slashes in channel group names by encoding the
slashes using an HTTP URL encode syntax.

### 2.0.9

Support for syncing Resources and a more comprehensive test suite for
validating functionality across multiple versions of Mirth.

### 2.0.3-SNAPSHOT

Bi-directional groups support.

You can check out the 2.0.3-SNAPSHOT release here - https://github.com/SagaHealthcareIT/mirthsync/releases/tag/2.0.3-SNAPSHOT

### 2.0.2-SNAPSHOT

Changed the local directory structure to nest channels within their respective
group.

## Future plans

- Support Mirth > version 3.12 and latest Open Integration Engine versions
- Enhanced Open Integration Engine CI/CD tooling and testing
- Advanced interface engine channel deployment best practices and strategies
- Enhanced filtering capabilities for selective channel deployments
- Additional CI/CD pipeline examples (Jenkins, GitLab CI, Azure DevOps)
- Addressing reported issues
- Implement more tests
- Performance optimizations for large healthcare integration configurations


## Installation

MirthSync installation is straightforward - this DevOps tool for Mirth Connect can be installed and configured for your version control and CI/CD workflows:

`$ git clone https://github.com/SagaHealthcareIT/mirthsync.git`

For detailed installation guides and integration with your specific CI/CD platform, see the documentation below.

## Prerequisites

Requires Java JRE or JDK version 8 or higher (Java 8, 11, 17, 21, and other LTS versions are supported)

## For MacOS users
To be able to run the script on MacOS, you need to install the following tools:
- coreutils
- gnu-sed

like this:

- brew install coreutils
- brew install gnu-sed

Put this line in your shell config file (.zshrc or .bashrc) to use commands from coreutils as standard MacOS commands
```
export PATH="/opt/homebrew/opt/coreutils/libexec/gnubin:$PATH"
```

## Help

How to generate help dialogue:

`$ java -jar mirthsync.jar -h`

### Help Dialogue:

``` text
Usage: mirthsync [options] action

Options:
  -s, --server SERVER_URL                              Full HTTP(s) url of the Mirth Connect server
  -u, --username USERNAME                              Username used for authentication
  -p, --password PASSWORD                  <computed>  Password used for authentication
      --token TOKEN                                    Authentication token (HTTP session token) for Mirth API.
        Mutually exclusive with username and password.
  -i, --ignore-cert-warnings                           Ignore certificate warnings
  -v                                                   Verbosity level
        May be specified multiple times to increase level.
  -f, --force                                          
        Overwrite existing local files during a pull and overwrite remote items
        without regard for revisions during a push.
  -t, --target TARGET_DIR                              Base directory used for pushing or pulling files
  -m, --disk-mode DISK_MODE                code        Use this flag to specify the target directory
        disk format.
        - backup : Equivalent to Mirth Administrator backup and restore.
        - groups : All items expanded to "Group" or "Library" level.
        - items  : Expand items one level deeper than 'groups' to the individual XML level.
            In other words - Channels and Code Templates are in their own individual
            XML files.
        - code   : Default behavior. Expands everything to the most granular level
            (Javascript, Sql, etc).
  -r, --restrict-to-path RESTRICT_TO_PATH              
        A path within the target directory to limit the scope of the push. This
        path may refer to a filename specifically or a directory. If the path
        refers to a file - only that file will be pushed. If the path refers to
        a directory - the push will be limited to resources contained within
        that directory. The RESTRICT_TO_PATH must be specified relative to
        the target directory.
      --include-configuration-map                       A boolean flag to include the
        configuration map in a push or pull. Default: false
      --skip-disabled                                   A boolean flag that indicates whether
        disabled channels should be pushed or pulled. Default: false
  -d, --deploy                                         Deploy channels on push
        During a push, deploy each included channel immediately
        after saving the channel to Mirth.
      --deploy-all                                      Deploy all channels in bulk after push
        During a push, collect all channel IDs and deploy them
        in a single bulk operation after all channels are saved.
        Allows Mirth's dependency logic to control deployment order.
        More efficient than individual deployment for multiple channels.
  -I, --interactive                                    
        Allow for console prompts for user input
      --commit-message MESSAGE             mirthsync commit  Commit message for git operations
      --git-author NAME                    <computed>        Git author name for commits
      --git-email EMAIL                                      Git author email for commits
      --auto-commit                                          Automatically commit changes after pull/push operations
      --git-init                                             Initialize git repository in target directory if not present
      --delete-orphaned                                      Delete orphaned local files during pull operations.
        When pulling from remote, compare local files with remote files and
        delete any local files that no longer exist on the remote server.
        Use with --interactive to confirm deletions before they occur.
  -h, --help

Actions:
  push     Push filesystem code to server
  pull     Pull server code to filesystem
  git      Git operations (init, status, add, commit, diff, log, branch, checkout, remote, pull, push, reset)
           git diff [--staged|--cached] [<revision-spec>]
           Examples: git diff, git diff --staged, git diff HEAD~1..HEAD, git diff main..feature-branch
           git reset [--soft|--mixed|--hard] [<commit>]
           Examples: git reset, git reset --soft HEAD~1, git reset --hard origin/main

Environment variables:
  MIRTHSYNC_PASSWORD     Alternative to --password command line option
```

## Examples

### CLI

> NOTE - The "-p" or "--password" option may be omitted from the the
> commands below if the environment variable MIRTHSYNC_PASSWORD is
> set. When interactive input is allowed (-I) and no password can be
> found, you will be prompted to enter the password at the terminal.

### Authentication Methods

mirthSync supports two authentication methods:

**Username and Password Authentication (traditional):**

How to pull Mirth Connect code from a Mirth Connect instance:

``` shell
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin pull -t ./mirth-config
```

**Token-based Authentication:**

Use an existing HTTP session token (JSESSIONID) for authentication, useful in automation scenarios:

``` shell
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api --token "your-session-token-here" pull -t ./mirth-config
```

> **Note about authentication:**
> - Username/password and token authentication are mutually exclusive - use one or the other
> - Token authentication is useful for CI/CD pipelines where you already have an authenticated session
> - Git operations do not require any authentication flags (see Git Integration section below)

> Note that the -t parameter accepts absolute and relative paths. Always use a dedicated directory for mirthsync operations, never use your home directory or other system directories.

Pulling code from a Mirth Connect instance allowing for overwriting existing files:

``` shell
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin -f pull -t ./mirth-config
```

Pull with orphaned file detection and cleanup:

``` shell
# Pull and show warnings about orphaned files (default behavior)
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin pull -t ./mirth-config

# Pull and automatically delete orphaned files
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin --delete-orphaned pull -t ./mirth-config

# Pull with interactive confirmation before deleting orphaned files
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin --delete-orphaned --interactive pull -t ./mirth-config
```

Pushing code to a Mirth Connect instance (doesn't have to be the same
instance that the code was originally pulled from):

``` shell
$ java -jar mirthsync-<version>-standalone.jar -s https://otherserver.localhost/api -u admin -p admin push -t ./mirth-config
```

Pushing a channel group and its channels to a Mirth Connect instance:

``` shell
$ java -jar mirthsync-<version>-standalone.jar -s https://otherserver.localhost/api -u admin -p admin push -t ./mirth-config -r "Channels/This is a group"
```

### Channel Deployment

mirthSync supports both individual and bulk channel deployment during push operations:

**Individual channel deployment:**
``` shell
# Deploy each channel immediately after it's saved (slower for multiple channels)
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin --deploy push -t ./mirth-config
```

**Bulk channel deployment (recommended for multiple channels):**
``` shell
# Collect all channel IDs during push, then deploy them all in one operation (faster)
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin --deploy-all push -t ./mirth-config
```

**Deploy specific channels in bulk:**
``` shell
# Push and bulk deploy only channels in a specific group
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin --deploy-all push -t ./mirth-config -r "Channels/Production Group"
```

**Performance comparison:**
- `--deploy`: Each channel is deployed immediately after being saved (N API calls for N channels)
- `--deploy-all`: All channels are collected during push, then deployed in a single bulk operation (1 API call for all channels)

**Benefits of bulk deployment:**
- **Performance**: Significantly faster when pushing multiple channels
- **Dependency Management**: Allows Mirth's dependency logic to control deployment order
- **Recommended**: The preferred approach for production deployments with multiple channels

### Orphaned File Management

mirthSync includes comprehensive orphaned file detection and management capabilities to help keep your local filesystem synchronized with your Mirth Connect server.

**What are orphaned files?**
Orphaned files are local files that no longer exist on the remote Mirth Connect server. This can happen when:
- Channels, code templates, or other resources are deleted from the server
- Items are moved or renamed on the server
- Server configurations are reset or restored from backup

**Automatic Detection:**
mirthSync automatically detects orphaned files during every pull operation, regardless of whether you use the `--delete-orphaned` flag. When orphaned files are found, you'll see clear warnings like:

```
WARNING: Found 3 orphaned local files that no longer exist on the remote server:
  - Channels/Default Group/Old Channel.xml
  - Code Templates/Unused Template.js
  - Global Scripts/Deprecated Script.js

To automatically delete these files, use the --delete-orphaned flag.
To see this warning again, run the same command without --delete-orphaned.
```

**Safe Deletion Options:**

``` shell
# Show warnings but don't delete (default behavior)
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin pull -t ./mirth-config

# Automatically delete orphaned files without confirmation
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin --delete-orphaned pull -t ./mirth-config

# Interactive mode - confirm each deletion
$ java -jar mirthsync-<version>-standalone.jar -s https://localhost:8443/api -u admin -p admin --delete-orphaned --interactive pull -t ./mirth-config
```

**Best Practices:**
- Always review the orphaned file warnings before using `--delete-orphaned`
- Use `--interactive` mode in production environments for safety
- Consider backing up your target directory before running with `--delete-orphaned`
- Orphaned file detection works with all disk modes (backup, groups, items, code)
- **Important**: Always use a dedicated directory for mirthsync operations (e.g., `./mirth-config`, `/opt/mirthsync/config`) - never use your home directory or other system directories

### Git Integration

mirthsync includes comprehensive built-in git integration for version control of your Mirth Connect configurations.

**Key Benefits:**
- **No Server Credentials Required**: Git operations work directly with your local target directory - no need to specify `-s`, `-u`, `-p`, or `--token` flags
- **Simplified Workflow**: Perform version control operations without connecting to your Mirth server
- **Complete Git Functionality**: Full support for init, status, add, commit, diff, log, branch, checkout, remote operations, and more

**⚠️ Experimental Feature Notice:** The native git integration is currently experimental and requires extensive testing. While the functionality is comprehensive, we recommend using it alongside traditional git workflows until it has been thoroughly validated across different environments and use cases.

#### Available Git Operations

**Initialize a git repository:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config git init
```

**Check repository status:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config git status
```

**Stage all changes:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config git add
```

**Commit changes:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config --commit-message "Updated channel configurations" git commit
```

**Commit with custom author information:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config --commit-message "Updated configurations" --git-author "John Doe" --git-email "john@example.com" git commit
```

**View differences:**
``` shell
# Show unstaged changes (working directory vs index)
$ java -jar mirthsync.jar -t ./mirth-config git diff

# Show staged changes (index vs HEAD)
$ java -jar mirthsync.jar -t ./mirth-config git diff --staged
$ java -jar mirthsync.jar -t ./mirth-config git diff --cached

# Show changes between commits/branches
$ java -jar mirthsync.jar -t ./mirth-config git diff HEAD~1..HEAD
$ java -jar mirthsync.jar -t ./mirth-config git diff main..feature-branch
```

**View commit history:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config git log
$ java -jar mirthsync.jar -t ./mirth-config git log 5  # Show last 5 commits
```

**List branches:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config git branch
```

**Switch branches:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config git checkout develop
```

**List remotes:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config git remote
```

**Pull from remote:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config git pull
```

**Push to remote:**
``` shell
$ java -jar mirthsync.jar -t ./mirth-config git push
```

**Reset changes:**
``` shell
# Reset staged changes (mixed mode - default)
$ java -jar mirthsync.jar -t ./mirth-config git reset

# Soft reset - move HEAD but keep changes staged
$ java -jar mirthsync.jar -t ./mirth-config git reset --soft HEAD~1

# Hard reset - discard all changes and reset to specific commit
$ java -jar mirthsync.jar -t ./mirth-config git reset --hard HEAD~1
$ java -jar mirthsync.jar -t ./mirth-config git reset --hard origin/main
```

#### Enhanced Diff Functionality

mirthsync provides comprehensive diff capabilities to help you understand changes at different stages of your git workflow:

**Show unstaged changes (working directory vs index):**
``` shell
# Default behavior - shows files you've modified but not yet staged
$ java -jar mirthsync.jar -t ./mirth-config git diff
```

**Show staged changes (index vs HEAD):**
``` shell
# Shows changes that are staged and ready to commit
$ java -jar mirthsync.jar -t ./mirth-config git diff --staged
$ java -jar mirthsync.jar -t ./mirth-config git diff --cached  # Same as --staged
```

**Show changes between commits/branches:**
``` shell
# Compare two commits
$ java -jar mirthsync.jar -t ./mirth-config git diff HEAD~1..HEAD

# Compare current branch with another branch  
$ java -jar mirthsync.jar -t ./mirth-config git diff main..feature-branch

# Compare any two commits (using commit hashes)
$ java -jar mirthsync.jar -t ./mirth-config git diff abc123..def456

# Show what changed from 3 commits ago to 1 commit ago
$ java -jar mirthsync.jar -t ./mirth-config git diff HEAD~3..HEAD~1
```

This enhanced diff functionality helps you:
- Review what you've changed before staging
- Verify staged changes before committing  
- Compare different versions of your configuration
- Understand changes between development branches
- Track configuration evolution over time

#### Git Reset Functionality

mirthsync provides comprehensive git reset capabilities to help you undo changes and move between different states in your git history:

**Reset staged changes (mixed mode - default):**
``` shell
# Unstage all staged changes, keeping working directory unchanged
$ java -jar mirthsync.jar -t ./mirth-config git reset
```

**Soft reset (move HEAD only):**
``` shell
# Move HEAD to previous commit, keep index and working directory unchanged
$ java -jar mirthsync.jar -t ./mirth-config git reset --soft HEAD~1

# Undo last commit but keep changes staged for recommit
$ java -jar mirthsync.jar -t ./mirth-config git reset --soft HEAD~1
```

**Hard reset (reset everything):**
``` shell
# Reset HEAD, index, and working directory to match the commit
$ java -jar mirthsync.jar -t ./mirth-config git reset --hard

# Reset to a specific commit, discarding all changes
$ java -jar mirthsync.jar -t ./mirth-config git reset --hard HEAD~2

# Reset to remote branch state
$ java -jar mirthsync.jar -t ./mirth-config git reset --hard origin/main
```

**Reset to specific commits:**
``` shell
# Reset to a specific commit hash
$ java -jar mirthsync.jar -t ./mirth-config git reset --mixed abc1234

# Reset to a tag
$ java -jar mirthsync.jar -t ./mirth-config git reset --hard v1.0.0
```

This git reset functionality helps you:
- Unstage files that were accidentally added to the index
- Undo commits while preserving your work (soft reset)
- Completely revert to a previous state (hard reset)
- Clean up commit history before pushing to remote
- Recover from mistakes in your local development

#### Git Workflow Examples

**Complete workflow - pull from server, commit changes, and push to remote:**
``` shell
# Pull latest from Mirth server (requires authentication)
$ java -jar mirthsync.jar -s https://localhost:8443/api -u admin -p admin -t ./mirth-config pull

# Check what changed (no authentication needed)
$ java -jar mirthsync.jar -t ./mirth-config git status

# View unstaged differences (no authentication needed)
$ java -jar mirthsync.jar -t ./mirth-config git diff

# Stage changes for commit (no authentication needed)
$ java -jar mirthsync.jar -t ./mirth-config git add

# Review staged changes before commit (no authentication needed)
$ java -jar mirthsync.jar -t ./mirth-config git diff --staged

# Commit the changes (no authentication needed)
$ java -jar mirthsync.jar -t ./mirth-config --commit-message "Updated from production server" git commit

# Push to remote repository (no authentication needed)
$ java -jar mirthsync.jar -t ./mirth-config git push
```

**Complete workflow using token authentication:**
``` shell
# Pull latest from Mirth server using token authentication
$ java -jar mirthsync.jar -s https://localhost:8443/api --token "your-session-token" -t ./mirth-config pull

# All git operations remain the same - no credentials needed
$ java -jar mirthsync.jar -t ./mirth-config git status
$ java -jar mirthsync.jar -t ./mirth-config git add
$ java -jar mirthsync.jar -t ./mirth-config --commit-message "Updated from production server" git commit
$ java -jar mirthsync.jar -t ./mirth-config git push
```

**Branch-based development workflow:**
``` shell
# Create and switch to development branch
$ java -jar mirthsync.jar -t ./mirth-config git checkout -b feature/new-channel

# Make changes and commit
$ java -jar mirthsync.jar -t ./mirth-config --commit-message "Added new channel configuration" git commit

# Switch back to main branch
$ java -jar mirthsync.jar -t ./mirth-config git checkout main

# Merge feature branch (requires actual git command for merge)
$ cd ./mirth-config && git merge feature/new-channel
```

### CI/CD Pipeline Integration

mirthSync is designed for Mirth Connect CI/CD pipelines and automated deployments. The tool enables continuous integration and continuous deployment workflows for healthcare integration engines.

**Key CI/CD Capabilities:**
- Automated channel promotion between dev, test, and prod environments
- Multi-environment deployment strategies for interface engines
- Integration with Jenkins, GitLab CI, GitHub Actions, and other CI/CD platforms
- Support for automated testing and validation of channel configurations
- Infrastructure as code and GitOps workflows for HL7, FHIR, and DICOM integrations

**CI/CD Example Projects:**
- See our [mirthsync-ci](https://github.com/SagaHealthcareIT/mirthsync-ci) repository for a complete GitHub Actions CI/CD pipeline example
- Jenkins integration examples for automated Mirth Connect channel deployments
- GitLab CI pipeline configurations for multi-environment healthcare integration deployments

### Auto-Commit Integration

mirthsync can automatically commit changes after pull or push operations, perfect for CI/CD automation:

Automatically commit changes after pulling from server:

``` shell
$ java -jar mirthsync.jar -s https://localhost:8443/api -u admin -p admin -t ./mirth-config --auto-commit pull
```

Initialize git repository and auto-commit in one command:

``` shell
$ java -jar mirthsync.jar -s https://localhost:8443/api -u admin -p admin -t ./mirth-config --git-init --auto-commit --commit-message "Initial pull from production" pull
```

Auto-commit with custom message and author (ideal for CI/CD systems):

``` shell
$ java -jar mirthsync.jar -s https://localhost:8443/api -u admin -p admin -t ./mirth-config --auto-commit --commit-message "Sync from dev server" --git-author "CI System" --git-email "ci@company.com" pull
```

### Cron

There is a sample script (git-cron-sample.sh) packaged that can be
customized and utilized from a cron job and/or the command line. The
script can take an optional commit message in cases where it is
desirable to immediately pull and commit changes to git with
meaningful commit messages.

Sample crontab...

``` shell
# At 02:24 on every day-of-week from Sunday through Saturday 
24 2 * * 0-6 /opt/mirthsync/git-cron-sample.sh >/dev/null 2>&1
```

### REPL

Pull/Push from a REPL.

The following pulls code to a dedicated mirthsync directory (relative to the
execution environment), overwriting existing files ("-f") and ignoring
the validity of the server certificate ("-i"), and then pushes back to
the local server from the same directory.

``` clj
(mirthsync.core/main-func "-s" "https://localhost:8443/api" "-u" "admin" "-p" "admin" "-t" "./mirth-config" "-f" "-i" "pull")
(mirthsync.core/main-func "-s" "https://localhost:8443/api" "-u" "admin" "-p" "admin" "-t" "./mirth-config" "-f" "-i" "push")
```

## Build from Source

Requires [Leiningen](https://leiningen.org/)

`$ lein uberjar`

## Todo

- Gracefully handle renames and deletions

## License

Copyright © 2017-2022 Saga IT LLC

Distributed under the Eclipse Public License either version 1.0 or any later version.

## Disclaimer

This tool is still under development. Use at your own discretion. 
