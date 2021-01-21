#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'


_dir=`dirname $(readlink -f $0)`

## mostly from - https://stackoverflow.com/a/7335524 goal is to detect
## java version and potentially disable access warnings
if type -p java; then
    echo found java executable in PATH
    _java=java
elif [[ -n "$JAVA_HOME" ]] && [[ -x "$JAVA_HOME/bin/java" ]];  then
    echo found java executable in JAVA_HOME     
    _java="$JAVA_HOME/bin/java"
else
    echo "no java"
fi

if [[ "$_java" ]]; then
    version=$("$_java" -version 2>&1 | awk -F '"' '/version/ {print $2}')
    echo version "$version"
    # if [[ "$version" > "1.8" ]]; then
    #     $_java --illegal-access=permit -jar ${_dir}/lib/uberjar/mirthsync-2.0.9-SNAPSHOT-standalone.jar $@
    # else         
        $_java -jar ${_dir}/../lib/mirthsync-2.0.9-standalone.jar $@
    # fi
fi
##
