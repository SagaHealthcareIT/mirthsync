(ns mirthsync.xml
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.zip :as cz]
            [clojure.data.zip.xml :as cdzx]
            [mirthsync.logging :as log]
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

(def ^:private unordered-paths
  "Tag paths of elements whose children Mirth holds in a HashSet or HashMap.
  Their serialized order depends on hashing (for enums it changes on every
  server restart), so they are sorted to keep pulls stable. A path matches
  when it equals the tail of an element's tag path, which starts with ::root
  so that a path can be anchored to the root element. Lists, where
  order is meaningful (destinationConnectors, filter and transformer elements,
  metaDataColumns, resources, ...), and LinkedHashMaps (resourceIds, headers)
  must never be added here."
  [;; The standalone configuration map and global scripts files
   [::root :map]
   ;; CodeTemplateContextSet (enum set)
   [:contextSet :delegate]
   ;; CodeTemplateLibrary
   [:codeTemplateLibrary :enabledChannelIds]
   [:codeTemplateLibrary :disabledChannelIds]
   ;; ChannelExportData. channelTags is a List, but is built from the
   ;; server's tag Set, so it has no meaningful order either.
   [:exportData :dependentIds]
   [:exportData :dependencyIds]
   [:exportData :channelTags]
   ;; ChannelTag
   [:channelTag :channelIds]
   ;; ConnectorProperties
   [:sourceConnector :properties :pluginProperties]
   [:connector :properties :pluginProperties]
   ;; AttachmentHandlerProperties
   [:attachmentProperties :properties]
   ;; AlertChannels, AlertConnectors and DefaultTrigger
   [:alertChannels :enabledChannels]
   [:alertChannels :disabledChannels]
   [:alertChannels :partialChannels]
   [:partialChannels :entry :alertConnectors :enabledConnectors]
   [:partialChannels :entry :alertConnectors :disabledConnectors]
   [:trigger :errorEventTypes]
   ;; ServerConfiguration
   [:serverConfiguration :channelTags]
   [:serverConfiguration :channelDependencies]
   [:serverConfiguration :globalScripts]
   [:serverConfiguration :configurationMap]
   [:serverConfiguration :pluginProperties]
   [:serverConfiguration :pluginProperties :entry :properties]])

(defn- unordered-path?
  [path]
  (some (fn [suffix]
          (let [offset (- (count path) (count suffix))]
            (and (not (neg? offset))
                 (= suffix (subvec path offset)))))
        unordered-paths))

(defn- element?
  [node]
  (instance? clojure.data.xml.Element node))

(defn sort-unordered
  "Returns the xml element with the children of every element listed in
  unordered-paths sorted by their serialized xml. Children are sorted
  bottom-up so nested sets are already canonical when compared."
  ([node]
   (sort-unordered [::root] node))
  ([ancestors node]
   (if (element? node)
     (let [path (conj ancestors (:tag node))
           content (map (partial sort-unordered path) (:content node))]
       (assoc node :content (if (and (unordered-path? path)
                                     (every? element? content))
                              ;; emit each child once rather than per comparison
                              (map second (sort-by first (map (juxt xml/emit-str identity) content)))
                              content)))
     node)))

(defn serialize-node
  "Take an xml location and write to the filesystem with a meaningful
  name and path. If the file exists it is not overwritten unless the
  -f option is set. Unordered collections are sorted first unless
  --no-sort-unordered is set. Returns app-conf."
  [{:keys [api el-loc restrict-to-path target disk-mode] :as app-conf}]

  (when-not (mi/should-skip? api el-loc app-conf)
    (loop [files-data (mi/deconstruct-node app-conf
                                           (mi/file-path api app-conf)
                                           (if (:sort-unordered app-conf)
                                             (cz/xml-zip (sort-unordered (cz/node el-loc)))
                                             el-loc))
           file-data (first files-data)]
      (when (seq files-data)
        (let [xml-str (second file-data)
              fpath (first file-data)
              required-prefix (str target File/separator restrict-to-path)]
          (if (.startsWith ^String fpath required-prefix)
            (do
              (when (seq restrict-to-path)
                (log/debugf "Found a match: %s" fpath))

              ;; never overwrite unless force is specified or we're updating
              ;; index files in disk mode 1
              (if (and (.exists (io/file fpath))
                       (not (app-conf :force))
                       (not (and (= "groups" disk-mode)
                                 (.endsWith fpath (str File/separator "index.xml")))))
                (log/warn (str "File at " fpath " already exists and the "
                               "force (-f) option was not specified. Refusing "
                               "to overwrite the file."))
                (do (io/make-parents fpath)
                    (log/debugf "\tFile: %s" fpath)
                    (spit fpath xml-str))))
            ;; else
            (log/debugf "Filtering pull of '%s' since it does not start with our required prefix: %s" fpath required-prefix)))
        (recur (rest files-data) (second files-data)))))
  app-conf)
