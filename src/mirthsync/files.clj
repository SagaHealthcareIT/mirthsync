(ns mirthsync.files
  "Comprehensive file operations and safety utilities for mirthsync"
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [mirthsync.interfaces :as mi])
  (:import java.io.File
           java.nio.file.Path
           java.nio.file.Paths))

(defn- filtered-file-seq
  "Sequence of java.io.File including files within directories nested to
  max-depth level (defaults to 0) filtered by predicates. Each
  predicate receives a java.io.file as a parameter."
  ([preds file]
   filtered-file-seq 0 preds file)
  ([max-depth preds file]
   (let [file (io/file file)
         walk (fn walk [^File f depth]
                (lazy-seq
                 (cons f
                       (when (and (< depth max-depth)
                                  (.isDirectory f))
                         (mapcat #(walk % (inc depth)) (.listFiles f))))))]
     (filter (fn [f] (every? #(% f) preds)) (walk file 0)))))

(defn- file?
  "True if not a directory."
  [^File f]
  (not (.isDirectory f)))

(defn- ends-xml?
  "True if the file ends with .xml."
  [^File f]
  (-> f
      (.getName)
      (cs/lower-case)
      (cs/ends-with? ".xml")))

(defn- filename-matches?
  "Returns a predicate that returns true if the case-insensitive filename matches"
  [filename]
  (fn
    [^File f]
    (-> f
        (.getName)
        (cs/lower-case)
        (= (cs/lower-case filename)))))

(defn- not-filename-matches?
  "Complement of filename-matches?"
  [filename]
  (complement (filename-matches? filename)))

(defn xml-file-seq
  "Xml file sequence at dir up to depth."
  [depth dir]
  (filtered-file-seq depth [file? ends-xml?] dir))

(defn without-named-xml-files-seq
  "Sequence of named xml files contained within directories in the
  target dir and subdirectories up to depth."
  [depth name dir]
  (filtered-file-seq depth [file? ends-xml? (not-filename-matches? (str name ".xml"))] dir))

(defn only-named-xml-files-seq
  "Sequence of named xml files contained within directories in the
  target dir and subdirectories up to depth."
  [depth name dir]
  (filtered-file-seq depth [file? ends-xml? (filename-matches? (str name ".xml"))] dir))

(defn- encode-path-chars
  "Mirth is very liberal with allowing weird characters in places that can cause
  issues with the mirthsync goal of mapping the Mirth configuration to a
  reasonable directory structure. The 'safe-name' function asserts and fails
  with an exception if there's any issue detected that could cause the filename
  to have issues that could result in path traversal, etc...

  Here we take an approach to handling some characters that cause issues that
  we've seen in real world examples. This is not an exhaustive conversion list -
  it is meant as a way to keep the code backwards compatible in terms of the
  filesystem layout while allowing for a few exceptional cases. Slashes will be
  replaced with their URLEncoder/encode equivalent - forward slash becomes %2F
  and backslash becomes %5C."
  [^String name]
  (if name
    (-> name
        (cs/replace "/" "%2F")
        (cs/replace "\\" "%5C"))
    name))

(defn safe-name
  "Asserts that the string param is creatable file that doesn't span paths. Fails
  with an exception if any issues are detected, otherwise returns the unmodified
  string (unless there are weird characters as defined in encode-path-chars; in
  which case the string is modified accordingly)."
  [name]
  (log/debugf "safe-name pre: (%s)" name)
  (if-let [name (encode-path-chars name)]
    (do
      (assert
       (and (string? name) (= name (.getName (File. ^String name))))
       (str "Name does not appear to be safe"
            " for file creation - " name
            " - Check for invalid characters."))

      (log/spyf :debug "safe-name post: (%s)" name))
    name))

(defn remove-extension
  [file-path]
  (if-let [lastdot (cs/last-index-of file-path ".")]
    (subs file-path 0 lastdot)
    file-path))

;;; ========================================================================
;;; SAFE FILE OPERATIONS
;;; Safe filesystem operations with comprehensive validation
;;; ========================================================================

(defn- normalize-path
  "Convert a file path to normalized absolute path for safe comparison"
  [^String path-str]
  (-> (Paths/get path-str (into-array String []))
      .toAbsolutePath
      .normalize
      .toString))


(defn get-all-managed-directories
  "Get all directories managed by mirthsync APIs for a given target"
  [target]
  (->> (mi/all-possible-apis)
       (map #(mi/local-path % target))
       (filter identity)
       (map #(normalize-path %))
       (into #{})))

(defn get-managing-api
  "Find which API manages a given file path, if any"
  [file-path target]
  (->> (mi/all-possible-apis)
       (filter #(mi/manages-file? % file-path target))
       first))

(defn is-api-managed-file?
  "Check if a file is managed by any mirthsync API using API self-validation"
  [^File file target]
  (let [file-path (.getAbsolutePath file)]
    (boolean (get-managing-api file-path target))))

(defn- path-is-within-target?
  "Validate that a path is within the target directory"
  [target-dir file-path]
  (let [^String normalized-target (normalize-path target-dir)
        ^String normalized-file (normalize-path file-path)]
    (and (.startsWith normalized-file normalized-target)
         (not= normalized-target normalized-file)))) ; Don't allow deleting target itself

(defn- is-mirthsync-managed-file?
  "Check if a file is managed by mirthsync using API intelligence"
  [^File file target-dir]
  (let [file-path (.getAbsolutePath file)]
    (and
     ;; Must be within target directory
     (path-is-within-target? target-dir file-path)
     
     ;; Must be managed by an API OR be our state file
     (or (is-api-managed-file? file target-dir)
         (= (.getName file) ".mirthsync-state.json")))))

(defn- is-mirthsync-managed-directory?
  "Check if a directory is managed by mirthsync using API intelligence"
  [^File dir target-dir]
  (let [dir-path (.getAbsolutePath dir)
        managed-dirs (get-all-managed-directories target-dir)
        normalized-dir-path (normalize-path dir-path)
        normalized-target (normalize-path target-dir)]
    (and
     ;; Must be within target directory
     (path-is-within-target? target-dir dir-path)
     
     ;; Must be a managed directory or subdirectory of one, but NOT just any subdir of target
     (and
      ;; Not the target directory itself
      (not= normalized-dir-path normalized-target)
      ;; Must match a managed directory pattern
      (or 
       ;; Exact match to a managed directory
       (contains? managed-dirs normalized-dir-path)
       ;; Subdirectory of a managed directory (but not just any subdirectory of target)
       (some (fn [managed-dir]
               (and (not= managed-dir normalized-target)  ; Don't match target itself
                    (.startsWith ^String normalized-dir-path 
                                (str managed-dir File/separator))))
             managed-dirs))))))

(defn validate-deletion-safety
  "Comprehensive validation before any deletion operation"
  [file-or-dir target-dir operation-description]
  (let [file ^File (io/file file-or-dir)
        file-path (.getAbsolutePath file)]
    
    (log/debug "Validating deletion safety for:" file-path "in target:" target-dir)
    
    ;; Basic existence check
    (when-not (.exists file)
      (throw (ex-info "File/directory does not exist" 
                      {:file file-path :operation operation-description})))
    
    ;; Target directory must exist and be a directory
    (let [target-file (io/file target-dir)]
      (when-not (and (.exists target-file) (.isDirectory target-file))
        (throw (ex-info "Target directory does not exist or is not a directory"
                        {:target target-dir :operation operation-description}))))
    
    ;; Path must be within target
    (when-not (path-is-within-target? target-dir file-path)
      (throw (ex-info "Attempted to delete file outside target directory - BLOCKED"
                      {:file file-path :target target-dir :operation operation-description})))
    
    ;; Must be mirthsync-managed
    (when (.isFile file)
      (when-not (is-mirthsync-managed-file? file target-dir)
        (throw (ex-info "Attempted to delete non-mirthsync file - BLOCKED"
                        {:file file-path :target target-dir :operation operation-description}))))
    
    (when (.isDirectory file)
      (when-not (is-mirthsync-managed-directory? file target-dir)
        (throw (ex-info "Attempted to delete non-mirthsync directory - BLOCKED"
                        {:directory file-path :target target-dir :operation operation-description}))))
    
    (log/debug "Deletion safety validation passed for:" file-path)
    true))

(defn safe-delete-file
  "Safely delete a single file with validation"
  [file-path target-dir operation-description]
  (try
    (validate-deletion-safety file-path target-dir operation-description)
    (let [file (io/file file-path)]
      (when (.exists file)
        (log/info "Safely deleting file:" file-path "(" operation-description ")")
        (.delete file)
        (when (.exists file)
          (log/warn "Failed to delete file:" file-path))
        true))
    (catch Exception e
      (log/error "Safe deletion blocked:" (.getMessage e))
      false)))

(defn safe-delete-directory-contents
  "Safely delete contents of a directory, then the directory itself"
  [dir-path target-dir operation-description]
  (try
    (validate-deletion-safety dir-path target-dir operation-description)
    (let [dir ^File (io/file dir-path)]
      (when (.exists dir)
        (log/info "Safely deleting directory contents:" dir-path "(" operation-description ")")
        
        ;; First, recursively delete all contents
        (doseq [^File child (.listFiles dir)]
          (if (.isDirectory child)
            (safe-delete-directory-contents (.getAbsolutePath child) target-dir 
                                           (str operation-description " (recursive)"))
            (safe-delete-file (.getAbsolutePath child) target-dir 
                             (str operation-description " (file in dir)"))))
        
        ;; Then delete the directory itself if it's empty
        (when (empty? (.listFiles dir))
          (log/info "Deleting empty directory:" dir-path)
          (.delete dir)
          (when (.exists dir)
            (log/warn "Failed to delete directory:" dir-path)))
        true))
    (catch Exception e
      (log/error "Safe directory deletion blocked:" (.getMessage e))
      false)))

(defn safe-delete-api-managed-path
  "Safely delete a path that's managed by a specific API"
  [path target-dir api operation-description]
  (try
    ;; Validate that the specified API actually manages this path
    (let [managing-api (get-managing-api path target-dir)]
      (when-not (= managing-api api)
        (throw (ex-info "Path is not managed by the specified API - BLOCKED"
                        {:path path :specified-api api :actual-managing-api managing-api
                         :operation operation-description}))))
    
    (let [file (io/file path)]
      (if (.isDirectory file)
        (safe-delete-directory-contents path target-dir operation-description)
        (safe-delete-file path target-dir operation-description)))
    
    (catch Exception e
      (log/error "API-managed path deletion blocked:" (.getMessage e))
      false)))

(defn list-files-that-would-be-deleted
  "Dry-run function to list what files would be deleted (for logging/confirmation)"
  [path target-dir operation-description]
  (try
    (validate-deletion-safety path target-dir operation-description)
    (let [file (io/file path)]
      (if (.isDirectory file)
        ;; Return all files in directory tree - use our enhanced filtered-file-seq!
        (->> (filtered-file-seq 10 [file?] file)  ; Use our existing file traversal
             (map #(.getAbsolutePath ^File %))
             (filter #(is-mirthsync-managed-file? (io/file %) target-dir)))
        ;; Single file
        (if (is-mirthsync-managed-file? file target-dir)
          [(.getAbsolutePath file)]
          [])))
    (catch Exception e
      (log/warn "Would-be-deleted validation failed:" (.getMessage e))
      [])))
