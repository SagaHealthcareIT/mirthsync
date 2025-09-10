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
      (.mkdirs (File. ^String temp-dir))
      (binding [*test-dir* temp-dir]
        (test-fn))
      (finally
        (when (.exists (File. ^String temp-dir))
          (let [dir (File. ^String temp-dir)]
            (doseq [^File file (reverse (file-seq dir))]
              (.delete file))))))))

(use-fixtures :each temp-dir-fixture)

(defn- git-repo-exists-test?
  "Helper function to test if git repo exists"
  [target-dir]
  (let [git-dir (File. ^String target-dir ".git")]
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
      (is (.exists (File. ^String *test-dir* ".git")))
      (is (not (nil? repo)))))
  
  (testing "ensure-git-repo returns existing repository"
    (ensure-git-repo *test-dir*) ; Create first
    (let [repo (ensure-git-repo *test-dir*)] ; Should return existing
      (is (.exists (File. ^String *test-dir* ".git")))
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
    (spit (File. ^String *test-dir* "test.txt") "test content")
    (let [status (git-status *test-dir*)]
      (is (contains? (:untracked status) "test.txt")))))

(deftest test-git-add-all
  (testing "git-add-all with non-existent repo returns false"
    (is (not (git-add-all *test-dir*))))
  
  (testing "git-add-all stages files correctly"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
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
    (spit (File. ^String *test-dir* "test.txt") "test content")
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
    (spit (File. ^String *test-dir* "test.txt") "test content")
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
      (is (.exists (File. ^String *test-dir* ".git")))))
  
  (testing "git-operation handles add subcommand"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "add")]
      (is (= 0 (:exit-code result)))
      (let [status (git-status *test-dir*)]
        (is (contains? (:added status) "test.txt")))))

  (testing "git-operation handles commit subcommand"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    (git-add-all *test-dir*)
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (let [app-conf {:target *test-dir* :commit-message "test commit"}
          result (git-operation app-conf "commit")]
      (is (= 0 (:exit-code result)))
      (let [status (git-status *test-dir*)]
        (is (empty? (:added status))))))

  (testing "git-operation handles commit subcommand with no staged changes"
    (let [test-dir (str (System/getProperty "java.io.tmpdir") "/mirthsync-git-test-" (System/currentTimeMillis))]
      (ensure-git-repo test-dir)
      ;; Configure git for testing
      (let [repo (git/load-repo test-dir)]
        (-> (git/git-config-load repo)
            (git/git-config-set "commit.gpgsign" "false")
            (git/git-config-set "user.name" "Test User")
            (git/git-config-set "user.email" "test@example.com")
            (git/git-config-save)))
      (let [app-conf {:target test-dir :commit-message "empty commit"}
            result (git-operation app-conf "commit")]
        (is (= 0 (:exit-code result))) ; Should succeed but not create a commit
        (let [log-result (git-log test-dir 5)]
          (is (empty? log-result)))))) ; Should have no commits
  
  (testing "git-operation handles unknown subcommand"
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "unknown")]
      (is (= 1 (:exit-code result)))
      (is (= "Unknown git subcommand: unknown" (:exit-msg result)))))

  (testing "git-operation handles checkout subcommand"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "first commit")
    (let [repo (git/load-repo *test-dir*)]
      (git/git-branch-create repo "develop"))

    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "checkout" "develop")]
      (is (= 0 (:exit-code result)))
      (let [repo (git/load-repo *test-dir*)]
        (is (= "develop" (.. repo getRepository getBranch))))))

  (testing "git-operation handles checkout without branch name"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "checkout")]
      (is (= 1 (:exit-code result)))
      (is (= "Branch name required for checkout" (:exit-msg result)))))

  (testing "git-operation handles pull subcommand"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "pull")]
      (is (= 1 (:exit-code result))) ; Fails as no remote configured
      (is (= "Git pull failed" (:exit-msg result)))))

  (testing "git-operation handles push subcommand"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "push")]
      (is (= 1 (:exit-code result))) ; Fails as no remote configured
      (is (= "Git push failed" (:exit-msg result)))))

  (testing "git-operation handles reset subcommand with default mixed mode"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    ;; Add and stage more changes
    (spit (File. ^String *test-dir* "test.txt") "modified content")
    (git-add-all *test-dir*)
    ;; Reset should succeed (mixed mode by default)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "reset")]
      (is (= 0 (:exit-code result)))))

  (testing "git-operation handles reset with --soft flag"
    (let [test-dir (str (System/getProperty "java.io.tmpdir") "/mirthsync-git-test-reset-soft-" (System/currentTimeMillis))]
      (ensure-git-repo test-dir)
      (spit (File. ^String test-dir "test.txt") "initial test content")
      ;; Configure git for testing
      (let [repo (git/load-repo test-dir)]
        (-> (git/git-config-load repo)
            (git/git-config-set "commit.gpgsign" "false")
            (git/git-config-set "user.name" "Test User")
            (git/git-config-set "user.email" "test@example.com")
            (git/git-config-save)))
      (git-add-all test-dir)
      (git-commit test-dir "initial commit")
      ;; Modify and commit again with distinctly different content
      (Thread/sleep 100) ; Brief delay to ensure file timestamp changes
      (spit (File. ^String test-dir "test.txt") "completely different content for the second commit")
      ;; Check status before staging
      (let [status-before (git-status test-dir)]
        (when (empty? (:modified status-before))
          (throw (Exception. "File modification not detected by git"))))
      (git-add-all test-dir)
      (git-commit test-dir "second commit")
      ;; Verify we have commits to work with
      (let [log-result (git-log test-dir 5)]
        (is (>= (count log-result) 2))) ; Should have at least 2 commits
      ;; Soft reset to previous commit should succeed
      (let [app-conf {:target test-dir}
            result (git-operation app-conf "reset" "--soft" "HEAD~1")]
        (is (= 0 (:exit-code result))))))

  (testing "git-operation handles reset with --hard flag"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    ;; Modify file and stage changes
    (spit (File. ^String *test-dir* "test.txt") "modified content")
    (git-add-all *test-dir*)
    ;; Hard reset should succeed and discard changes
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "reset" "--hard")]
      (is (= 0 (:exit-code result)))))

  (testing "git-operation handles reset with target commit"
    (let [test-dir (str (System/getProperty "java.io.tmpdir") "/mirthsync-git-test-reset-target-" (System/currentTimeMillis))]
      (ensure-git-repo test-dir)
      (spit (File. ^String test-dir "test.txt") "test content")
      ;; Configure git for testing
      (let [repo (git/load-repo test-dir)]
        (-> (git/git-config-load repo)
            (git/git-config-set "commit.gpgsign" "false")
            (git/git-config-set "user.name" "Test User")
            (git/git-config-set "user.email" "test@example.com")
            (git/git-config-save)))
      (git-add-all test-dir)
      (git-commit test-dir "initial commit")
      ;; Create second commit
      (Thread/sleep 100) ; Brief delay to ensure file timestamp changes
      (spit (File. ^String test-dir "test2.txt") "second file content")
      (git-add-all test-dir)
      (git-commit test-dir "second commit")
      ;; Reset to previous commit using HEAD~1
      (let [app-conf {:target test-dir}
            result (git-operation app-conf "reset" "HEAD~1")]
        (is (= 0 (:exit-code result))))))

  (testing "git-operation handles reset without git repository"
    (let [non-git-dir (str "/tmp/mirthsync-no-git-" (System/currentTimeMillis))]
      (.mkdirs (File. ^String non-git-dir))
      (try
        (let [app-conf {:target non-git-dir}
              result (git-operation app-conf "reset")]
          (is (= 1 (:exit-code result)))
          (is (= "Git reset failed" (:exit-msg result))))
        (finally
          (let [dir (File. ^String non-git-dir)]
            (doseq [^File file (reverse (file-seq dir))]
              (.delete file)))))))
)

(deftest test-cli-git-integration
  (testing "CLI accepts git commands with proper validation"
    (let [config (cli/config ["-t" *test-dir* "git" "init"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["init"] (vec (:arguments config))))))
  
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
        (.mkdirs (java.io.File. ^String temp-dir))
        ;; Should log warning but not create repo without git-init flag
        (auto-commit-after-operation app-conf)
        (is (not (git-repo-exists-test? temp-dir)))
        (finally
          ;; Clean up
          (when (.exists (java.io.File. ^String temp-dir))
            (let [dir (java.io.File. ^String temp-dir)]
              (doseq [^java.io.File file (reverse (file-seq dir))]
                (.delete file))))))))

  (testing "auto-commit-after-operation disabled does nothing"
    ;; Use a different temp directory to isolate this test
    (let [temp-dir (str "/tmp/mirthsync-git-test-disabled-" (System/currentTimeMillis))
          app-conf {:target temp-dir :action "pull" :server "test-server"}]
      (try
        (.mkdirs (java.io.File. ^String temp-dir))
        (auto-commit-after-operation app-conf)
        (is (not (git-repo-exists-test? temp-dir)))
        (finally
          ;; Clean up
          (when (.exists (java.io.File. ^String temp-dir))
            (let [dir (java.io.File. ^String temp-dir)]
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
      
      ;; Create file, add, and commit
      (spit (File. ^String *test-dir* "integration-test.txt") "integration test content")
      (is (= 0 (:exit-code (git-operation app-conf "add"))))
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
      (let [status (git-status *test-dir*)]
        (is (empty? (:untracked status)))
        (is (empty? (:added status)))
        (is (empty? (:modified status)))
        (is (empty? (:removed status)))))))

(deftest test-git-diff
  (testing "git-diff with non-existent repo returns nil"
    (is (nil? (git-diff *test-dir*))))
  
  (testing "git-diff with clean repo shows no differences"
    (ensure-git-repo *test-dir*)
    (let [diff (git-diff *test-dir*)]
      (is (= [] diff))))
  
  (testing "git-diff shows unstaged differences (working dir vs index)"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "initial content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    
    ;; Modify file (unstaged change)
    (spit (File. ^String *test-dir* "test.txt") "modified content")
    (let [diff (git-diff *test-dir*)]
      (is (not (empty? diff))))))

(deftest test-git-diff-staged
  (testing "git-diff --staged shows staged changes (index vs HEAD)"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "initial content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    
    ;; Modify file and stage it
    (spit (File. ^String *test-dir* "test.txt") "modified and staged content")
    (git-add-all *test-dir*)
    
    ;; Should show staged differences
    (let [staged-diff (git-diff *test-dir* {:staged true})]
      (is (not (empty? staged-diff))))
    
    ;; Should also work with :cached option
    (let [cached-diff (git-diff *test-dir* {:cached true})]
      (is (not (empty? cached-diff)))))
  
  (testing "git-diff --staged with initial commit shows all staged files"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "new-file.txt") "new file content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    
    ;; Before any commit, staged diff should show all staged files
    (let [staged-diff (git-diff *test-dir* {:staged true})]
      (is (not (empty? staged-diff))))))

(deftest test-git-diff-revisions
  (testing "git-diff with single revision shows changes from HEAD"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "initial content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    
    ;; Make another commit
    (spit (File. ^String *test-dir* "test.txt") "second content")
    (git-add-all *test-dir*)
    (git-commit *test-dir* "second commit")
    
    ;; Check diff against HEAD~1..HEAD (should show changes from previous commit to current)
    (let [rev-diff (git-diff *test-dir* {:revision-spec "HEAD~1..HEAD"})]
      (is (not (empty? rev-diff)))))
  
  (testing "git-diff with revision range shows changes between commits"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "range-test.txt") "first content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "range test first commit")
    
    ;; Make second commit  
    (spit (File. ^String *test-dir* "range-test.txt") "second content")
    (git-add-all *test-dir*)
    (git-commit *test-dir* "range test second commit")
    
    ;; Make third commit
    (spit (File. ^String *test-dir* "range-test.txt") "third content")
    (git-add-all *test-dir*)
    (git-commit *test-dir* "range test third commit")
    
    ;; Check diff between HEAD~2 and HEAD~1 (should show first->second change)
    (let [range-diff (git-diff *test-dir* {:revision-spec "HEAD~2..HEAD~1"})]
      (is (not (empty? range-diff))))))

(deftest test-parse-revision-spec
  (testing "parse-revision-spec handles nil input"
    (is (nil? (parse-revision-spec nil))))
  
  (testing "parse-revision-spec handles single revision"
    (let [parsed (parse-revision-spec "HEAD")]
      (is (= "HEAD" (:old-rev parsed)))
      (is (nil? (:new-rev parsed)))))
  
  (testing "parse-revision-spec handles revision range"
    (let [parsed (parse-revision-spec "HEAD~2..HEAD")]
      (is (= "HEAD~2" (:old-rev parsed)))
      (is (= "HEAD" (:new-rev parsed)))))
  
  (testing "parse-revision-spec handles range with empty old revision"
    (let [parsed (parse-revision-spec "..HEAD")]
      (is (nil? (:old-rev parsed)))
      (is (= "HEAD" (:new-rev parsed)))))
  
  (testing "parse-revision-spec handles range with empty new revision"
    (let [parsed (parse-revision-spec "HEAD~1..")]
      (is (= "HEAD~1" (:old-rev parsed)))
      (is (nil? (:new-rev parsed))))))

(deftest test-git-operation-diff-commands
  (testing "git-operation handles diff subcommand with no options"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "diff")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles diff --staged subcommand"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "diff" "--staged")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles diff --cached subcommand"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "diff" "--cached")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles diff with revision spec"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "initial content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "diff" "HEAD")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles diff with revision range"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "initial content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "first commit")
    
    ;; Make another commit
    (spit (File. ^String *test-dir* "test.txt") "modified content")
    (git-add-all *test-dir*)
    (git-commit *test-dir* "second commit")
    
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "diff" "HEAD~1..HEAD")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation ignores --staged when revision spec provided"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "initial content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    
    ;; When both --staged and revision-spec provided, revision-spec takes precedence
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "diff" "--staged" "HEAD")]
      (is (= 0 (:exit-code result))))))

(deftest test-git-log
  (testing "git-log with non-existent repo returns nil"
    (is (nil? (git-log *test-dir*))))
  
  (testing "git-log with empty repo returns empty list"
    (ensure-git-repo *test-dir*)
    (let [log (git-log *test-dir*)]
      (is (= [] log))))
  
  (testing "git-log shows commits after making commits"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "first commit")
    
    ;; Make another commit
    (spit (File. ^String *test-dir* "test2.txt") "second file")
    (git-add-all *test-dir*)
    (git-commit *test-dir* "second commit")
    
    (let [log (git-log *test-dir* 5)]
      (is (= 2 (count log)))
      ;; Most recent commit should be first
      (is (clojure.string/includes? (:message (first log)) "second commit"))))
  
  (testing "git-log respects limit parameter"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    
    ;; Make 3 commits
    (dotimes [i 3]
      (spit (File. ^String *test-dir* (str "file" i ".txt")) (str "content " i))
      (git-add-all *test-dir*)
      (git-commit *test-dir* (str "commit " i)))
    
    (let [log (git-log *test-dir* 2)]
      (is (= 2 (count log))))))

(deftest test-git-branch
  (testing "git-branch with non-existent repo returns nil"
    (is (nil? (git-branch *test-dir*))))
  
  (testing "git-branch with empty repo returns empty list"
    (ensure-git-repo *test-dir*)
    (let [branches (git-branch *test-dir*)]
      (is (= [] branches))))
  
  (testing "git-branch shows branches after making commits"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "first commit")
    
    (let [repo (git/load-repo *test-dir*)]
        (git/git-branch-create repo "develop"))
    
    (let [branches (git-branch *test-dir*)]
      (is (= 2 (count branches)))
      (is (some #{"develop"} branches))
      (is (some #(or (= "master" %) (= "main" %)) branches)))))

(deftest test-git-checkout
  (testing "git-checkout with non-existent repo returns false"
    (is (not (git-checkout *test-dir* "master"))))

  (testing "git-checkout to non-existent branch returns false"
    (ensure-git-repo *test-dir*)
    (is (not (git-checkout *test-dir* "non-existent-branch"))))

  (testing "git-checkout to existing branch succeeds"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "first commit")
    (let [repo (git/load-repo *test-dir*)]
      (git/git-branch-create repo "develop"))
    (is (git-checkout *test-dir* "develop"))
    (let [repo (git/load-repo *test-dir*)]
      (is (= "develop" (.. repo getRepository getBranch))))))

(deftest test-git-remote
  (testing "git-remote with non-existent repo returns nil"
    (is (nil? (git-remote *test-dir*))))

  (testing "git-remote with no remotes returns an empty map"
    (ensure-git-repo *test-dir*)
    (is (= {} (git-remote *test-dir*))))

  (testing "git-remote lists remotes correctly"
    (ensure-git-repo *test-dir*)
    (let [repo (git/load-repo *test-dir*)]
      (git/git-remote-add repo "origin" "https://github.com/test/repo.git"))
    (let [remotes (git-remote *test-dir*)]
      (is (= 1 (count remotes)))
      (is (= "https://github.com/test/repo.git" (get-in remotes ["origin" :fetch])))
      (is (= "https://github.com/test/repo.git" (get-in remotes ["origin" :push]))))))

(deftest test-git-pull
  (testing "git-pull with non-existent repo returns false"
    (is (not (git-pull *test-dir*))))

  (testing "git-pull on repo with no remote fails gracefully"
    (ensure-git-repo *test-dir*)
    ;; jgit pull on a repo with no remote throws an exception
    (is (not (git-pull *test-dir*))))

  (testing "git-pull works correctly with a remote"
    ;; 1. Set up a bare "remote" repository
    (let [remote-dir (io/file (str "/tmp/mirthsync-git-test-remote-" (System/currentTimeMillis)))]
      (try
        (.mkdirs remote-dir)
        (git/git-init :dir (.getPath remote-dir) :bare true)

        ;; 2. Clone the remote to a fresh test directory
        (let [clone-dir (str "/tmp/mirthsync-git-test-clone-" (System/currentTimeMillis))]
          (git/git-clone (str "file://" (.getPath remote-dir)) :dir clone-dir)
          
          ;; Configure git for the clone
          (let [clone-repo (git/load-repo clone-dir)]
            (-> (git/git-config-load clone-repo)
                (git/git-config-set "commit.gpgsign" "false")
                (git/git-config-set "user.name" "Test User")
                (git/git-config-set "user.email" "test@example.com")
                (git/git-config-save)))

          ;; 3. Make a commit in a temporary clone of the remote to simulate a change
          (let [temp-clone-dir (io/file (str "/tmp/mirthsync-git-test-temp-clone-" (System/currentTimeMillis)))]
            (try
              (git/git-clone (str "file://" (.getPath remote-dir)) :dir (.getPath temp-clone-dir))
              (spit (io/file temp-clone-dir "remote_file.txt") "from remote")
              (let [temp-repo (git/load-repo (.getPath temp-clone-dir))]
                (-> (git/git-config-load temp-repo)
                    (git/git-config-set "commit.gpgsign" "false")
                    (git/git-config-set "user.name" "Remote User")
                    (git/git-config-set "user.email" "remote@example.com")
                    (git/git-config-save))
                (git/git-add temp-repo "remote_file.txt")
                (git/git-commit temp-repo "commit on remote")
                (git/git-push temp-repo))
              (finally
                (when (.exists temp-clone-dir)
                  (doseq [^File file (reverse (file-seq temp-clone-dir))]
                    (.delete file))))))

          ;; 4. Now, pull the changes in our original repo
          (is (git-pull clone-dir))

          ;; 5. Verify the file from remote exists
          (is (.exists (io/file clone-dir "remote_file.txt"))))

        (finally
          (when (.exists remote-dir)
            (doseq [^File file (reverse (file-seq remote-dir))]
              (.delete file))))))))

(deftest test-git-operation-read-commands
  (testing "git-operation handles status subcommand"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "status")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles diff subcommand"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "diff")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles log subcommand with default limit"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "log")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles log subcommand with custom limit"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "log" "5")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles log subcommand with invalid limit"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "log" "not-a-number")]
      (is (= 0 (:exit-code result))))) ; Should default to 10 when parsing fails
  
  (testing "git-operation handles branch subcommand"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "branch")]
      (is (= 0 (:exit-code result)))))
  
  (testing "git-operation handles remote subcommand"
    (ensure-git-repo *test-dir*)
    (let [app-conf {:target *test-dir*}
          result (git-operation app-conf "remote")]
      (is (= 0 (:exit-code result)))))
)

(deftest test-cli-git-subcommands-integration
  (testing "CLI accepts git add command"
    (let [config (cli/config ["-t" *test-dir* "git" "add"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["add"] (vec (:arguments config))))))

  (testing "CLI accepts git status command"
    (let [config (cli/config ["-t" *test-dir* "git" "status"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["status"] (vec (:arguments config))))))

  (testing "CLI accepts git diff commands"
    (let [config (cli/config ["-t" *test-dir* "git" "diff"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["diff"] (vec (:arguments config))))))
  
  (testing "CLI accepts git log commands"
    (let [config (cli/config ["-t" *test-dir* "git" "log"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["log"] (vec (:arguments config))))))
  
  (testing "CLI accepts git log commands with limit"
    (let [config (cli/config ["-t" *test-dir* "git" "log" "5"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["log" "5"] (vec (:arguments config))))))

  (testing "CLI accepts git branch command"
    (let [config (cli/config ["-t" *test-dir* "git" "branch"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["branch"] (vec (:arguments config))))))

  (testing "CLI accepts git checkout command"
    (let [config (cli/config ["-t" *test-dir* "git" "checkout" "master"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["checkout" "master"] (vec (:arguments config))))))

  (testing "CLI accepts git remote command"
    (let [config (cli/config ["-t" *test-dir* "git" "remote"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["remote"] (vec (:arguments config))))))

  (testing "CLI accepts git pull command"
    (let [config (cli/config ["-t" *test-dir* "git" "pull"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["pull"] (vec (:arguments config))))))

  (testing "CLI accepts git diff with --staged option"
    (let [config (cli/config ["-t" *test-dir* "git" "diff" "--staged"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["diff" "--staged"] (vec (:arguments config))))))

  (testing "CLI accepts git diff with --cached option"
    (let [config (cli/config ["-t" *test-dir* "git" "diff" "--cached"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["diff" "--cached"] (vec (:arguments config))))))

  (testing "CLI accepts git diff with revision spec"
    (let [config (cli/config ["-t" *test-dir* "git" "diff" "HEAD~1..HEAD"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["diff" "HEAD~1..HEAD"] (vec (:arguments config))))))

  (testing "CLI accepts git diff with multiple options"
    (let [config (cli/config ["-t" *test-dir* "git" "diff" "--staged" "HEAD~1..HEAD"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["diff" "--staged" "HEAD~1..HEAD"] (vec (:arguments config))))))

  (testing "CLI accepts git reset command"
    (let [config (cli/config ["-t" *test-dir* "git" "reset"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["reset"] (vec (:arguments config))))))

  (testing "CLI accepts git reset with --soft flag"
    (let [config (cli/config ["-t" *test-dir* "git" "reset" "--soft"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["reset" "--soft"] (vec (:arguments config))))))

  (testing "CLI accepts git reset with --hard flag"
    (let [config (cli/config ["-t" *test-dir* "git" "reset" "--hard"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["reset" "--hard"] (vec (:arguments config))))))

  (testing "CLI accepts git reset with commit reference"
    (let [config (cli/config ["-t" *test-dir* "git" "reset" "HEAD~1"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["reset" "HEAD~1"] (vec (:arguments config))))))

  (testing "CLI accepts git reset with --soft and commit reference"
    (let [config (cli/config ["-t" *test-dir* "git" "reset" "--soft" "HEAD~1"])]
      (is (= 0 (:exit-code config)))
      (is (= "git" (:action config)))
      (is (= ["reset" "--soft" "HEAD~1"] (vec (:arguments config)))))))

(deftest test-git-reset
  (testing "git-reset with non-existent repo returns false"
    (is (not (git-reset *test-dir*))))
  
  (testing "git-reset with default mixed mode succeeds"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    ;; Stage some changes
    (spit (File. ^String *test-dir* "test.txt") "modified content")
    (git-add-all *test-dir*)
    ;; Reset should succeed and unstage changes
    (is (git-reset *test-dir*)))

  (testing "git-reset with soft mode succeeds"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    ;; Make another commit
    (spit (File. ^String *test-dir* "test.txt") "modified content")
    (git-add-all *test-dir*)
    (git-commit *test-dir* "second commit")
    ;; Soft reset to previous commit should succeed
    (is (git-reset *test-dir* {:reset-type :soft :ref "HEAD~1"})))

  (testing "git-reset with hard mode succeeds"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    ;; Modify file (uncommitted changes)
    (spit (File. ^String *test-dir* "test.txt") "modified content")
    ;; Hard reset should succeed and discard changes
    (is (git-reset *test-dir* {:reset-type :hard})))

  (testing "git-reset with specific commit reference succeeds"
    (ensure-git-repo *test-dir*)
    (spit (File. ^String *test-dir* "test.txt") "test content")
    ;; Configure git for testing
    (let [repo (git/load-repo *test-dir*)]
      (-> (git/git-config-load repo)
          (git/git-config-set "commit.gpgsign" "false")
          (git/git-config-set "user.name" "Test User")
          (git/git-config-set "user.email" "test@example.com")
          (git/git-config-save)))
    (git-add-all *test-dir*)
    (git-commit *test-dir* "initial commit")
    ;; Make another commit
    (spit (File. ^String *test-dir* "test2.txt") "second file")
    (git-add-all *test-dir*)
    (git-commit *test-dir* "second commit")
    ;; Reset to previous commit using HEAD~1
    (is (git-reset *test-dir* {:ref "HEAD~1"}))))
