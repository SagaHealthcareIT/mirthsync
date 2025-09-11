(ns mirthsync.actions
  (:require [clojure.data.xml :as cdx]
            [clojure.tools.logging :as log]
            [clojure.zip :as cz]
            [clojure.java.io :as io]
            [mirthsync.files :as mf]
            [mirthsync.interfaces :as mi]
            [mirthsync.http-client :as mhttp]
            [mirthsync.xml :as mxml]
            [mirthsync.state :as mstate]
            [mirthsync.files :as files])
  (:import java.io.File))

(defn- upload-node
  "Extracts the id from the xmlloc using the find-id predicates. PUTs or
  POSTs the params to the location constructed from the base-url,
  rest-path, and id."
  [{:keys [force api] :as app-conf}]
  (let [params (log/spyf :trace "Push params: %s" (mi/push-params api app-conf))
        query-params (log/spyf :trace "Query params: %s" (mi/query-params api app-conf))
        result (if (mi/post-path api)
                 (mhttp/post-xml app-conf (mi/post-path api) params query-params true)
                 (mhttp/put-xml app-conf query-params))]
    (if (mi/after-push api app-conf result)
      app-conf
      (assoc app-conf :exit-code 1 :exit-msg "Failure(s) were encountered during the push"))))

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
  "Lazy sequence of local el-locs for the current api."
  [{:keys [api restrict-to-path target] :as app-conf}]

  (let [^String required-prefix (str target File/separator restrict-to-path)]
    (log/debugf "required-prefix: %s" required-prefix)

    (sequence
     (comp (filter #(let [matches (.startsWith (.toString ^File %) required-prefix)]
                    (if matches
                      (do (when (seq restrict-to-path)
                            (log/infof "Found a match: %s" %))
                          true)
                      (do (log/infof "filtering push of '%s' since it does not start with our required prefix: %s" % required-prefix)
                          false))))
          (map #(expand-filerefs %
                                (mxml/to-zip
                                 (do
                                   (log/infof "\tFile: %s" (.toString ^File %))
                                   (slurp %)))))
          (filter #(not (mi/should-skip? api % app-conf))))
     (mi/api-files api (mi/local-path api (:target app-conf))))))

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

(defn clean-target-directories
  "Safely remove all tracked mirthsync directories for a clean pull"
  [target apis]
  (log/info "Starting clean-target operation with comprehensive safety checks")
  
  ;; First, log what would be deleted for transparency
  (log/info "Files/directories that will be cleaned:")
  (doseq [api apis]
    (let [local-path (mi/local-path api target)]
      (when (.exists (io/file local-path))
        (let [files-to-delete (files/list-files-that-would-be-deleted 
                              local-path target "clean-target directory scan")]
          (when (seq files-to-delete)
            (log/info "  API" api "- would delete" (count files-to-delete) "files in" local-path))))))
  
  ;; Now safely delete API-managed directories
  (doseq [api apis]
    (let [local-path (mi/local-path api target)]
      (when (.exists (io/file local-path))
        (files/safe-delete-api-managed-path 
         local-path target api (str "clean-target for API " api)))))
  
  ;; Also clean up individual files that might be at the target root
  (doseq [api [:configuration-map :resources :server-configuration]]
    (when-let [local-path (mi/local-path api target)]
      (when (.exists (io/file local-path))
        (files/safe-delete-api-managed-path 
         local-path target api (str "clean-target root file for API " api)))))
  
  (log/info "Clean-target operation completed"))

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

(defn collect-server-entities
  "Collect all entities from the server for all APIs for state tracking"
  [app-conf apis]
  (into {}
        (map (fn [api]
               (let [entities (remote-locs (assoc app-conf :api api))]
                 [api entities]))
             apis)))

(defn download-with-state-tracking
  "Download with three-way sync state tracking to handle renames/deletions"
  [app-conf apis]
  (let [target (:target app-conf)]
    (log/info "Starting download with state tracking...")
    
    ;; 1. Load previous state
    (let [previous-state (mstate/load-sync-state target)]
      (log/debug "Loaded previous state with" (count previous-state) "APIs")
      
      ;; 2. Collect current server entities for all APIs
      (log/info "Collecting server entities for state comparison...")
      (let [server-entities (collect-server-entities app-conf apis)
            current-state (mstate/build-current-state app-conf server-entities)]
        
        ;; 3. Detect changes (renames, deletions, new)
        (let [changes (mstate/detect-changes previous-state current-state)]
          (log/info "Detected changes:"
                    (count (:renamed changes)) "renamed,"
                    (count (:deleted changes)) "deleted,"
                    (count (:new changes)) "new entities")
          
          ;; 4. Clean up stale files before download
          (mstate/cleanup-stale-files changes target)
          
          ;; 5. Proceed with normal download for all APIs
          (let [updated-app-conf 
                (reduce (fn [acc-conf api]
                          (download (assoc acc-conf :api api)))
                        app-conf
                        apis)]
            
            ;; 6. Save new state
            (mstate/save-sync-state target current-state)
            (log/info "Download with state tracking completed")
            updated-app-conf))))))

(defn upload
  "Takes the current app-conf with the current api and finds associated
  files within the target directory. The files in the specified path
  directory are each read and handed to upload-node to push to Mirth."
  [{:keys [api] :as app-conf}]

  (process-nodes
       app-conf
       (str "Uploading from " (mi/local-path api (:target app-conf)) " to " (mi/rest-path api))
       (local-locs app-conf)
       upload-node))
