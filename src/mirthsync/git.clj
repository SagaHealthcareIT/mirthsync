(ns mirthsync.git
  (:require [clj-jgit.porcelain :as git]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.io.File
           java.io.ByteArrayOutputStream
           org.eclipse.jgit.diff.DiffEntry
           org.eclipse.jgit.diff.DiffFormatter
           org.eclipse.jgit.lib.Repository
           org.eclipse.jgit.revwalk.RevWalk
           org.eclipse.jgit.revwalk.RevCommit
           org.eclipse.jgit.lib.Ref
           org.eclipse.jgit.transport.RemoteConfig
           org.eclipse.jgit.api.PullResult
           org.eclipse.jgit.transport.FetchResult
           org.eclipse.jgit.api.Git
           org.eclipse.jgit.lib.ObjectId))

(defn- git-repo-exists?
  "Check if a git repository exists in the given directory"
  [target-dir]
  (let [git-dir (File. ^String target-dir ".git")]
    (.exists git-dir)))

(defn ensure-git-repo
  "Initialize git repository if it doesn't exist. Returns the repo object."
  [target-dir]
  (let [target-file (io/file target-dir)]
    (if (git-repo-exists? target-dir)
      (do
        (log/info "Git repository already exists in" target-dir)
        (git/load-repo target-dir))
      (do
        (log/info "Initializing git repository in" target-dir)
        (.mkdirs target-file) ; Ensure target directory exists
        (git/git-init :dir target-dir)))))

(defn git-status
  "Get git status for the target directory"
  [target-dir]
  (if (git-repo-exists? target-dir)
    (let [repo (git/load-repo target-dir)
          status (git/git-status repo)]
      (log/info "Git status for" target-dir)
      (when (seq (:untracked status))
        (log/info "Untracked files:" (str/join ", " (:untracked status))))
      (when (seq (:modified status))
        (log/info "Modified files:" (str/join ", " (:modified status))))
      (when (seq (:added status))
        (log/info "Added files:" (str/join ", " (:added status))))
      (when (seq (:removed status))
        (log/info "Removed files:" (str/join ", " (:removed status))))
      (when (and (empty? (:untracked status))
                 (empty? (:modified status))
                 (empty? (:added status))
                 (empty? (:removed status)))
        (log/info "Working directory is clean"))
      status)
    (do
      (log/error "No git repository found in" target-dir)
      nil)))

(defn git-add-all
  "Add all files in the target directory to git staging"
  [target-dir]
  (if (git-repo-exists? target-dir)
    (let [repo (git/load-repo target-dir)]
      (log/info "Adding all files to git staging")
      (git/git-add repo ".")
      true)
    (do
      (log/error "No git repository found in" target-dir)
      false)))

(defn git-commit
  "Create a git commit with the specified message and optional author info"
  ([target-dir message]
   (git-commit target-dir message nil nil))
  ([target-dir message author-name author-email]
   (if (git-repo-exists? target-dir)
     (let [repo (git/load-repo target-dir)
           commit-args (->> [message
                             (when author-name :author-name) author-name
                             (when author-email :author-email) author-email]
                            (remove nil?))]
       (try
         (log/info "Creating git commit:" message)
         (apply git/git-commit repo commit-args)
         (log/info "Git commit created successfully")
         true
         (catch Exception e
           (log/error "Failed to create git commit:" (.getMessage e))
           false)))
     (do
       (log/error "No git repository found in" target-dir)
       false))))

(defn auto-commit
  "Convenience function to add all files and commit with a message"
  ([target-dir message]
   (auto-commit target-dir message nil nil))
  ([target-dir message author-name author-email]
   (if (git-add-all target-dir)
     (git-commit target-dir message author-name author-email)
     false)))

(defn generate-commit-message
  "Generate a commit message based on the operation and server"
  [{:keys [action server commit-message] :as app-conf}]
  (if (and commit-message (not= commit-message "mirthsync commit"))
    commit-message
    (case action
      "pull" (str "Pull from " (or server "server"))
      "push" (str "Push to " (or server "server"))
      commit-message)))

(defn git-diff
  "Show git diff for the target directory"
  ([target-dir]
   (git-diff target-dir nil))
  ([target-dir options]
   (if (git-repo-exists? target-dir)
     (try
       (log/info "Getting git diff for" target-dir)
       ;; Use JGit API directly to get the diff
       (let [git-api (Git/open (io/file target-dir))
             diff-entries (.call (.diff git-api))]
         (if (empty? diff-entries)
           (do
             (log/info "No differences found")
             [])
           (do
             (log/info "Found" (count diff-entries) "changed files")
             ;; Generate and output the actual diff content
             (doseq [^DiffEntry entry diff-entries]
               (log/info "Changed:" (.getNewPath entry)))
             ;; Use DiffCommand with ByteArrayOutputStream to capture diff output
             (let [output-stream (ByteArrayOutputStream.)
                   diff-cmd (.diff git-api)
                   _ (.setOutputStream diff-cmd output-stream)
                   diff-entries-result (.call diff-cmd)]
               (when (seq diff-entries-result)
                 (let [diff-text (.toString output-stream)]
                   (when (not (empty? diff-text))
                     (println diff-text))))
               (.close output-stream))
             diff-entries)))
       (catch Exception e
         (log/error "Failed to get git diff:" (.getMessage e))
         [])) ; Return empty list on error
     (do
       (log/error "No git repository found in" target-dir)
       nil))))

(defn git-log
  "Show git log for the target directory with optional limit"
  ([target-dir]
   (git-log target-dir 10)) ; Default to 10 commits
  ([target-dir limit]
   (if (git-repo-exists? target-dir)
     (try
       (log/info "Getting git log for" target-dir "with limit" limit)
       ;; Use JGit API directly to get the log
       (let [git-api (Git/open (io/file target-dir))
             commits (.call (.log git-api))
             commit-list (doall (map (fn [^RevCommit c]
                                      {:id (.getName c)
                                       :message (.getShortMessage c)})
                                    (take limit commits)))]
         (if (empty? commit-list)
           (do
             (log/info "No commits found")
             [])
           (do
             (log/info "Showing" (count commit-list) "commits")
             (doseq [commit commit-list]
               (log/info "Commit:" (:id commit) "-" (:message commit)))
             commit-list)))
       (catch Exception e
         (log/error "Failed to get git log:" (.getMessage e))
         [])) ; Return empty list instead of nil on error
     (do
       (log/error "No git repository found in" target-dir)
       nil))))

(defn git-branch
  "List git branches for the target directory"
  [target-dir]
  (if (git-repo-exists? target-dir)
    (try
      (log/info "Getting git branches for" target-dir)
      ;; Use JGit API directly to get branches
      (let [git-api (Git/open (io/file target-dir))
            branches (.call (.branchList git-api))
            branch-names (map (fn [^Ref branch-ref]
                                (let [ref-name (.getName branch-ref)
                                      short-name (if (.startsWith ref-name "refs/heads/")
                                                   (.substring ref-name 11)
                                                   ref-name)]
                                  short-name))
                              branches)]
        (if (empty? branch-names)
          (do
            (log/info "No branches found")
            [])
          (do
            (log/info "Found" (count branch-names) "branches")
            (doseq [branch branch-names]
              (log/info "Branch:" branch))
            branch-names)))
      (catch Exception e
        (log/error "Failed to get git branches:" (.getMessage e))
        []))
    (do
      (log/error "No git repository found in" target-dir)
      nil)))

(defn git-checkout
  "Checkout a git branch for the target directory"
  [target-dir branch-name]
  (if (git-repo-exists? target-dir)
    (try
      (log/info "Checking out branch" branch-name "in" target-dir)
      ;; Use JGit API directly to checkout
      (let [git-api (Git/open (io/file target-dir))]
        (.call (.setName (.checkout git-api) branch-name)))
      (log/info "Successfully checked out branch" branch-name)
      true
      (catch Exception e
        (log/error "Failed to checkout branch" branch-name ":" (.getMessage e))
        false))
    (do
      (log/error "No git repository found in" target-dir)
      false)))

(defn git-remote
  "List git remotes for the target directory, similar to 'git remote -v'"
  [target-dir]
  (if (git-repo-exists? target-dir)
    (try
      (log/info "Getting git remotes for" target-dir)
      ;; Use JGit API directly to get remotes
      (let [git-api (Git/open (io/file target-dir))
            remotes (.call (.remoteList git-api))]
        (if (empty? remotes)
          (do
            (log/info "No remotes found")
            {})
          (do
            (log/info "Found" (count remotes) "remotes")
            (into {}
                  (for [^RemoteConfig remote remotes]
                    (let [name (.getName remote)
                          fetch-url (when-let [uri (first (.getURIs remote))] (.toString uri))
                          push-url (if-let [push-uri (first (.getPushURIs remote))]
                                     (.toString push-uri)
                                     fetch-url)]
                      (when fetch-url (log/info name "\t" fetch-url "(fetch)"))
                      (when push-url (log/info name "\t" push-url "(push)"))
                      [name {:fetch fetch-url :push push-url}]))))))
      (catch Exception e
        (log/error "Failed to get git remotes:" (.getMessage e))
        nil))
    (do
      (log/error "No git repository found in" target-dir)
      nil)))

(defn git-pull
  "Pull changes from a remote repository"
  [target-dir]
  (if (git-repo-exists? target-dir)
    (try
      (log/info "Pulling changes from remote for" target-dir)
      ;; Use JGit API directly to pull
      (let [git-api (Git/open (io/file target-dir))
            ^PullResult result (.call (.pull git-api))]
        (if (.isSuccessful ^FetchResult (.getFetchResult result))
          (do
            (log/info "Git pull successful")
            true)
          (do
            (log/warn "Git pull failed:" (.getFetchResult result))
            false)))
      (catch Exception e
        (log/error "Failed to perform git pull:" (.getMessage e))
        false))
    (do
      (log/error "No git repository found in" target-dir)
      false)))

(defn auto-commit-after-operation
  "Perform auto-commit after a successful pull/push operation"
  [{:keys [target auto-commit git-init] :as app-conf}]
  (when auto-commit
    (log/info "Auto-commit enabled, processing git operations")
    
    ;; Initialize git repository if requested and needed
    (when git-init
      (ensure-git-repo target))
    
    ;; Only proceed if we have a git repository  
    (let [git-dir (File. ^String target ".git")]
      (if (.exists git-dir)
        (do
          (log/info "Auto-committing changes with message:" (generate-commit-message app-conf))
          (try
            (git-add-all target)
            (git-commit target 
                       (generate-commit-message app-conf)
                       (:git-author app-conf)
                       (:git-email app-conf))
            (log/info "Auto-commit successful")
            (catch Exception e
              (log/warn "Auto-commit failed:" (.getMessage e)))))
        (log/warn "Auto-commit requested but no git repository found in" target 
                  "- use --git-init to create one automatically")))))

(defn git-operation
  "Dispatch git operations based on subcommand and arguments"
  [app-conf git-subcommand & args]
  (let [target-dir (:target app-conf)]
    (case git-subcommand
      "init" (do
               (ensure-git-repo target-dir)
               (assoc app-conf :exit-code 0))
      
      "status" (do
                 (git-status target-dir)
                 (assoc app-conf :exit-code 0))
      
      "add" (if (git-add-all target-dir)
              (assoc app-conf :exit-code 0)
              (assoc app-conf :exit-code 1 :exit-msg "Git add failed"))

      "commit" (let [message (or (first args)
                                 (:commit-message app-conf)
                                 "mirthsync commit")
                     author-name (:git-author app-conf)
                     author-email (:git-email app-conf)]
                 (if (git-commit target-dir message author-name author-email)
                   (assoc app-conf :exit-code 0)
                   (assoc app-conf :exit-code 1 :exit-msg "Git commit failed")))
      
      "diff" (do
               (git-diff target-dir)
               (assoc app-conf :exit-code 0))
      
      "log" (let [limit (if-let [limit-arg (first args)]
                          (try 
                            (Integer/parseInt limit-arg)
                            (catch NumberFormatException e 
                              10)) ; Default if parse fails
                          10)] ; Default if no arg provided
              (git-log target-dir limit)
              (assoc app-conf :exit-code 0))
      
      "branch" (do
                 (git-branch target-dir)
                 (assoc app-conf :exit-code 0))
      
      "checkout" (if-let [branch-name (first args)]
                   (if (git-checkout target-dir branch-name)
                     (assoc app-conf :exit-code 0)
                     (assoc app-conf :exit-code 1 :exit-msg (str "Failed to checkout branch " branch-name)))
                   (assoc app-conf :exit-code 1 :exit-msg "Branch name required for checkout"))
      
      "remote" (if (git-remote target-dir)
                 (assoc app-conf :exit-code 0)
                 (assoc app-conf :exit-code 1 :exit-msg "Failed to list remotes"))
      
      "pull" (if (git-pull target-dir)
               (assoc app-conf :exit-code 0)
               (assoc app-conf :exit-code 1 :exit-msg "Git pull failed"))
      
      ;; Default case - unknown subcommand
      (assoc app-conf 
             :exit-code 1 
             :exit-msg (str "Unknown git subcommand: " git-subcommand)))))
