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
    (testing "Export data sets are sorted, including nested tag channel ids"
      (is (= ["d1" "d2"] (texts loc :exportData :dependentIds :string)))
      (is (= ["e1" "e2"] (texts loc :exportData :dependencyIds :string)))
      (is (= ["tag1" "tag2"] (texts loc :exportData :channelTags :channelTag :id)))
      (is (= ["c1" "c2"] (texts loc :exportData :channelTags :channelTag :channelIds :string))))
    (testing "Connector plugin properties are sorted"
      (is (= [:com.mirth.connect.plugins.a.Props :com.mirth.connect.plugins.b.Props]
             (map :tag (:content (cdzx/xml1-> loc :sourceConnector :properties :pluginProperties cz/node))))))
    (testing "Ordered collections keep the server's order"
      (is (= ["Second" "First"] (texts loc :destinationConnectors :connector :name)))
      (is (= ["b" "a"] (texts loc :sourceConnector :transformer :elements :step :name)))
      (is (= ["second" "first"]
             (take-nth 2 (texts loc :sourceConnector :properties :sourceConnectorProperties :resourceIds :entry :string)))))))

(deftest sort-unordered-root-map
  (testing "A standalone map file is sorted by key"
    (is (= ["alpha" "zeta"] (texts (sorted-zip configuration-map) :entry :string))))
  (testing "A map that is not the root is left alone"
    (let [nested (str "<wrapper>" configuration-map "</wrapper>")]
      (is (= ["zeta" "alpha"] (texts (sorted-zip nested) :map :entry :string))))))

(deftest sort-unordered-is-idempotent
  (doseq [x [code-template-library channel configuration-map]]
    (let [once (mx/sort-unordered (cdx/parse-str x))]
      (is (= (cdx/emit-str once) (cdx/emit-str (mx/sort-unordered once)))))))

(deftest serialize-node-honors-sort-flag
  (let [target (str (Files/createTempDirectory "mirthsync-xml-test" (make-array FileAttribute 0)))
        pull (fn [sort?]
               (mx/serialize-node {:api :configuration-map
                                   :el-loc (mx/to-zip configuration-map)
                                   :target target
                                   :restrict-to-path ""
                                   :disk-mode "code"
                                   :force true
                                   :sort-unordered sort?})
               (texts (mx/to-zip (slurp (io/file target "ConfigurationMap.xml"))) :entry :string))]
    (testing "Sorted by default"
      (is (= ["alpha" "zeta"] (pull true))))
    (testing "Server order with --no-sort-unordered"
      (is (= ["zeta" "alpha"] (pull false))))))
