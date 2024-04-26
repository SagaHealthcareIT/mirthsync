(ns mirthsync.common-tests
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]))

;; NOTE - it's important that some of these tests run in order
(defn test-integration
  [repo-dir baseline-dir version]

  (testing "Complex actions with empty target and restrict-to-path and skip-disabled"
    ;; this specific test is not compatible with 3.08
    (is (= [0 0 0 true 0 0 true 0 false]
           (if (= "target/tmp-3-08" repo-dir)
             [0 0 0 true 0 0 true 0 false]
             [
              ;; we're going to start this process out by pushing a
              ;; single group from the baseline so that the following
              ;; single channel push ends up in the group for
              ;; subsequent pulling
              (main-func "-s" "https://localhost:8443/api" "--restrict-to-path" "Channels/This is a group/index.xml"
                         "-u" "admin" "-p" "admin" "-t" baseline-dir "-i" "-f" "push")

              ;; this should push nothing to an empty mirth because it's the first
              ;; test and we're restricting the path to a single disabled channel
              ;; with skip-disabled set
              (main-func "-s" "https://localhost:8443/api" "--restrict-to-path" "Channels/This is a group/Http Hello2 3081"
                         "--skip-disabled" "-u" "admin" "-p" "admin" "-t" baseline-dir
                         "-i" "-f" "push")

              ;; to verify that there's nothing we'll pull everything from the empty
              ;; mirth and check to see if the target directory is empty
              (main-func "-s" "https://localhost:8443/api" "--restrict-to-path" "Channels/This is a group/Http Hello2 3081"
                         "-u" "admin" "-p" "admin" "-t" repo-dir "--include-configuration-map"
                         "-i" "-f" "pull")

              ;; this should return true currently
              (do (ensure-directory-exists repo-dir)
                  (empty-directory? repo-dir))

              ;; now we'll push the single channel from the baseline directory
              ;; without skip disabled and it should show up in mirth
              (main-func "-s" "https://localhost:8443/api" "--restrict-to-path" "Channels/This is a group/Http Hello2 3081"
                         "-u" "admin" "-p" "admin" "-t" baseline-dir
                         "-i" "-f" "push")

              ;; now let's pull again with skip-enabled and we should still have
              ;; nothing in the target directory
              (main-func "-s" "https://localhost:8443/api" "--restrict-to-path" "Channels/This is a group/Http Hello2 3081"
                         "--skip-disabled" "-u" "admin" "-p" "admin" "-t" repo-dir
                         "--include-configuration-map" "-i" "-f" "pull")

              ;; this should return true currently
              (empty-directory? repo-dir)

              ;; pull without skipping disabled and the target directory should not
              ;; be empty
              (main-func "-s" "https://localhost:8443/api" "--restrict-to-path" "Channels/"
                         "-u" "admin" "-p" "admin" "-t" repo-dir "-i" "-f"
                         "--include-configuration-map" "pull")

              ;; this should return false now
              (empty-directory? repo-dir)]))))


  (testing "Actions fail with default params and invalid certification path."
    (is (= 1 (main-func "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" repo-dir
                        "-f" "pull"))))

  (testing "Actions fail with invalid credentials"
    (is (= 1 (main-func "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "invalidpass" "-t" repo-dir
                        "-i" "-f" "pull"))))

  (testing "Pushing/pulling using restrict-to-path and skip-disabled behaves properly."
    (is (= 0 (main-func "-s" "https://localhost:8443/api" "--restrict-to-path" "Channels/This is a group/Http Hello2 3081"
                        "--skip-disabled" "-u" "admin" "-p" "admin" "-t" baseline-dir
                        "-i" "-f" "push"))))

  (testing "Push from baseline succeeds without errors"
    (is (= 0 (main-func "--include-configuration-map" "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" baseline-dir
                        "-i" "-f" "push"))))

  (testing "Pull from Mirth succeeds without errors"
    (is (= 0 (main-func "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" repo-dir
                        "-i" "-f" "--include-configuration-map" "pull"))))

  (testing "Pull diff from baseline has only inconsequential differences (ordering, etc)"
    (is (= "" (diff "--recursive" "--suppress-common-lines" "-I" ".*<contextType>.*" "-I" ".*<time>.*" "-I" ".*<timezone>.*" "-I" ".*<revision>.*" repo-dir baseline-dir))))

  (testing "Push back from pull dir succeeds without errors"
    (is (= 0 (main-func "--include-configuration-map" "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" repo-dir
                        "-i" "-f" "push"))))

  (testing "Pull back from Mirth after last push succeeds without errors"
    (is (= 0 (main-func "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" repo-dir
                        "-i" "-f" "--include-configuration-map" "pull"))))

  (testing "Pull diff from baseline after multiple pushes has only inconsequential differences (ordering, etc)"
    (is (= "" (diff "--recursive" "--exclude" ".DS_Store" "--suppress-common-lines" "-I" ".*<contextType>.*" "-I" ".*<time>.*" "-I" ".*<timezone>.*" "-I" ".*<revision>.*" repo-dir baseline-dir))))

  (testing "Code template push fails wth changes and --force not enabled."
    (is (= 1 (do
               (update-all-xml repo-dir)
               (main-func "-s" "https://localhost:8443/api"
                          "-u" "admin" "-p" "admin" "-t" repo-dir
                          "-i" "--restrict-to-path" "CodeTemplates" "push")))))

  (testing "Code template push succeeds wth changes and --force enabled."
    (is (= 0 (do
               (update-all-xml repo-dir)
               (main-func "-s" "https://localhost:8443/api"
                          "-u" "admin" "-p" "admin" "-t" repo-dir
                          "-i" "--restrict-to-path" "CodeTemplates" "-f" "push")))))

  (testing "Channel push fails wth changes and --force not enabled."
    (is (= 1 (do
               (update-all-xml repo-dir)
               (main-func "-s" "https://localhost:8443/api"
                          "-u" "admin" "-p" "admin" "-t" repo-dir
                          "-i" "--restrict-to-path" "Channels" "push")))))

  (testing "Channel push succeeds wth changes and --force enabled."
    (is (= 0 (do
               (update-all-xml repo-dir)
               (main-func "-s" "https://localhost:8443/api"
                          "-u" "admin" "-p" "admin" "-t" repo-dir
                          "-i" "--restrict-to-path" "Channels" "-f" "push")))))

  (testing "Disk mode 0 pull and push works"
    (let [repo-dir (str repo-dir "-0")]
      (is (= 0 (do
                 (main-func "-s" "https://localhost:8443/api"
                            "-u" "admin" "-p" "admin" "-t" repo-dir
                            "-i" "-m" "backup" "pull"))))
      (is (= "" (diff "--exclude" ".DS_Store" "--suppress-common-lines" "-I" ".*<contextType>.*" "-I" ".*<time>.*" "-I" ".*<timezone>.*" "-I" ".*<revision>.*" (str repo-dir "/FullBackup.xml") (str baseline-dir "/../mirth-backup-" version ".xml"))))
      (is (= 0 (do
                 (main-func "-s" "https://localhost:8443/api"
                            "-u" "admin" "-p" "admin" "-t" repo-dir
                            "-i" "-m" "backup" "push"))))))

  (testing "Disk mode 1 pull and push works"
    (let [repo-dir (str repo-dir "-1")
          baseline-dir (str baseline-dir "-1")]
      (is (= 0 (do
                 (main-func "-s" "https://localhost:8443/api"
                            "-u" "admin" "-p" "admin" "-t" repo-dir
                            "-i" "-m" "groups" "--include-configuration-map" "pull"))))
      (is (= "" (diff "--exclude" ".DS_Store" "--recursive" "--suppress-common-lines" "-I" ".*<contextType>.*" "-I" ".*<time>.*" "-I" ".*<timezone>.*" "-I" ".*<revision>.*" repo-dir baseline-dir)))
      (is (= 0 (do
                 (main-func "-s" "https://localhost:8443/api"
                            "-u" "admin" "-p" "admin" "-t" repo-dir
                            "-i" "-m" "groups" "push")))))))


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

;; ignore a few extra line types
;; (diff "--recursive" "--suppress-common-lines" "-I" ".*<contextType>.*" "-I" ".*<time>.*" "-I" ".*<timezone>.*" "-I" ".*<revision>.*" "-I" ".*version=\"[[:digit:]].[[:digit:]]\\+.[[:digit:]]\\+\".*" "-I" ".*<pruneErroredMessages>.*" repo-dir (str "dev-resources/mirth-" version "-baseline"))
