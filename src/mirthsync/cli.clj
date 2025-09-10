(ns mirthsync.cli
  (:require [clojure.string :as string]
            [clojure.tools
             [cli :refer [parse-opts]]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as log-impl]
            [environ.core :refer [env]])
  (:import java.net.URL
           java.io.File
           ch.qos.logback.classic.Level
           ))

(defn- strip-trailing-slashes
  "Removes one or more trailing forward or backward trailing slashes
  from the string unless the string is all slashes."
  [s]
  (when s
    (str/replace s #"(?!^)[\\/]+$" "")))

(def ^{:private true} log-levels (concat
                                  [Level/INFO
                                   Level/DEBUG
                                   Level/TRACE]
                                  (repeat Level/ALL)))

(def ^{:private true} cli-options
  [["-s" "--server SERVER_URL" "Full HTTP(s) url of the Mirth Connect server"
    :parse-fn strip-trailing-slashes
    :validate [#(URL. %) (str "Must be a valid URL to a mirth server api "
                              "path (EX. https://mirth-server:8443/api)")]]
   
   ["-u" "--username USERNAME" "Username used for authentication"]

   ["-p" "--password PASSWORD" "Password used for authentication"
    :default-fn (constantly (:mirthsync-password env))]

   ["-i" "--ignore-cert-warnings" "Ignore certificate warnings"
    :default false]

   ["-v" nil "Verbosity level
        May be specified multiple times to increase level."
    :id :verbosity
    :default 0
    :update-fn inc]

   ["-f" "--force" "
        Overwrite existing local files during a pull and overwrite remote items
        without regard for revisions during a push."]

   ["-t" "--target TARGET_DIR" "Base directory used for pushing or pulling files"
    :missing "--target is required"
    :parse-fn strip-trailing-slashes]

   ["-m" "--disk-mode DISK_MODE" "Use this flag to specify the target directory
        disk format.
        - backup : Equivalent to Mirth Administrator backup and restore.
        - groups : All items expanded to \"Group\" or \"Library\" level.
        - items  : Expand items one level deeper than 'groups' to the individual XML level.
            In other words - Channels and Code Templates are in their own individual
            XML files.
        - code   : Default behavior. Expands everything to the most granular level
            (Javascript, Sql, etc)."
    :default "code"
    :validate [#(contains? #{"backup" "groups" "items" "code"} %)
               (str "Must be either backup, groups, items, or code")]]

   ["-r" "--restrict-to-path RESTRICT_TO_PATH" "
        A path within the target directory to limit the scope of the push. This
        path may refer to a filename specifically or a directory. If the path
        refers to a file - only that file will be pushed. If the path refers to
        a directory - the push will be limited to resources contained within
        that directory. The RESTRICT_TO_PATH must be specified relative to
        the target directory."
    :default ""
    :parse-fn strip-trailing-slashes]

   [nil "--include-configuration-map" " A boolean flag to include the
        configuration map in a push or pull. Default: false"
    :default false]

   [nil "--skip-disabled" " A boolean flag that indicates whether
        disabled channels should be pushed or pulled. Default: false"
    :default false]

   ["-d" "--deploy" "Deply channels on push
        During a push, deploy each included channel immediately
        after saving the channel to Mirth."]

   ["-I" "--interactive" "
        Allow for console prompts for user input"]

   ;; Git integration options
   [nil "--commit-message MESSAGE" "Commit message for git operations"
    :default "mirthsync commit"]
   
   [nil "--git-author NAME" "Git author name for commits"
    :default (System/getProperty "user.name")]
   
   [nil "--git-email EMAIL" "Git author email for commits"]
   
   [nil "--auto-commit" "Automatically commit changes after pull/push operations"
    :default false]
   
   [nil "--git-init" "Initialize git repository in target directory if not present"
    :default false]

   ["-h" "--help"]])

(defn- usage [errors summary]
  (str
   (when errors (str "The following errors occurred while parsing your command:\n\n"
                     (string/join \newline errors)
                     "\n\n"))
   (string/join \newline
                ["Usage: mirthsync [options] action"
                 ""
                 "Options:"
                 summary
                 ""
                 "Actions:"
                 "  push     Push filesystem code to server"
                 "  pull     Pull server code to filesystem"
                 "  git      Git operations (init, status, add, commit, diff, log, branch, checkout, remote, pull, push)"
                 "           git diff [--staged|--cached] [<revision-spec>]"
                 "           Examples: git diff, git diff --staged, git diff HEAD~1..HEAD, git diff main..feature-branch"
                 ""
                 "Environment variables:"
                 "  MIRTHSYNC_PASSWORD     Alternative to --password command line option"])))

(defn- set-log-level
  "Set the logging level by number"
  [lvl]
  (let [^ch.qos.logback.classic.Logger logger (log-impl/get-logger
                                               (log-impl/find-factory)
                                               "mirthsync")]
    (.setLevel logger (nth log-levels lvl))))

(defn- ^String get-cannonical-path
  [^String path]
  (.getCanonicalPath (File. path)))

(defn- is-child-path
  "Ensures that the second param is a child of the first param with respect to
  canonical file paths."
  [parent child]
  (-> (str parent File/separator child)
      get-cannonical-path
      (.startsWith (get-cannonical-path parent))))

(defn- valid-initial-config?
  "Validate the initial config map. Returns a truth value."
  [{:keys [arguments errors] :as config
    {:keys [help target restrict-to-path password server username]} :options}]
  (or help
      (let [action (first arguments)
            git-action? (= "git" action)
            valid-arg-count? (if git-action? 
                               (>= (count arguments) 2) ; git needs subcommand
                               (= 1 (count arguments))) ; push/pull are single
            server-valid? (if git-action?
                            true ; git doesn't need server credentials
                            (and server username (> (count password) 0)))]
        (and (= 0 (count errors))
             valid-arg-count?
             (#{"pull" "push" "git"} action)
             server-valid?
             (is-child-path target restrict-to-path)))))

(defn read-password
  "Read a password from the console if it is available - otherwise nil"
  [prompt]
  (if-let [console (System/console)]
    (let [chars (.readPassword console "%s" (into-array [prompt]))]
      (apply str chars))))

(defn- maybe-prompt-for-password
  "If interaction is allowed \"-I\" and there's no password, get the
  password using a console prompt."
  [{:as config {:keys [interactive password]} :options}]
  (if (and (= (count password) 0)
           interactive)
    (assoc-in config [:options :password] (read-password "Password:"))
    config))

(defn- git-subcommand-args
  "Extract git subcommand arguments that should not be parsed as main CLI options"
  [args]
  (if (nil? args)
    {:main-args [] :git-args []}
    (let [git-idx (.indexOf args "git")]
      (if (and (>= git-idx 0) (< (+ git-idx 2) (count args)))
        (let [main-args (take (+ git-idx 2) args)  ; up to and including git subcommand
              git-args (drop (+ git-idx 2) args)]   ; everything after git subcommand
          {:main-args main-args :git-args git-args})
        {:main-args args :git-args []}))))

(defn- preprocess-git-args
  "Preprocess arguments to separate git subcommand options from main CLI options"
  [args]
  (let [{:keys [main-args git-args]} (git-subcommand-args args)]
    (if (empty? git-args)
      args  ; No git args, return original
      main-args)))  ; Return just main args, git args will be handled separately

(defn config
  "Parse the CLI arguments and construct a map representing selected
  options and action with sensible defaults provided if
  necessary."
  [args]
  (let [processed-args (preprocess-git-args args)
        {:keys [main-args git-args]} (git-subcommand-args args)
        config (parse-opts processed-args cli-options)
        config (maybe-prompt-for-password config)
        args-valid? (valid-initial-config? config)
        
        config (-> config

                   ;; pull options and arguments into top level for
                   ;; convenience in rest of code
                   (into (:options config))
                   (dissoc :options)
                   (assoc :action (first (:arguments config)))
                   (assoc :arguments (if (= "git" (first (:arguments config)))
                                       ;; For git commands, include git subcommand + git-specific args  
                                       (concat (rest (:arguments config)) git-args)
                                       ;; For non-git commands, use original processing
                                       (rest (:arguments config))))
                   ;; Store original git args for reference only if it's a git command
                   (#(if (= "git" (first (:arguments config)))
                       (assoc % :git-subcommand-args git-args)
                       %))
                   
                   ;; Set up our exit code
                   (assoc :exit-code
                          (if (or (:errors config)
                                  (not args-valid?))
                            1
                            0)))

        config (-> config
                   ;; exit message if errors - add custom validation errors
                   (assoc :exit-msg
                          (when (or (> (:exit-code config) 0)
                                    (:help config))
                            (let [action (first (:arguments config))
                                  git-action? (= "git" action)
                                  custom-errors (when (and (not git-action?) 
                                                           (not (:help config)))
                                                  (cond-> []
                                                    (not (:server config)) 
                                                    (conj "--server is required for push/pull operations")
                                                    (not (:username config)) 
                                                    (conj "--username is required for push/pull operations")
                                                    (not (and (:password config) 
                                                             (> (count (:password config)) 0)))
                                                    (conj "--password is required for push/pull operations")))
                                  all-errors (concat (:errors config) custom-errors)]
                              (usage all-errors (:summary config)))))

                   ;; keep config clean by removing unecessary entries  
                   (dissoc :summary))]

    (set-log-level (:verbosity config))

    config))
