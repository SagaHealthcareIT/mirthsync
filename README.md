# Mirthsync

Mirthsync is a command line tool for synchronizing Mirth Connect code
between servers by allowing you to push or pull channels, code
templates, configuration map and global scripts using version control
tools like Git or SVN. The only requirements are having credentials
for the server that is being synced and the server also needs to
support and allow access to Mirth Connect's REST API.

Mirthsync is ideal for implementing code across environments such as
Production, Test and Development. Environment specific variables such
as data sources can be stored in the configuration map allowing the
rest of the Mirth Connect code to be environment agnostic.

## Status
### 2.0 Release is coming soon.
The 2.0 release of Mirthsync will bring bi-directional groups
support. The 2.0 snapshot release is fully functional and will be
released as 2.0 after further testing.

## Changes
### 2.0.0-SNAPSHOT
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

Usage: mirthsync [options] action

Options:

  -s, --server SERVER_URL  https://localhost:8443/api  Full HTTP(s) url of the Mirth Connect server  
  -u, --username USERNAME  admin                       Username used for authentication  
  -p, --password PASSWORD                              Password used for authentication  
  -f, --force                                          Overwrite any conflicting files in the target directory  
  -t, --target TARGET_DIR  .                           Base directory used for pushing or pulling files  
  -v                                                   Verbosity level; may be specified multiple times to increase value  
  -h, --help
  

Actions:
  
  push     Push local code to remote  
  pull     Pull remote code to local
 


## Examples

How to pull Mirth Connect code from a remote repository:

`$ java -jar mirthsync.jar  -s https://localhost:8443/api -u admin -p admin pull -t /home/user/`

Pull/Push from a REPL using mostly CLI defaults. The following pulls
code to a directory called 'tmp' (relative to the execution
environment), overwriting existing files ("-f"), and then pushes back
to the local server from the same directory.

```clj
(do (mirthsync.core/run (mirthsync.cli/config ["pull" "-t" "tmp" "-p" "admin" "-f"]))
    (mirthsync.core/run (mirthsync.cli/config ["push" "-t" "tmp" "-p" "admin"])))
```

## Build from Source

Requires [Leiningen](https://leiningen.org/)

`$ lein uberjar`

## Todo

- strip or encode filenames created from server data

## License

Copyright © 2017 Saga IT LLC

Distributed under the Eclipse Public License either version 1.0 or any later version.

## Disclaimer

This tool is still under development. Use at your own discretion. 
