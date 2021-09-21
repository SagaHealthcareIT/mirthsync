(ns mirthsync.integration-test-8
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]))

(use-fixtures :once mirth-8-fixture)

(def baseline-dir "dev-resources/mirth-8-baseline")
(def repo-dir "target/tmp-8")

(deftest integration
  (testing "Actions fail with default params and invalid certification path."
    (is (= 1 (main-func "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" repo-dir
                        "-f" "pull"))))
  
  (testing "Actions fail with invalid credentials"
    (is (= 1 (main-func "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "invalidpass" "-t" repo-dir
                        "-i" "-f" "pull"))))

  (testing "Push from baseline succeeds without errors"
    (is (= 0 (main-func "--include-configuration-map" "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" baseline-dir
                        "-i" "-f" "push"))))

  (testing "Pull from Mirth succeeds without errors"
    (is (= 0 (main-func "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" repo-dir
                        "-i" "-f" "pull"))))

  (testing "Pull diff from baseline has only inconsequential differences (ordering, etc)"
    (is (= "" (diff "--recursive" "--suppress-common-lines" "-I" ".*<contextType>.*" "-I" ".*<time>.*" "-I" ".*<timezone>.*" "-I" ".*<revision>.*" repo-dir "dev-resources/mirth-8-baseline"))))

    (testing "Push back from pull dir succeeds without errors"
    (is (= 0 (main-func "--include-configuration-map" "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" repo-dir
                        "-i" "-f" "push"))))

  (testing "Pull back from Mirth after last push succeeds without errors"
    (is (= 0 (main-func "-s" "https://localhost:8443/api"
                        "-u" "admin" "-p" "admin" "-t" repo-dir
                        "-i" "-f" "pull"))))

  (testing "Pull diff from baseline after multiple pushes has only inconsequential differences (ordering, etc)"
    (is (= "" (diff "--recursive" "--suppress-common-lines" "-I" ".*<contextType>.*" "-I" ".*<time>.*" "-I" ".*<timezone>.*" "-I" ".*<revision>.*" repo-dir "dev-resources/mirth-8-baseline")))))


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
