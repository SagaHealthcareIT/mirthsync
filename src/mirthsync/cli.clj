(ns mirthsync.cli
  (:require [clojure.string :as string]
            [clojure.tools
             [cli :refer [parse-opts]]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as log-impl]
            )
  (:import java.net.URL
           java.io.File
           ch.qos.logback.classic.Level
           ))

(defn strip-trailing-slashes
  "Removes one or more trailing forward or backward trailing slashes
  from the string unless the string is all slashes."
  [s]
  (when s
    (str/replace s #"(?!^)[\\/]+$" "")))

(def log-levels (concat
                 [Level/INFO
                  Level/DEBUG
                  Level/TRACE]
                 (repeat Level/ALL)))

(def cli-options
  [["-s" "--server SERVER_URL" "Full HTTP(s) url of the Mirth Connect server"
    :missing "--server is required"
    :parse-fn strip-trailing-slashes
    :validate [#(URL. %) (str "Must be a valid URL to a mirth server api "
                              "path (EX. https://mirth-server:8443/api)")]]
   
   ["-u" "--username USERNAME" "Username used for authentication"
    :missing "--username is required"]

   ["-p" "--password PASSWORD" "Password used for authentication"
    :missing "--password is required"]

   ["-i" "--ignore-cert-warnings" "Ignore certificate warnings"
    :default false]
   
   ["-f" "--force" "
        Overwrite existing local files during a pull and overwrite remote items
        without regard for revisions during a push."]

   ["-t" "--target TARGET_DIR" "Base directory used for pushing or pulling files"
    :missing "--target is required"
    :parse-fn strip-trailing-slashes]

   ["-r" "--resource-path TARGET_RESOURCE_PATH" "
        A path within the target directory to limit the scope of the push/pull.
        This path may refer to a filename specifically or a directory. In the
        case of a pull - only resources that would end up within that path are
        saved to the filesystem. In the case of a push - only resources within
        that path are pushed to the specified server. The TARGET_RESOURCE_PATH
        must be specified relative to the target directory."
    :default ""
    :parse-fn strip-trailing-slashes]

   ["-v" nil "Verbosity level; may be specified multiple times to increase level"
    :id :verbosity
    :default 0
    :update-fn inc]

   [nil "--push-config-map" "A boolean flag to push the configuration map - default false"
    :default false]

   ["-h" "--help"]])

(defn usage [errors summary]
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
                 "  pull     Pull server code to filesystem"])))

(defn set-log-level
  "Set the logging level by number"
  [lvl]
  (let [^ch.qos.logback.classic.Logger logger (log-impl/get-logger
                                               (log-impl/find-factory)
                                               "mirthsync")]
    (.setLevel logger (nth log-levels lvl))))

(defn get-cannonical-path
  [path]
  (.getCanonicalPath (File. path)))

(defn is-child-path
  "Ensures that the second param is a child of the first param with respect to
  canonical file paths."
  [parent child]
  (-> (str parent File/separator child)
      get-cannonical-path
      (.startsWith (get-cannonical-path parent))))

(defn valid-initial-config?
  "Validate the initial config map. Returns a truth value."
  [{:keys [arguments errors] :as config
    {:keys [help target resource-path]} :options}]
  (or help
      (and (= 0 (count errors))
           (= 1 (count arguments))
           (#{"pull" "push"} (first arguments))
           (is-child-path target resource-path))))

(defn config
  "Parse the CLI arguments and construct a map representing selected
  options and action with sensible defaults provided if
  necessary."
  [args]
  (let [config (parse-opts args cli-options)

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
