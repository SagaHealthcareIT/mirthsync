(ns mirthsync.http-client
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [mirthsync.logging :as log]
            [clojure.zip :as zip]
            [mirthsync.interfaces :as mi]
            [mirthsync.xml :as mxml]))

(defn- build-headers
  "Build headers map, including session token if provided"
  [token]
  (if token
    {:x-requested-with "XMLHttpRequest"
     :cookie (str "JSESSIONID=" token)}
    {:x-requested-with "XMLHttpRequest"}))

(defn put-xml
  "HTTP PUTs the current api and el-loc to the server."
  [{:keys [server el-loc ignore-cert-warnings api token]}
   params]
  (log/logf :debug "putting xml to: %s" (mi/rest-path api))
  (client/put (str server (mi/rest-path api) "/" (mi/find-id api el-loc))
              {:headers (build-headers token)
               :insecure? ignore-cert-warnings
               :body (xml/indent-str (zip/node el-loc))
               :query-params params
               :content-type "application/xml"}))

(defn post-xml
  "HTTP multipart posts the params of the api to the server. Multiple
  params are supported and should be passed as one or more [name
  value] vectors. Name should be a string and value should be an xml
  string."
  [{:keys [server ignore-cert-warnings token]} path params query-params multipart?]
  (log/logf :debug "posting xml to: %s" path)
  (let [base-params {:headers (build-headers token)
                     :insecure? ignore-cert-warnings
                     :query-params query-params}]
    (client/post (str server path)
                 (if multipart?
                   (assoc base-params :multipart (map (fn
                                                        [[k v]]
                                                        {:name k
                                                         :content v
                                                         :mime-type "application/xml"
                                                         :encoding "UTF-8"})
                                                      params))
                   (assoc base-params
                          :body params
                          :content-type "application/xml"
                          :accept "application/xml")))))

(defn with-authentication
  "Binds a cookie store to keep auth cookies, authenticates using the
  supplied url/credentials or token, and returns the results of executing the
  supplied parameterless function for side effects"
  [{:as app-conf :keys [server username password token ignore-cert-warnings]} func]
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (when-not token
      ;; Only perform login if using username/password (not token)
      (client/post
       (str server "/users/_login")
       {:headers {:x-requested-with "XMLHttpRequest"}
        :form-params
        {:username username
         :password password}
        :insecure? ignore-cert-warnings}))
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
  [{:as app-conf :keys [ignore-cert-warnings token]}
   find-elements]
  (-> (api-url app-conf)
      (client/get {:headers (build-headers token)
                   :insecure? ignore-cert-warnings})
      :body
      mxml/to-zip
      find-elements))
