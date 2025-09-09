(ns mirthsync.git-test
  (:require [mirthsync.git :refer :all]
            [mirthsync.cli :as cli]
            [mirthsync.core :as core]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.java.shell]
            [clojure.string]
            [clj-jgit.porcelain :as git])
  (:import java.io.File))

(def ^:dynamic *test-dir* nil)

(defn temp-dir-fixture
  "Creates a temporary directory for testing and cleans it up"
  [test-fn]
  (let [temp-dir (str "/tmp/mirthsync-git-test-" (System/currentTimeMillis))]
    (try
      (.mkdirs (File. temp-dir))
      (binding [*test-dir* temp-dir]
        (test-fn))
      (finally
        (when (.exists (File. temp-dir))
          (let [dir (File. temp-dir)]
            (doseq [^File file (reverse (file-seq dir))]
              (.delete file))))))))

(use-fixtures :each temp-dir-fixture)

(defn- git-repo-exists-test?
  "Helper function to test if git repo exists"
  [target-dir]
  (let [git-dir (File. target-dir ".git")]
    (.exists git-dir)))

(deftest test-git-repo-exists
  (testing "git repo detection works for non-existent repo"
    (is (not (git-repo-exists-test? *test-dir*))))
  
  (testing "git repo detection works after repo initialization"
    (ensure-git-repo *test-dir*)
    (is (git-repo-exists-test? *test-dir*))))

(deftest test-ensure-git-repo
  (testing "ensure-git-repo creates new repository"
    (let [repo (ensure-git-repo *test-dir*)]
      (is (.exists (File. *test-dir* ".git")))
      (is (not (nil? repo)))))
  
  (testing "ensure-git-repo returns existing repository"
    (ensure-git-repo *test-dir*) ; Create first
    (let [repo (ensure-git-repo *test-dir*)] ; Should return existing
      (is (.exists (File. *test-dir* ".git")))
      (is (not (nil? repo))))))

(deftest test-git-status
  (testing "git-status with non-existent repo returns nil"
    (is (nil? (git-status *test-dir*))))
  
  (testing "git-status with clean repo shows clean status"
    (ensure-git-repo *test-dir*)
    (let [status (git-status *test-dir*)]
      (is (not (nil? status)))
      (is (empty? (:untracked status)))
      (is (empty? (:modified status)))
      (is (empty? (:added status)))
      (is (empty? (:removed status)))))
  
  (testing "git-status shows untracked files"
    (ensure-git-repo *test-dir*)
    (spit (File. *test-dir* "test.txt") "test content")
    (let [status (git-status *test-dir*)]
      (is (contains? (:untracked status) "test.txt")))))

(deftest test-git-add-all
  (testing "git-add-all with non-existent repo returns false"
    (is (not (git-add-all *test-dir*))))
  
  (testing "git-add-all stages files correctly"
    (ensure-git-repo *test-dir*)
    (spit (File. *test-dir* "test.txt") "test content")
    (is (git-add-all *test-dir*))
    (let [status (git-status *test-dir*)]
      (is (contains? (:added status) "test.txt"))
      (is (not (contains? (:untracked status) "test.txt"))))))

(deftest test-git-commit
  (testing "git-commit with non-existent repo returns false"
    (is (not (git-commit *test-dir* "test commit"))))
  
  (testing "git-commit with no staged changes succeeds (JGit allows empty commits)"
    (ensure-git-repo *test-dir*)
    ;; JGit allows empty commits unlike native git, so this should succeed
    (is (git-commit *test-dir* "empty commit")))
  
  (testing "git-commit with staged changes succeeds"
    (ensure-git-repo *test-dir*)
    (spit (File. *test-dir* "test.txt") "test content")
    (git-add-all *test-dir*)
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (is (git-commit *test-dir* "test commit"))
    (let [status (git-status *test-dir*)]
      (is (empty? (:added status)))
      (is (empty? (:modified status))))))

(deftest test-auto-commit
  (testing "auto-commit stages and commits files"
    (ensure-git-repo *test-dir*)
    (spit (File. *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (is (auto-commit *test-dir* "auto commit test"))
    (let [status (git-status *test-dir*)]
      (is (empty? (:untracked status)))
      (is (empty? (:added status))))))

(deftest test-git-operation-dispatch
  (testing "git-operation handles init subcommand"
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "init")]
      (is (= 0 (:exit-code result)))
      (is (.exists (File. *test-dir* ".git")))))
  
  (testing "git-operation handles status subcommand"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "status")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles commit subcommand"
    (ensure-git-repo *test-dir*)
    (spit (File. *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (let [app-conf {:target *test-dir* :commit-message "test commit"}
          result (git-operation app-conf "commit")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles unknown subcommand"
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "unknown")]
      (is (= 1 (:exit-code result)))
      (is (re-find #"Unknown git subcommand" (:exit-msg result))))))

(deftest test-cli-git-integration
  (testing "CLI accepts git commands with proper validation"
    (let [config (cli/config ["-t" *test-dir* "git" "init"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["init"] (:arguments config)))))
  
  (testing "CLI rejects git commands without subcommand"
    (let [config (cli/config ["-t" *test-dir* "git"])]
      (is (= 1 (:exit-code config)))
      (is (not (nil? (:exit-msg config))))))
  
  (testing "CLI accepts git commands without server credentials"
    (let [config (cli/config ["-t" *test-dir* "git" "status"])]
      (is (= 0 (:exit-code config)))
      (is (nil? (:server config)))
      (is (nil? (:username config))))))

(deftest test-auto-commit-functions
  (testing "generate-commit-message creates appropriate messages"
    (is (= "Pull from https://server:8443/api" 
           (generate-commit-message {:action "pull" :server "https://server:8443/api" :commit-message "mirthsync commit"})))
    (is (= "Push to https://server:8443/api" 
           (generate-commit-message {:action "push" :server "https://server:8443/api" :commit-message "mirthsync commit"})))
    (is (= "Custom message" 
           (generate-commit-message {:action "pull" :server "server" :commit-message "Custom message"}))))

  (testing "auto-commit-after-operation with git-init"
    (let [app-conf {:target *test-dir* :auto-commit true :git-init true :action "pull" :server "test-server"}]
      (auto-commit-after-operation app-conf)
      ;; Should have created git repo and made initial commit
      (is (git-repo-exists-test? *test-dir*))))

  (testing "auto-commit-after-operation without git repo warns"
    ;; Use a different temp directory to isolate this test
    (let [temp-dir (str "/tmp/mirthsync-git-test-warn-" (System/currentTimeMillis))
          app-conf {:target temp-dir :auto-commit true :action "pull" :server "test-server"}]
      (try
        (.mkdirs (java.io.File. temp-dir))
        ;; Should log warning but not create repo without git-init flag
        (auto-commit-after-operation app-conf)
        (is (not (git-repo-exists-test? temp-dir)))
        (finally
          ;; Clean up
          (when (.exists (java.io.File. temp-dir))
            (let [dir (java.io.File. temp-dir)]
              (doseq [^java.io.File file (reverse (file-seq dir))]
                (.delete file))))))))

  (testing "auto-commit-after-operation disabled does nothing"
    ;; Use a different temp directory to isolate this test
    (let [temp-dir (str "/tmp/mirthsync-git-test-disabled-" (System/currentTimeMillis))
          app-conf {:target temp-dir :action "pull" :server "test-server"}]
      (try
        (.mkdirs (java.io.File. temp-dir))
        (auto-commit-after-operation app-conf)
        (is (not (git-repo-exists-test? temp-dir)))
        (finally
          ;; Clean up
          (when (.exists (java.io.File. temp-dir))
            (let [dir (java.io.File. temp-dir)]
              (doseq [^java.io.File file (reverse (file-seq dir))]
                (.delete file)))))))))

(deftest test-auto-commit-cli-integration
  (testing "CLI accepts auto-commit flags"
    (let [config (cli/config ["-s" "https://server:8443/api" "-u" "admin" "-p" "password" "-t" *test-dir* "--auto-commit" "--git-init" "pull"])]
      (is (= 0 (:exit-code config)))
      (is (:auto-commit config))
      (is (:git-init config))))

  (testing "CLI auto-commit options have correct defaults"
    (let [config (cli/config ["-s" "https://server:8443/api" "-u" "admin" "-p" "password" "-t" *test-dir* "pull"])]
      (is (= 0 (:exit-code config)))
      (is (not (:auto-commit config)))
      (is (not (:git-init config))))))

;; Integration test using the git-operation directly 
(deftest test-git-integration-end-to-end
  (testing "Full git workflow integration"
    (let [app-conf {:target *test-dir* :commit-message "integration test"}]
      ;; Test init
      (let [init-result (git-operation app-conf "init")]
        (is (= 0 (:exit-code init-result)))
        (is (git-repo-exists-test? *test-dir*)))
      
      ;; Test status on empty repo
      (let [status-result (git-operation app-conf "status")]
        (is (= 0 (:exit-code status-result))))
      
      ;; Create file and test commit
      (spit (File. *test-dir* "integration-test.txt") "integration test content")
      ;; Configure git for testing
      (let [repo (git/load-repo *test-dir*)]
        (-> (git/git-config-load repo)
            (git/git-config-set "commit.gpgsign" "false")
            (git/git-config-set "user.name" "Integration Test")
            (git/git-config-set "user.email" "integration@test.com")
            (git/git-config-save)))
      
      (let [commit-result (git-operation app-conf "commit")]
        (is (= 0 (:exit-code commit-result))))
      
      ;; Test final status is clean
      (let [final-status-result (git-operation app-conf "status")]
        (is (= 0 (:exit-code final-status-result)))))))