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
    :missing "--server is required"
    :parse-fn strip-trailing-slashes
    :validate [#(URL. %) (str "Must be a valid URL to a mirth server api "
                              "path (EX. https://mirth-server:8443/api)")]]
   
   ["-u" "--username USERNAME" "Username used for authentication"
    :missing "--username is required"]

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
        - 0 Equivalent to Mirth Administrator backup and restore.
        - 1 All items expanded to \"Group\" or \"Library\" level.
        - 2 Expand items one level deeper than '1' to the individual XML level.
            In other words - Channels and Code Templates are in their own individual
            XML files.
        - Numbers greater than 2 (Default). Expand everything to the most
          granular level (Javascript, Sql, etc)."
    :default 9
    :parse-fn #(Integer/parseInt %)]

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
        configuration map in the push. Default: false"
    :default false]

   [nil "--skip-disabled" " A boolean flag that indicates whether
        disabled channels should be pushed or pulled. Default: false"
    :default false]

   ["-d" "--deploy" "Deply channels on push
        During a push, deploy each included channel immediately
        after saving the channel to Mirth."]

   ["-I" "--interactive" "
        Allow for console prompts for user input"]

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
    {:keys [help target restrict-to-path password]} :options}]
  (or help
      (and (= 0 (count errors))
           (= 1 (count arguments))
           (#{"pull" "push"} (first arguments))
           (> (count password) 0)
           (is-child-path target restrict-to-path))))

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

(defn config
  "Parse the CLI arguments and construct a map representing selected
  options and action with sensible defaults provided if
  necessary."
  [args]
  (let [config (parse-opts args cli-options)
        config (maybe-prompt-for-password config)
        args-valid? (valid-initial-config? config)
        
        config (-> config

                   ;; pull options and first arg into top level for
                   ;; convenience in rest of code
                   (into (:options config))
                   (dissoc :options)
                   (assoc :action (first (:arguments config)))
                   
                   ;; Set up our exit code
                   (assoc :exit-code
                          (if (or (:errors config)
                                  (not args-valid?))
                            1
                            0)))

        config (-> config
                   ;; exit message if errors
                   (assoc :exit-msg
                          (when (or (> (:exit-code config) 0)
                                    (:help config))
                            (usage (:errors config) (:summary config))))

                   ;; keep config clean by removing unecessary entries
                   (dissoc :summary :arguments))]

    (set-log-level (:verbosity config))

    config))
