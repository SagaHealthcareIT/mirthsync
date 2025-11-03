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
                :ignore-cert-warnings false,
                :arguments (),
                :commit-message "mirthsync commit",
                :git-author (System/getProperty "user.name"),
                :auto-commit false,
                :git-init false,
                :delete-orphaned false}]
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
                :ignore-cert-warnings false,
                :arguments (),
                :commit-message "mirthsync commit",
                :git-author (System/getProperty "user.name"),
                :auto-commit false,
                :git-init false,
                :delete-orphaned false}]
      (is (= conf (config ["-f" "-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "pull"])))))

  (testing "Force defaults to nil"
    (is (nil? (:force (config ["pull"])))))

  (testing "Delete-orphaned flag is parsed correctly"
    (let [conf (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "--delete-orphaned" "pull"])]
      (is (= true (:delete-orphaned conf)))))

  (testing "Delete-orphaned defaults to false"
    (let [conf (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "pull"])]
      (is (= false (:delete-orphaned conf)))))

  (testing "Deploy flag is parsed correctly"
    (let [conf (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "--deploy" "push"])]
      (is (= true (:deploy conf)))))

  (testing "Deploy defaults to nil"
    (let [conf (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "push"])]
      (is (nil? (:deploy conf)))))

  (testing "Deploy-all flag is parsed correctly"
    (let [conf (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "--deploy-all" "push"])]
      (is (= true (:deploy-all conf)))))

  (testing "Deploy-all defaults to nil"
    (let [conf (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "push"])]
      (is (nil? (:deploy-all conf)))))

  (testing "Deploy and deploy-all are mutually exclusive options"
    (let [conf-with-deploy (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "--deploy" "push"])
          conf-with-deploy-all (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "-t" "foo" "--deploy-all" "push"])]
      (is (and (:deploy conf-with-deploy) (nil? (:deploy-all conf-with-deploy))))
      (is (and (:deploy-all conf-with-deploy-all) (nil? (:deploy conf-with-deploy-all))))))

  (testing "Token authentication is accepted"
    (let [conf (config ["-s" "https://localhost:8443/api" "--token" "test-token-123" "-t" "foo" "pull"])]
      (is (= 0 (:exit-code conf)))
      (is (nil? (:exit-msg conf)))
      (is (= "test-token-123" (:token conf)))))

  (testing "Token and username/password are mutually exclusive"
    (let [conf (config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "password" "--token" "test-token" "-t" "foo" "pull"])]
      (is (= 1 (:exit-code conf)))
      (is (seq (:exit-msg conf)))
      (is (re-find #"mutually exclusive" (:exit-msg conf)))))

  (testing "Git operations don't require server or authentication"
    (let [conf (config ["-t" "foo" "git" "status"])]
      (is (= 0 (:exit-code conf)))
      (is (nil? (:exit-msg conf)))
      (is (= "git" (:action conf)))))

  (testing "Push/pull require authentication (username/password or token)"
    (let [conf-no-auth (config ["-s" "https://localhost:8443/api" "-t" "foo" "pull"])]
      (is (= 1 (:exit-code conf-no-auth)))
      (is (seq (:exit-msg conf-no-auth)))
      (is (re-find #"Authentication required" (:exit-msg conf-no-auth)))))

  (testing "Push/pull require server"
    (let [conf-no-server (config ["-u" "admin" "-p" "password" "-t" "foo" "pull"])]
      (is (= 1 (:exit-code conf-no-server)))
      (is (seq (:exit-msg conf-no-server)))
      (is (re-find #"--server is required" (:exit-msg conf-no-server))))))


