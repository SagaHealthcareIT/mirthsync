(ns mirthsync.actions
  (:require [clojure.data.xml :as cdx]
            [clojure.tools.logging :as log]
            [clojure.zip :as cz]
            [mirthsync.files :as mf]
            [mirthsync.interfaces :as mi]
            [mirthsync.http-client :as mhttp]
            [mirthsync.xml :as mxml])
  (:import java.io.File))

(defn- upload-node
  "Extracts the id from the xmlloc using the find-id predicates. PUTs or
  POSTs the params to the location constructed from the base-url,
  rest-path, and id."
  [{:keys [force api] :as app-conf}]
  (let [params (log/spyf :trace "Push params: %s" (mi/push-params api app-conf))
        query-params (log/spyf :trace "Query params: %s" (mi/query-params api force))
        result (if (mi/post-path api)
                 (mhttp/post-xml app-conf (mi/post-path api) params query-params true)
                 (mhttp/put-xml app-conf params))]
    (mi/after-push api app-conf result)
    app-conf))

(defn fetch-and-pre-assoc
  "Fetches the children of the current api from the server. Wraps the
  children with the supplied keyword tag, zippers the zml and returns
  a modified app-conf with the zipper assoc'ed using the supplied
  keyword."
  [k ktag app-conf]
  (assoc app-conf k (->> (mhttp/fetch-all app-conf identity)
                         cz/children
                         (apply cdx/element ktag nil)
                         cz/xml-zip)))

(defn expand-filerefs
  "Expands fileref elements found within the passed xml zip and returns the
  expanded modified xml (zip)"
  [main-file el-loc]
  (loop [el-loc el-loc]
    (if (cz/end? el-loc)
      (cz/xml-zip (cz/root el-loc))
      (let [node (cz/node el-loc)
            tag (:tag node)
            next-el-loc (if-let
                            [fileref (when tag (:msync-fileref (:attrs node)))]
                          (let [file-text (slurp (str (mf/remove-extension (.toString ^File main-file)) File/separator (mf/safe-name fileref)))]
                            (cz/next (cz/edit el-loc (fn [node]
                                                       (assoc (assoc node :content (list file-text)) :attrs (dissoc (:attrs node) :msync-fileref))))))
                          (cz/next el-loc))]

        (recur next-el-loc)))))

(defn- local-locs
  "Lazy seq of local el-locs for the current api."
  [{:keys [api restrict-to-path target api] :as app-conf}]
  (let [^String required-prefix (str target File/separator restrict-to-path)
        filtered-api-files (filter #(let [matches (.startsWith (.toString ^File %) required-prefix)]
                                      (if matches
                                        (do (when (seq restrict-to-path)
                                              (log/infof "Found a match: %s" %))
                                            true)
                                        (do (log/infof "filtering push of '%s' since it does not start with our required prefix: %s" % required-prefix)
                                            false)))
                                   (mi/api-files api (mi/local-path api (:target app-conf))))]
    (log/debugf "required-prefix: %s" required-prefix)

    (map #(expand-filerefs %
                           (mxml/to-zip
                            (do
                              (log/infof "\tFile: %s" (.toString ^File %))
                              (slurp %))))
         filtered-api-files)))

(defn- remote-locs
  "Seq of remote el-locs for the current api. Could be lazy or not
  depending on the implementation of find-elements."
  [{:keys [api] :as app-conf}]
  (mhttp/fetch-all app-conf (partial mi/find-elements api)))

(defn-  process-nodes
  "Prints the message and processes the el-locs via the action."
  [{:keys [api] :as app-conf} msg el-locs action]
  (log/info msg)
  (loop [app-conf app-conf
         el-locs el-locs]
    (if-let [el-loc (first el-locs)]
      (recur
                                    ; We need to re-zip our node since it's part
                                    ; of a larger doc and the called code
                                    ; shouldn't be exposed to that. cz/root for instance
                                    ; would return something unexpected.
       (->> (assoc app-conf :el-loc (cz/xml-zip (cz/node el-loc)))
            (mi/pre-node-action api)
            action)
       (rest el-locs))
      app-conf)))

(defn download
  "Serializes all xml found at the api rest-path to the filesystem using the
  supplied config. Returns a (potentially) updated app-conf with
  details about the fetched apis."
  [{:keys [api] :as app-conf}]
  (process-nodes
   app-conf
   (str "Downloading from " (mhttp/api-url app-conf) " to " (mi/local-path api (:target app-conf)))
   (remote-locs app-conf)
   mxml/serialize-node))

(defn upload
  "Takes the current app-conf with the current api and finds associated
  files within the target directory. The files in the specified path
  directory are each read and handed to upload-node to push to Mirth."
  [{:keys [api] :as app-conf}]

  ;; The only time dont want to push is when
  ;; rest-path = "/server/configurationMap" and include-configuration-map false.
  ;; This is being done to preserve backward compatibility with
  ;; versions of mirthSync prior to 2.0.11 that weren't able to
  ;; upload the configurationmap and resources. For resources, we're
  ;; not preserving backward compatibility and are defaulting to pushing
  ;; them automatically in releases after 2.0.10.
  ;;
  ;; TODO - Consideration should be given to whether the following approach is
  ;; the best way to decide when to push the configurationmap. It might
  ;; be better to do this check earlier in the flow and remove the
  ;; configurationmap api from the vector of apis in the run function
  ;; in core.clj.
  (if (and (= (mi/rest-path api) "/server/configurationMap") (not (:include-configuration-map app-conf)))
    app-conf
    (process-nodes
       app-conf
       (str "Uploading from " (mi/local-path api (:target app-conf)) " to " (mi/rest-path api))
       (local-locs app-conf)
       upload-node)))
