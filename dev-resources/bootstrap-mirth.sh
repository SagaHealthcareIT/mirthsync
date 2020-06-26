#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o nounset
#set -o xtrace

# https://stackoverflow.com/questions/59895/how-to-get-the-source-directory-of-a-bash-script-from-within-the-script-itself
MIRTHS_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )/../target/mirths"

mkdir -p "${MIRTHS_DIR}"

MIRTHS=(
    "http://downloads.mirthcorp.com/connect/3.9.0.b2526/mirthconnect-3.9.0.b2526-unix.tar.gz"
    "http://downloads.mirthcorp.com/connect/3.8.0.b2464/mirthconnect-3.8.0.b2464-unix.tar.gz"
)

SHAS=(
    "cf4cc753a8918c601944f2f4607b07f2b008d19c685d936715fe30a64dc90343  mirthconnect-3.9.0.b2526-unix.tar.gz"
    "e4606d0a9ea9d35263fb7937d61c98f26a7295d79b8bf83d0dab920cf875206d  mirthconnect-3.8.0.b2464-unix.tar.gz"
)


(cd "${MIRTHS_DIR}"
 for MIRTH in "${MIRTHS[@]}"; do
     printf "${MIRTH}\n"
     if [[ ! -f $(basename "${MIRTH}") ]]; then
	 curl  -O -J -L "${MIRTH}"
     fi
 done

 for SHA in "${SHAS[@]}"; do
     printf "${SHA}\n"
     echo "${SHA}" | sha256sum -c
 done

 for MIRTH in "${MIRTHS[@]}"; do
     TGZ=$(basename "${MIRTH}")
     DIR="${TGZ%.tar.gz}"
     if [[ ! -d "${DIR}" ]]; then
	 mkdir "${DIR}"
	 tar -xzf "${TGZ}" --directory="${DIR}" --strip-components=1
	 cp "${DIR}/docs/mcservice-java9+.vmoptions" "${DIR}/mcservice.vmoptions"
     fi
 done
)


printf "Mirth 8 and 9 are available in the target directory\n"
exit 0


# cp -a /opt/mirthconnect/mcservice.vmoptions /opt/mirthconnect/docs/mcservice-java8.vmoptions
# cp -a /opt/mirthconnect/docs/mcservice-java9+.vmoptions /opt/mirthconnect/mcservice.vmoptions
