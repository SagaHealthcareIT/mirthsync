# mirthsync

Mirthsync is a command line tool for keeping a local copy of important
aspects of Mirth Connect configuration in order to allow for the use
of traditional version control tools like Git or SVN. Downloading and
uploading to a remote Mirth server are both supported. The only
requirements are having credentials for the server that is being
synced and the server also needs to support and allow access to the
REST API.

## Installation

Source code is at [github](https://github.com/SagaHealthcareIT/mirthsync)

  * clone
  * `lein uberjar`

## Usage

Run the compiled jar for usage help

    $ java -jar target/uberjar/mirthsync-0.1.0-SNAPSHOT-standalone.jar

## Options

FIXME: listing of options this app accepts.

## Examples

java -jar mirthsync.jar -a fetch

## License

Copyright © 2017 Saga IT LLC

Distributed under the Eclipse Public License either version 1.0 or any later version.
