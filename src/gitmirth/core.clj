(ns gitmirth.core
  (:gen-class)
  (:require [clj-http.client :as client]
            [clojure
             [pprint :as pp]
             [zip :as zip]]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io])
  (:import java.io.File))

(defn to-zip
  "Takes a string of xml and returns an xml zipper"
  [x]
  (zip/xml-zip (xml/parse-str x)))

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
  channel locs based on the supplied predicates. In other words - grab
  the xml from the url via a GET request, extract the :body of the
  result, parse the XML, create a 'zipper', and return the result of
  the query (predicates)"
  [url predicates]
  (as-> url v
    (client/get v {:insecure? true})
    (:body v)
    (to-zip v)
    (apply zx/xml-> v predicates)))

(defn serialize-node
  "Take an xml location and write to the filesystem with a meaningful
  name and path"
  [dirname xmlloc name-predicate]
  (let [name (apply zx/xml1-> xmlloc name-predicate)
        xml-str (xml/indent-str (zip/node xmlloc))
        file-path (str "target/tmpfoo/" dirname (File/separator) "/" name)]
    (io/make-parents file-path)
    (spit file-path xml-str)))

(def config
  {:channel {:element-preds [:list :channel]
             :id-preds [:id zip/down zip/node]
             :name-preds [:name zip/down zip/node]
             :path "channels"}

   :codeTemplate {:element-preds [:list :codeTemplate]
                  :id-preds [:id zip/down zip/node]
                  :name-preds [:name zip/down zip/node]
                  :path "codeTemplates"}

   :configurationMap {:element-preds [:map]
                      :id-preds [(fn [_] "")]
                      :name-preds [(fn [_] "configurationMap")]
                      :path "server/configurationMap"}

   :globalScripts {:element-preds [:map]
                   :id-preds [(fn [_] "")]
                   :name-preds [(fn [_] "globalScripts")]
                   :path "server/globalScripts"}
   })


(defn download
  "Serializes all xml found at the api path using the supplied config"
  [base-url {:keys [element-preds name-preds path]}]
  (doseq [loc (fetch-all (str base-url (str "/" path)) element-preds)]
    (serialize-node path loc name-preds)))

(defn upload-node
  ""
  [base-url path id-preds xmlloc]
  (let [id (apply zx/xml1-> xmlloc id-preds)]
    (client/put (str base-url "/" path "/" id)
                {:insecure? true
                 :body (xml/indent-str (zip/node xmlloc))
                 :content-type "application/xml"})))

(defn upload
  ""
  [base-url {:keys [id-preds path]}]
  (doseq [f (.listFiles (io/file (str "target/tmpfoo/" path "/")))]
    (upload-node base-url path id-preds (to-zip (slurp f)))))

(defn makeitso
  "App logic"
  [base-url username password]
  (with-authentication base-url username password
    (fn [] (doseq [c config]
            (download base-url (val c))
            (upload base-url (val c))))))

(defn -main
  [& args]
  (println "starting")
  (pp/pprint (makeitso "https://misc:8443/api" "admin" "admin"))
  (println "finished"))
