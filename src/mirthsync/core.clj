(ns mirthsync.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [mirthsync.actions :as act]
            [mirthsync.apis :as api]
            [mirthsync.cli :as cli]
            [mirthsync.http-client :as http])
  (:import org.slf4j.bridge.SLF4JBridgeHandler))

(defn- run
  "Links the action specified in the application config to the
  appropriate function, authenticates to the server, and calls the
  function for each api in the defined api/apis (in order). These calls
  happen within the context of an authenticated (thread local) http
  client. The app-config is returned with any updates from the
  action."
  [{:keys [server username password action] :as app-conf}]

  (log/info (str "Authenticating to server at " server " as " username))
  (let [action-fn   ({"push" act/upload, "pull" act/download} action)
        app-conf (http/with-authentication
                   app-conf
                   #(-> app-conf
                        (api/iterate-apis (api/apis app-conf) api/preprocess-api)
                        (api/iterate-apis (api/apis app-conf) action-fn)))]
    (log/info "Finished!")
    (log/spy :trace app-conf)))


(defn- exit-prep
  "Print message and return exit status code"
  [{:keys [exit-msg exit-code] :as conf}]
  (when exit-msg
    (log/info exit-msg))
  exit-code)

(defn main-func
  [& args]
  ;; redirect jul logging
  (SLF4JBridgeHandler/removeHandlersForRootLogger)
  (SLF4JBridgeHandler/install)
  
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
