(ns mirthsync.http-client
  (:require [clj-http.client :as client]
            [mirthsync.xml :as mxml]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip]
            [clojure.tools.logging :as log]))

(defn put-xml
  "HTTP PUTs the current api and el-loc to the server."
  [{:keys [server el-loc ignore-cert-warnings]
    {:keys [find-id rest-path] :as api} :api}
   params]
  (log/logf :debug "putting xml to: %s" (rest-path api))
  (client/put (str server (rest-path api) "/" (find-id el-loc))
              {:insecure? ignore-cert-warnings
               :body (xml/indent-str (zip/node el-loc))
               :query-params params
               :content-type "application/xml"}))

(defn post-xml
  "HTTP multipart posts the params of the api to the server. Multiple
  params are supported and should be passed as one or more [name
  value] vectors. Name should be a string and value should be an xml
  string."
  [{:keys [server ignore-cert-warnings]
    {:keys [post-path] :as api} :api}
   params
   query-params]
  (log/logf :debug "posting xml to: %s" (post-path api))
  (client/post (str server (post-path api))
               {:insecure? ignore-cert-warnings
                :query-params query-params
                :multipart (map (fn
                                  [[k v]]
                                  {:name k
                                   :content v
                                   :mime-type "application/xml"
                                   :encoding "UTF-8"})
                                params)}))

(defn with-authentication
  "Binds a cookie store to keep auth cookies, authenticates using the
  supplied url/credentials, and returns the results of executing the
  supplied parameterless function for side effects"
  [{:as app-conf :keys [server username password ignore-cert-warnings]} func]
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (client/post
     (str server "/users/_login")
     {:form-params
      {:username username
       :password password}
      :insecure? ignore-cert-warnings})
    (func)))

(defn api-url
  "Returns the constructed api url."
  [{:keys [server]
    {:keys [rest-path find-elements] :as api} :api}]
  (str server (rest-path api)))

(defn fetch-all
  "Fetch everything at url from remote server and return a sequence of
  locs based on the supplied function. In other words - grab the xml
  from the url via a GET request, extract the :body of the result,
  parse the XML, create a 'zipper', and return the result of the
  function on the xml zipper."
  [{:as app-conf :keys [ignore-cert-warnings]}
   find-elements]
  (-> (api-url app-conf)
      (client/get {:insecure? ignore-cert-warnings})
      (:body)
      (mxml/to-zip)
      (find-elements)))

