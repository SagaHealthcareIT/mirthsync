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
# rm -rf '/home/test/git/Channels'
# rm -rf '/home/test/git/CodtTemplates'
# rm -rf '/home/test/git/GlobalScripts'
# rm -f '/home/test/git/ConfigurationMap.xml'
# rm -f '/home/test/git/Resources.xml'

/home/test/bin/mirthsync.sh -s https://localhost:8443/api -u admin -p admin -t '/home/test/git' -f pull

git -C '/home/test/git' add -A
git -C '/home/test/git' commit -a -m "$message"
