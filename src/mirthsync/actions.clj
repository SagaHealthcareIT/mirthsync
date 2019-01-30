(ns mirthsync.actions
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip]
            [mirthsync.cli :as cli]
            [mirthsync.http-client :refer [fetch-all]]
            [mirthsync.xml :refer [serialize-node to-zip]]
            [clojure.data.zip.xml :as zx]))

(defn api-url
  "Returns the constructed api url."
  [{:keys [server]
    {:keys [path find-elements] :as api} :api}]
  (str server (path api)))

(defn download
  "Serializes all xml found at the api path to the filesystem using the
  supplied config. Returns a (potentially) updated app-conf with
  details about the fetched apis. If the save parameter is false, the
  apis are fetched and processed but not saved to the filesystem. This
  is useful for accumulating data related to the apis without
  committing anything to disk."
  ([app-conf]
   (download true app-conf))

  ([save {:keys [server]
          {:keys [find-elements path local-path transformer] :as api} :api
          :as app-conf}]
   (let [remote-path (api-url app-conf)
         _ (when save
             (cli/out 0 (str "Downloading from "
                                remote-path " to " (local-path app-conf))))]

     (loop [app-conf app-conf
            el-locs (fetch-all remote-path find-elements)]
       (if (seq el-locs)
         (let [el-loc (first el-locs)
               app-conf (assoc app-conf :el-loc el-loc)
               app-conf (transformer app-conf)]
           (when save (serialize-node app-conf))
           (recur app-conf (rest el-locs)))
         app-conf)))))

(defn upload-node
  "Extracts the id from the xmlloc using the find-id predicates. PUTs
  the formatted XML to the location constructed from the base-url,
  path, and id."
  [{:keys [server api server server-groups
           channel-groups el-loc] :as app-conf
    {:keys [path find-id path find-id find-name] :as api} :api}]
  (let [id (find-id el-loc)
        name (find-name el-loc)]
    (if (= (path api) "/channelgroups")
      (let [existing-id-loc (zx/xml1-> server-groups
                                       :set
                                       :channelGroup
                                       :id
                                       (zx/text= id))

            server-groups (if existing-id-loc
                            (zip/remove (zip/up existing-id-loc))
                            server-groups)

            server-groups (zip/append-child server-groups (zip/node el-loc))
            set-el (zip/node server-groups)
            ;; set-el (xml/element "set" nil (zip/node el-loc))
            ;; set-el (xml/element "set" nil (zip/children el-loc))
            ]
        (client/post (str server (path api) "/_bulkUpdate")
                     {:insecure? true
                      :multipart [{:name "channelGroups"
                                   :content (xml/indent-str set-el)
                                   :mime-type "application/xml"
                                   :encoding "UTF-8"}
                                  {:name "removedChannelGroupIds"
                                   :content "<set/>"
                                   :mime-type "application/xml"
                                   :encoding "UTF-8"}]})
        (assoc app-conf :server-groups server-groups))
      (do
        (client/put (str server (path api) "/" id)
                    {:insecure? true
                     :body (xml/indent-str (zip/node el-loc))
                     :content-type "application/xml"})
        app-conf))))

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


(defn upload
  "Takes the current app-conf with the current api and finds associated
  files within the target directory. If pushing is allowed for the
  current API, the files in the specified path directory are each read
  and handed to upload-node to push to Mirth."
  [{:keys [server] :as app-conf
    {:keys [local-path path api-files transformer] :as api} :api}]

  (cli/out 0 (str "Uploading from " (local-path app-conf) " to " (path api)))
  (let [app-conf (if (= (path api) "/channelgroups")
                   (assoc-server-groups app-conf)
                   app-conf)
        files (api-files (local-path app-conf))]
    
    (cli/out 0 (str "found " (count files) " files"))
    
    (loop [app-conf app-conf
           files files]
      (if-let [f (first files)]
        (do
          (cli/out 1 (.getName f))
          (recur
           (-> app-conf
               (assoc :el-loc (to-zip (slurp f)))
               (transformer)
               upload-node)
           (rest files)))
        app-conf))))
