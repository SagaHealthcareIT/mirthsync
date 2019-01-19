(ns mirthsync.core
  (:gen-class)
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [mirthsync.cli :as cli])
  (:import java.io.File))

(defn to-zip
  "Takes a string of xml and returns an xml zipper"
  [x]
  (zip/xml-zip (xml/parse-str x)))

(defn with-authentication
  "Binds a cookie store to keep auth cookies, authenticates using the
  supplied url/credentials, and returns the results of executing the
  supplied parameterless function for side effects"
  [base-url username password func]
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (client/post
     (str base-url "/users/_login")
     {:form-params
      {:username username
       :password password}
      :insecure? true})
    (func)))

(defn fetch-all
  "Fetch everything at url from remote server and return a sequence of
  locs based on the supplied predicates. In other words - grab the xml
  from the url via a GET request, extract the :body of the result,
  parse the XML, create a 'zipper', and return the result of the
  query (predicates)"
  [url predicates]
  (as-> url v
    (client/get v {:insecure? true})
    (:body v)
    (to-zip v)
    (apply zx/xml-> v predicates)))

(defn serialize-node
  "Take an xml location and write to the filesystem with a meaningful
  name and path. If the file exists it is not overwritten unless the
  -f option is set. Returns nil."
  [xmlloc {:keys [target channel-groups] :as app-conf} {:keys [local-path find-name find-elements find-id] :as api}]
  
  (let [id (apply zx/xml1-> xmlloc find-id)
        extra-path (when (and
                          channel-groups
                          (= :channel (last find-elements))
                          (channel-groups id))
                     (str File/separator (channel-groups id) File/separator))
        name (apply zx/xml1-> xmlloc find-name)
        xml-str (xml/indent-str (zip/node xmlloc))
        file-path (str target (File/separator) local-path (File/separator) extra-path name ".xml")]
    (if (and (.exists (io/file file-path))
             (not (:force app-conf)))
      (cli/output (str "File at " file-path " already exists and the "
                       "force (-f) option was not specified. Refusing "
                       "to overwrite the file."))
      (do (io/make-parents file-path)
          (spit file-path xml-str)))
    nil))

(defn upload-node
  "Extracts the id from the xmlloc using the find-id predicates. PUTs
  the formatted XML to the location constructed from the base-url,
  path, and id."
  [base-url path find-id xmlloc]
  (let [id (apply zx/xml1-> xmlloc find-id)]
    (client/put (str base-url "/" path "/" id)
                {:insecure? true
                 :body (xml/indent-str (zip/node xmlloc))
                 :content-type "application/xml"})))

(defn groups-handler
  "Takes the application config and a loc. Adds a map of channel
  ids (keys) group names (values) to the app config and returns the
  new config."
  [app-conf loc]
  (let [group-name (zx/xml1-> loc :name zx/text)
        channel-ids (zx/xml-> loc :channels :channel :id zip/down zip/node)
        channel-groups (or (:channel-groups app-conf) {})]
    (assoc
     app-conf
     :channel-groups
     (into
      channel-groups
      (map #(sorted-map % group-name) channel-ids)))))

(defn download
  "Serializes all xml found at the api path to the filesystem using the
  supplied config. Returns a (potentially) updated app-conf with
  details about the fetched apis. If the save parameter is false, the
  apis are fetched and processed but not saved to the filesystem. This
  is useful for accumulating data about the apis without committing
  anything to disk."
  ([app-conf]
   (download true app-conf))
  
  ([save {:keys [server target parent-api api] :as app-conf}]
   (let [{:keys [find-elements path local-path conf-handler]} api
         remote-path (str server (str "/" path))
         _ (cli/output 0 (str "Downloading from " remote-path " to " local-path))
         locs (fetch-all remote-path find-elements)]
     
     (loop [app-conf app-conf
            locs locs]
       (if (seq locs)
         (let [loc (first locs)
               app-conf (if conf-handler
                          (conf-handler app-conf loc)
                          app-conf)]
           (when save (serialize-node loc app-conf api))
           (recur app-conf (rest locs)))
         app-conf)))))



(defn upload
  "Extracts the server url and the target local directory that contains
  our mirth files from the application config param. Also extracts
  some of the api spec from the api param. If pushing is allowed for
  the current API, the files in the specified path directory are
  iterated, parsed, and uploaded to mirth via the upload-node
  function."
  [{:keys [server target api] :as app-conf}]
  (let [{:keys [find-id local-path path find-elements]} api]
    (if (= :channelGroup (last find-elements))
      (do
        (cli/output 0 (str "Uploading from " local-path " to " path " is not currently supported - skipping"))
        app-conf)
      (do
        (cli/output 0 (str "Uploading from " local-path " to " path))
        (let [files (filter
                     #(not (.isDirectory %))
                     (.listFiles (io/file (str target (File/separator) local-path))))]
          (cli/output 0 (str "found " (count files) " files"))
          (doseq [f files]
            (upload-node server path find-id (to-zip (slurp f)))))
        app-conf))))

(defn api-vals
  "Extracts map containing useful values from Mirth Connect top level
  element at 'loc'."
  [loc]
  (zx/xml1-> loc :id zx/text))

(def apis
  [{:local-path "."
    :find-elements [:map]
    :find-id [(constantly nil)]
    :find-name [(constantly "ConfigurationMap")]
    :path "server/configurationMap"}

   {:local-path "CodeTemplates"
    :find-elements [:list :codeTemplate]
    :find-id [:id zx/text]
    :find-name [:name zip/down zip/node]
    :path "codeTemplates"}

   {:local-path "GlobalScripts"
    :find-elements [:map]
    :find-id [(constantly nil)]
    :find-name [(constantly "globalScripts")]
    :path "server/globalScripts"}

   {:local-path "Channels"
    :find-elements [:list :channelGroup]
    :find-id [:id zip/down zip/node]
    :find-name [:name zip/down zip/node #(str % File/separator "Group")]
    :path "channelgroups"
    :conf-handler groups-handler
    :apis [{:local-path "."
            :find-elements [:list :channel]
            :find-id [:id zip/down zip/node]
            :find-name [:name zip/down zip/node]
            :path "channels"}]}
   ])

(defn- apis-action
  "Recursively iterate apis and perform the action with the
  app-conf."
  [app-conf parent-api apis action]
  (doseq [api apis]
    (let [api (if parent-api
                (assoc api :local-path (str (:local-path parent-api) File/separator (:local-path api)))
                api)
          app-conf (-> app-conf
                       (assoc :parent-api parent-api)
                       (assoc :api api)
                       action)]
      (when (seq (:apis api))
        (apis-action app-conf api (:apis api) action))
      (cli/output 2 "Current application config: ")
      (cli/output 2 app-conf))))

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

(comment
  (do
    (run (cli/config ["pull" "-t" "tmp" "-p" "admin" "-f"]))
    (run (cli/config ["push" "-t" "tmp" "-p" "admin" "-f"]))
    )
  )
