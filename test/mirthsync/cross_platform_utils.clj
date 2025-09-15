(ns mirthsync.cross-platform-utils
  "Cross-platform utilities to replace Unix-specific shell commands in tests"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File FileInputStream ByteArrayOutputStream]
           [java.nio.file Files Paths StandardCopyOption LinkOption FileVisitOption]
           [java.nio.file.attribute BasicFileAttributes]
           [java.util.zip GZIPInputStream ZipInputStream ZipEntry]
           [java.security MessageDigest]
           [org.apache.commons.io FileUtils]
           [org.apache.commons.compress.archivers.tar TarArchiveInputStream TarArchiveEntry]
           [org.apache.commons.compress.compressors.gzip GzipCompressorInputStream]
           [org.apache.commons.codec.digest DigestUtils]
           [java.net URL]
           [java.nio.channels Channels ReadableByteChannel]))


;; File system operations

(defn safe-delete-file?
  "Check if a file can be safely deleted by ensuring it's within the allowed directory.
   Returns true only if the file's canonical path is within the allowed directory."
  [file allowed-dir]
  (try
    (let [file-canonical (.getCanonicalPath (io/file file))
          allowed-canonical (.getCanonicalPath (io/file allowed-dir))]
      (.startsWith file-canonical allowed-canonical))
    (catch Exception e
      ;; If we can't resolve paths, be safe and don't allow deletion
      false)))

(defn mkdir-p
  "Cross-platform mkdir -p equivalent"
  [path]
  (let [file (io/file path)]
    (.mkdirs file)
    file))

(defn copy-file
  "Cross-platform file copy"
  [src dest]
  (let [src-path (Paths/get src (into-array String []))
        dest-path (Paths/get dest (into-array String []))]
    (Files/copy src-path dest-path
                (into-array [StandardCopyOption/REPLACE_EXISTING]))))

(defn copy-recursive
  "Cross-platform recursive directory copy"
  [src dest]
  (FileUtils/copyDirectory (io/file src) (io/file dest)))

(defn delete-file
  "Cross-platform file deletion with safety check"
  [path & {:keys [allowed-dir]}]
  (let [file (io/file path)]
    (when (.exists file)
      (if allowed-dir
        (when (safe-delete-file? file allowed-dir)
          (.delete file))
        (throw (ex-info "delete-file requires :allowed-dir parameter for safety"
                       {:path path}))))))

(defn delete-recursive
  "Cross-platform recursive directory deletion with safety check"
  [path & {:keys [allowed-dir]}]
  (let [file (io/file path)]
    (when (.exists file)
      (if allowed-dir
        (when (safe-delete-file? file allowed-dir)
          (FileUtils/deleteDirectory file))
        (throw (ex-info "delete-recursive requires :allowed-dir parameter for safety"
                       {:path path}))))))

(defn file-exists?
  "Cross-platform file existence check"
  [path]
  (.exists (io/file path)))

(defn directory?
  "Cross-platform directory check"
  [path]
  (.isDirectory (io/file path)))

(defn empty-directory?
  "Check if directory is empty"
  [path]
  (let [dir (io/file path)]
    (and (.isDirectory dir)
         (empty? (.listFiles dir)))))

;; Find equivalent
(defn find-files
  "Cross-platform find equivalent for files matching pattern"
  [root-path & {:keys [type name]}]
  (let [root (Paths/get root-path (into-array String []))
        files (atom [])]
    (Files/walkFileTree root
      (reify java.nio.file.FileVisitor
        (visitFile [this file attrs]
          (let [file-path (.toString file)
                filename (.toString (.getFileName file))]
            (when (or (nil? name)
                     (if (str/includes? name "*")
                       (let [pattern (str/replace name "*" ".*")]
                         (re-matches (re-pattern pattern) filename))
                       (str/includes? filename name)))
              (when (or (nil? type)
                       (case type
                         "f" (Files/isRegularFile file (into-array LinkOption []))
                         "d" (Files/isDirectory file (into-array LinkOption []))))
                (swap! files conj file-path))))
          java.nio.file.FileVisitResult/CONTINUE)
        (preVisitDirectory [this dir attrs]
          (let [dirname (.toString (.getFileName dir))]
            (when (and (= type "d")
                       (or (nil? name)
                           (if (str/includes? name "*")
                             (let [pattern (str/replace name "*" ".*")]
                               (re-matches (re-pattern pattern) dirname))
                             (str/includes? dirname name))))
              (swap! files conj (.toString dir))))
          java.nio.file.FileVisitResult/CONTINUE)
        (visitFileFailed [this file exc]
          java.nio.file.FileVisitResult/CONTINUE)
        (postVisitDirectory [this dir exc]
          java.nio.file.FileVisitResult/CONTINUE)))
    (str/join "\n" @files)))

;; Checksum operations
(defn sha256sum
  "Cross-platform SHA-256 checksum calculation"
  [file-path]
  (DigestUtils/sha256Hex (FileInputStream. file-path)))

(defn validate-checksum
  "Validate file checksum against expected value"
  [file-path expected-checksum]
  (let [actual-checksum (sha256sum file-path)]
    (= actual-checksum expected-checksum)))

;; Download operations
(defn download-file
  "Cross-platform file download with progress indication"
  [url dest-path & {:keys [progress-fn]}]
  (let [url-obj (URL. url)
        dest-file (io/file dest-path)]
    ;; Ensure parent directory exists
    (.mkdirs (.getParentFile dest-file))
    (let [conn (.openConnection url-obj)]
      ;; Follow redirects
      (when (instance? java.net.HttpURLConnection conn)
        (.setInstanceFollowRedirects ^java.net.HttpURLConnection conn true))
      (with-open [in (.getInputStream conn)
                  out (java.io.FileOutputStream. dest-file)]
        ;; Use simple copy instead of NIO channels for better compatibility
        (io/copy in out)))
    (when progress-fn (progress-fn dest-path))
    dest-path))

;; Archive operations
(defn unpack-tar-gz
  "Cross-platform tar.gz extraction"
  [archive-path dest-dir & {:keys [strip-components]}]
  (mkdir-p dest-dir)
  (let [strip-count (or strip-components 0)]
    (with-open [fis (FileInputStream. archive-path)
                gis (GzipCompressorInputStream. fis)
                tis (TarArchiveInputStream. gis)]
      (loop []
        (when-let [entry (.getNextTarEntry tis)]
          (let [name (.getName entry)
                path-parts (str/split name #"/")
                ;; Only strip if we have enough parts to strip
                stripped-parts (if (> (count path-parts) strip-count)
                                 (drop strip-count path-parts)
                                 path-parts)
                stripped-name (str/join "/" stripped-parts)]
            (when (not (str/blank? stripped-name))
              (let [dest-file (io/file dest-dir stripped-name)]
                (if (.isDirectory entry)
                  (.mkdirs dest-file)
                  (do
                    (.mkdirs (.getParentFile dest-file))
                    (with-open [fos (io/output-stream dest-file)]
                      (let [buffer (byte-array 4096)]
                        (loop []
                          (let [bytes-read (.read tis buffer)]
                            (when (> bytes-read 0)
                              (.write fos buffer 0 bytes-read)
                              (recur))))))))))
          (recur)))))))

(defn unpack-zip
  "Cross-platform ZIP extraction"
  [archive-path dest-dir & {:keys [strip-components]}]
  (mkdir-p dest-dir)
  (let [strip-count (or strip-components 0)]
    (with-open [fis (FileInputStream. archive-path)
                zis (ZipInputStream. fis)]
      (loop []
        (when-let [entry (.getNextEntry zis)]
          (let [name (.getName entry)
                path-parts (str/split name #"/")
                ;; Only strip if we have enough parts to strip
                stripped-parts (if (> (count path-parts) strip-count)
                                 (drop strip-count path-parts)
                                 path-parts)
                stripped-name (str/join "/" stripped-parts)]
            (when (not (str/blank? stripped-name))
              (let [dest-file (io/file dest-dir stripped-name)]
                (if (.isDirectory entry)
                  (.mkdirs dest-file)
                  (do
                    (.mkdirs (.getParentFile dest-file))
                    (with-open [fos (io/output-stream dest-file)]
                      (let [buffer (byte-array 4096)]
                        (loop []
                          (let [bytes-read (.read zis buffer)]
                            (when (> bytes-read 0)
                              (.write fos buffer 0 bytes-read)
                              (recur)))))))))))
          (recur))))))

;; Process operations
(defn java-version
  "Get Java version information"
  []
  (System/getProperty "java.version"))

(defn is-java8?
  "Check if running Java 8"
  []
  (let [version (java-version)]
    (or (str/starts-with? version "1.8")
        (str/starts-with? version "8"))))

;; Text processing
(defn update-xml-files
  "Cross-platform XML file updates using Java regex"
  [root-path]
  (let [xml-files (str/split (find-files root-path :type "f" :name "*.xml") #"\n")]
    (doseq [file-path xml-files]
      (when (and (not (str/blank? file-path)) (file-exists? file-path))
        (let [content (slurp file-path)
              updated-content (-> content
                                (str/replace #"<revision>.*?</revision>" "<revision>98</revision>")
                                (str/replace #"<time>.*?</time>" "<time>1056232311111</time>")
                                (str/replace #"<description/>" "<description>a description</description>"))]
          (when (not= content updated-content)
            (spit file-path updated-content)))))))

;; Directory comparison operations
(defn- parse-diff-options
  "Parse Unix diff command options"
  [args]
  (let [parsed (loop [remaining-args args
                      options []
                      exclude-patterns []
                      ignore-patterns []]
                 (cond
                   (empty? remaining-args)
                   {:options options
                    :exclude-patterns exclude-patterns
                    :ignore-patterns ignore-patterns
                    :files (take-last 2 args)}

                   (= "--exclude" (first remaining-args))
                   (recur (drop 2 remaining-args)
                          (conj options (first remaining-args))
                          (conj exclude-patterns (second remaining-args))
                          ignore-patterns)

                   (= "-I" (first remaining-args))
                   (let [pattern (second remaining-args)]
                     (recur (drop 2 remaining-args)
                            (conj options (first remaining-args))
                            exclude-patterns
                            (if (and pattern (not (str/blank? pattern)))
                              (conj ignore-patterns (re-pattern pattern))
                              ignore-patterns)))

                   (str/starts-with? (first remaining-args) "-")
                   (recur (rest remaining-args)
                          (conj options (first remaining-args))
                          exclude-patterns
                          ignore-patterns)

                   :else
                   {:options options
                    :exclude-patterns exclude-patterns
                    :ignore-patterns ignore-patterns
                    :files (vec remaining-args)}))

        options (:options parsed)
        file1 (first (:files parsed))
        file2 (second (:files parsed))]
    {:recursive? (some #(str/includes? % "r") options)
     :suppress-common? (some #(= % "--suppress-common-lines") options)
     :exclude-patterns (:exclude-patterns parsed)
     :ignore-patterns (:ignore-patterns parsed)
     :file1 file1
     :file2 file2}))

(defn- should-exclude-file?
  "Check if file should be excluded based on exclude patterns"
  [file-path exclude-patterns]
  (some #(str/includes? (.getName (io/file file-path)) %) exclude-patterns))

(defn- filter-content-by-ignore-patterns
  "Filter file content by ignore patterns (lines matching patterns are ignored)"
  [content ignore-patterns]
  (if (empty? ignore-patterns)
    content
    (let [lines (str/split-lines content)]
      (->> lines
           (remove (fn [line]
                     (some #(re-find % line) ignore-patterns)))
           (str/join "\n")))))

(defn- compare-files
  "Compare two files, applying ignore patterns"
  [file1 file2 ignore-patterns]
  (try
    (let [content1 (filter-content-by-ignore-patterns (slurp file1) ignore-patterns)
          content2 (filter-content-by-ignore-patterns (slurp file2) ignore-patterns)]
      (= content1 content2))
    (catch Exception e
      ;; If files can't be read, they're different
      false)))

(defn- get-relative-path
  "Get relative path of file within a base directory"
  [base-path file-path]
  (let [base-canonical (.getCanonicalPath (io/file base-path))
        file-canonical (.getCanonicalPath (io/file file-path))]
    (if (.startsWith file-canonical base-canonical)
      (.substring file-canonical (inc (.length base-canonical)))
      file-path)))

(defn- collect-files-recursive
  "Recursively collect all files in a directory"
  [dir-path exclude-patterns]
  (let [dir (io/file dir-path)]
    (when (.isDirectory ^File dir)
      (->> (file-seq dir)
           (filter #(.isFile ^File %))
           (map #(.getCanonicalPath ^File %))
           (remove #(should-exclude-file? % exclude-patterns))
           (map #(get-relative-path dir-path %))
           set))))

(defn diff-directories
  "Cross-platform directory/file comparison equivalent to Unix diff command"
  [& args]
  (let [{:keys [recursive? suppress-common? exclude-patterns ignore-patterns file1 file2]}
        (parse-diff-options args)]

    (cond
      ;; Both are files
      (and (file-exists? file1) (file-exists? file2)
           (not (directory? file1)) (not (directory? file2)))
      (if (compare-files file1 file2 ignore-patterns)
        ""
        (str "Files " file1 " and " file2 " differ"))

      ;; Both are directories
      (and (directory? file1) (directory? file2))
      (if recursive?
        (let [files1 (collect-files-recursive file1 exclude-patterns)
              files2 (collect-files-recursive file2 exclude-patterns)
              common-files (clojure.set/intersection files1 files2)
              only-in-1 (clojure.set/difference files1 files2)
              only-in-2 (clojure.set/difference files2 files1)
              different-files (filter #(not (compare-files
                                           (str file1 File/separator %)
                                           (str file2 File/separator %)
                                           ignore-patterns))
                                     common-files)]
          (let [differences (concat
                           (map #(str "Only in " file1 ": " %) only-in-1)
                           (map #(str "Only in " file2 ": " %) only-in-2)
                           (map #(str "Files " file1 File/separator % " and " file2 File/separator % " differ") different-files))]
            (if (and (empty? differences) suppress-common?)
              ""
              (str/join "\n" differences))))
        ;; Non-recursive directory comparison
        (let [listing1 (set (map #(.getName ^File %) (.listFiles ^File (io/file file1))))
              listing2 (set (map #(.getName ^File %) (.listFiles ^File (io/file file2))))]
          (if (= listing1 listing2)
            ""
            "Directory contents differ")))

      ;; One exists, one doesn't
      (not (file-exists? file1))
      (str "diff: " file1 ": No such file or directory")

      (not (file-exists? file2))
      (str "diff: " file2 ": No such file or directory")

      ;; One is file, one is directory
      :else
      (str "File " file1 " is a " (if (directory? file1) "directory" "regular file")
           " while file " file2 " is a " (if (directory? file2) "directory" "regular file")))))

;; HTTP client for health checks
(defn http-head-check
  "Simple HTTP HEAD check for server availability"
  [url]
  (try
    (with-open [conn (.openConnection (URL. url))]
      (.setRequestMethod conn "HEAD")
      (.setConnectTimeout conn 5000)
      (.setReadTimeout conn 5000)
      (= 200 (.getResponseCode conn)))
    (catch Exception _ false)))
