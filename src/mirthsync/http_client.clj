(ns mirthsync.http-client
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [clojure.tools.logging :as log]
            [clojure.zip :as zip]
            [mirthsync.interfaces :as mi]
            [mirthsync.xml :as mxml]))

(defn put-xml
  "HTTP PUTs the current api and el-loc to the server."
  [{:keys [server el-loc ignore-cert-warnings api]}
   params]
  (log/logf :debug "putting xml to: %s" (mi/rest-path api))
  (client/put (str server (mi/rest-path api) "/" (mi/find-id api el-loc))
              {:headers {:x-requested-with "XMLHttpRequest"}
               :insecure? ignore-cert-warnings
               :body (xml/indent-str (zip/node el-loc))
               :query-params params
               :content-type "application/xml"}))

(defn post-xml
  "HTTP multipart posts the params of the api to the server. Multiple
  params are supported and should be passed as one or more [name
  value] vectors. Name should be a string and value should be an xml
  string."
  [{:keys [server ignore-cert-warnings api]}
   params
   query-params]
  (log/logf :debug "posting xml to: %s" (mi/post-path api))
  (client/post (str server (mi/post-path api))
               {:headers {:x-requested-with "XMLHttpRequest"}
                :insecure? ignore-cert-warnings
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
     {:headers {:x-requested-with "XMLHttpRequest"}
      :form-params
      {:username username
       :password password}
      :insecure? ignore-cert-warnings})
    (func)))

(defn api-url
  "Returns the constructed api url."
  [{:keys [server api]}]
  (str server (mi/rest-path api)))

(defn fetch-all
  "Fetch everything at url from remote server and return a sequence of
  locs based on the supplied function. In other words - grab the xml
  from the url via a GET request, extract the :body of the result,
  parse the XML, create a 'zipper', and return the result of the
  function on the xml zipper."
  [{:as app-conf :keys [ignore-cert-warnings]}
   find-elements]
  (-> (api-url app-conf)
      (client/get {:headers {:x-requested-with "XMLHttpRequest"}
                   :insecure? ignore-cert-warnings})
      :body
      mxml/to-zip
      find-elements))
