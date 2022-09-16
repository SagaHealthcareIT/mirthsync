(ns mirthsync.xml
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as cz]
            [clojure.data.zip.xml :as cdzx]
            [clojure.tools.logging :as log]
            [mirthsync.interfaces :as mi])
  (:import java.io.File))

(defn to-zip
  "Takes a string of xml and returns an xml zipper"
  [x]
  (cz/xml-zip (xml/parse-str x)))

(defn add-update-child
  "Adds or updates (by ID) an child element within the supplied root. Assumes that
  the typical mirth xml structure is present with a root collection element
  and an ID element within the child."
  [root-loc child-loc]
  (let [collection-keyword (:tag (cz/node root-loc))
        node-element-keyword (:tag (cz/node child-loc))
        id (cdzx/xml1-> child-loc :id cdzx/text)
        found-id-loc (cdzx/xml1-> root-loc
                                collection-keyword
                                node-element-keyword
                                :id
                                (cdzx/text= id))]
    (if found-id-loc
      (cz/up (cz/replace (cz/up found-id-loc) (cz/node child-loc)))
      (cz/append-child root-loc (cz/node child-loc)))))

(defn serialize-node
  "Take an xml location and write to the filesystem with a meaningful
  name and path. If the file exists it is not overwritten unless the
  -f option is set. Returns app-conf."
  [{:keys [api el-loc restrict-to-path target] :as app-conf}]

  (when-not (mi/should-skip? api el-loc app-conf)
    (loop [files-data (mi/deconstruct-node app-conf (mi/file-path api app-conf) el-loc)
           file-data (first files-data)]
      (when (seq files-data)
        (let [xml-str (second file-data)
              fpath (first file-data)
              required-prefix (str target File/separator restrict-to-path)]
          (if (.startsWith ^String fpath required-prefix)
            (do
              (when (seq restrict-to-path)
                (log/infof "Found a match: %s" fpath))

              (if (and (.exists (io/file fpath))
                       (not (app-conf :force)))
                (log/warn (str "File at " fpath " already exists and the "
                               "force (-f) option was not specified. Refusing "
                               "to overwrite the file."))
                (do (io/make-parents fpath)
                    (log/infof "\tFile: %s" fpath)
                    (spit fpath xml-str))))
            ;; else
            (log/infof "Filtering pull of '%s' since it does not start with our required prefix: %s" fpath required-prefix)))
        (recur (rest files-data) (second files-data)))))
  app-conf)
