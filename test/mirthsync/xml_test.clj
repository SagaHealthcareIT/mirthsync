(ns mirthsync.xml-test
  (:require [clojure.data.xml :as cdx]
            [clojure.data.zip.xml :as cdzx]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.zip :as cz]
            [mirthsync.apis]
            [mirthsync.xml :as mx])
  (:import java.nio.file.Files
           java.nio.file.attribute.FileAttribute))

(defn- sorted-zip
  [xml-str]
  (cz/xml-zip (mx/sort-unordered (cdx/parse-str xml-str))))

(defn- texts
  [loc & path]
  (apply cdzx/xml-> loc (concat path [cdzx/text])))

(def code-template-library
  "<codeTemplateLibrary version=\"4.5.2\">
     <id>lib</id>
     <name>Library 1</name>
     <enabledChannelIds>
       <string>c</string>
       <string>a</string>
       <string>b</string>
     </enabledChannelIds>
     <disabledChannelIds>
       <string>z</string>
       <string>y</string>
     </disabledChannelIds>
     <codeTemplates>
       <codeTemplate version=\"4.5.2\">
         <id>t2</id>
         <contextSet>
           <delegate>
             <contextType>SOURCE_RECEIVER</contextType>
             <contextType>CHANNEL_DEPLOY</contextType>
             <contextType>GLOBAL_PREPROCESSOR</contextType>
           </delegate>
         </contextSet>
       </codeTemplate>
       <codeTemplate version=\"4.5.2\">
         <id>t1</id>
       </codeTemplate>
     </codeTemplates>
   </codeTemplateLibrary>")

(def channel
  "<channel version=\"4.5.2\">
     <id>chan</id>
     <sourceConnector version=\"4.5.2\">
       <properties class=\"com.mirth.connect.connectors.http.HttpReceiverProperties\">
         <pluginProperties>
           <com.mirth.connect.plugins.b.Props><x>1</x></com.mirth.connect.plugins.b.Props>
           <com.mirth.connect.plugins.a.Props><x>1</x></com.mirth.connect.plugins.a.Props>
         </pluginProperties>
         <sourceConnectorProperties>
           <resourceIds class=\"linked-hash-map\">
             <entry><string>second</string><string>[second]</string></entry>
             <entry><string>first</string><string>[first]</string></entry>
           </resourceIds>
         </sourceConnectorProperties>
       </properties>
       <transformer>
         <elements>
           <step><sequenceNumber>1</sequenceNumber><name>b</name></step>
           <step><sequenceNumber>0</sequenceNumber><name>a</name></step>
         </elements>
       </transformer>
     </sourceConnector>
     <destinationConnectors>
       <connector><name>Second</name></connector>
       <connector><name>First</name></connector>
     </destinationConnectors>
     <exportData>
       <dependentIds><string>d2</string><string>d1</string></dependentIds>
       <dependencyIds><string>e2</string><string>e1</string></dependencyIds>
       <channelTags>
         <channelTag>
           <id>tag2</id>
           <channelIds><string>c2</string><string>c1</string></channelIds>
         </channelTag>
         <channelTag>
           <id>tag1</id>
         </channelTag>
       </channelTags>
     </exportData>
   </channel>")

(def configuration-map
  "<map>
     <entry>
       <string>zeta</string>
       <com.mirth.connect.util.ConfigurationProperty>
         <value>z</value>
         <comment/>
       </com.mirth.connect.util.ConfigurationProperty>
     </entry>
     <entry>
       <string>alpha</string>
       <com.mirth.connect.util.ConfigurationProperty>
         <value>a</value>
         <comment/>
       </com.mirth.connect.util.ConfigurationProperty>
     </entry>
   </map>")

(def server-configuration
  "<serverConfiguration version=\"4.5.2\">
     <channelTags>
       <channelTag>
         <id>t2</id>
         <name>Tag 2</name>
         <channelIds><string>c2</string><string>c1</string></channelIds>
       </channelTag>
       <channelTag>
         <id>t1</id>
         <name>Tag 1</name>
         <channelIds/>
       </channelTag>
     </channelTags>
     <channelDependencies>
       <channelDependency><dependentId>b</dependentId><dependencyId>x</dependencyId></channelDependency>
       <channelDependency><dependentId>a</dependentId><dependencyId>y</dependencyId></channelDependency>
     </channelDependencies>
     <globalScripts>
       <entry><string>Undeploy</string><string>u</string></entry>
       <entry><string>Deploy</string><string>d</string></entry>
     </globalScripts>
     <pluginProperties>
       <entry>
         <string>Data Pruner</string>
         <properties>
           <property name=\"enabled\">false</property>
           <property name=\"archiveEnabled\">true</property>
         </properties>
       </entry>
       <entry>
         <string>B Plugin</string>
         <properties/>
       </entry>
     </pluginProperties>
     <configurationMap>
       <entry><string>db_user</string><com.mirth.connect.util.ConfigurationProperty><value>u</value></com.mirth.connect.util.ConfigurationProperty></entry>
       <entry><string>db.url</string><com.mirth.connect.util.ConfigurationProperty><value>jdbc</value></com.mirth.connect.util.ConfigurationProperty></entry>
       <entry><string>db</string><com.mirth.connect.util.ConfigurationProperty><value>x</value></com.mirth.connect.util.ConfigurationProperty></entry>
     </configurationMap>
     <alerts>
       <alertModel version=\"4.5.2\"><id>alert-b</id><name>B</name></alertModel>
       <alertModel version=\"4.5.2\"><id>alert-a</id><name>A</name></alertModel>
     </alerts>
     <channels>
       <channel version=\"4.5.2\"><id>chan-b</id></channel>
       <channel version=\"4.5.2\"><id>chan-a</id></channel>
     </channels>
     <resourceProperties>
       <list>
         <b/>
         <a/>
       </list>
     </resourceProperties>
   </serverConfiguration>")

(def alert
  "<alertModel version=\"4.5.2\">
     <trigger class=\"defaultTrigger\" version=\"4.5.2\">
       <alertChannels version=\"4.5.2\">
         <enabledChannels><string>b</string><string>a</string></enabledChannels>
         <disabledChannels/>
         <partialChannels>
           <entry>
             <string>chan2</string>
             <alertConnectors>
               <enabledConnectors><int>10</int><int>2</int><int>1</int></enabledConnectors>
               <disabledConnectors/>
             </alertConnectors>
           </entry>
           <entry>
             <string>chan1</string>
             <alertConnectors>
               <enabledConnectors/>
               <disabledConnectors><int>3</int><int>0</int></disabledConnectors>
             </alertConnectors>
           </entry>
         </partialChannels>
       </alertChannels>
       <errorEventTypes>
         <errorEventType>SOURCE_CONNECTOR</errorEventType>
         <errorEventType>ANY</errorEventType>
       </errorEventTypes>
     </trigger>
   </alertModel>")

(deftest sort-unordered-code-templates
  (let [loc (sorted-zip code-template-library)]
    (testing "Library channel id sets are sorted"
      (is (= ["a" "b" "c"] (texts loc :enabledChannelIds :string)))
      (is (= ["y" "z"] (texts loc :disabledChannelIds :string))))
    (testing "Template context types are sorted"
      (is (= ["CHANNEL_DEPLOY" "GLOBAL_PREPROCESSOR" "SOURCE_RECEIVER"]
             (texts loc :codeTemplates :codeTemplate :contextSet :delegate :contextType))))
    (testing "The code template list keeps the server's order"
      (is (= ["t2" "t1"] (texts loc :codeTemplates :codeTemplate :id))))))

(deftest sort-unordered-channels
  (let [loc (sorted-zip channel)]
    (testing "Export data sets are sorted"
      (is (= ["d1" "d2"] (texts loc :exportData :dependentIds :string)))
      (is (= ["e1" "e2"] (texts loc :exportData :dependencyIds :string))))
    (testing "The export data tag list keeps its order, but each tag's channel id set is sorted"
      (is (= ["tag2" "tag1"] (texts loc :exportData :channelTags :channelTag :id)))
      (is (= ["c1" "c2"] (texts loc :exportData :channelTags :channelTag :channelIds :string))))
    (testing "Connector plugin properties are sorted"
      (is (= [:com.mirth.connect.plugins.a.Props :com.mirth.connect.plugins.b.Props]
             (map :tag (:content (cdzx/xml1-> loc :sourceConnector :properties :pluginProperties cz/node))))))
    (testing "Ordered collections keep the server's order"
      (is (= ["Second" "First"] (texts loc :destinationConnectors :connector :name)))
      (is (= ["b" "a"] (texts loc :sourceConnector :transformer :elements :step :name)))
      (is (= ["second" "first"]
             (take-nth 2 (texts loc :sourceConnector :properties :sourceConnectorProperties :resourceIds :entry :string)))))))

(deftest sort-unordered-server-configuration
  (let [loc (sorted-zip server-configuration)]
    (testing "Channel tags and each tag's channel ids are sorted"
      (is (= ["t1" "t2"] (texts loc :channelTags :channelTag :id)))
      (is (= ["c1" "c2"] (texts loc :channelTags :channelTag :channelIds :string))))
    (testing "Channel dependencies are sorted by dependent then dependency"
      (is (= ["a" "b"] (texts loc :channelDependencies :channelDependency :dependentId))))
    (testing "Maps are sorted by key, with shorter prefix keys first"
      (is (= ["Deploy" "Undeploy"] (take-nth 2 (texts loc :globalScripts :entry :string))))
      (is (= ["db" "db.url" "db_user"] (texts loc :configurationMap :entry :string))))
    (testing "Plugin properties are sorted by plugin name, and their properties by name"
      (is (= ["B Plugin" "Data Pruner"] (texts loc :pluginProperties :entry :string)))
      (is (= ["archiveEnabled" "enabled"]
             (cdzx/xml-> loc :pluginProperties :entry :properties :property (cdzx/attr :name)))))
    (testing "Alerts are sorted by id, since their order follows database insertion order"
      (is (= ["alert-a" "alert-b"] (texts loc :alerts :alertModel :id))))
    (testing "Other lists keep the server's order"
      (is (= ["chan-b" "chan-a"] (texts loc :channels :channel :id)))
      (is (= [:b :a] (map :tag (:content (cdzx/xml1-> loc :resourceProperties :list cz/node))))))))

(deftest sort-unordered-alerts
  (let [loc (sorted-zip alert)
        channels (cdzx/xml1-> loc :trigger :alertChannels)]
    (testing "Alert channel sets and error event types are sorted"
      (is (= ["a" "b"] (texts channels :enabledChannels :string)))
      (is (= ["ANY" "SOURCE_CONNECTOR"] (texts loc :trigger :errorEventTypes :errorEventType))))
    (testing "Partial channels are sorted by channel id"
      (is (= ["chan1" "chan2"] (texts channels :partialChannels :entry :string))))
    (testing "Connector id sets are sorted numerically"
      (is (= ["0" "3"] (texts channels :partialChannels :entry :alertConnectors :disabledConnectors :int)))
      (is (= ["1" "2" "10"] (texts channels :partialChannels :entry :alertConnectors :enabledConnectors :int))))))

(deftest sort-unordered-skips-ordered-classes
  (testing "An allowlisted element serialized as an ordered collection keeps its order"
    (let [loc (sorted-zip "<serverConfiguration>
                             <configurationMap class=\"linked-hash-map\">
                               <entry><string>b</string><string>1</string></entry>
                               <entry><string>a</string><string>2</string></entry>
                             </configurationMap>
                             <pluginProperties class=\"linked-hash-set\"><b/><a/></pluginProperties>
                           </serverConfiguration>")]
      (is (= ["b" "a"] (take-nth 2 (texts loc :configurationMap :entry :string))))
      (is (= [:b :a] (map :tag (:content (cdzx/xml1-> loc :pluginProperties cz/node)))))))
  (testing "An explicit hash set or hash map class is still sorted"
    (let [loc (sorted-zip "<codeTemplateLibrary>
                             <enabledChannelIds class=\"set\"><string>b</string><string>a</string></enabledChannelIds>
                           </codeTemplateLibrary>")]
      (is (= ["a" "b"] (texts loc :enabledChannelIds :string))))))

(deftest sort-unordered-root-map
  (testing "A standalone map file is sorted by key"
    (is (= ["alpha" "zeta"] (texts (sorted-zip configuration-map) :entry :string))))
  (testing "A map that is not the root is left alone"
    (let [nested (str "<wrapper>" configuration-map "</wrapper>")]
      (is (= ["zeta" "alpha"] (texts (sorted-zip nested) :map :entry :string))))))

(deftest sort-unordered-is-idempotent
  (doseq [x [code-template-library channel configuration-map server-configuration alert]]
    (let [once (mx/sort-unordered (cdx/parse-str x))]
      (is (= (cdx/emit-str once) (cdx/emit-str (mx/sort-unordered once)))))))

(deftest serialize-node-honors-sort-flag
  (let [target (.toFile (Files/createTempDirectory "mirthsync-xml-test" (make-array FileAttribute 0)))
        pull (fn [sort?]
               (mx/serialize-node {:api :configuration-map
                                   :el-loc (mx/to-zip configuration-map)
                                   :target (str target)
                                   :restrict-to-path ""
                                   :disk-mode "code"
                                   :force true
                                   :sort-unordered sort?})
               (texts (mx/to-zip (slurp (io/file target "ConfigurationMap.xml"))) :entry :string))]
    (try
      (testing "Sorted by default"
        (is (= ["alpha" "zeta"] (pull true))))
      (testing "Server order with --no-sort-unordered"
        (is (= ["zeta" "alpha"] (pull false))))
      (finally
        (doseq [^java.io.File f (reverse (file-seq target))]
          (.delete f))))))
