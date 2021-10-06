(ns mirthsync.xml
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [mirthsync.cli :as cli]
            [clojure.tools.logging :as log])
  (:import java.io.File))

(defn to-zip
  "Takes a string of xml and returns an xml zipper"
  [x]
  (zip/xml-zip (xml/parse-str x)))

(defn serialize-node
  "Take an xml location and write to the filesystem with a meaningful
  name and path. If the file exists it is not overwritten unless the
  -f option is set. Returns app-conf."
  [{:keys [el-loc restrict-to-path target] :as app-conf
    {:keys [file-path]} :api}]

  (let [xml-str (xml/indent-str (zip/node el-loc))
        fpath (file-path app-conf)
        required-prefix (str target File/separator restrict-to-path)]
    (if (.startsWith ^String fpath required-prefix)
      (do
        (log/infof "Found a match: %s" fpath)

        (if (and (.exists (io/file fpath))
                 (not (:force app-conf)))
          (log/warn (str "File at " fpath " already exists and the "
                         "force (-f) option was not specified. Refusing "
                         "to overwrite the file."))
          (do (io/make-parents fpath)
              (log/infof "\tFile: %s" fpath)
              (spit fpath xml-str))))

      (log/infof "filtering pull of '%s' since it does not start with our required prefix: %s" fpath required-prefix))
    app-conf))
