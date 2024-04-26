(ns mirthsync.fixture-tools
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clj-http.client :as client]
            [mirthsync.core :refer :all]
            [me.raynes.conch :refer [programs with-programs let-programs]]
            [me.raynes.conch.low-level :as sh]))

;;; note that these tests will only work in unix'ish environments with
;;; appropriate commands in the path

;;Check if the os is MAcOS
(defn is-macos? []
  (= "Mac OS X" (System/getProperty "os.name")))

(if (is-macos?)
  (do (programs mkdir sha256sum curl tar cp rm rmdir diff ps java find gsed)
      (def sed gsed))
  (programs mkdir sha256sum curl tar cp rm rmdir diff ps java find sed))

(defn update-all-xml [path]
  (as-> (find path "-type" "f" "-iname" "*.xml") v
    (cs/split v #"\n")
    (reduce (fn [_ file]
              (sed "-i"
                   "-e" "s/<revision>.*/<revision>99<\\/revision>/g"
                   "-e" "s/<time>.*<\\/time>/<time>1556232311111<\\/time>/g"
                   "-e" "s/<description\\/>/<description>a description<\\/description>/g" file)) nil v)))

(defn ensure-directory-exists [path]
  (mkdir "-p" path))

(defn empty-directory? [path]
  (= (find path) (str path "\n")))

;;;; starting data and accessor fns
(def mirths-dir "vendor/mirths")

(defn mirth-name [mirth]
  (str "mirthconnect-" (:version mirth) "-unix"))

(defn mirth-targz [mirth]
  (str (mirth-name mirth) ".tar.gz"))

(defn mirth-checksum [mirth]
  (str (:sha256 mirth) "  " (mirth-targz mirth)))

(defn mirth-url [mirth]
  (str "https://downloads.mirthcorp.com/connect/" (:version mirth) "/" (mirth-targz mirth)))

;;;; impure actions and checks
(defn ensure-target-dir []
  (mkdir "-p" mirths-dir))

(defn mirth-base-dir [mirth]
  (str mirths-dir "/" (mirth-name mirth)))

(defn mirth-db-dir [mirth]
  (str (mirth-base-dir mirth) "/appdata/mirthdb"))

(defn mirth-unpacked? [mirth]
  (.isDirectory (io/file (mirth-base-dir mirth))))

(defn mirth-tgz-here? [mirth]
  (.exists (io/file (str mirths-dir "/" (mirth-targz mirth)))))

(defn validate-mirth [mirth]
  (sha256sum "-c" {:dir mirths-dir :in (mirth-checksum mirth)}))

(defn unpack-mirth [mirth]
  (mkdir (mirth-name mirth) {:dir mirths-dir})
  (tar "-xzf" (mirth-targz mirth) (str "--directory=" (mirth-name mirth)) "--strip-components=1"
         {:dir mirths-dir}))

(defn download-mirth [mirth]
  (curl "-O" "-J" "-L" "-k" "--progress-bar" "--stderr" "-" (mirth-url mirth) {:dir mirths-dir}))

(defn select-jvm-options [mirth]
  (java "-version" {:seq true :redirect-err true})
  (when-not (re-matches #".*version.\"(1\.)?8.*" (first (java "-version" {:seq true :redirect-err true})))
    (cp "docs/mcservice-java9+.vmoptions" "mcserver.vmoptions"
        {:dir (mirth-base-dir mirth)})))

(defn remove-mirth-db [mirth]
  (let-programs [system-test "test"]
    (let [dbdir (mirth-db-dir mirth)]
      (and (clojure.string/ends-with? dbdir "mirthdb")
           (= 0 @(:exit-code (system-test "-d" dbdir {:throw false :verbose true})))
           (rm "-f" "--preserve-root" "--one-file-system" "-r" dbdir)))))

(defn delete-dir? [dir]
  (and
   (.exists (io/file dir))
   (clojure.string/starts-with? dir "target") ;; not sure if this is needed, already defined as parameter to find
   (not (clojure.string/includes? dir " "))
   (not (clojure.string/includes? dir "\n"))))

(defn delete-temp-dirs [version]
  (let [dirs-pattern (str "tmp-" version "*")
        temp-dirs-string (find "target" "-type" "d" "-name" dirs-pattern)
        temp-dirs (str/split temp-dirs-string #"\n")
        dirs (filter delete-dir? temp-dirs)]
    (when (seq dirs)
      (mapv #(rm "-rf" "--preserve-root" "--one-file-system" %) dirs))))

;;;; A couple of helper functions to track the flow of
;;;; tracking the flow and outcomes
(defn do-to-mirth [mirth mirth-fn]
  (let [result (mirth-fn mirth)]
    (when result (print result))
    (update mirth :what-happened? #(conj %1 result))))

(defn run-with-mirth [mirth & actions]
  (reduce do-to-mirth mirth actions))

(defn ensure-valid-mirth [mirth]
  (cond
    (mirth-unpacked? mirth) (run-with-mirth mirth
                                            select-jvm-options
                                            remove-mirth-db)
    (mirth-tgz-here? mirth) (run-with-mirth mirth
                                            validate-mirth
                                            unpack-mirth
                                            select-jvm-options
                                            remove-mirth-db)
    :else (run-with-mirth mirth
                          download-mirth
                          validate-mirth
                          unpack-mirth
                          select-jvm-options
                          remove-mirth-db)))

(def mirths [{:enabled true
              :version "4.0.1.b293"
              :sha256 "fd5223a15cdcaaf0d8071c1bdd9a0409fecd93fcec25e18c1daab1e9fe1f991d"
              :what-happened? []}
             {:enabled false
              :version "3.12.0.b2650"
              :sha256 "57d5790efb5fc976f7e98a47fa4acecfca39809f846975ca4450a6c42caa6f5f"
              :what-happened? []}
             {:enabled false
              :version "3.11.0.b2609"
              :sha256 "4df341312de34fb9a79083c5c1f8c2214cea6efd5b9d34ea0551dee4a2249286"
              :what-happened? []}
             {:enabled false
              :version "3.9.0.b2526"
              :sha256 "cf4cc753a8918c601944f2f4607b07f2b008d19c685d936715fe30a64dc90343"
              :what-happened? []}
             {:enabled true
              :version "3.8.0.b2464"
              :sha256 "e4606d0a9ea9d35263fb7937d61c98f26a7295d79b8bf83d0dab920cf875206d"
              :what-happened? []}])

(def mirth-4-01 [(nth mirths 0) "4-01"])
(def mirth-3-12 [(nth mirths 1) "3-12"])
(def mirth-3-11 [(nth mirths 2) "3-11"])
(def mirth-3-09 [(nth mirths 3) "3-09"])
(def mirth-3-08 [(nth mirths 4) "3-08"])


(defn make-all-mirths-ready []
  (ensure-target-dir)
  (doall
   (map ensure-valid-mirth mirths)))

(defn start-mirth [mirth]
  (let [mirth-base (mirth-base-dir mirth)
        mcserver (sh/proc "./mcserver" :dir mirth-base :verbose :very :redirect-err true)]
    (future (sh/stream-to-out mcserver :out))

    ;; wait up to 90 seconds for the server to appear
    (loop [i 0]
      ;; (println (ps "-axw"))
      ;; (when (= i 0) (prn mcserver))
      (sh/flush mcserver)
      (when-not (or (try
                      (client/head "http://localhost:8080")
                      true
                      (catch Exception e
                        false))
                    (> i 90))
        (do
          (println (str "waiting up to 90s for mirth to be available - " i))
          (Thread/sleep 1000)
          (recur (inc i)))))
    mcserver))

(defn stop-mirth [mirth-proc]
  (let [exit-code (future (sh/exit-code mirth-proc))]
    (sh/destroy mirth-proc)
    @exit-code))

(defn mirth-fixture
  [mirth version]
  (fn [f]
    (when (:enabled mirth)
      (delete-temp-dirs version)
      (make-all-mirths-ready)
      (let [mirth-proc (start-mirth mirth)]
        (f)
        (stop-mirth mirth-proc)))))

(def mirth-3-08-fixture (apply mirth-fixture mirth-3-08))
(def mirth-3-09-fixture (apply mirth-fixture mirth-3-09))
(def mirth-3-11-fixture (apply mirth-fixture mirth-3-11))
(def mirth-3-12-fixture (apply mirth-fixture mirth-3-12))
(def mirth-4-01-fixture (apply mirth-fixture mirth-4-01))

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
