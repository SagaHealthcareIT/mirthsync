# mirthSync

![](https://github.com/SagaHealthcareIT/mirthsync/workflows/Clojure%20CI/badge.svg)

mirthSync is a command line tool for synchronizing Mirth Connect code
between servers by allowing you to push or pull channels, code
templates, configuration map and global scripts. The tool can also be
integrated with Git or other version control systems for the purpose
of tracking changes to Mirth Connect code and configuration.

The only requirements are having credentials for the server that is
being synced and the server also needs to support and allow access to
Mirth Connect's REST API.

## Suggestions for use

- Use mirthSync in conjunction with Git (or any VCS) to back up and
  track changes to code and settings
- Pull and push changes between Mirth Connect servers
- Utilize Git branches to track and merge code between dev, test, and
  prod environments
- Use mirthSync as part of your CI/CD workflow
  - Please see our
    [mirthsync-ci](https://github.com/SagaHealthcareIT/mirthsync-ci) repository
    for an example that works with Github Actions
- Work on mirth javascript locally with your favorite editor without having to
  edit XML

## Current release

The latest version of mirthSync is "3.0.1". Note the changes below. Version 3 of
mirthSync changed the layout of the target directory structure. Javascript is
extracted into separate files and top level channels are now placed in a default
group directory.

## Changes

### 3.0.2 (Not yet released)

- Support for getting the password from the MIRTHSYNC_PASSWORD
  environment variable if --password is not specified
- Prompt for password on console if no password is set, the console is
  available and the interactive "-I" flag is set

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

- Support Mirth > version 3.11 (should be ready soon)
- Extract javascript from xml (should be ready soon)
- Support filtering pulls just like we currently are able to filter pushes.
- Addressing reported issues.
- Implement more tests.


## Installation 

`$ git clone https://github.com/SagaHealthcareIT/mirthsync.git`


## Prerequisites 

Requires Java JRE or JDK version 8 (versions 9 and above are not
currently supported)


## Help

How to generate help dialogue:

`$ java -jar mirthsync.jar -h`

### Help Dialogue:

``` text
  The following errors occurred while parsing your command:

  --server is required
  --username is required
  --password is required
  --target is required

  Usage: mirthsync [options] action

  Options:
    -s, --server SERVER_URL                    Full HTTP(s) url of the Mirth Connect server
    -u, --username USERNAME                    Username used for authentication
    -p, --password PASSWORD                    Password used for authentication
    -i, --ignore-cert-warnings                 Ignore certificate warnings
    -f, --force
          Overwrite existing local files during a pull and overwrite remote items
          without regard for revisions during a push.
    -t, --target TARGET_DIR                    Base directory used for pushing or pulling files
    -r, --restrict-to-path RESTRICT_TO_PATH
          A path within the target directory to limit the scope of the push. This
          path may refer to a filename specifically or a directory. If the path
          refers to a file - only that file will be pushed. If the path refers to
          a directory - the push will be limited to resources contained within
          that directory. The RESTRICT_TO_PATH must be specified relative to
          the target directory.
    -v                                         Verbosity level
          May be specified multiple times to increase level.
        --include-configuration-map
          A boolean flag to include the configuration map in the push - defaults
          to false
    -h, --help

  Actions:
    push     Push filesystem code to server
    pull     Pull server code to filesystem

  Environment variables:
    MIRTHSYNC_PASSWORD     Alternative to --password command line option
```

## Examples

### CLI

> NOTE - The "-p" or "--password" option may be omitted from the the
> commands below if the environment variable MIRTHSYNC_PASSWORD is set.v

How to pull Mirth Connect code from a Mirth Connect instance:

``` shell
$ java -jar mirthsync.jar -s https://localhost:8443/api -u admin -p admin pull -t /home/user/
```
> Note that the -t parameter accepts absolute and relative paths.

Pulling code from a Mirth Connect instance allowing for overwriting existing files:

``` shell
$ java -jar mirthsync.jar -s https://localhost:8443/api -u admin -p admin -f pull -t /home/user/
```
Pushing code to a Mirth Connect instance (doesn't have to be the same
instance that the code was originally pulled from):

``` shell
$ java -jar mirthsync.jar -s https://otherserver.localhost/api -u admin -p admin push -t /home/user/
```

Pushing a channel group and its channels to a Mirth Connect instance

``` shell
$ java -jar mirthsync.jar -s https://otherserver.localhost/api -u admin -p admin push -t /home/user/  -r "Channels/This is a group"
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

The following pulls code to a directory called 'tmp' (relative to the
execution environment), overwriting existing files ("-f") and ignoring
the validity of the server certificate ("-i"), and then pushes back to
the local server from the same directory.

``` clj
(mirthsync.core/main-func "-s" "https://localhost:8443/api" "-u" "admin" "-p" "admin" "-t" "target/tmp" "-f" "-i" "pull")
(mirthsync.core/main-func "-s" "https://localhost:8443/api" "-u" "admin" "-p" "admin" "-t" "target/tmp" "-f" "-i" "push")
```

## Build from Source

Requires [Leiningen](https://leiningen.org/)

`$ lein uberjar`

## Todo

- Add the ability to selectively push or pull any channel, group,
  script, etc (this is in progress)
- Add granular extraction of scripts into their own files and merge
  back into XML for pushing

## License

Copyright © 2017-2021 Saga IT LLC

Distributed under the Eclipse Public License either version 1.0 or any later version.

## Disclaimer

This tool is still under development. Use at your own discretion. 
