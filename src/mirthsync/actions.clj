(ns mirthsync.actions
  (:require [clojure.data.xml :as cdx]
            [clojure.tools.logging :as log]
            [clojure.zip :as cz]
            [clojure.string :as str]
            [clojure.java.io :as io]
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

  (process-nodes
       app-conf
       (str "Uploading from " (mi/local-path api (:target app-conf)) " to " (mi/rest-path api))
       (local-locs app-conf)
       upload-node))

(defn get-remote-expected-file-paths
  "Get a set of file paths that would be generated from remote elements."
  [{:keys [api] :as app-conf}]
  (let [remote-elements (remote-locs app-conf)]
    (set (mapcat (fn [el-loc]
                   (let [app-conf-with-el (assoc app-conf :el-loc el-loc)]
                     (map first (mi/deconstruct-node app-conf-with-el (mi/file-path api app-conf-with-el) el-loc))))
                 remote-elements))))

(defn- find-orphaned-files-in-list
  "Find files in the provided list that don't have corresponding expected paths.
   Used internally by orphan cleanup functions."
  [files expected-paths target-path]
  (let [target-absolute-path (.getCanonicalPath (io/file target-path))]
    (filter (fn [^File file]
              (let [file-absolute-path (.getAbsolutePath file)
                    ;; Convert absolute path to relative path starting with target
                    file-relative-path (if (.startsWith file-absolute-path target-absolute-path)
                                         (str target-path
                                              (.substring file-absolute-path (.length target-absolute-path)))
                                         file-absolute-path)]
                (not (contains? expected-paths file-relative-path))))
            files)))

(defn confirm-deletion
  "Ask user for confirmation to delete orphaned files in interactive mode."
  [orphaned-files]
  (if (empty? orphaned-files)
    true
    (do
      (log/info "The following orphaned files will be deleted:")
      (doseq [^File file orphaned-files]
        (log/info (str "  " (.getAbsolutePath file))))
      (print "Do you want to delete these orphaned files? (y/N): ")
      (flush)
      (let [response (read-line)]
        (or (= (str/lower-case response) "y")
            (= (str/lower-case response) "yes"))))))

(defn- safe-delete-file?
  "Check if a file can be safely deleted by ensuring it's within the target directory."
  [^File file target-dir]
  (try
    (let [file-canonical (.getCanonicalPath file)
          target-canonical (.getCanonicalPath (io/file target-dir))]
      ;; Allow deletion if the file path is within target directory, even if file no longer exists
      (.startsWith file-canonical target-canonical))
    (catch Exception e
      (log/warn (str "Failed to validate file path for deletion: " (.getAbsolutePath file) " - " (.getMessage e)))
      false)))

(defn delete-orphaned-files
  "Delete orphaned files with proper logging and confirmation."
  [{:keys [interactive api] :as app-conf} orphaned-files]
  (if (empty? orphaned-files)
    (do
      (log/info "No orphaned files found.")
      app-conf)
    (let [should-delete (if interactive
                          (confirm-deletion orphaned-files)
                          true)
          target-dir (:target app-conf)]
      (if should-delete
        (do
          (log/info (str "Deleting " (count orphaned-files) " orphaned files..."))
          (doseq [^File file orphaned-files]
            (log/info (str "Deleting: " (.getAbsolutePath file)))
            (if (safe-delete-file? file target-dir)
              (when (.exists file)  ; Only attempt deletion if file still exists
                (try
                  (.delete file)
                  (catch Exception e
                    (log/error (str "Failed to delete " (.getAbsolutePath file) ": " (.getMessage e))))))
              (log/warn (str "Skipping deletion of file outside target directory: " (.getAbsolutePath file)))))
          (log/info "Orphaned file cleanup completed."))
        (do
          (log/info "Skipping deletion of orphaned files.")
          (log/info "Use --interactive flag to confirm deletions interactively.")))
      app-conf)))

(defn- all-files-seq
  "Get all files (not just XML) in a directory tree."
  [dir]
  (when (.exists (io/file dir))
    (filter #(.isFile %) (file-seq (io/file dir)))))

(defn capture-pre-pull-local-files
  "Capture API-managed local files before pull operation for orphan detection."
  [{:keys [api] :as app-conf}]
  (let [local-path (mi/local-path api (:target app-conf))
        target-path (:target app-conf)
        ;; Normalize paths by removing trailing slashes for comparison
        normalized-local (if (.endsWith local-path "/")
                           (.substring local-path 0 (dec (.length local-path)))
                           local-path)
        normalized-target (if (.endsWith target-path "/")
                            (.substring target-path 0 (dec (.length target-path)))
                            target-path)
        ;; For APIs that use the root target directory (like configuration-map, resources),
        ;; only capture files that match the API's file pattern instead of all files
        api-managed-files (if (= normalized-local normalized-target)
                            ;; For root-level APIs, use mi/api-files to get only managed files
                            (mi/api-files api local-path)
                            ;; For subdirectory APIs, capture all files in their directory
                            (all-files-seq local-path))]
    (update app-conf :pre-pull-local-files (fnil into []) api-managed-files)))

(defn capture-expected-paths
  "Capture expected file paths from remote server before pull operation."
  [{:keys [api] :as app-conf}]
  (let [expected-paths (get-remote-expected-file-paths app-conf)]
    (assoc app-conf :expected-paths expected-paths)))


(defn cleanup-orphaned-files-with-pre-pull
  "Clean up orphaned local files using pre-pull captured files."
  [{:keys [delete-orphaned pre-pull-local-files] :as app-conf} apis]
  (log/info "Checking for orphaned files using pre-pull captured files...")
  (let [all-expected-paths (set (mapcat (fn [api]
                                          (let [api-conf (assoc app-conf :api api)]
                                            (get-remote-expected-file-paths api-conf)))
                                        apis))
        orphaned-files (find-orphaned-files-in-list pre-pull-local-files all-expected-paths (:target app-conf))
        ;; Remove duplicate files that may be captured by multiple APIs
        unique-orphaned-files (distinct orphaned-files)]
    (if (empty? unique-orphaned-files)
      (do
        (log/info "No orphaned files found.")
        app-conf)
      (if delete-orphaned
        (delete-orphaned-files app-conf unique-orphaned-files)
        (do
          (log/info "WARNING: Found orphaned files that no longer exist on the remote server:")
          (doseq [^File file unique-orphaned-files]
            (log/info (str "  " (.getAbsolutePath file))))
          (log/info (str "These " (count unique-orphaned-files) " orphaned files were not deleted."))
          (log/info "Use the --delete-orphaned flag to automatically delete orphaned files during pull operations.")
          app-conf)))))
