(ns mirthsync.actions
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip]
            [mirthsync.cli :as cli]
            [mirthsync.http-client :refer [fetch-all put-xml post-xml]]
            [mirthsync.xml :refer [serialize-node to-zip]]
            [clojure.data.zip.xml :as zx]))

(defn api-url
  "Returns the constructed api url."
  [{:keys [server]
    {:keys [rest-path find-elements] :as api} :api}]
  (str server (rest-path api)))

(defn upload-node
  "Extracts the id from the xmlloc using the find-id predicates. PUTs or
  POSTs the params to the location constructed from the base-url,
  rest-path, and id."
  [{:keys [el-loc] :as app-conf
    {:keys [post-path post-params] :as api} :api}]
  
  (if (post-path api)
    (apply post-xml app-conf (post-params app-conf))
    (put-xml app-conf))
  app-conf)

(defn assoc-server-groups
  "Fetches the current groups from server. Adds the top level element
  loc to app-conf."
  [app-conf]
  (assoc app-conf
         :server-groups
         (zip/xml-zip
          (apply xml/element
                 :set nil (zip/children
                           (fetch-all (api-url app-conf)
                                      identity))))))

;FIXME: dedupe 'bulk' logic
(defn assoc-server-codelibs
  "Fetches the current code libs from server. Adds the top level element
  loc to app-conf."
  [app-conf]
  (assoc app-conf
         :server-codelibs
         (zip/xml-zip
          (apply xml/element
                 :list nil (zip/children
                           (fetch-all (api-url app-conf)
                                      identity))))))
(defn local-locs
  "Lazy seq of local el-locs for the current api."
  [{:as app-conf
    {:keys [local-path api-files]} :api}]
  (map #(to-zip (slurp %)) (api-files (local-path app-conf))))

(defn remote-locs
  "Seq of remote el-locs for the current api. Could be lazy or not
  depending on the implementation of find-elements."
  [{:as app-conf
    {:keys [find-elements] :as api} :api}]
  (fetch-all (api-url app-conf) find-elements))

(defn process
  "Prints the message and processes the el-locs via the action."
  [{:as app-conf
    {:keys [pre-transform]} :api} msg el-locs action]
  (cli/out msg)
  (loop [app-conf app-conf
         el-locs el-locs]
    (if-let [el-loc (first el-locs)]
      (recur
       (-> app-conf
           (assoc :el-loc el-loc)
           (pre-transform)
           action)
       (rest el-locs))
      app-conf)))

(defn download
  "Serializes all xml found at the api rest-path to the filesystem using the
  supplied config. Returns a (potentially) updated app-conf with
  details about the fetched apis."
  [{:as app-conf
    {:keys [local-path transformer] :as api} :api}]
  (process
   app-conf
   (str "Downloading from " (api-url app-conf) " to " (local-path app-conf))
   (remote-locs app-conf)
   serialize-node))

(defn upload
  "Takes the current app-conf with the current api and finds associated
  files within the target directory. The files in the specified path
  directory are each read and handed to upload-node to push to Mirth."
  [{:as app-conf
    {:keys [local-path rest-path transformer] :as api} :api}]

  (process
   app-conf
   (str "Uploading from " (local-path app-conf) " to " (rest-path api))
   (local-locs app-conf)
   upload-node))
