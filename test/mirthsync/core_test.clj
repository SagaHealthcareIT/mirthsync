(ns mirthsync.core-test
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
(def mirths-dir "target/mirths")

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
                                            select-jvm-9-options)
    (mirth-tgz-here? mirth) (run-with-mirth mirth
                                            validate-mirth
                                            unpack-mirth
                                            select-jvm-9-options)
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

(use-fixtures :once mirth-8-fixture)

(def baseline-dir "dev-resources/mirth-8-baseline")

(deftest integration
  (testing "Actions fail with default params and invalid certification path."
    (is (= 1 (mirthsync.core/-main "-s" "https://localhost:8443/api"
                                      "-u" "admin" "-p" "admin" "-t" "target/tmp"
                                      "-f" "pull"))))
  
  (testing "Actions fail with invalid credentials"
    (is (= 1 (mirthsync.core/-main "-s" "https://localhost:8443/api"
                                   "-u" "admin" "-p" "invalidpass" "-t" "target/tmp"
                                   "-i" "-f" "pull"))))

  (testing "Push from baseline succeeds without errors"
    (is (= 0 (mirthsync.core/-main "-s" "https://localhost:8443/api"
                                   "-u" "admin" "-p" "admin" "-t" baseline-dir
                                   "-i" "-f" "push"))))

    (testing "Pull from Mirth succeeds without errors"
    (is (= 0 (mirthsync.core/-main "-s" "https://localhost:8443/api"
                                   "-u" "admin" "-p" "admin" "-t" "target/tmp"
                                   "-i" "-f" "pull"))))

  (testing "Pull diff from baseline has only inconsequential differences (ordering, etc)"
    (is (= "" (diff "--recursive" "--suppress-common-lines" "-I .*<contextType>.*" "-I .*<time>.*" "-I .*<revision>.*" "target/tmp" "dev-resources/mirth-8-baseline")))))


;;;;; original approach till the proper diff params were found
;; (is (= "cf447090a77086562eb0d6d9eb6e03703c936ed2a10c3afe02e51587171a665b  -\n"
;;        (let-programs [native-sort "sort"] ; don't shadow clojure sort
;;          (sha256sum
;;           {:in
;;            (native-sort "-bfi" {:seq true :in 
;;                                 (sed "s/<time>.*<\\/time>//"
;;                                      {:seq true :in
;;                                       (diff "--rcs" "--suppress-common-lines" "target/tmp/" "dev-resources/mirth-8-baseline"
;;                                             {:seq true :throw false})})})}))))

;; (sed "'s/diff -r.*\\/\\(target.*\\/ \\).*\\/\\(dev-resources.*\\)/diff -r \\1 \\2/'"
;;      (diff "-r" "target/tmp/" "dev-resources/mirth-8-baseline" {:seq true :throw false}))
;; ;; ;; diff -r target/tmp/ dev-resources/mirth-8-baseline | sed 's/diff -r.*\/\(target.*\/ \).*\/\(dev-resources.*\)/diff -r \1 \2/'
