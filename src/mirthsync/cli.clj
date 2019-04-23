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
           ch.qos.logback.classic.Level
           ))

(defn strip-trailing-slashes
  "Removes one or more trailing forward or backward trailing slashes
  from the string unless the string is all slashes."
  [s]
  (str/replace s #"(?!^)[\\/]+$" ""))

(def log-levels (concat
                 [Level/INFO
                  Level/DEBUG
                  Level/TRACE]
                 (repeat Level/ALL)))

(def cli-options
  [["-s" "--server SERVER_URL" "Full HTTP(s) url of the Mirth Connect server"
    ;;;; Removing for safety. Server url should be explicitly specified.
    ;; :default "https://localhost:8443/api"
    :validate [#(URL. %) (str "Must be a valid URL to a mirth server api "
                              "path (EX. https://mirth-server:8443/api)")]
    :parse-fn strip-trailing-slashes]
   
   ["-u" "--username USERNAME" "Username used for authentication"]

   ["-p" "--password PASSWORD" "Password used for authentication"
    :default ""]

   ["-i" "--ignore-cert-warnings" "Ignore certificate warnings"]
   
   ["-f" "--force" (str "Overwrite existing local files during pull "
                        "and always overwrite remote items without "
                        "regard for revisions during push")]

   ["-t" "--target TARGET_DIR" "Base directory used for pushing or pulling files"
    :default "."
    :parse-fn strip-trailing-slashes]

   ["-v" nil "Verbosity level; may be specified multiple times to increase level"
    :id :verbosity
    :default 0
    :update-fn inc]

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

(defn config
  "Parse the CLI arguments and construct a map representing selected
  options and action with sensible defaults provided if
  necessary."
  [args]
  (let [config (parse-opts args cli-options)

        args-valid? (or (:help config)
                        (and (= 1 (count (:arguments config)))
                             (#{"pull" "push"} (first (:arguments config)))))
        
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

    (let [^ch.qos.logback.classic.Logger logger (log-impl/get-logger
                                                 (log-impl/find-factory)
                                                 "mirthsync")
          verbosity (:verbosity config)]
      (.setLevel logger (nth log-levels verbosity)))
    
    config))
