#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

###############################################################
## This file is an example. Modify and use at your own risk. ##
###############################################################

message="Mirth changes found on $(date)"

while getopts "m:" opt; do
    case $opt in
        m)
            message="$OPTARG"
            ;;
        \?)
            echo "Usage: git-cron-sample.sh [-m 'custom commit message']" >&2                
            exit 1
            ;;
    esac
done

#### delete existing files before fetch to handle renames
rm -rf '/home/test/git/channelgroups'
rm -rf '/home/test/git/channels'
rm -rf '/home/test/git/codeTemplates'
rm -rf '/home/test/git/server'

java -jar mirthsync-2.0.3-SNAPSHOT-standalone.jar -s https://localhost:8443/api -u admin -p admin -t '/home/test/git' -f pull

git -C '/home/test/git' add -A
git -C '/home/test/git' commit -a -m "$message"
