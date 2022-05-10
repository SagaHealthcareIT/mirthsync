(defproject com.saga-it/mirthsync "3.0.2-SNAPSHOT"
  :description "Mirthsync is a command line tool, created by Saga IT,
  for keeping a local copy of important aspects of Mirth Connect
  configuration in order to allow for the use of traditional version
  control tools like Git or SVN. With Mirthsync you are able to
  selectively pull the code for channels, groups, and more from a
  local or remote Mirth Connect instance and have the code placed into
  a local hierarchy of files and directories that can be tracked using
  version control. Selectively pushing code to local or remote Mirth
  Connect servers is also possible using Mirthsync.

  The only requirements are having credentials for the
  server that is being synced and ensuring that the server is
  configured to allow access to its REST API."
  :url "https://github.com/SagaHealthcareIT/mirthsync"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.9.1"
  :signing {:gpg-key "jessedowell@gmail.com"}
  :repositories ^:replace [["central" {:url "https://repo1.maven.org/maven2/" :snapshots false :checksum :ignore}]
                           ["clojars" {:url "https://repo.clojars.org/"}]]
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [clj-http "3.10.1" :exclusions [commons-logging]]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "1.0.0"]
                 [org.clojure/tools.cli "1.0.206"]
                 ;; [tolitius/xml-in "0.1.0"]
                 ;; [com.rpl/specter "1.1.3"]
                 [org.clojure/tools.logging "1.1.0"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [ch.qos.logback/logback-core "1.2.3"]
                 [org.slf4j/slf4j-api "1.7.30"]

                 ;; enhanced exceptions
                 [slingshot "0.12.2"]

                 ;; logging redirects
                 [org.slf4j/jcl-over-slf4j "1.7.30"]
                 [org.slf4j/log4j-over-slf4j "1.7.30"]
                 [org.apache.logging.log4j/log4j-to-slf4j "2.13.3"]
                 [org.slf4j/osgi-over-slf4j "1.7.30"]
                 [org.slf4j/jul-to-slf4j "1.7.30"]

                 ;; optionally pull config from environment
                 [environ "1.2.0"]

                 ;;;; don't need this for now
                 ;; [com.fasterxml.jackson.core/jackson-core "2.9.6"]
                 ;; [com.fasterxml.jackson.dataformat/jackson-dataformat-xml "2.9.6"]
                 ;; [com.fasterxml.jackson.core/jackson-databind "2.9.6"]
                 ]
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
   ;; :uberjar {:aot :all
   ;;           :omit-source true}
   
   :repl {:plugins [[cider/cider-nrepl "0.25.2"]]}

   :dev {:dependencies [[clj-commons/conch "0.9.2"]]}
   }

  :release-tasks [["vcs" "assert-committed"]
                  ["clean"]
                  ["test"]
                  ;; bump to release
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["shell" "sed" "-E" "-i.bak" "s/(version of mirthSync is) \"[0-9]+\\\\.[0-9]+\\\\.[0-9]+\"/\\\\1 \"${:version}\"/g" "README.md"]
                  ["shell" "rm" "-f" "README.md.bak"]
                  ["shell" "sed" "-E" "-i.bak" "s/[0-9]+\\\\.[0-9]+\\\\.[0-9]+/${:version}/g" "pkg/mirthsync.sh"]
                  ["shell" "rm" "-f" "pkg/mirthsync.sh.bak"]
                  ["shell" "sed" "-E" "-i.bak" "s/[0-9]+\\\\.[0-9]+\\\\.[0-9]+/${:version}/g" "pkg/mirthsync.bat"]
                  ["shell" "rm" "-f" "pkg/mirthsync.bat.bak"]
                  ["uberjar"]
                  ["shell" "mkdir" "-p" "target/mirthsync-${:version}/lib"]
                  ["shell" "cp" "-a" "pkg" "target/mirthsync-${:version}/bin"]
                  ["shell" "cp" "target/uberjar/mirthsync-${:version}-standalone.jar" "target/mirthsync-${:version}/lib"]
                  ["shell" "tar" "-C" "target/" "-cvzf" "target/mirthsync-${:version}.tar.gz" "mirthsync-${:version}"]
                  ["shell" "gpg" "--detach-sign" "--armor" "target/mirthsync-${:version}.tar.gz"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ;; bump to new minor snapshot and commit
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]])
