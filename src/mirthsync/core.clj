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
  channel locs based on the supplied predicates. In other words - grab
  the xml from the url via a GET request, extract the :body of the
  result, parse the XML, create a 'zipper', and return the result of
  the query (predicates)"
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
  [xmlloc {:keys [target] :as app-conf} {:keys [local-path find-name] :as api}]
  
  (let [name (apply zx/xml1-> xmlloc find-name)
        xml-str (xml/indent-str (zip/node xmlloc))
        file-path (str target (File/separator) local-path (File/separator) name ".xml")]
    (if (and (.exists (io/file file-path))
             (not (:force app-conf)))
      (cli/output (str "File at " file-path " already exists and the "
                        "force (-f) option was not specified. Refusing "
                        "to overwrite the file."))
      (do (io/make-parents file-path)
          (spit file-path xml-str)))
    nil))

(comment TODO - We need to always pull channel groups first when
  working with channels. Every channel is now in a channel group,
  whether 'Default Group' or custom. The new directory structure will
  be "Channels/[group-name]". Each [group-name] directory will contain
  a "Group.xml" and one or more channels. Upon upload of a group, this
  Group.xml file will be parsed and the respective channels in the
  directory will be inserted in the Group.xml "Channels" element. The
  resulting xml will be posted multipart to the bulkUpload
  webservice.)

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

(defn download
  "Serializes all xml found at the api path using the supplied config."
  [{:keys [server target parent-api api] :as app-conf}]
  (let [{:keys [find-elements path local-path]} api
        local-path (if parent-api
                     (str (:local-path parent-api) "/" local-path)
                     local-path)]
    (cli/output 0 (str "Downloading from " path " to " local-path))
    (doseq [loc (fetch-all (str server (str "/" path)) find-elements)]
      (serialize-node loc app-conf api))))

(defn upload
  "Extracts the server url and the target local directory that contains
  our mirth files from the application config param. Also extracts
  some of the api spec from the api param. If pushing is allowed for
  the current API, the files in the specified path directory are
  iterated, parsed, and uploaded to mirth via the upload-node
  function."
  [{:keys [server target api parent-api api] :as app-conf}]
  (let [{:keys [find-id local-path path prevent-push]} api
        local-path (if parent-api
                     (str (:local-path parent-api) "/" local-path)
                     local-path)]
    (if prevent-push
      (cli/output 0 (str "Uploading from " local-path " to " path " is not currently supported - skipping"))
      (do
        (cli/output 0 (str "Uploading from " local-path " to " path))
        (let [files (filter #(not (.isDirectory %)) (.listFiles (io/file (str target (File/separator) local-path))))]
          (cli/output 0 (str "found " (count files) " files"))
          (doseq [f files]
            (upload-node server path find-id (to-zip (slurp f)))))))))

(def apis
  [{:local-path "."
    :find-elements [:map]
    :find-id [(constantly nil)]
    :find-name [(fn [_] "ConfigurationMap")]
    :path "server/configurationMap"}

   {:local-path "CodeTemplates"
    :find-elements [:list :codeTemplate]
    :find-id [:id zip/down zip/node]
    :find-name [:name zip/down zip/node]
    :path "codeTemplates"}

   {:local-path "GlobalScripts"
    :find-elements [:map]
    :find-id [(constantly nil)]
    :find-name [(fn [_] "globalScripts")]
    :path "server/globalScripts"}

   {:local-path "Channels"
    :find-elements [:list :channelGroup]
    :find-id [:id zip/down zip/node]
    :find-name [:name zip/down zip/node]
    :path "channelgroups"
    :prevent-push true
    :apis [{:local-path "tmp-channels"
            :find-elements [:list :channel]
            :find-id [:id zip/down zip/node]
            :find-name [:name zip/down zip/node]
            :path "channels"}]}
   ])

(defn- apis-action
  [app-conf parent-api apis action]
  (doseq [api apis]
    (let [app-conf (-> app-conf
                       (assoc :parent-api parent-api)
                       (assoc :api api))]
      (cli/output 1 api)
      (action app-conf)
      (when (seq (:apis api))
        (apis-action app-conf api (:apis api) action)))))

(defn run
  "Start of app logic. Links the action specified in the application
  config to the appropriate function, authenticates to the server, and
  calls the function for each api in the api map (in order). These
  calls happen within the context of an authenticated (thread local)
  http client. Returns the application config."
  [{:keys [server username password] :as app-conf}]
  (let [action ({"push" upload, "pull" download} (:action app-conf))]
    (cli/output 0 (str "Authenticating to server at " server " as " username))
    (with-authentication server username password
      #(apis-action app-conf nil apis action))
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

