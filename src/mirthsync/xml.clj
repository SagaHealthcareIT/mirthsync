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
  -f option is set. Returns nil."
  [{:keys [target channel-groups] :as app-conf}
   {:keys [local-path find-name find-elements find-id append-name path] :as api}
   loc]

  (let [id (find-id loc)
        extra-path (when (and
                          channel-groups
                          (= "channels" (path api))
                          (channel-groups id))
                     (str (first (channel-groups id))
                          File/separator))
        name (str (find-name loc) (append-name api))

        xml-str (xml/indent-str (zip/node loc))
        file-path (str target (File/separator)
                       (local-path api) (File/separator)
                       extra-path name ".xml")]
    (if (and (.exists (io/file file-path))
             (not (:force app-conf)))
      (cli/output (str "File at " file-path " already exists and the "
                       "force (-f) option was not specified. Refusing "
                       "to overwrite the file."))
      (do (io/make-parents file-path)
          (spit file-path xml-str)))
    nil))
