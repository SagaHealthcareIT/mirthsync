(ns mirthsync.apis
  (:require [clojure.data.xml :as cdx]
            [clojure.data.zip :as cdz]
            [clojure.data.zip.xml :as cdzx]
            [clojure.tools.logging :as log]
            [clojure.zip :as cz]
            [mirthsync.actions :as ma]
            [mirthsync.files :as mf]
            [mirthsync.interfaces :as mi]
            [mirthsync.xml :as mx]
            [clojure.string :as cs]
            [mirthsync.http-client :as mhttp])
  (:use [slingshot.slingshot :only [try+]])
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

(defn- file-path
  "Returns a string file path for the current api with path appended."
  [path {:keys [api el-loc] :as app-conf}]
  (log/debugf "Received file-path: %s" path)
  (log/spyf :debug "Constructed file path: %s"
            (let [local-path (mi/local-path api (:target app-conf))]
              (str local-path
                   (when-not (.endsWith ^String local-path File/separator) File/separator)
                   (mf/safe-name (mi/find-name api el-loc))
                   path))))

(defn preprocess-api
  [{:as app-conf {:as api} :api}]
  (mi/preprocess api app-conf))

(defn nested-file-path
  "Returns the nested xml file path for the provided api. 'Nested' means
  that the api xml may be part of a library or group and the path
  should take that into account. In the case of disk-mode 'groups' the file
  path may point to an existing xml file representing the group or
  library."
  [group-xml-zip selectors {:keys [target el-loc api disk-mode]}]
  (let [id (mi/find-id api el-loc)
        lib-name (mf/safe-name
                  (apply cdzx/xml1->
                         group-xml-zip
                         (flatten [selectors
                                   :id id
                                   (repeat (count selectors) cz/up)
                                   :name cdzx/text])))]
    (str (mi/local-path api target) File/separator
         (if lib-name
           (str lib-name File/separator)
           (str "Default Group" File/separator))
         (if (and lib-name (= "groups" disk-mode)) ; point to group/lib index xml if mode groups
           "index"
           (mf/safe-name (mi/find-name api el-loc)))
         ".xml")))

(defn- alert-file-path
  "Returns the alert xml path."
  [{:keys [api el-loc] :as app-conf}]
  (str (mi/local-path api (:target app-conf))
       File/separator
       (mf/safe-name (mi/find-name api el-loc))
       ".xml"))

(defn- unexpected-response
  [r]
  (log/warn "An unexpected response was received from the server...")
  (log/warnf "Status: %s, Phrase: %s, Body: %s" (:status r) (:reason-phrase r) (:body r)))

(defn- check-results
  "Ensures the result satisfies the predicates. If not, a warning is logged."
  [result & preds]
  (log/trace result)
  (log/debugf "status: %s, phrase: %s, body: %s"
              (:status result)
              (:reason-phrase result)
              (:body result))

  (if ((apply every-pred preds) result)
    true
    (do (unexpected-response result)
        false)))

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
         (mx/add-update-child (keyword app-conf) (:el-loc app-conf))))

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

(defn apis [{:keys [disk-mode include-configuration-map] :as app-conf}]
  (filter #(cond
             (and (= :server-configuration %) (not= "backup" disk-mode)) false
             (and (not= :server-configuration %) (= "backup" disk-mode)) false
             (and (= :configuration-map %) (not include-configuration-map)) false
             :else true)

          [:server-configuration
           :configuration-map
           :global-scripts
           :resources
           :code-template-libraries
           :code-templates
           :channel-groups
           :channels
           :alerts]))

(defmethod mi/find-name :default [_ api-loc] (api-element-name api-loc))
(defmethod mi/find-name :configuration-map [_ _] nil)
(defmethod mi/find-name :global-scripts [_ _] nil)
(defmethod mi/find-name :resources [_ _] nil)
(defmethod mi/find-name :server-configuration [_ _] nil)

(defmethod mi/query-params :default [_ _] nil)
(defmethod mi/query-params :code-template-libraries [_ app-conf] (override-params (app-conf :force)))
(defmethod mi/query-params :code-templates [_ app-conf] (override-params (app-conf :force)))
(defmethod mi/query-params :channel-groups [_ app-conf] (override-params (app-conf :force)))
(defmethod mi/query-params :channels [_ app-conf] (override-params (app-conf :force)))

(defmethod mi/push-params :default [_ _] nil)
(defmethod mi/push-params :code-template-libraries [_ app-conf]
    {"libraries" (cdx/indent-str (cz/node (:server-codelibs app-conf)))
     "removedLibraryIds" "<set/>"
     "updatedCodeTemplates" "<list/>"
     "removedCodeTemplateIds" "<set/>"})

(defmethod mi/push-params :channel-groups [_ app-conf]
  {"channelGroups" (cdx/indent-str (cz/node (:server-groups app-conf)))
   "removedChannelGroupsIds" "<set/>"})

(defmethod mi/preprocess :default [_ app-conf] app-conf)
(defmethod mi/preprocess :code-template-libraries [_ app-conf]
  (ma/fetch-and-pre-assoc :server-codelibs :list app-conf))
(defmethod mi/preprocess :channel-groups [_ app-conf]
  (ma/fetch-and-pre-assoc :server-groups :set app-conf))

;; (defmethod mi/after-push :default [_ app-conf result] (true-200 result))
(defmethod mi/after-push :configuration-map [_ app-conf result] (null-204 result))
(defmethod mi/after-push :global-scripts [_ app-conf result] (null-204 result))
(defmethod mi/after-push :resources [_ app-conf result] (null-204 result))
(defmethod mi/after-push :code-template-libraries [_ app-conf result] (revision-success result))
(defmethod mi/after-push :code-templates [_ app-conf result] (true-200 result))
(defmethod mi/after-push :channel-groups [_ app-conf result] (true-200 result))
;; TODO - If the body of the result is false but it's a 200 http code it means
;; that there's a chance that it wasn't updated because the override parameter
;; needs to be set. The current error message is just a warning about an
;; unexpected result. This warning should be enhanced to include a sensible
;; message about what happened and possible solutions (Override param). This
;; applies to channels and some other entities as well.
(defmethod mi/after-push :channels [api app-conf result]
  (if (true-200 result)
    (do
      (when (:deploy app-conf)
        (try+
         (mhttp/post-xml
          app-conf
          "/channels/_deploy"
          (str "<set><string>" (mi/find-id api (:el-loc app-conf)) "</string></set>")
          {:returnErrors "true" :debug "false"}
          false)
         (catch Object {:keys [body]}
           (log/warn (str "There was an error deploying the channel.
" body)))))
      true)
    (do (log/error (str "Unable to save the channel."
                        (when-not (app-conf :force) " There may be remote changes or the remote version does not match the local version. If you want to push the local changes anyway you can use the \"-f\" flag to force an overwrite.")))
        false)))


(defmethod mi/after-push :alerts [_ app-conf result] (null-204 result))
(defmethod mi/after-push :server-configuration [_ app-conf result] (null-204 result))

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
(defmethod mi/api-files :server-configuration [_  directory]
  (mf/only-named-xml-files-seq 1 "FullBackup" directory))

(defmethod mi/enabled? :default [_ _]
  true)
(defmethod mi/enabled? :channels [_ el-loc]
  (not= (cdzx/xml1-> el-loc :exportData :metadata :enabled cdzx/text) "false"))

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
(defmethod mi/find-elements :server-configuration [_ el-loc] (cdzx/xml-> el-loc :serverConfiguration))

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
                    app-conf))
(defmethod mi/file-path :channel-groups [_ app-conf]
  (file-path (str File/separator "index.xml") app-conf))
(defmethod mi/file-path :channels [_ app-conf]
  (nested-file-path (:server-groups app-conf)
                    [:channelGroup :channels :channel]
                    app-conf))
(defmethod mi/file-path :alerts [_ app-conf]
  (alert-file-path app-conf))
(defmethod mi/file-path :server-configuration [_ app-conf]
  (file-path "FullBackup.xml" app-conf))

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
(defmethod mi/local-path :server-configuration [_ target]
  (local-path-str File/separator target))

(defmethod mi/rest-path :configuration-map [_] "/server/configurationMap")
(defmethod mi/rest-path :global-scripts [_] "/server/globalScripts")
(defmethod mi/rest-path :resources [_] "/server/resources")
(defmethod mi/rest-path :code-template-libraries [_] "/codeTemplateLibraries")
(defmethod mi/rest-path :code-templates [_] "/codeTemplates")
(defmethod mi/rest-path :channel-groups [_] "/channelgroups")
(defmethod mi/rest-path :channels [_] "/channels")
(defmethod mi/rest-path :alerts [_] "/alerts")
(defmethod mi/rest-path :server-configuration [_] "/server/configuration")

(defn- script-node->fileref
  [script script-name]
  (cz/edit script (fn [node]
                    (assoc
                     (assoc-in node [:attrs :msync-fileref] script-name)
                     :content
                     (lazy-seq)))))

(defn- loc-text
  "Clojure.data.zip.xml/text replaces various whitespace characters. This
  retrieves the contents of text nodes unaltered."
  [el-loc]
  (apply str (cdzx/xml-> el-loc cdz/descendants cz/node string?)))

(defn deconstruct-node
  "Takes an xml node representing one of our major api elements and returns one or
  more deconstructed parts with filenames and a zip loc for the content."
  [file-path el-loc find-name-val]
  (let [base-path (str (mf/remove-extension file-path) File/separator)]
    (loop [el-loc el-loc
           deconstruction []]
      ;; (log/info (str "tag: " (:tag (cz/node el-loc))))
      (if (cz/end? el-loc)
        (conj deconstruction [file-path (cdx/indent-str (cz/root el-loc))])
        (let [[script-name script] (find-name-val el-loc)
              [next-el-loc deconstruction] (if script
                                             (let [script-name (str (mf/safe-name script-name) ".js")]
                                               [(cz/next (script-node->fileref script script-name))
                                                (conj deconstruction [(str base-path script-name) (loc-text script)])])
                                             [(cz/next el-loc) deconstruction])]

          (recur next-el-loc deconstruction))))))


(defn name-script-sequence
  "Take a script loc to find and build the script name."
  [prefix loc]
  (str prefix
       (cdzx/xml1-> loc cz/up :sequenceNumber cdzx/text)
       (cdzx/xml1-> loc cz/up :name cdzx/text (fn [x]
                                                (if (= x "")
                                                  nil
                                                  (str "-" x))))))

(defn this-tag=
  "Returns a query predicate that matches if the current exact node is a tag named
  tagname."
  [tagname]
  (fn [loc]
    (when (= tagname (:tag (cz/node loc)))
      loc)))

(def channel-deconstructors
  [[(fn [_] "PreprocessingScript")
    #(cdzx/xml1-> % (this-tag= :preprocessingScript))]

   [(fn [_] "PostprocessingScript")
    #(cdzx/xml1-> % (this-tag= :postprocessingScript))]

   [(fn [_] "DeployScript")
    #(cdzx/xml1-> % (this-tag= :deployScript))]

   [(fn [_] "UndeployScript")
    #(cdzx/xml1-> % (this-tag= :undeployScript))]

   [(fn [loc] (let [name (cdzx/xml1-> loc cz/up cz/up :name cdzx/text)]
                (str "destinationConnector" (when-not (cs/blank? name)
                                              (str "-" name)))))
    #(cdzx/xml1-> % (this-tag= :script) [cz/up :properties cz/up :connector])]

   [(fn [loc] (let [name (cdzx/xml1-> loc cz/up cz/up :name cdzx/text)]
                (str "sourceConnector" (when-not (cs/blank? name)
                                         (str "-" name)))))
    #(cdzx/xml1-> % (this-tag= :script) [cz/up :properties cz/up :sourceConnector])]

   [(partial name-script-sequence "sourceConnector-filter-step-")
    #(cdzx/xml1-> %
                  (this-tag= :com.mirth.connect.plugins.javascriptrule.JavaScriptRule)
                  :script
                  [cz/up cz/up cz/up :filter cz/up :sourceConnector])]

   [(partial name-script-sequence "sourceConnector-transformer-step-")
    #(cdzx/xml1-> %
                  (this-tag= :com.mirth.connect.plugins.javascriptstep.JavaScriptStep)
                  :script
                  [cz/up cz/up cz/up :transformer cz/up :sourceConnector])]

   [(fn [loc] (let [destname (apply cdzx/xml1-> loc (flatten [(repeat 4 cz/up) :name cdzx/text]))]
                (str "destinationConnector" (when-not (cs/blank? destname)
                                              (str "-" destname))
                     "-" (name-script-sequence "filter-step-" loc))))
    #(cdzx/xml1-> %
                  (this-tag= :com.mirth.connect.plugins.javascriptrule.JavaScriptRule)
                  :script
                  [cz/up cz/up cz/up :filter cz/up :connector cz/up :destinationConnectors])]

   [(fn [loc] (let [destname (apply cdzx/xml1-> loc (flatten [(repeat 4 cz/up) :name cdzx/text]))]
                (str "destinationConnector" (when-not (cs/blank? destname)
                                              (str "-" destname))
                     "-" (name-script-sequence "transformer-step-" loc))))
    #(cdzx/xml1-> %
                  (this-tag= :com.mirth.connect.plugins.javascriptstep.JavaScriptStep)
                  :script
                  [cz/up cz/up cz/up :transformer cz/up :connector cz/up :destinationConnectors])]

   [(fn [loc] (let [destname (apply cdzx/xml1-> loc (flatten [(repeat 4 cz/up) :name cdzx/text]))]
                (str "destinationConnector" (when-not (cs/blank? destname)
                                              (str "-" destname))
                     "-" (name-script-sequence "responseTransformer-step-" loc))))
    #(cdzx/xml1-> %
                  (this-tag= :com.mirth.connect.plugins.javascriptstep.JavaScriptStep)
                  :script
                  [cz/up cz/up cz/up :responseTransformer cz/up :connector cz/up :destinationConnectors])]])


;; ************** TODO - deal with the duplication/ugliness
(defmethod mi/deconstruct-node :channels [{:keys [disk-mode] :as app-conf} ^String file-path el-loc]
  ;; handle default-group channels
  (let [effective-disk-mode (if (and (= "groups" disk-mode)
                                     (not (.endsWith file-path (str File/separator "index.xml"))))
                              "items"
                              disk-mode)]
    (case effective-disk-mode
      "groups" (let [index-loc (mx/to-zip (slurp file-path))]
                 [[file-path (cdx/indent-str (cz/root (mx/add-update-child (cdzx/xml1-> index-loc :channels) el-loc)))]])
      "items" [[file-path (cdx/indent-str (cz/node el-loc))]]
      (deconstruct-node
       file-path
       el-loc
       (fn [el-loc]
         (some (fn [[name script]]
                 (when-let [script-loc (script el-loc)]
                   [(name script-loc) script-loc]))
               channel-deconstructors))))))

(defmethod mi/deconstruct-node :code-templates [{:keys [disk-mode] :as app-conf} file-path el-loc]
  (case disk-mode
    "groups" (let [index-loc (mx/to-zip (slurp file-path))]
               [[file-path (cdx/indent-str (cz/root (mx/add-update-child (cdzx/xml1-> index-loc :codeTemplates) el-loc)))]])
    "items" [[file-path (cdx/indent-str (cz/node el-loc))]]
    (deconstruct-node
     file-path
     el-loc
     (fn [el-loc]
       (when-let [script-loc (cdzx/xml1-> el-loc :properties :code)]
         [(cdzx/xml1-> el-loc :name cdzx/text) script-loc])))))

(defmethod mi/deconstruct-node :global-scripts [{:keys [disk-mode] :as app-conf} file-path el-loc]
  (if-not (= disk-mode "code")
    [[file-path (cdx/indent-str (cz/node el-loc))]]
    (deconstruct-node
     file-path
     el-loc
     (fn [el-loc]
       (when-let [name-loc (cdzx/xml1-> el-loc :entry :string)]
         [(cdzx/text name-loc) (cz/right name-loc)])))))

(defmethod mi/deconstruct-node :default [_ file-path el-loc]
  [[file-path (cdx/indent-str (cz/node el-loc))]]) ;; just return whole node

(comment

  ;; pull
  (mirthsync.core/main-func "-s" "https://localhost:8443/api"
                            "-u" "admin" "-p" "admin" "-t" "target/tmp"
                            "-i" "-f" "pull")

  ;; load a channel for repl work
  (def el-loc2 (cz/xml-zip (cdx/parse-str (slurp "target/tmp/Channels/This is a group/Hello DB Writer.xml"))))

  ;; select first transformer elements
  (cdzx/xml1-> el-loc2 :sourceConnector :transformer :elements cz/node)

  (def first-transformer-jscript-step
    (cdzx/xml1-> el-loc2
                 :sourceConnector
                 :transformer
                 :elements
                 :com.mirth.connect.plugins.javascriptstep.JavaScriptStep
                 :script
                 [cz/up cz/up cz/up :transformer cz/up :sourceConnector])))

