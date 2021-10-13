(ns mirthsync.apis
  (:require [clojure.data.xml :as cdx]
            [clojure.data.zip.xml :as cdzx]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [clojure.zip :as cz]
            [mirthsync.actions :as ma]
            [mirthsync.files :as mf])
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

(defn- safe-name
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
  "Returns a function that, when given an app conf, returns a string file
  path for the current api with path appended."
  [path]
  (log/debugf "Received file-path: %s" path)
  (fn [{:keys [el-loc] :as app-conf
       {:keys [local-path find-name]} :api}]
    (log/spyf :debug "Constructed file path: %s"
              (str (local-path (:target app-conf))
                   (when-not (.endsWith ^String (local-path (:target app-conf)) File/separator) File/separator)
                   (safe-name (
find-name el-loc))
                   path))))

(defn preprocess-api
  [{:as app-conf {:keys [preprocess]} :api}]
  (preprocess app-conf))

(defn nested-file-path
  "Returns the nested xml file path for the provided api."
  [group-xml-zip selectors target-dir el-loc {:keys [local-path find-name find-id]}]
  (str (local-path target-dir) File/separator
       (when-let [lib-name (safe-name
                            (let [id (find-id el-loc)]
                              (apply cdzx/xml1->
                                     group-xml-zip
                                     (flatten [selectors
                                               :id id
                                               (repeat (count selectors) cz/up)
                                               :name cdzx/text]))))]
         (str lib-name File/separator))
       (safe-name (find-name el-loc))
       ".xml"))

(defn- alert-file-path
  "Returns the alert xml path."
  [{:keys [el-loc] :as app-conf
    {:keys [local-path find-name]} :api}]
  (str (local-path (:target app-conf))
       File/separator
       (safe-name (find-name el-loc))
       ".xml"))

(defn- unexpected-response
  [r]
  (log/warn "An unexpected response was received from the server...")
  (log/warnf "Status: %s, Phrase: %s" (:status r) (:reason-phrase r)))

(defn- after-push
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

(def ^{:private true} null-204 (after-push #(= 204 (:status %))
                          #(nil? (:body %))))

(def ^{:private true} true-200 (after-push #(= 200 (:status %))
                          ;; Handle xml, json, or plain cdzx/text to
                          ;; accommodate different mirth
                          ;; versions. Version 9, for instance,
                          ;; returns json by default.
                          (fn [{body :body}]
                            (or (= body "<boolean>true</boolean>")
                                (= body "{\"boolean\":true}")
                                (= body "true")))))

(def ^{:private true} revision-success
  (after-push
   (fn [{body :body}]
     (let [loc (-> body
                   cdx/parse-str
                   cz/xml-zip)
           override (cdzx/xml1-> loc :overrideNeeded cz/node)
           success  (cdzx/xml1-> loc :librariesSuccess cz/node)]
       (and
        (= "false" (first (:content override)))
        (= "true" (first (:content success))))))))


(defn- make-api
  "Builds an api from an api-map. rest-path, local-path and
  find-elements fns are required and the rest of the api uses sensible
  defaults if a value is not supplied."
  [api]
  (merge {
          :rest-path nil                ; required - server api path for GET/PUT
          :local-path nil               ; required - base dir for saving files
          :find-elements nil            ; required - find elements in the returned xml
          :file-path nil                ; required - build the xml file path

          :find-id api-element-id                ; find the current xml loc id
          :find-name api-element-name            ; find the current xml loc name
          :api-files (partial mf/xml-file-seq 1) ; find local api xml files for upload
          :post-path (constantly nil)   ; HTTP POST on upload path
          :push-params (constantly nil) ; params for HTTP PUT/POST
          :pre-node-action identity     ; transform app-conf before processing
          :after-push true-200          ; process result of item push
          :preprocess identity          ; preprocess app-conf before any other work
          :query-params {}              ; query-params for HTTP POST 
          }
         api))

(defn- post-path
  "Takes an api and builds a _bulkUpdate from the rest-path"
  [{:keys [rest-path] :as api}]
  (str (rest-path api) "/_bulkUpdate"))

(def apis
  [(make-api
    {:rest-path (constantly "/server/configurationMap")
     :local-path (partial local-path-str File/separator)
     :find-elements #(cdzx/xml-> % :map)
     :find-id (constantly nil)
     :find-name (constantly nil)
     :file-path (file-path "ConfigurationMap.xml")
     :api-files (partial mf/only-named-xml-files-seq 1 "ConfigurationMap")
     :after-push null-204})

   (make-api
    {:rest-path (constantly "/server/globalScripts")
     :local-path (partial local-path-str "GlobalScripts")
     :find-elements #(cdzx/xml-> % :map)
     :find-id (constantly nil)
     :find-name (constantly nil)
     :file-path (file-path "globalScripts.xml")
     :after-push null-204})

   (make-api
    {:rest-path (constantly "/server/resources")
     :local-path (partial local-path-str File/separator)
     :find-elements #(cdzx/xml-> % :list)
     :find-id (constantly nil)
     :find-name (constantly nil)
     :file-path (file-path "Resources.xml")
     :api-files (partial mf/only-named-xml-files-seq 1 "Resources")
     :after-push null-204})

   (make-api
    {:rest-path (constantly "/codeTemplateLibraries")
     :local-path (partial local-path-str "CodeTemplates")
     :find-elements #(or (cdzx/xml-> % :list :codeTemplateLibrary) ; from server
                         (cdzx/xml-> % :codeTemplate)) ; from filesystem
     :file-path (file-path (str File/separator "index.xml"))
     :api-files (partial mf/only-named-xml-files-seq 2 "index")
     :post-path post-path
     :push-params #(let [{:keys [server-codelibs force]} %]
                     {"libraries" (cdx/indent-str (cz/node server-codelibs))
                      "removedLibraryIds" "<set/>"
                      "updatedCodeTemplates" "<list/>"
                      "removedCodeTemplateIds" "<set/>"
                      "override" (if force "true" "false")})
     :preprocess (partial ma/fetch-and-pre-assoc :server-codelibs :list)
     :pre-node-action (fn [app-conf]
                        (assoc app-conf
                               :server-codelibs
                               (add-update-child (:server-codelibs app-conf) (:el-loc app-conf))))
     :after-push revision-success
     :query-params override-params})

   (make-api
    {:rest-path (constantly "/codeTemplates")
     :local-path (partial local-path-str "CodeTemplates")
     :find-elements #(cdzx/xml-> % :list :codeTemplate)
     :file-path #(nested-file-path (:server-codelibs %)
                                   [:codeTemplateLibrary :codeTemplates :codeTemplate]
                                   (:target %)
                                   (:el-loc %)
                                   (:api %))
     :api-files (partial mf/without-named-xml-files-seq 2 "index")
     :push-params override-params})

   (make-api
    {:rest-path (constantly "/channelgroups")
     :local-path (partial local-path-str "Channels")
     :find-elements #(or (cdzx/xml-> % :list :channelGroup) ; from server
                         (cdzx/xml-> % :channelGroup)) ; from filesystem
     :file-path (file-path (str File/separator "index.xml"))
     :api-files (partial mf/only-named-xml-files-seq 2 "index")
     :post-path post-path
     :push-params #(let [{:keys [server-groups force]} %]
                     {"channelGroups" (cdx/indent-str (cz/node server-groups))
                      "removedChannelGroupsIds" "<set/>"
                      "override" (if force "true" "false")})
     :preprocess (partial ma/fetch-and-pre-assoc :server-groups :set)
     :pre-node-action (fn [app-conf]
                        (assoc app-conf
                               :server-groups
                               (add-update-child (:server-groups app-conf) (:el-loc app-conf))))
     :query-params override-params})

   (make-api
    {:rest-path (constantly "/channels")
     :local-path (partial local-path-str "Channels")
     :find-elements #(cdzx/xml-> % :list :channel)
     :file-path #(nested-file-path (:server-groups %)
                                   [:channelGroup :channels :channel]
                                   (:target %)
                                   (:el-loc %)
                                   (:api %))
     :api-files (partial mf/without-named-xml-files-seq 2 "index")
     :push-params #(override-params (:force %))})

   (make-api
    {:rest-path (constantly "/alerts")
     :local-path (partial local-path-str "Alerts")
     :find-elements #(cdzx/xml-> % :list :alertModel)
     :file-path alert-file-path
     :after-push null-204})
   ])

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
