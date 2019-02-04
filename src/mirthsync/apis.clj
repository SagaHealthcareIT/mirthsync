(ns mirthsync.apis
  (:require [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.zip :as zip]
            [mirthsync.actions :as mact]
            [mirthsync.cli :as cli]
            [mirthsync.files :as mf]
            [clojure.data.xml :as xml])
  (:import java.io.File))

(defn zxffby
  "Returns a fn that returns first text at preds in the loc argument."
  [& preds]
  (fn [loc]
    (apply zx/xml1-> loc (conj (vec preds) zx/text))))

(def by-id (zxffby :id))
(def by-name (zxffby :name))

(defn local-path
  "Returns a fn that takes the app-conf and prepends the target
  directory to the supplied path."
  [path]
  (fn [app-conf]
    (str (:target app-conf) File/separator path)))

;; FIXME: Maybe there's a better way to handle post params that
;; results in less duplication. Also, the Mirth api can take override
;; parameters that are version related. This ties into the FIXME
;; comment in http_client.clj. We need to gracefully handle responses
;; and overrides.
(defn group-post-params
  "Build params for groups post-xml."
  [{:keys [server-groups]}]
  (vector
   ["channelGroups" (xml/indent-str (zip/node server-groups))]
   ["removedChannelGroupsIds" "<set/>"]))

(defn codelib-post-params
  "Build params for codelib post-xml."
  [{:keys [server-codelibs]}]
  (vector
   ["libraries" (xml/indent-str (zip/node server-codelibs))]
   ["removedLibraryIds" "<set/>"]
   ["updatedCodeTemplates" "<list/>"]
   ["removedCodeTemplateIds" "<set/>"]))

(defn groups-pre-node-action
  "Returns an updated app-conf with the current group added to
  server-groups."
  [{:keys [server-groups el-loc] :as app-conf
    {:keys [find-id]} :api}]
  (let [existing-id-loc (zx/xml1-> server-groups
                                   :set
                                   :channelGroup
                                   :id
                                   (zx/text= (find-id el-loc)))

        server-groups (if existing-id-loc
                        (zip/remove (zip/up existing-id-loc))
                        server-groups)

        server-groups (zip/append-child server-groups (zip/node el-loc))]
    (assoc app-conf :server-groups server-groups)))

;FIXME: dedupe with groups
(defn codelibs-pre-node-action
  "Returns an updated app-conf with the current codelib added to
  server-codelibs."
  [{:keys [server-codelibs el-loc] :as app-conf
    {:keys [find-id]} :api}]
  (let [existing-id-loc (zx/xml1-> server-codelibs
                                   :list
                                   :codeTemplateLibrary
                                   :id
                                   (zx/text= (find-id el-loc)))

        server-codelibs (if existing-id-loc
                        (zip/remove (zip/up existing-id-loc))
                        server-codelibs)

        server-codelibs (zip/append-child server-codelibs (zip/node el-loc))]
    (assoc app-conf :server-codelibs server-codelibs)))

(defn safe-name?
  "Takes and returns an unmodified string that should represent a
  creatable file that doesn't span paths. Prints a human readable error
  and then throws an exception if any problems are detected."
  [name]
  (if (and
       name
       (not= name (.getName (File. name))))
    (do
      (cli/out (str name " has invalid characters."))
      (throw (AssertionError. (str "Name does not appear to be safe"
                                    " for file creation: " name))))
    name))

(defn file-path
  "Returns a function that, when given an app conf, returns a string file
  path for the current api with path appended."
  [path]
  (fn [{:keys [el-loc] :as app-conf
       {:keys [local-path find-name] :as api} :api}]
    (str (local-path app-conf)
         File/separator
         (safe-name? (find-name el-loc))
         path)))

(defn preprocess
  [{:as app-conf {:keys [preprocess]} :api}]
  (preprocess app-conf))

(defn channel-file-path
  "Returns the channel xml path accounting for group nesting."
  [{:keys [server-groups el-loc] :as app-conf
    {:keys [local-path find-name find-id] :as api} :api}]
  (str (local-path app-conf)
       File/separator
       (if-let [group-name (safe-name?
                            (let [id (find-id el-loc)]
                              (zx/xml1-> server-groups 
                                         :channelGroup :channels :channel
                                         :id id
                                         zip/up zip/up zip/up
                                         :name zx/text)))]
         (str group-name File/separator))
       (find-name el-loc)
       ".xml"))

                                        ;FIXME: dedupe
(defn codetemplate-file-path
  "Returns the codetemplate xml path accounting for lib nesting."
  [{:keys [server-codelibs el-loc] :as app-conf
    {:keys [local-path find-name find-id] :as api} :api}]
  (str (local-path app-conf)
       File/separator
       (if-let [lib-name (safe-name?
                            (let [id (find-id el-loc)]
                              (zx/xml1-> server-codelibs
                                         :codeTemplateLibrary :codeTemplates :codeTemplate
                                         :id id
                                         zip/up zip/up zip/up
                                         :name zx/text)))]
         (str lib-name File/separator))
       (find-name el-loc)
       ".xml"))

(defn make-api
  "Builds an api from an api-map. rest-path, local-path and find-elements fns
  are required and the rest of the api uses sensible defaults if a
  value is not supplied."
  [api]
  (merge {:rest-path nil                ; required - server api path for GET/PUT
          :local-path nil               ; required - base dir for saving files
          :find-elements nil            ; required - find elements in the returned xml

          :find-id by-id                ; find the current xml loc id
          :find-name by-name            ; find the current xml loc name

          :file-path (file-path ".xml") ; build the xml file path
          :api-files (partial mf/xml-file-seq 1) ; find local api xml files for upload
          :post-path (constantly nil)   ; HTTP POST on upload path
          :post-params (constantly nil) ; params for HTTP POST
          :pre-node-action identity     ; transform app-conf before processing
          :preprocess identity}         ; preprocess app-conf before any other work
         api))

(defn post-path
  "Takes an api and builds a _bulkUpdate from the rest-path"
  [{:keys [rest-path] :as api}]
  (str (rest-path api) "/_bulkUpdate"))

(def apis
  [(make-api
    {:rest-path (constantly "/server/configurationMap")
     :local-path (local-path ".")
     :find-elements #(zx/xml-> % :map)
     :find-id (constantly nil)
     :find-name (constantly nil)
     :file-path (file-path "ConfigurationMap.xml")})

   (make-api
    {:rest-path (constantly "/server/globalScripts")
     :local-path (local-path "GlobalScripts")
     :find-elements #(zx/xml-> % :map)
     :find-id (constantly nil)
     :find-name (constantly nil)
     :file-path (file-path "globalScripts.xml")})
   
   (make-api
    {:rest-path (constantly "/codeTemplateLibraries")
     :local-path (local-path "CodeTemplates")
     :find-elements #(or (zx/xml-> % :list :codeTemplateLibrary) ; from server
                         (zx/xml-> % :codeTemplate)) ; from filesystem
     :file-path (file-path (str File/separator "index.xml"))
     :api-files (partial mf/only-index-files-seq 2)
     :post-path post-path
     :post-params codelib-post-params
     :preprocess (partial mact/fetch-and-pre-assoc :server-codelibs :list)
     :pre-node-action codelibs-pre-node-action})

   (make-api
    {:rest-path (constantly "/codeTemplates")
     :local-path (local-path "CodeTemplates")
     :find-elements #(zx/xml-> % :list :codeTemplate)
     :file-path codetemplate-file-path
     :api-files (partial mf/without-index-files-seq 2)})
   
   (make-api
    {:rest-path (constantly "/channelgroups")
     :local-path (local-path "Channels")
     :find-elements #(or (zx/xml-> % :list :channelGroup) ; from server
                         (zx/xml-> % :channelGroup)) ; from filesystem
     :file-path (file-path (str File/separator "index.xml"))
     :api-files (partial mf/only-index-files-seq 2)
     :post-path post-path
     :post-params group-post-params
     :preprocess (partial mact/fetch-and-pre-assoc :server-groups :set)
     :pre-node-action groups-pre-node-action})
   
   (make-api
    {:rest-path (constantly "/channels")
     :local-path (local-path "Channels")
     :find-elements #(zx/xml-> % :list :channel)
     :file-path channel-file-path
     :api-files (partial mf/without-index-files-seq 2)})])

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
