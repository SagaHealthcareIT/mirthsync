(ns mirthsync.apis
  (:require [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.zip :as zip]
            [mirthsync.cli :as cli])
  (:import java.io.File))

(defn zxffby
  "Returns a fn that returns first text at preds in the loc argument."
  [& preds]
  (fn [loc]
    (apply zx/xml1-> loc (conj (vec preds) zx/text))))

(defn default-transformer
  "Takes and returns app-conf without transforming. Extra params are
  ignored."
  [app-conf & _]
  app-conf)

(defn xml-files
  "Sequence of xml files in the target dir and base path."
  [target local-path]
  (filter
   (fn [file]
     (and (not (.isDirectory file))
          (-> file
              (.getName)
              str/lower-case
              (str/ends-with? ".xml"))))
   (.listFiles (io/file (str target (File/separator) local-path)))))

(defn group-xml-files
  "Sequence of group.xml files nested within the target dir and base
  path."
  [target local-path]
  (map #(io/file % "Group.xml")
       (filter #(.isDirectory %)
               (-> target
                   (str (File/separator) local-path)
                   io/file
                   .listFiles))))

(defn groups-handler
  "Takes the application config and an element loc. Adds a map of
  channel ids (keys) group names (values) to the app config and
  returns the new config."
  [app-conf {:keys [find-id find-name] :as api} el-loc]
  (let [id (find-id el-loc)
        group-name (find-name el-loc)
        channel-ids (zx/xml-> el-loc :channels :channel :id zip/down zip/node)
        channel-groups (or (:channel-groups app-conf) {})]
    (assoc
     app-conf
     :channel-groups
     (into
      channel-groups
      (map #(sorted-map % [group-name id]) channel-ids)))))

(def apis
  [{:path (constantly "server/configurationMap")
    :local-path (constantly ".")
    :find-elements #(zx/xml-> % :map)
    :find-id (constantly nil)
    :find-name (constantly "ConfigurationMap")
    :append-name (constantly nil)
    :api-files xml-files
    :transformer default-transformer
    :apis nil}

   {:path (constantly "codeTemplates")
    :local-path (constantly "CodeTemplates")
    :find-elements #(zx/xml-> % :list :codeTemplate)
    :find-id (zxffby :id)
    :find-name (zxffby :name)
    :append-name (constantly nil)
    :api-files xml-files
    :transformer default-transformer
    :apis nil}

   {:path (constantly "server/globalScripts")
    :local-path (constantly "GlobalScripts")
    :find-elements #(zx/xml-> % :map)
    :find-id (constantly nil)
    :find-name (constantly "globalScripts")
    :append-name (constantly nil)
    :api-files xml-files
    :transformer default-transformer
    :apis nil}

   {:path (constantly "channelgroups")
    :local-path (constantly "Channels")
    :find-elements #(zx/xml-> % :list :channelGroup)
    :find-id (zxffby :id)
    :find-name (zxffby :name)
    :append-name (constantly (str File/separator "Group"))
    :api-files group-xml-files
    :transformer groups-handler
    ;;:transformer (fn [app-conf api loc] (assoc app-conf :channel-groups loc))
    :apis [{:path (constantly "channels")
            :local-path (constantly ".")
            :find-elements #(zx/xml-> % :list :channel)
            :find-id (zxffby :id)
            :find-name (zxffby :name)
            :append-name (constantly nil)
            :api-files xml-files
            :transformer default-transformer
            :apis nil}]}
   ])

(defn apis-action
  "Recursively iterate apis and perform the action with the
  app-conf."
  [app-conf parent-api apis action]
  (doseq [api apis]
    (let [api (if parent-api
                (assoc api
                       :local-path
                       (constantly
                        (str
                         ((:local-path parent-api) parent-api)
                         File/separator
                         ((:local-path api) api))))
                api)
          app-conf (-> app-conf
                       (assoc :parent-api parent-api)
                       (assoc :api api)
                       action)]
      (when (seq (:apis api))
        (apis-action app-conf api (:apis api) action))
      (cli/out 2 "Current application config: ")
      (cli/out 2 app-conf))))
