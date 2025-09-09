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
  
  (testing "git-commit with no staged changes returns false"
    (ensure-git-repo *test-dir*)
    ;; Note: JGit may allow empty commits unlike native git
    ;; This test might need adjustment based on clj-jgit behavior
    (is (not (git-commit *test-dir* "empty commit"))))
  
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