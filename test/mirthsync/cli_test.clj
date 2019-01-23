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
      nil))

  (testing "Verbosity increases with extra v's"
    (is (= 3 (:verbosity (config ["-vvv" "push"])))))

  (testing "Sensible push defaults"
    (let [conf {:errors nil,
                :exit-code 0,
                :verbosity 0,
                :password "",
                :server "https://localhost:8443/api",
                :username "admin",
                :action "push",
                :target ".",
                :exit-msg nil}]
      (is (= conf (config ["push"])))))

  (testing "Sensible pull defaults"
    (let [conf {:errors nil,
                :exit-code 0,
                :force true,
                :verbosity 0,
                :password "",
                :server "https://localhost:8443/api",
                :username "admin",
                :action "pull",
                :target ".",
                :exit-msg nil}]
      (is (= conf (config ["-f" "pull"])))))

  (testing "Force defaults to nil"
    (is (nil? (:force (config ["pull"]))))))


