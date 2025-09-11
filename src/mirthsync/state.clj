(ns mirthsync.state
  "State tracking for three-way sync to handle renames and deletions gracefully"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [mirthsync.interfaces :as mi]
            [mirthsync.files :as files])
  (:import java.io.File))

(def ^:private state-filename ".mirthsync-state.json")

(defn- state-file-path
  "Returns the path to the state file for a given target directory"
  [target]
  (str target File/separator state-filename))

(defn load-sync-state
  "Load the previous sync state from target directory. Returns empty map if no state exists."
  [target]
  (let [state-path (state-file-path target)]
    (try
      (if (.exists (io/file state-path))
        (do
          (log/debug "Loading sync state from" state-path)
          (-> state-path
              slurp
              (json/read-str :key-fn keyword)))
        (do
          (log/debug "No existing sync state found at" state-path)
          {}))
      (catch Exception e
        (log/warn "Failed to load sync state from" state-path ":" (.getMessage e))
        {}))))

(defn save-sync-state
  "Save the current sync state to target directory"
  [target state]
  (let [state-path (state-file-path target)]
    (try
      (log/debug "Saving sync state to" state-path)
      (io/make-parents state-path)
      (spit state-path (json/write-str state :indent true))
      (log/debug "Sync state saved successfully")
      true
      (catch Exception e
        (log/error "Failed to save sync state to" state-path ":" (.getMessage e))
        false))))

(defn build-entity-state
  "Build state entry for a single entity from its XML location"
  [api el-loc app-conf]
  (let [id (mi/find-id api el-loc)
        name (mi/find-name api el-loc)
        file-path (mi/file-path api app-conf)]
    (when (and id file-path)
      {:id id
       :name name
       :file-path file-path
       :api api})))

(defn build-current-state
  "Build complete state map from server entities"
  [app-conf api-entities]
  (->> api-entities
       (map (fn [[api entities]]
              [api (->> entities
                       (map #(build-entity-state api % (assoc app-conf :api api :el-loc %)))
                       (filter identity)
                       (map (fn [entity] [(:id entity) entity]))
                       (into {}))]))
       (into {})))

(defn detect-changes
  "Compare server state with previous state to detect changes.
   Returns {:renamed [] :deleted [] :new [] :unchanged []}"
  [previous-state current-state]
  (let [all-apis (set (concat (keys previous-state) (keys current-state)))
        changes {:renamed [] :deleted [] :new [] :unchanged []}]
    
    (reduce
     (fn [acc api]
       (let [prev-entities (get previous-state api {})
             curr-entities (get current-state api {})
             prev-ids (set (keys prev-entities))
             curr-ids (set (keys curr-entities))]
         
         (-> acc
             ;; Deleted entities (in previous but not current)
             (update :deleted concat
                     (->> (clojure.set/difference prev-ids curr-ids)
                          (map #(get prev-entities %))
                          (filter identity)))
             
             ;; New entities (in current but not previous)  
             (update :new concat
                     (->> (clojure.set/difference curr-ids prev-ids)
                          (map #(get curr-entities %))
                          (filter identity)))
             
             ;; Check existing entities for renames
             (update :renamed concat
                     (->> (clojure.set/intersection prev-ids curr-ids)
                          (map (fn [id]
                                 (let [prev-entity (get prev-entities id)
                                       curr-entity (get curr-entities id)]
                                   (when (not= (:file-path prev-entity) (:file-path curr-entity))
                                     {:old prev-entity :new curr-entity}))))
                          (filter identity)))
             
             ;; Unchanged entities
             (update :unchanged concat
                     (->> (clojure.set/intersection prev-ids curr-ids)
                          (map (fn [id]
                                 (let [prev-entity (get prev-entities id)
                                       curr-entity (get curr-entities id)]
                                   (when (= (:file-path prev-entity) (:file-path curr-entity))
                                     curr-entity))))
                          (filter identity))))))
     changes
     all-apis)))

(defn cleanup-stale-files
  "Safely remove files for renamed or deleted entities with comprehensive validation"
  [changes target-dir]
  (log/info "Starting safe cleanup of stale files")
  
  ;; Log what will be cleaned up for transparency
  (let [deleted-count (count (:deleted changes))
        renamed-count (count (:renamed changes))]
    (log/info "Will clean up:" deleted-count "deleted entities," renamed-count "renamed entities"))
  
  ;; Clean up deleted entities
  (doseq [deleted-entity (:deleted changes)]
    (let [file-path (:file-path deleted-entity)
          api (:api deleted-entity)]
      (when (.exists (io/file file-path))
        (log/info "Safely removing deleted entity:" file-path)
        (files/safe-delete-api-managed-path 
         file-path target-dir api 
         (str "cleanup deleted entity: " (:name deleted-entity))))))
  
  ;; Clean up old files for renamed entities
  (doseq [rename-change (:renamed changes)]
    (let [old-path (get-in rename-change [:old :file-path])
          old-api (get-in rename-change [:old :api])
          new-path (get-in rename-change [:new :file-path])]
      (when (.exists (io/file old-path))
        (log/info "Safely removing renamed entity old file:" old-path "-> will be recreated as" new-path)
        (files/safe-delete-api-managed-path 
         old-path target-dir old-api
         (str "cleanup renamed entity: " (get-in rename-change [:old :name]) 
              " -> " (get-in rename-change [:new :name]))))))
  
  (log/info "Safe cleanup of stale files completed"))