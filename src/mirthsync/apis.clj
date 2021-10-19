(ns mirthsync.apis
  (:require [clojure.data.xml :as cdx]
            [clojure.data.zip.xml :as cdzx]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [clojure.zip :as cz]
            [mirthsync.actions :as ma]
            [mirthsync.files :as mf]
            [mirthsync.interfaces :as mi])
  (:import java.io.File))

(defn- api-element-id
  "Return element ID text"
  [api-loc]
  (cdzx/xml1-> api-loc :id cdzx/text))

(defn- api-element-name
  "Return element name tag text"
  [api-loc]
  (cdzx/xml1-> api-loc :name cdzx/text))

(defn- local-path-str
  "Prepends the target directory to the supplied path."
  [path target]
  (str target (when (not= path File/separator) File/separator) path))

(defn- override-params
  "Override if force is specified"
  [force]
  {"override" (if force "true" "false")})

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

(defn- encode-path-chars
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
        (cs/replace "/" "%2F")
        (cs/replace "\\" "%5C"))
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

(defn- file-path
  "Returns a string file path for the current api with path appended."
  [path {:keys [api el-loc] :as app-conf}]
  (log/debugf "Received file-path: %s" path)
  (log/spyf :debug "Constructed file path: %s"
              (str (mi/local-path api (:target app-conf))
                   (when-not (.endsWith ^String (mi/local-path api (:target app-conf)) File/separator) File/separator)
                   (safe-name (mi/find-name api el-loc))
                   path)))

(defn preprocess-api
  [{:as app-conf {:as api} :api}]
  (mi/preprocess api app-conf))

(defn nested-file-path
  "Returns the nested xml file path for the provided api."
  [group-xml-zip selectors target-dir el-loc api]
  (str (mi/local-path api target-dir) File/separator
       (when-let [lib-name (safe-name
                            (let [id (mi/find-id api el-loc)]
                              (apply cdzx/xml1->
                                     group-xml-zip
                                     (flatten [selectors
                                               :id id
                                               (repeat (count selectors) cz/up)
                                               :name cdzx/text]))))]
         (str lib-name File/separator))
       (safe-name (mi/find-name api el-loc))
       ".xml"))

(defn- alert-file-path
  "Returns the alert xml path."
  [{:keys [api el-loc] :as app-conf}]
  (str (mi/local-path api (:target app-conf))
       File/separator
       (safe-name (mi/find-name api el-loc))
       ".xml"))

(defn- unexpected-response
  [r]
  (log/warn "An unexpected response was received from the server...")
  (log/warnf "Status: %s, Phrase: %s" (:status r) (:reason-phrase r)))

(defn- check-results
  "Ensures the result satisfies the predicates. If not, an warning is logged."
  [result & preds]
  (log/trace result)
  (log/debugf "status: %s, phrase: %s, body: %s"
              (:status result)
              (:reason-phrase result)
              (:body result))

  (when-not ((apply every-pred preds) result)
    (unexpected-response result)))

(defn- null-204
  "Result needs a 204 status and a nil body"
  [result]
  (check-results result #(= 204 (:status %)) #(nil? (:body %))))

(defn- true-200
  "Result needs a 200 status and a 'truthy' body"
  [result]
  (check-results result
   #(= 200 (:status %))
   ;; Handle xml, json, or plain cdzx/text to
   ;; accommodate different mirth
   ;; versions. Version 9, for instance,
   ;; returns json by default.
   (fn [{body :body}]
     (or (= body "<boolean>true</boolean>")
         (= body "{\"boolean\":true}")
         (= body "true")))))

(defn- revision-success
  "Parses the result body to determine success and whether an override (version
  issue) is needed"
  [result]
  (check-results result
   (fn [{body :body}]
     (let [loc (-> body
                   cdx/parse-str
                   cz/xml-zip)
           override (cdzx/xml1-> loc :overrideNeeded cz/node)
           success  (cdzx/xml1-> loc :librariesSuccess cz/node)]
       (and
        (= "false" (first (:content override)))
        (= "true" (first (:content success))))))))

(defn pre-node-action [keyword app-conf]
  (assoc app-conf
         keyword
         (add-update-child (keyword app-conf) (:el-loc app-conf))))

(defn- post-path
  "Takes an api and builds a _bulkUpdate from the rest-path"
  [api]
  (str (mi/rest-path api) "/_bulkUpdate"))

(defn iterate-apis
  "Iterates through the apis calling action on app-conf. If an api
  updates app-conf the apis processed afterward use the updated
  app-conf."
  [app-conf apis action]
  (if-let [api (first apis)]
    (recur (log/spyf "App config post-api: %s" (action (assoc app-conf :api api)))
           (rest apis)
           action)
    app-conf))

(def apis
  [:configuration-map
   :global-scripts
   :resources
   :code-template-libraries
   :code-templates
   :channel-groups
   :channels
   :alerts])

(defmethod mi/find-name :default [_ api-loc] (api-element-name api-loc))
(defmethod mi/find-name :configuration-map [_ _] nil)
(defmethod mi/find-name :global-scripts [_ _] nil)
(defmethod mi/find-name :resources [_ _] nil)

(defmethod mi/query-params :default [_ _] nil)
(defmethod mi/query-params :code-template-libraries [_ app-conf] (override-params (:force app-conf)))
(defmethod mi/query-params :channel-groups [_ app-conf] (override-params (:force app-conf)))

(defmethod mi/push-params :default [_ _] nil)
(defmethod mi/push-params :code-template-libraries [_ app-conf]
    {"libraries" (cdx/indent-str (cz/node (:server-codelibs app-conf)))
     "removedLibraryIds" "<set/>"
     "updatedCodeTemplates" "<list/>"
     "removedCodeTemplateIds" "<set/>"
     "override" (if (:force app-conf) "true" "false")})
(defmethod mi/push-params :code-templates [_ app-conf] (override-params (:force app-conf)))
(defmethod mi/push-params :channel-groups [_ app-conf]
  {"channelGroups" (cdx/indent-str (cz/node (:server-groups app-conf)))
   "removedChannelGroupsIds" "<set/>"
   "override" (if (:force app-conf) "true" "false")})
(defmethod mi/push-params :channels [_ app-conf] (override-params (:force app-conf)))

(defmethod mi/preprocess :default [_ app-conf] app-conf)
(defmethod mi/preprocess :code-template-libraries [_ app-conf]
  (ma/fetch-and-pre-assoc :server-codelibs :list app-conf))
(defmethod mi/preprocess :channel-groups [_ app-conf]
  (ma/fetch-and-pre-assoc :server-groups :set app-conf))

;; (defmethod mi/after-push :default [_ result] (true-200 result))
(defmethod mi/after-push :configuration-map [_ result] (null-204 result))
(defmethod mi/after-push :global-scripts [_ result] (null-204 result))
(defmethod mi/after-push :resources [_ result] (null-204 result))
(defmethod mi/after-push :code-template-libraries [_ result] (revision-success result))
(defmethod mi/after-push :code-templates [_ result] (true-200 result))
(defmethod mi/after-push :channel-groups [_ result] (true-200 result))
(defmethod mi/after-push :channels [_ result] (true-200 result))
(defmethod mi/after-push :alerts [_ result] (null-204 result))

(defmethod mi/pre-node-action :default [_ app-conf] app-conf)
(defmethod mi/pre-node-action :code-template-libraries [_ app-conf]
  (pre-node-action :server-codelibs app-conf))
(defmethod mi/pre-node-action :channel-groups [_ app-conf]
  (pre-node-action :server-groups app-conf))

(defmethod mi/post-path :default [_] nil)
(defmethod mi/post-path :code-template-libraries [api] (post-path api))
(defmethod mi/post-path :channel-groups [api] (post-path api))

(defmethod mi/api-files :default [_  directory]
  (mf/xml-file-seq 1 directory))
(defmethod mi/api-files :configuration-map [_  directory]
  (mf/only-named-xml-files-seq 1 "ConfigurationMap" directory))
(defmethod mi/api-files :resources [_  directory]
  (mf/only-named-xml-files-seq 1 "Resources" directory))
(defmethod mi/api-files :code-template-libraries [_  directory]
  (mf/only-named-xml-files-seq 2 "index" directory))
(defmethod mi/api-files :code-templates     [_  directory]
  (mf/without-named-xml-files-seq 2 "index" directory))
(defmethod mi/api-files :channel-groups [_  directory]
  (mf/only-named-xml-files-seq 2 "index" directory))
(defmethod mi/api-files :channels [_  directory]
  (mf/without-named-xml-files-seq 2 "index" directory))

(defmethod mi/find-id :default [_  el-loc]
  (api-element-id el-loc))
(defmethod mi/find-id :configuration-map [_ _] nil)
(defmethod mi/find-id :global-scripts [_ _] nil)
(defmethod mi/find-id :resources [_ _] nil)

(defmethod mi/find-elements :configuration-map [_ el-loc] (cdzx/xml-> el-loc :map))
(defmethod mi/find-elements :global-scripts [_ el-loc] (cdzx/xml-> el-loc :map))
(defmethod mi/find-elements :resources [_ el-loc] (cdzx/xml-> el-loc :list))
(defmethod mi/find-elements :code-template-libraries [_ el-loc]
  (or (cdzx/xml-> el-loc :list :codeTemplateLibrary) ; from server
      (cdzx/xml-> el-loc :codeTemplate))) ; from filesystem
(defmethod mi/find-elements :code-templates [_ el-loc] (cdzx/xml-> el-loc :list :codeTemplate))
(defmethod mi/find-elements :channel-groups [_ el-loc]
  (or (cdzx/xml-> el-loc :list :channelGroup) ; from server
      (cdzx/xml-> el-loc :channelGroup))) ; from filesystem
(defmethod mi/find-elements :channels [_ el-loc] (cdzx/xml-> el-loc :list :channel))
(defmethod mi/find-elements :alerts [_ el-loc] (cdzx/xml-> el-loc :list :alertModel))

(defmethod mi/file-path :configuration-map [_ app-conf]
  (file-path "ConfigurationMap.xml" app-conf))
(defmethod mi/file-path :global-scripts [_ app-conf]
  (file-path "globalScripts.xml" app-conf))
(defmethod mi/file-path :resources [_ app-conf]
  (file-path "Resources.xml" app-conf))
(defmethod mi/file-path :code-template-libraries [_ app-conf]
  (file-path (str File/separator "index.xml") app-conf))
(defmethod mi/file-path :code-templates [_ app-conf]
  (nested-file-path (:server-codelibs app-conf)
                    [:codeTemplateLibrary :codeTemplates :codeTemplate]
                    (:target app-conf)
                    (:el-loc app-conf)
                    (:api app-conf)))
(defmethod mi/file-path :channel-groups [_ app-conf]
  (file-path (str File/separator "index.xml") app-conf))
(defmethod mi/file-path :channels [_ app-conf]
  (nested-file-path (:server-groups app-conf)
                    [:channelGroup :channels :channel]
                    (:target app-conf)
                    (:el-loc app-conf)
                    (:api app-conf)))
(defmethod mi/file-path :alerts [_ app-conf]
  (alert-file-path app-conf))

(defmethod mi/local-path :configuration-map [_ target]
  (local-path-str File/separator target))
(defmethod mi/local-path :global-scripts [_ target]
  (local-path-str "GlobalScripts" target))
(defmethod mi/local-path :resources [_ target]
  (local-path-str File/separator target))
(defmethod mi/local-path :code-template-libraries [_ target]
  (local-path-str "CodeTemplates" target))
(defmethod mi/local-path :code-templates [_ target]
  (local-path-str "CodeTemplates" target))
(defmethod mi/local-path :channel-groups [_ target]
  (local-path-str "Channels" target))
(defmethod mi/local-path :channels [_ target]
  (local-path-str "Channels" target))
(defmethod mi/local-path :alerts [_ target]
  (local-path-str "Alerts" target))

(defmethod mi/rest-path :configuration-map [_] "/server/configurationMap")
(defmethod mi/rest-path :global-scripts [_] "/server/globalScripts")
(defmethod mi/rest-path :resources [_] "/server/resources")
(defmethod mi/rest-path :code-template-libraries [_] "/codeTemplateLibraries")
(defmethod mi/rest-path :code-templates [_] "/codeTemplates")
(defmethod mi/rest-path :channel-groups [_] "/channelgroups")
(defmethod mi/rest-path :channels [_] "/channels")
(defmethod mi/rest-path :alerts [_] "/alerts")
