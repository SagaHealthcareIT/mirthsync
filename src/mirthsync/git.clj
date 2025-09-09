(ns mirthsync.git
  (:require [clj-jgit.porcelain :as git]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.io.File))

(defn- git-repo-exists?
  "Check if a git repository exists in the given directory"
  [target-dir]
  (let [git-dir (File. target-dir ".git")]
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
     (let [repo (git/load-repo target-dir)]
       (try
         (log/info "Creating git commit:" message)
         (git/git-commit repo message)
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
   (when (git-add-all target-dir)
     (git-commit target-dir message author-name author-email))))

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
      
      "commit" (let [message (or (first args)
                                 (:commit-message app-conf)
                                 "mirthsync commit")
                     author-name (:git-author app-conf)
                     author-email (:git-email app-conf)]
                 (if (auto-commit target-dir message author-name author-email)
                   (assoc app-conf :exit-code 0)
                   (assoc app-conf :exit-code 1 :exit-msg "Git commit failed")))
      
      ;; Default case - unknown subcommand
      (assoc app-conf 
             :exit-code 1 
             :exit-msg (str "Unknown git subcommand: " git-subcommand)))))