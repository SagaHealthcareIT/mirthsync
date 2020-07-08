(ns mirthsync.fixture-tools
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [mirthsync.core :refer :all]
            [me.raynes.conch :refer [programs with-programs let-programs]]
            [me.raynes.conch.low-level :as sh]))

;;; note that these tests will only work in unix'ish environments with
;;; appropriate commands in the path

(programs mkdir sha256sum curl tar cp rm rmdir diff) ;; sed ;; echo

;;;; starting data and accessor fns
(def mirths-dir "vendor/mirths")

(def mirths [{:version "3.9.0.b2526"
              :sha256 "cf4cc753a8918c601944f2f4607b07f2b008d19c685d936715fe30a64dc90343"
              :what-happened? []}
             {:version "3.8.0.b2464"
              :sha256 "e4606d0a9ea9d35263fb7937d61c98f26a7295d79b8bf83d0dab920cf875206d"
              :what-happened? []}])

(def mirth-9 (first mirths))
(def mirth-8 (second mirths))

(defn mirth-name [mirth]
  (str "mirthconnect-" (:version mirth) "-unix"))

(defn mirth-targz [mirth]
  (str (mirth-name mirth) ".tar.gz"))

(defn mirth-checksum [mirth]
  (str (:sha256 mirth) "  " (mirth-targz mirth)))

(defn mirth-url [mirth]
  (str "http://downloads.mirthcorp.com/connect/" (:version mirth) "/" (mirth-targz mirth)))

;;;; impure actions and checks
(defn ensure-target-dir []
  (mkdir "-p" mirths-dir))

(defn mirth-base-dir [mirth]
  (str mirths-dir "/" (mirth-name mirth)))

(defn mirth-db-dir [mirth]
  (str (mirth-base-dir mirth) "/appdata/mirthdb" ))

(defn mirth-unpacked? [mirth]
  (.isDirectory (io/file (mirth-base-dir mirth))))

(defn mirth-tgz-here? [mirth]
  (.exists (io/file (str mirths-dir "/" (mirth-targz mirth)))))

(defn validate-mirth [mirth]
  (sha256sum "-c" {:dir mirths-dir :in (mirth-checksum mirth) :verbose true}))

(defn unpack-mirth [mirth]
  (mkdir (mirth-name mirth) {:dir mirths-dir})
  (tar "-xzf" (mirth-targz mirth) (str "--directory=" (mirth-name mirth)) "--strip-components=1"
         {:dir mirths-dir :verbose true}))

(defn download-mirth [mirth]
  (curl  "-O" "-J" "-L" "--progress-bar" (mirth-url mirth) {:dir mirths-dir :verbose true}))

(defn select-jvm-9-options [mirth]
  (cp "docs/mcservice-java9+.vmoptions" "mcserver.vmoptions"
      {:dir (mirth-base-dir mirth) :verbose true}))

(defn remove-mirth-db [mirth]
  (let-programs [system-test "test"]
    (let [dbdir (mirth-db-dir mirth)]
      (and (clojure.string/ends-with? dbdir "mirthdb")
           (= 0 @(:exit-code (system-test "-d" dbdir {:throw false :verbose true})))
           (rm "-f" "-v" "--preserve-root=all" "--one-file-system" "-r" (mirth-db-dir mirth))))))

;;;; A couple of helper functions to track the flow of
;;;; tracking the flow and outcomes
(defn do-to-mirth [mirth mirth-fn]
  (update mirth :what-happened? #(conj %1 (mirth-fn mirth))))

(defn run-with-mirth [mirth & actions]
  (reduce do-to-mirth mirth actions))

(defn ensure-valid-mirth [mirth]
  (cond
    (mirth-unpacked? mirth) (run-with-mirth mirth
                                            select-jvm-9-options
                                            remove-mirth-db)
    (mirth-tgz-here? mirth) (run-with-mirth mirth
                                            validate-mirth
                                            unpack-mirth
                                            select-jvm-9-options
                                            remove-mirth-db)
    :else (run-with-mirth mirth
                          download-mirth
                          validate-mirth
                          unpack-mirth
                          select-jvm-9-options
                          remove-mirth-db)))

(defn make-all-mirths-ready []
  (ensure-target-dir)
  (doall
   (map ensure-valid-mirth mirths)))

(defn start-mirth [mirth]
  (let [mirth-base (mirth-base-dir mirth)
        mcserver (sh/proc "./mcserver" :dir mirth-base)]
    (future (sh/stream-to-out mcserver))

    ;; wait up to 10 seconds for the server to appear
    (dotimes [i 10]
      (try
        (client/head "http://localhost:8080")
        (catch Exception e))
      (Thread/sleep 1000))
    
    mcserver))

(defn stop-mirth [mirth-proc]
  (let [exit-code (future (sh/exit-code mirth-proc))]
    (sh/destroy mirth-proc)
    @exit-code))

(defn mirth-8-fixture [f]
  (make-all-mirths-ready)
  (let [mirth-proc (start-mirth mirth-8)]
    (f)
    (stop-mirth mirth-proc)))

(defn mirth-9-fixture [f]
  (make-all-mirths-ready)
  (let [mirth-proc (start-mirth mirth-9)]
    (f)
    (stop-mirth mirth-proc)))

;;;;;;;;;;;;;;; The following was the original script created for
;;;;;;;;;;;;;;; fetching and validating mirth.  It was ported to the
;;;;;;;;;;;;;;; clojure code as a fixture for testing. Keeping it here
;;;;;;;;;;;;;;; for a while in case it comes in handy.
;; #!/usr/bin/env bash

;; set -o errexit
;; set -o pipefail
;; set -o nounset
;; #set -o xtrace

;; # https://stackoverflow.com/questions/59895/how-to-get-the-source-directory-of-a-bash-script-from-within-the-script-itself
;; MIRTHS_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )/../target/mirths"

;; mkdir -p "${MIRTHS_DIR}"

;; MIRTHS=(
;;     "http://downloads.mirthcorp.com/connect/3.9.0.b2526/mirthconnect-3.9.0.b2526-unix.tar.gz"
;;     "http://downloads.mirthcorp.com/connect/3.8.0.b2464/mirthconnect-3.8.0.b2464-unix.tar.gz"
;; )

;; SHAS=(
;;     "cf4cc753a8918c601944f2f4607b07f2b008d19c685d936715fe30a64dc90343  mirthconnect-3.9.0.b2526-unix.tar.gz"
;;     "e4606d0a9ea9d35263fb7937d61c98f26a7295d79b8bf83d0dab920cf875206d  mirthconnect-3.8.0.b2464-unix.tar.gz"
;; )


;; (cd "${MIRTHS_DIR}"
;;  for MIRTH in "${MIRTHS[@]}"; do
;;      printf "${MIRTH}\n"
;;      if [[ ! -f $(basename "${MIRTH}") ]]; then
;; 	 curl  -O -J -L "${MIRTH}"
;;      fi
;;  done

;;  for SHA in "${SHAS[@]}"; do
;;      printf "${SHA}\n"
;;      echo "${SHA}" | sha256sum -c
;;  done

;;  for MIRTH in "${MIRTHS[@]}"; do
;;      TGZ=$(basename "${MIRTH}")
;;      DIR="${TGZ%.tar.gz}"
;;      if [[ ! -d "${DIR}" ]]; then
;; 	 mkdir "${DIR}"
;; 	 tar -xzf "${TGZ}" --directory="${DIR}" --strip-components=1
;; 	 cp "${DIR}/docs/mcservice-java9+.vmoptions" "${DIR}/mcservice.vmoptions"
;;      fi
;;  done
;; )


;; printf "Mirth 8 and 9 are available in the target directory\n"
;; exit 0


;; # cp -a /opt/mirthconnect/mcservice.vmoptions /opt/mirthconnect/docs/mcservice-java8.vmoptions
;; # cp -a /opt/mirthconnect/docs/mcservice-java9+.vmoptions /opt/mirthconnect/mcservice.vmoptions
