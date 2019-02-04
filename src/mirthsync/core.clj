(ns mirthsync.core
  (:gen-class)
  (:require [mirthsync.actions :as act]
            [mirthsync.apis :as api]
            [mirthsync.cli :as cli]
            [mirthsync.http-client :as http]))

(defn run
  "Links the action specified in the application config to the
  appropriate function, authenticates to the server, and calls the
  function for each api in the defined api/apis (in order). These calls
  happen within the context of an authenticated (thread local) http
  client. The app-config is returned with any updates from the
  action."
  [{:keys [server username password action] :as app-conf}]

  (cli/out (str "Authenticating to server at " server " as " username))
  (let [action   ({"push" act/upload, "pull" act/download} action)
        app-conf (http/with-authentication
                   server username password
                   #(-> app-conf
                        (api/apis-action api/apis api/preprocess)
                        (api/apis-action api/apis action)))]    
    (cli/out "Finished!")
    app-conf))


(defn exit!
  "Print message and System/exit with status code"
  [{:keys [exit-msg exit-code] :as conf}]
  (when exit-msg
    (cli/out exit-msg))
  (System/exit exit-code))

(defn -main
  [& args]
  (let [conf (cli/config args)]
    (when-not (seq (:exit-msg conf))
      (run conf))
    
    (exit! conf)))
