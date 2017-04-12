(ns gitmirth.core
  (:gen-class)
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.zip :as zip]))

(def api-url "https://misc:8443/api")

(defn with-authentication
  "Binds a cookie store to keep auth cookies, authenticates, and
  returns the results of executing the supplied fn"
  [func]
  (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
    (client/post
     (str api-url "/users/_login")
     {:form-params
      {:username "admin"
       :password "admin"}
      :insecure? true})
    (func)))

(defn fetch-all-channels-xml
  "Fetch all channels from remote server"
  []
  (:body (client/get (str api-url "/channels") {:insecure? true})))

(defn to-zip
  "Takes a string of xml and returns an xml zipper"
  [x]
  (zip/xml-zip (xml/parse-str x)))

(defn process-channels
  ""
  []
  (let [channel-zips (zx/xml-> (to-zip (fetch-all-channels-xml)) :list :channel)]
    (map #(zx/xml-> % :name zip/down zip/node) channel-zips)))

(defn foo
  ""
  []
  (process-channels))

(defn dostuff
  "App logic"
  []
  (with-authentication foo))

(defn -main
  [& args]
  (dostuff))
