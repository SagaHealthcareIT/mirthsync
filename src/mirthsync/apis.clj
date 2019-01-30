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

(defn dir-files
  "Sequence of java.io.File within specified directory matching all
  predicates. Each predicate receives a file as a parameter."
  [dir preds]
  (filter
   (fn [f] (every? #(% f) preds))
   (.listFiles (io/file dir))))

(defn xml-file?
  "True if the file ends with xml."
  [file]
  (and
   (not (.isDirectory file))
   #(-> file
        (.getName)
        str/lower-case
        (str/ends-with? ".xml"))))

(defn xml-files
  "Sequence of java.io.File in the target dir filtered by preds."
  [dir]
  (dir-files dir [xml-file?]))

(defn channel-xml-files
  "Sequence of channel xml files contained within directories in the
  target dir and 1st level subdirectories."
  [dir]
  (let [dirs (dir-files dir [#(.isDirectory %)])
        dirs (conj dirs (io/file dir))]
    (mapcat (fn [dir]
              (dir-files dir [xml-file?
                              #(not= "Group.xml" (.getName %))]))
            dirs)))

(defn group-xml-files
  "Sequence of group.xml files contained within directories in the
  target dir."
  [dir]
  (let [dirs (dir-files dir [#(.isDirectory %)])]
    (mapcat (fn [dir]
              (dir-files dir [xml-file?
                              #(= "Group.xml" (.getName %))]))
            dirs)))

(defn groups-handler
  "Takes the application config and an element loc. Adds a map of
  channel ids (keys) group names (values) to the app config and
  returns the new config."
  [{:keys [el-loc] 
    {:keys [find-id find-name] :as api} :api
    :as app-conf}]
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

(defn local-path
  "Returns a fn that takes the app-conf and prepends the target
  directory to the supplied path."
  [path]
  (fn [app-conf]
    (str (:target app-conf) path)))

(def apis
  [{:path (constantly "/server/configurationMap")
    :local-path (local-path "/.")
    :find-elements #(zx/xml-> % :map)
    :find-id (constantly nil)
    :find-name (constantly "ConfigurationMap")
    :append-name (constantly nil)
    :api-files xml-files
    :transformer default-transformer}

   {:path (constantly "/codeTemplates")
    :local-path (local-path "/CodeTemplates")
    :find-elements #(zx/xml-> % :list :codeTemplate)
    :find-id (zxffby :id)
    :find-name (zxffby :name)
    :append-name (constantly nil)
    :api-files xml-files
    :transformer default-transformer}

   {:path (constantly "/server/globalScripts")
    :local-path (local-path "/GlobalScripts")
    :find-elements #(zx/xml-> % :map)
    :find-id (constantly nil)
    :find-name (constantly "globalScripts")
    :append-name (constantly nil)
    :api-files xml-files
    :transformer default-transformer}
   
   {:path (constantly "/channels")
    :local-path (local-path "/Channels")
    :find-elements #(zx/xml-> % :list :channel)
    :find-id (zxffby :id)
    :find-name (zxffby :name)
    :append-name (constantly nil)
    :api-files channel-xml-files
    :transformer default-transformer}

   {:path (constantly "/channelgroups")
    :local-path (local-path "/Channels")
    :find-elements #(or (zx/xml-> % :list :channelGroup) ; from server
                        (zx/xml-> % :channelGroup)) ; from filesystem
    :find-id (zxffby :id)
    :find-name (zxffby :name)
    :append-name (constantly (str File/separator "Group"))
    :api-files group-xml-files
    :transformer groups-handler}])

(defn apis-action
  "Iterates through the apis calling action on app-conf. If an api
  updates app-conf the apis processed afterward use the updated
  app-conf."
  [app-conf apis action]
  (if-let [api (first apis)]
    (let [app-conf (-> app-conf
                       (assoc :api api)
                       action)]
      (cli/out 2 "App config post-api: ")
      (cli/out 2 app-conf)
      (recur app-conf (rest apis) action))  

    app-conf))
