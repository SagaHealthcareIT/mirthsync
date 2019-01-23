(ns mirthsync.core
  (:gen-class)
  (:require [mirthsync.actions :refer [download upload]]
            [mirthsync.apis :refer [apis apis-action]]
            [mirthsync.cli :as cli]
            [mirthsync.http-client :refer [with-authentication]]))

(comment
  - REPL
    (do
      (run (cli/config ["pull" "-t" "tmp" "-p" "admin" "-f"]))
      (run (cli/config ["push" "-t" "tmp" "-p" "admin" "-f"]))
      )
  - api via curl
  curl -v -include --cookie \
    "JSESSIONID=node06i1z8hqqzbed1axrw3nfn8enx1.node0"  -k \
    --header "Accept: application/xml" -X POST --header \
    "Content-Type: application/xml" -F "body=@./foo.xml" \
    "https://localhost:8443/api/channelgroups/_getChannelGroups"

  - TODO
    - strip or encode filenames
    - remove need for download before upload
    - look into bulk group download
    - group upload
  )

(defn run
  "Start of app logic. Links the action specified in the application
  config to the appropriate function, authenticates to the server, and
  calls the function for each api in the api map (in order). These
  calls happen within the context of an authenticated (thread local)
  http client. Returns the application config."
  [{:keys [server username password action] :as app-conf}]

  (cli/output 0 (str "Authenticating to server at " server " as " username))
  (let [action   ({"push" upload, "pull" download} action)
        app-conf (with-authentication server username password
                   #(apis-action app-conf nil apis action))]
    
    (cli/output 0 "Finished!")
    app-conf))


(defn exit!
  "Print message and System/exit with status code"
  [{:keys [exit-msg exit-code] :as conf}]
  (cli/output exit-msg)
  (System/exit exit-code))

(defn -main
  [& args]
  (let [conf (cli/config args)]
    (when-not (seq (:exit-msg conf))
      (run conf))
    
    (exit! conf)))
