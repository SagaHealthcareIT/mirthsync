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
  
  (testing "git-diff shows differences after file modification"
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
    
    ;; Modify file
    (spit (File. ^String *test-dir* "test.txt") "modified content")
    (let [diff (git-diff *test-dir*)]
      (is (not (empty? diff))))))

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

        ;; 2. Clone the remote to our test directory
        (git/git-clone (str "file://" (.getPath remote-dir)) :dir *test-dir*)

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
        (is (git-pull *test-dir*))

        ;; 5. Verify the file from remote exists
        (is (.exists (io/file *test-dir* "remote_file.txt")))

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
      (is (= ["pull"] (vec (:arguments config)))))))
