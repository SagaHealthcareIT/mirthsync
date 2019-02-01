(ns mirthsync.core
  (:gen-class)
  (:require [mirthsync.actions :refer [download upload assoc-server-groups]]
            [mirthsync.apis :refer [apis apis-action]]
            [mirthsync.cli :as cli]
            [mirthsync.http-client :refer [with-authentication]]))

(defn run
  "Links the action specified in the application config to the
  appropriate function, authenticates to the server, and calls the
  function for each api in the defined apis (in order). These calls
  happen within the context of an authenticated (thread local) http
  client. The app-config is returned with any updates from the
  action."
  [{:keys [server username password action] :as app-conf}]

  (cli/out (str "Authenticating to server at " server " as " username))
  (let [action   ({"push" upload, "pull" download} action)
        app-conf (with-authentication server username password
                   ;; add server groups to app-conf as first step
                   #(apis-action
                     (assoc-server-groups (assoc app-conf :api (first apis)))
                     apis
                     action))]
    
    (cli/out "Finished!")
    app-conf))


(defn exit!
  "Print message and System/exit with status code"
  [{:keys [exit-msg exit-code] :as conf}]
  (cli/out exit-msg)
  (System/exit exit-code))

(defn -main
  [& args]
  (let [conf (cli/config args)]
    (when-not (seq (:exit-msg conf))
      (run conf))
    
    (exit! conf)))
