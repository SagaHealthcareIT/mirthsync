# mirthsync

Mirthsync is a command line tool for keeping a local copy of important
aspects of Mirth Connect configuration in order to allow for the use
of traditional version control tools like Git or SVN. Downloading and
uploading to a remote Mirth server are both supported. The only
requirements are having credentials for the server that is being
synced and the server also needs to support and allow access to the
REST API.


## Installation 

`$ git clone https://github.com/SagaHealthcareIT/mirthsync.git`


## Prerequisites 

Requires Java JRE


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

## Build from Source

Requires [Leiningen](https://leiningen.org/)

`$ lein uberjar`


## License

Copyright © 2017 Saga IT LLC

Distributed under the Eclipse Public License either version 1.0 or any later version.

## Disclaimer

This tool is still under development. Use at your own discretion. 
