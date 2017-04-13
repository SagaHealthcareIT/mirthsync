(ns gitmirth.core
  (:gen-class)
  (:require [clj-http.client :as client]
            [clojure
             [pprint :as pp]
             [zip :as zip]]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]))

(defn to-zip
  "Takes a string of xml and returns an xml zipper"
  [x]
  (zip/xml-zip (xml/parse-str x)))

(defn with-authentication
  "Binds a cookie store to keep auth cookies, authenticates using the
  supplied url/credentials, and returns the results of executing the
  supplied fn"
  [base-url username password func]
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (client/post
     (str base-url "/users/_login")
     {:form-params
      {:username username
       :password password}
      :insecure? true})
    (func)))

(defn fetch-all-channels-zip
  "Fetch all channels from remote server"
  [base-url]
  (-> base-url
      (str "/channels")                 ;combine the base url with the rest path
      (client/get                       ;fetch the channels via rest
       {:insecure? true})
      :body                             ;extract the response body
      to-zip                            ;get an xml zipper
      (zx/xml-> :list :channel)         ;get a zipper per channel
      ))

(defn process-channel
  "Take a channel zip and write to the filesystem"
  [chanzip]
  (zx/xml-> chanzip :name zip/down zip/node))

(defn makeitso
  "App logic"
  [base-url username password]
  (with-authentication base-url username password
    #(map process-channel (fetch-all-channels-zip base-url))))

(defn -main
  [& args]
  (println "starting")
  (pp/pprint (makeitso "https://misc:8443/api" "admin" "admin"))
  (println "finished"))
