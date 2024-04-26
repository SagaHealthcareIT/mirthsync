(ns mirthsync.files
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log])
  (:import java.io.File))

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
