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

## Current release

The current stable version of mirthSync is "2.0.10"

There is a release candidate of mirthSync "2.1.0" that supports selectively
pushing arbitrary paths in the target directory to Mirth. This allows, for
instance, the ability to push only the configurationMap or a single channel or
channel group by specifying a base path within the target directory to use as a
starting point for find the files to push.

**NOTE** 2.1.0 includes new features that break compatibility in a very minor
way. Previously, resources weren't pushed by default but this changes in 2.1.0.
They're now being pushed by default. Also, previous versions of mirthSync were
not pushing the configurationMap. The ability to push the configurationMap has
been added to this version but defaults to false to preserve backward
compatibility. If you're trying out 2.1.0, be careful to test thoroughly in a
dev environment in case there are undiscovered bugs in the new features.

## Changes

### Plans for 2.1.x

Support pushing and pulling arbitrary channels and/or channel groups

### Status

- the current filter is mostly working for pushes but doesn't do anything for
  downloads. needs more testing
- need to implement more tests, the current tests missed the fact that the
  configurationMap and resourceMap would not push

### 2.0.10

Small change that shouldn't impact the current directory layout. This change
allows for forward and backward slashes in channel group names by encoding the
slashes using a http url encode syntax.

### 2.0.9

Support for syncing Resources and a more comprehensive test suite for
validating functionality across multiple versions of Mirth.

### 2.0.3-SNAPSHOT

Bi-directional groups support.

You can check out the 2.0.3-SNAPSHOT release here - https://github.com/SagaHealthcareIT/mirthsync/releases/tag/2.0.3-SNAPSHOT

### 2.0.2-SNAPSHOT

The local directory structure has been changed to nest channels within
their respective group.

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
  Usage: mirthsync [options] action

  Options:
    -s, --server SERVER_URL                     Full HTTP(s) url of the Mirth Connect server
    -u, --username USERNAME                     Username used for authentication
    -p, --password PASSWORD                     Password used for authentication
    -i, --ignore-cert-warnings                  Ignore certificate warnings
    -f, --force                                 Overwrite existing local files during pull and always overwrite remote items without regard for revisions during push
    -t, --target TARGET_DIR                     Base directory used for pushing or pulling files
    -r, --resource-path TARGET_RESOURCE_PATH    A path within the target
     directory to limit the scope of the push/pull. This path may refer to a
     filename specifically or a directory. In the case of a pull - only resources
     that would end up within that path are saved to the filesystem. In the case
     of a push - only resources within that path are pushed to the specified
     server. *This path needs to be relative to the target directory.*
    -v                                          Verbosity level; may be specified multiple times to increase level
        --push-config-map                       A boolean flag to push the configuration map - default false
    -h, --help

  Actions:
    push     Push filesystem code to server
    pull     Pull server code to filesystem
```

## Examples

### CLI

How to pull Mirth Connect code from a Mirth Connect instance:

``` shell
$ java -jar mirthsync.jar -s https://localhost:8443/api -u admin -p admin pull -t /home/user/
```
> Note that the -t paramter accepts absolute and relative paths.

Pulling code from a Mirth Connect instance allowing for overwriting existing files:

``` shell
$ java -jar mirthsync.jar -s https://localhost:8443/api -u admin -p admin -f pull -t /home/user/
```
Pushing code to a Mirth Connect instance (doesn't have to be the same
instance that was pulled from):

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
