(ns mirthsync.interfaces)
;;;;;;;;;; API multimethods

;; simply dispatch on the api keyword
(defn- first-param [x & _] x)

;;;;;;;;;;;;;;;;;;;; Event hooks ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmulti after-push "Process result of item push"
  {:arglists '([api-key app-conf result])} first-param)

(defmulti pre-node-action "Transform app-conf before processing"
  {:arglists '([api app-conf])} first-param)

(defmulti preprocess "Preprocess app-conf before any other work"
  {:arglists '([api app-conf])} first-param)

;;;;;;;;;;;;;;;;;;;; Filesystem methods;;;;;;;;;;;;;;;;;;;;;;

(defmulti api-files "Find local API XML files for upload"
  {:arglists '([api directory])} first-param)

(defmulti file-path "Build the XML file path"
  {:arglists '([api app-conf])} first-param)

;;;;;;;;;;;;;;;;;;;; Work with the the XML ;;;;;;;;;;;;;;;;;;;;

(defmulti deconstruct-node "Explode XML node into file/content pairs"
  {:arglists '([api file-path el-loc])} first-param)

(defmulti find-elements "Find elements in the returned XML"
  {:arglists '([api el-loc])} first-param)

(defmulti find-id "Find the current xml loc Id"
  {:arglists '([api el-loc])} first-param)

(defmulti find-name "Find the current xml loc name"
  {:arglists '([api el-loc])} first-param)

(defmulti local-path "Base dir for saving files for the api"
  {:arglists '([api target])} first-param)

;;;;;;;;;;;;;;;;;;; HTTP API info ;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti post-path "HTTP Post on upload path"
  {:arglists '([api])} first-param)

(defmulti push-params "params for HTTP PUT/POST"
  {:arglists '([api app-conf])} first-param)

(defmulti query-params "query-params to use for HTTP Post"
  {:arglists '([api app-conf])} first-param)

(defmulti rest-path "Server API path for GET/PUT"
  {:arglists '([api])} first-param)
