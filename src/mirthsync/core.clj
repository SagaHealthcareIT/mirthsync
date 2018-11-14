(ns mirthsync.core
  (:gen-class)
  (:require [clj-http.client :as client]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [mirthsync.cli :as cli])
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
  name and path. If the file exists it is not overwritten unless the
  -f option is set. Returns nil."
  [target-dir dirname xmlloc name-predicate]
  (let [name (apply zx/xml1-> xmlloc name-predicate)
        xml-str (xml/indent-str (zip/node xmlloc))
        file-path (str target-dir (File/separator) dirname (File/separator) name ".xml")]
    (if (and (.exists (io/file file-path))
             (not cli/*force*))
      (cli/output (str "File at " file-path " already exists and the "
                        "force (-f) option was not specified. Refusing "
                        "to overwrite the file."))
      (do (io/make-parents file-path)
          (spit file-path xml-str)))
    nil))

(def config
  {:configurationMap {:find-elements [:map]
                      :find-id [(fn [_] nil)]
                      :find-name [(fn [_] "configurationMap")]
                      :path "server/configurationMap"}

   :codeTemplate {:find-elements [:list :codeTemplate]
                  :find-id [:id zip/down zip/node]
                  :find-name [:name zip/down zip/node]
                  :path "codeTemplates"}

   :globalScripts {:find-elements [:map]
                   :find-id [(fn [_] nil)]
                   :find-name [(fn [_] "globalScripts")]
                   :path "server/globalScripts"}

   :channelGroup {:find-elements [:list :channelGroup]
                  :find-id [:id zip/down zip/node]
                  :find-name [:name zip/down zip/node]
                  :path "channelgroups"
                  :prevent-push true}

   :channel {:find-elements [:list :channel]
             :find-id [:id zip/down zip/node]
             :find-name [:name zip/down zip/node]
             :path "channels"}
   })


(defn download
  "Serializes all xml found at the api path using the supplied config"
  [base-url target-dir {:keys [find-elements find-name path]}]
  (cli/output 0 (str "Downloading " path))
  (doseq [loc (fetch-all (str base-url (str "/" path)) find-elements)]
    (serialize-node target-dir path loc find-name)))

(defn upload-node
  ""
  [base-url path find-id xmlloc]
  (let [id (apply zx/xml1-> xmlloc find-id)]
    (client/put (str base-url "/" path "/" id)
                {:insecure? true
                 :body (xml/indent-str (zip/node xmlloc))
                 :content-type "application/xml"})))

(defn upload
  ""
  [base-url target-dir {:keys [find-id path prevent-push]}]
  (if prevent-push
    (cli/output 0 (str "Uploading " path " is not currently supported - skipping"))
    (do
      (cli/output 0 (str "Uploading " path))
      (let [files (.listFiles (io/file (str target-dir (File/separator) path)))]
        (cli/output 0 (str "found " (count files) " files"))
        (doseq [f files]
          (upload-node base-url path find-id (to-zip (slurp f))))))))

(defn run
  "App logic. Returns nil."
  [base-url username password target-dir action]
  (cli/output 0 (str "Authenticating to server at " base-url " as " username))
  (with-authentication base-url username password
    (fn [] (doseq [c config]
            (cli/output 1 (key c))
            (action base-url target-dir (val c)))))
  (cli/output 0 "Finished!")
  nil)

(defn exit! [status msg]
  "Print message and System/exit with status code"
  (cli/output msg)
  ;; (System/exit status)
  )

(defn -main
  [& args]
  (let [{:keys [action options exit-message ok?]} (cli/validate-args args)
        actions {"push" upload "pull" download}]
    (if exit-message
      (exit! (if ok? 0 1) exit-message)
      (run
        (:server options)
        (:username options)
        (:password options)
        (:target options)
        (actions action)))))

