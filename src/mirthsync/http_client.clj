(ns mirthsync.http-client
  (:require [clj-http.client :as client]
            [mirthsync.xml :as mxml]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip]))

;FIXME: Need to check http response and/or exception. Status codes can
;cause an exception to be thrown and the server can also return xml
;body responses that indicate success/failure.

(defn put-xml
  "HTTP PUTs the current api and el-loc to the server."
  [{:keys [server el-loc]
    {:keys [find-id rest-path] :as api} :api}]
  (client/put (str server (rest-path api) "/" (find-id el-loc))
              {:insecure? true
               :body (xml/indent-str (zip/node el-loc))
               :content-type "application/xml"}))

(defn post-xml
  "HTTP multipart posts the params of the api to the server. Multiple
  params are supported and should be passed as one or more [name
  value] vectors. Name should be a string and value should be an xml
  string."
  [{:keys [server]
    {:keys [post-path] :as api} :api}
   & params]
  
  (client/post (str server (post-path api))
               {:insecure? true
                :multipart (map (fn
                                  [[n x]]
                                  {:name n
                                   :content x
                                   :mime-type "application/xml"
                                   :encoding "UTF-8"})
                                params)}))

(defn with-authentication
  "Binds a cookie store to keep auth cookies, authenticates using the
  supplied url/credentials, and returns the results of executing the
  supplied parameterless function for side effects"
  [base-url username password func]
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (client/post
     (str base-url "/users/_login")
     {:form-params
      {:username username
       :password password}
      :insecure? true})
    (func)))

(defn fetch-all
  "Fetch everything at url from remote server and return a sequence of
  locs based on the supplied function. In other words - grab the xml
  from the url via a GET request, extract the :body of the result,
  parse the XML, create a 'zipper', and return the result of the
  function on the xml zipper."
  [url find-elements]
  (-> url
      (client/get {:insecure? true})
      (:body)
      (mxml/to-zip)
      (find-elements)))
