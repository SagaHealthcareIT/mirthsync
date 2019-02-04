(ns mirthsync.xml
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [mirthsync.cli :as cli])
  (:import java.io.File))

(defn to-zip
  "Takes a string of xml and returns an xml zipper"
  [x]
  (zip/xml-zip (xml/parse-str x)))

(defn serialize-node
  "Take an xml location and write to the filesystem with a meaningful
  name and path. If the file exists it is not overwritten unless the
  -f option is set. Returns app-conf."
  [{:keys [el-loc] :as app-conf
    {:keys [file-path]} :api}]

  (let [xml-str (xml/indent-str (zip/node el-loc))
        fpath (file-path app-conf)]
    (if (and (.exists (io/file fpath))
             (not (:force app-conf)))
      (cli/out (str "File at " fpath " already exists and the "
                       "force (-f) option was not specified. Refusing "
                       "to overwrite the file."))
      (do (io/make-parents fpath)
          (spit fpath xml-str)))
    app-conf))
