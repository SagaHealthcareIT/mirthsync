(ns mirthsync.apis
  (:require [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.zip :as zip]
            [mirthsync.actions :as mact]
            [mirthsync.cli :as cli]
            [mirthsync.files :as mf]
            [clojure.data.xml :as xml]
            [clojure.tools.logging :as log])
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
    (str (:target app-conf) (when (not= path File/separator) File/separator) path)))

(defn group-push-params
  "Build params for groups post-xml."
  [{:keys [server-groups force]}]
  {"channelGroups" (xml/indent-str (zip/node server-groups))
   "removedChannelGroupsIds" "<set/>"
   "override" (if force "true" "false")})

(defn codelib-push-params
  "Build params for codelib post-xml."
  [{:keys [server-codelibs force]}]
  {"libraries" (xml/indent-str (zip/node server-codelibs))
   "removedLibraryIds" "<set/>"
   "updatedCodeTemplates" "<list/>"
   "removedCodeTemplateIds" "<set/>"
   "override" (if force "true" "false")})

(defn override-params
  "Override if force is specified"
  [{:keys [force]}]
  {"override" (if force "true" "false")})

;FIXME: The logic is fine but the function could stand to have some
;attention to make it a little less ugly both here and in the apis
;that use it below.
(defn pre-node-action
  "Returns an updated app-conf with the current api element node added
  to the relevant api list/set in app conf. If node matching by id is
  found - it is removed before the new element is appended. set-key is
  the key used to find the root loc in app-conf and root-tag is the
  key for the root tag. node tag is the wrapper tag keyword."
  [set-key root-tag node-tag
   {:keys [el-loc] :as app-conf
    {:keys [find-id]} :api}]
  (let [set (set-key app-conf)
        found-id-loc (zx/xml1-> set
                                root-tag
                                node-tag
                                :id
                                (zx/text= (find-id el-loc)))

        set (if found-id-loc
              (zip/remove (zip/up found-id-loc))
              set)

        set (zip/append-child set (zip/node el-loc))]
    (assoc app-conf set-key set)))

(defn encode-path-chars
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
        (str/replace "/" "%2F")
        (str/replace "\\" "%5C"))
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

(defn file-path
  "Returns a function that, when given an app conf, returns a string file
  path for the current api with path appended."
  [path]
  (log/debugf "Received file-path: %s" path)
  (fn [{:keys [el-loc] :as app-conf
       {:keys [local-path find-name] :as api} :api}]
    (log/spyf :debug "Constructed file path: %s"
              (str (local-path app-conf)
                   (when (not= (local-path app-conf) File/separator) File/separator)
                   (safe-name (find-name el-loc))
                   path))))

(defn preprocess
  [{:as app-conf {:keys [preprocess]} :api}]
  (preprocess app-conf))

;; FIXME: dedupe codetemplate...path and channel.....path
(defn channel-file-path
  "Returns the channel xml path accounting for group nesting."
  [{:keys [server-groups el-loc] :as app-conf
    {:keys [local-path find-name find-id] :as api} :api}]
  (str (local-path app-conf)
       File/separator
       (if-let [group-name (safe-name
                            (let [id (find-id el-loc)]
                              (zx/xml1-> server-groups
                                         :channelGroup :channels :channel
                                         :id id
                                         zip/up zip/up zip/up
                                         :name zx/text)))]
         (str group-name File/separator))
       (safe-name (find-name el-loc))
       ".xml"))

;; FIXME: dedupe codetemplate...path and channel.....path
(defn codetemplate-file-path
  "Returns the codetemplate xml path accounting for lib nesting."
  [{:keys [server-codelibs el-loc] :as app-conf
    {:keys [local-path find-name find-id] :as api} :api}]
  (str (local-path app-conf)
       File/separator
       (if-let [lib-name (safe-name
                            (let [id (find-id el-loc)]
                              (zx/xml1-> server-codelibs
                                         :codeTemplateLibrary :codeTemplates :codeTemplate
                                         :id id
                                         zip/up zip/up zip/up
                                         :name zx/text)))]
         (str lib-name File/separator))
       (safe-name (find-name el-loc))
       ".xml"))

(defn unexpected-response
  [r]
  (log/warn "An unexpected response was received from the server...")
  (log/warnf "Status: %s, Phrase: %s" (:status r) (:reason-phrase r)))

(defn after-push
  "Returns a function that takes app-conf and the result of an action
  and calls all assertions with the action result in order (short
  circuiting and logging failures). Associates the result and returns
  app-conf."
  [& preds]
  (fn [app-conf result]
    (if (log/enabled? :trace)
      (log/trace result)
      (log/debugf "status: %s, phrase: %s, body: %s"
                  (:status result)
                  (:reason-phrase result)
                  (:body result)))

    (when-not ((apply every-pred preds) result)
      (unexpected-response result))
    (assoc app-conf :result result)))

(def null-204 (after-push #(= 204 (:status %))
                          #(nil? (:body %))))

(def true-200 (after-push #(= 200 (:status %))
                          ;; Handle xml, json, or plain text to
                          ;; accommodate different mirth
                          ;; versions. Version 9, for instance,
                          ;; returns json by default.
                          (fn [{body :body}]
                            (or (= body "<boolean>true</boolean>")
                                (= body "{\"boolean\":true}")
                                (= body "true")))))

(def revision-success
  (after-push
   (fn [{body :body}]
     (let [loc (-> body
                   xml/parse-str
                   zip/xml-zip)
           override (zx/xml1-> loc :overrideNeeded zip/node)
           success  (zx/xml1-> loc :librariesSuccess zip/node)]
       (and
        (= "false" (first (:content override)))
        (= "true" (first (:content success))))))))


(defn make-api
  "Builds an api from an api-map. rest-path, local-path and
  find-elements fns are required and the rest of the api uses sensible
  defaults if a value is not supplied."
  [api]
  (merge {
          :rest-path nil                ; required - server api path for GET/PUT
          :local-path nil               ; required - base dir for saving files
          :find-elements nil            ; required - find elements in the returned xml
          :file-path nil                ; required - build the xml file path

          :find-id by-id                ; find the current xml loc id
          :find-name by-name            ; find the current xml loc name
          :api-files (partial mf/xml-file-seq 1) ; find local api xml files for upload
          :post-path (constantly nil)   ; HTTP POST on upload path
          :push-params (constantly nil) ; params for HTTP PUT/POST
          :pre-node-action identity     ; transform app-conf before processing
          :after-push true-200          ; process result of item push
          :preprocess identity          ; preprocess app-conf before any other work
          :query-params {}              ; query-params for HTTP POST 
          }
         api))

(defn post-path
  "Takes an api and builds a _bulkUpdate from the rest-path"
  [{:keys [rest-path] :as api}]
  (str (rest-path api) "/_bulkUpdate"))

(def apis
  [(make-api
    {:rest-path (constantly "/server/configurationMap")
     :local-path (local-path File/separator)
     :find-elements #(zx/xml-> % :map)
     :find-id (constantly nil)
     :find-name (constantly nil)
     :file-path (file-path "ConfigurationMap.xml")
     :api-files (partial mf/only-named-xml-files-seq 1 "ConfigurationMap")
     :after-push null-204})

   (make-api
    {:rest-path (constantly "/server/globalScripts")
     :local-path (local-path "GlobalScripts")
     :find-elements #(zx/xml-> % :map)
     :find-id (constantly nil)
     :find-name (constantly nil)
     :file-path (file-path "globalScripts.xml")
     :after-push null-204})

   (make-api
    {:rest-path (constantly "/server/resources")
     :local-path (local-path File/separator)
     :find-elements #(zx/xml-> % :list)
     :find-id (constantly nil)
     :find-name (constantly nil)
     :file-path (file-path "Resources.xml")
     :api-files (partial mf/only-named-xml-files-seq 1 "Resources")
     :after-push null-204})

   (make-api
    {:rest-path (constantly "/codeTemplateLibraries")
     :local-path (local-path "CodeTemplates")
     :find-elements #(or (zx/xml-> % :list :codeTemplateLibrary) ; from server
                         (zx/xml-> % :codeTemplate)) ; from filesystem
     :file-path (file-path (str File/separator "index.xml"))
     :api-files (partial mf/only-named-xml-files-seq 2 "index")
     :post-path post-path
     :push-params codelib-push-params
     :preprocess (partial mact/fetch-and-pre-assoc :server-codelibs :list)
     :pre-node-action (partial pre-node-action :server-codelibs :list :codeTemplateLibrary)
     :after-push revision-success
     :query-params override-params})

   (make-api
    {:rest-path (constantly "/codeTemplates")
     :local-path (local-path "CodeTemplates")
     :find-elements #(zx/xml-> % :list :codeTemplate)
     :file-path codetemplate-file-path
     :api-files (partial mf/without-named-xml-files-seq 2 "index")
     :push-params override-params})

   (make-api
    {:rest-path (constantly "/channelgroups")
     :local-path (local-path "Channels")
     :find-elements #(or (zx/xml-> % :list :channelGroup) ; from server
                         (zx/xml-> % :channelGroup)) ; from filesystem
     :file-path (file-path (str File/separator "index.xml"))
     :api-files (partial mf/only-named-xml-files-seq 2 "index")
     :post-path post-path
     :push-params group-push-params
     :preprocess (partial mact/fetch-and-pre-assoc :server-groups :set)
     :pre-node-action (partial pre-node-action :server-groups :set :channelGroup)
     :query-params override-params})

   (make-api
    {:rest-path (constantly "/channels")
     :local-path (local-path "Channels")
     :find-elements #(zx/xml-> % :list :channel)
     :file-path channel-file-path
     :api-files (partial mf/without-named-xml-files-seq 2 "index")
     :push-params override-params})
   ])

(defn apis-action
  "Iterates through the apis calling action on app-conf. If an api
  updates app-conf the apis processed afterward use the updated
  app-conf."
  [app-conf apis action]
  (if-let [api (first apis)]
    (let [app-conf (-> app-conf
                       (assoc :api api)
                       action)]
      (log/trace "App config post-api:")
      (log/trace app-conf)
      (recur app-conf (rest apis) action))  

    app-conf))
