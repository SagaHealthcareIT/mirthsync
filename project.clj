(defproject com.saga-it/mirthsync "3.5.1-SNAPSHOT"
  :description "MirthSync is an open source DevOps tool for Mirth Connect and
  Open Integration Engine version control and CI/CD automation. This command line
  tool enables healthcare integration DevOps workflows by keeping a local copy of
  Mirth Connect configurations (channels, code templates, global scripts) that can
  be tracked with Git or other version control systems. MirthSync supports
  multi-environment deployments (dev, test, prod) with automated channel promotion
  between servers, making it ideal for interface engine CI/CD pipelines and GitOps
  workflows. The tool provides infrastructure as code capabilities for HL7, FHIR,
  and DICOM integration engine deployments with built-in Git integration, channel
  versioning, and continuous deployment support for healthcare environments.

  The only requirements are having credentials for the server that is being synced
  and ensuring that the server is configured to allow access to its REST API."
  :url "https://saga-it.com/blog/getting-started-with-mirthsync/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.9.1"
  :signing {:gpg-key "jessedowell@gmail.com"}
  :repositories ^:replace [["central" {:url "https://repo1.maven.org/maven2/" :snapshots false :checksum :ignore}]
                           ["clojars" {:url "https://repo.clojars.org/"}]]
  :deploy-repositories [["releases" {:url "https://repo.clojars.org" :creds :gpg}]
                      ["snapshots" {:url "https://repo.clojars.org" :creds :gpg}]]
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [clj-http "3.10.1" :exclusions [commons-logging]]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "1.0.0"]
                 [org.clojure/tools.cli "1.0.206"]
                 ;; [tolitius/xml-in "0.1.0"]
                 ;; [com.rpl/specter "1.1.3"]
                 ;; enhanced exceptions
                 [slingshot "0.12.2"]

                 ;; optionally pull config from environment
                 [environ "1.2.0"]
                 
                 ;; git integration
                 [clj-jgit "1.1.0"]

                 ;; Cross-platform file operations and compression
                 [commons-io/commons-io "2.11.0"]
                 [org.apache.commons/commons-compress "1.26.0"]
                 [commons-codec/commons-codec "1.16.0"]]

                 ;;;; don't need this for now
                 ;; [com.fasterxml.jackson.core/jackson-core "2.9.6"]
                 ;; [com.fasterxml.jackson.dataformat/jackson-dataformat-xml "2.9.6"]
                 ;; [com.fasterxml.jackson.core/jackson-databind "2.9.6"]

  ;; :exclusions [commons-logging
  ;;              log4j
  ;;              org.apache.logging.log4j/log4j
  ;;              org.slf4j/simple
  ;;              org.slf4j/slf4j-jcl
  ;;              org.slf4j/slf4j-nop
  ;;              org.slf4j/slf4j-log4j12
  ;;              org.slf4j/slf4j-log4j13]
  
  :plugins [[lein-ancient "0.6.15"]
            [lein-nvd "1.4.0"]
            [lein-shell "0.5.0"]]
  ;; :pedantic? :abort
  :checksum :fail
  :global-vars {*warn-on-reflection* true}
  :aot [mirthsync.core]
  :main mirthsync.core
  :target-path "target/%s"
  :profiles
  {
   :uberjar {:dependencies [[org.slf4j/slf4j-nop "1.7.36"]]}

   :repl {:plugins [[cider/cider-nrepl "0.25.2"]]}

   :dev {:dependencies [[clj-commons/conch "0.9.2"]]}}

  :prep-tasks [["shell" "tar" "-xzf" "dev-resources/test-data.tar.gz" "--directory=target"]
               "javac" "compile"]
  :release-tasks [["make" "release"]])
