(ns mirthsync.cli-test
  (:require [mirthsync.cli :refer :all]
            [clojure.test :refer :all]
            [clojure.tools
             [cli :refer [parse-opts]]]))

(deftest configuration
  (testing "Fail on invalid arguments"
    (are [args] (let [conf (config args)]
                  (is (seq (:exit-msg conf)))
                  (is (= 1 (:exit-code conf))))
      ["blah"]
      ["-z"]
      [""]
      ["-f"]
      ;; following is invalid due to the resource path traversal
      ["-s" "https://localhost:8443/api/" "-u" "admin" "-p" "password"  "-t" "./tmp/" "-r" "../foo" "push"]
      nil))

  (testing "Verbosity increases with extra v's"
    (is (= 3 (:verbosity (config ["-vvv" "push"])))))

  (testing "Sensible push defaults and ensure end slashes stripped from
  api and target"
    (let [conf {:errors nil,
                :exit-code 0,
                :verbosity 0,
                :password "password",
                :server "https://localhost:8443/api",
                :include-configuration-map false,
                :username "admin",
                :disk-mode "code",
                :action "push",
                :skip-disabled false,
                :target "./tmp",
                :restrict-to-path "",
                :exit-msg nil,
                :ignore-cert-warnings false}]
      (is (= conf (config ["-s" "https://localhost:8443/api/" "-u" "admin" "-p" "password"  "-t" "./tmp/" "push"])))))

  (testing "Sensible pull defaults"
    (let [conf {:errors nil,
                :exit-code 0,
                :force true,
                :verbosity 0,
                :password "password",
                :server "https://localhost:8443/api",
                :include-configuration-map false,
                :username "admin",
                :disk-mode "code",
                :action "pull",
                :skip-disabled false,
                :target "foo",
                :restrict-to-path "",
                :exit-msg nil,
                :ignore-cert-warnings false}]
      (is (= conf (config ["-f" "-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "pull"])))))

  (testing "Force defaults to nil"
    (is (nil? (:force (config ["pull"]))))))


