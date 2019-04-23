(ns mirthsync.files
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import java.io.File))

(defn filtered-file-seq
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

(defn file?
  "True if not a directory."
  [^File f]
  (not (.isDirectory f)))

(defn ends-xml?
  "True if the file ends with .xml."
  [^File f] 
  (-> f
      (.getName)
      (str/lower-case)
      (str/ends-with? ".xml")))

(defn index-xml?
  "True if the file is index.xml"
  [^File f]
  (-> f
      (.getName)
      (str/lower-case)
      (= "index.xml")))

(def not-index-xml?
  "Complement of index-xml?"
  (complement index-xml?))

(defn xml-file-seq
  "Xml file sequence at dir up to depth."
  [depth dir]
  (filtered-file-seq depth [file? ends-xml?] dir))

(defn without-index-files-seq
  "Sequence of non-index.xml files contained within directories in the
  target dir and subdirectories up to depth."
  [depth dir]
  (filtered-file-seq depth [file? ends-xml? not-index-xml?] dir))

(defn only-index-files-seq
  "Sequence of index.xml files contained within directories in the
  target dir and subdirectories up to depth."
  [depth dir]
  (filtered-file-seq depth [file? ends-xml? index-xml?] dir))
