(ns mirthsync.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [mirthsync.actions :as act]
            [mirthsync.apis :as api]
            [mirthsync.cli :as cli]
            [mirthsync.http-client :as http])
  (:import org.slf4j.bridge.SLF4JBridgeHandler))

(defn run
  "Links the action specified in the application config to the
  appropriate function, authenticates to the server, and calls the
  function for each api in the defined api/apis (in order). These calls
  happen within the context of an authenticated (thread local) http
  client. The app-config is returned with any updates from the
  action."
  [{:keys [server username password action] :as app-conf}]

  (log/info (str "Authenticating to server at " server " as " username))
  (let [action   ({"push" act/upload, "pull" act/download} action)
        app-conf (http/with-authentication
                   app-conf
                   #(-> app-conf
                        (api/apis-action api/apis api/preprocess)
                        (api/apis-action api/apis action)))]    
    (log/info "Finished!")
    (log/spy :trace app-conf)))


(defn exit!
  "Print message and System/exit with status code"
  [{:keys [exit-msg exit-code] :as conf}]
  (when exit-msg
    (log/info exit-msg))
  ;; don't exit if nrepl is loaded
  (if-not (resolve 'nrepl.version/version)
    (System/exit exit-code)
    exit-code))

(defn -main
  [& args]

  ;; redirect jul logging
  (SLF4JBridgeHandler/removeHandlersForRootLogger)
  (SLF4JBridgeHandler/install)
  
  (let [conf (cli/config args)
        conf (if-not (seq (:exit-msg conf))
               (try
                 (run conf)
                 (catch Exception e
                   (-> conf
                       (assoc :exit-code 1)
                       (assoc :exit-msg (.getMessage e)))))
               conf)]
    (exit! conf)))
