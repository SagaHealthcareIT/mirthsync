(ns mirthsync.core
  (:require [mirthsync.logging :as log]
            [mirthsync.actions :as act]
            [mirthsync.apis :as api]
            [mirthsync.cli :as cli]
            [mirthsync.git :as mgit]
            [mirthsync.http-client :as http])
  (:import [java.io Writer OutputStream PrintWriter])
  (:gen-class
    :methods [^{:static true} [mainFunc ["[Ljava.lang.String;" java.io.Writer java.io.Writer] int]]))

(defn- run
  "Links the action specified in the application config to the
  appropriate function, authenticates to the server, and calls the
  function for each api in the defined api/apis (in order). These calls
  happen within the context of an authenticated (thread local) http
  client. The app-config is returned with any updates from the
  action."
  [{:keys [server username password action arguments] :as app-conf}]

  (cond
    ;; Git operations don't require server authentication
    (= "git" action)
    (let [git-subcommand (first arguments)
          git-args (rest arguments)]
      (log/info (str "Executing git " git-subcommand))
      (apply mgit/git-operation app-conf git-subcommand git-args))

    ;; Traditional push/pull operations require authentication
    :else
    (do
      (log/info (str "Authenticating to server at " server " as " username))
      (let [action-fn   ({"push" act/upload, "pull" act/download} action)
            app-conf (http/with-authentication
                       app-conf
                       (fn []
                         (let [preprocessed-conf (api/iterate-apis app-conf (api/apis app-conf) api/preprocess-api)
                               ;; For pull operations, always capture local files before pull for orphan detection
                               conf-with-pre-pull (if (= "pull" action)
                                                     (api/iterate-apis preprocessed-conf (api/apis preprocessed-conf) act/capture-pre-pull-local-files)
                                                     preprocessed-conf)
                               ;; Initialize bulk deployment atom if needed
                               conf-with-bulk-deploy (if (and (= "push" action) (:deploy-all conf-with-pre-pull))
                                                        (assoc conf-with-pre-pull :bulk-deploy-channels (atom []))
                                                        conf-with-pre-pull)
                               processed-conf (api/iterate-apis conf-with-bulk-deploy (api/apis conf-with-bulk-deploy) action-fn)]
                           ;; After push with --deploy-all, deploy all channels
                           (when (and (= "push" action) (:deploy-all processed-conf))
                             (api/deploy-all-channels processed-conf))
                           ;; After pull, always check for orphaned files
                           (if (= "pull" action)
                             (act/cleanup-orphaned-files-with-pre-pull processed-conf (api/apis processed-conf))
                             processed-conf))))]
        (log/info "Finished!")
        ;; Perform auto-commit after successful operation
        (mgit/auto-commit-after-operation app-conf)
        (log/spy :trace app-conf)))))


(defn- exit-prep
  "Print message and return exit status code"
  [{:keys [exit-msg exit-code] :as conf}]
  (when exit-msg
    (log/info exit-msg))
  exit-code)

(defn main-func
  [& args]
  (let [conf (cli/config args)
        conf (if-not (seq (:exit-msg conf))
               (try
                 (run conf)
                 (catch Exception e
                   (.printStackTrace e)
                   (-> conf
                       (assoc :exit-code 1)
                       (assoc :exit-msg (.getMessage e)))))
               conf)]
    (exit-prep conf)))

(defn- ^Writer coerce-writer
  [candidate default-supplier]
  (cond
    (instance? Writer candidate) candidate
    (instance? OutputStream candidate) (PrintWriter. ^OutputStream candidate true)
    (nil? candidate) (default-supplier)
    :else (throw (IllegalArgumentException.
                  (str "Unsupported writer type: " (class candidate))))))

(defn ^int -mainFunc
  [^"[Ljava.lang.String;" args out-stream err-stream]
  (let [out-writer (coerce-writer out-stream #(PrintWriter. System/out true))
        err-writer (coerce-writer err-stream #(PrintWriter. System/err true))
        exit-code (log/with-log-writers out-writer err-writer
                     (or (apply main-func (or (seq args) [])) 0))]
    (int exit-code)))

(defn -main
  [& args]
  (System/exit (apply main-func args)))

(comment
  (cli/config ["-s" "https://localhost:8443/api" "-u" "admin" "-p" "adminpass" "-i" "-t" "target/tmp" "-f"])
;; gives
  {:errors nil,
   :exit-code 1,
   :force true,
   :verbosity 0,
   :password "adminpass",
   :server "https://localhost:8443/api",
   :username "admin",
   :restrict-to-path "",
   :action nil,
   :target "target/tmp",
   :exit-msg
   "Usage: mirthsync [options] action\n\nOptions:\n  -s, --server SERVER_URL                    Full HTTP(s) url of the Mirth Connect server\n  -u, --username USERNAME                    Username used for authentication\n  -p, --password PASSWORD                    Password used for authentication\n  -i, --ignore-cert-warnings                 Ignore certificate warnings\n  -f, --force                                \n        Overwrite existing local files during a pull and overwrite remote items\n        without regard for revisions during a push.\n  -t, --target TARGET_DIR                    Base directory used for pushing or pulling files\n  -r, --restrict-to-path RESTRICT_TO_PATH    \n        A path within the target directory to limit the scope of the push. This\n        path may refer to a filename specifically or a directory. If the path\n        refers to a file - only that file will be pushed. If the path refers to\n        a directory - the push will be limited to resources contained within\n        that directory. The RESTRICT_TO_PATH must be specified relative to\n        the target directory.\n  -v                                         Verbosity level\n        May be specified multiple times to increase level.\n      --include-configuration-map            \n        A boolean flag to include the configuration map in the push - defaults\n        to false\n  -h, --help\n\nActions:\n  push     Push filesystem code to server\n  pull     Pull server code to filesystem",
   :ignore-cert-warnings true,
   :include-configuration-map false})

