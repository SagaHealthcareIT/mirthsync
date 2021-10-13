(ns mirthsync.apis-test
  (:require [clojure.data.xml :as cdx]
            [clojure.data.zip.xml :as cdzx]
            [clojure.test :as ct]
            [clojure.zip :as cz]
            [mirthsync.apis :as ma]
            [mirthsync.xml :as mx]))

(defn update-id [loc]
  (-> loc
      (cdzx/xml1-> :id)
      cz/next
      (cz/replace "1234b87b-71a7-42dd-b00e-049d28adae64")
      cz/root
      cz/xml-zip))

(def channel-groups-loc (mx/to-zip "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<set>
  <channelGroup version=\"3.11.0\">
    <id>630d82bd-0727-48d6-bf13-bfa86080d9f5</id>
    <name>/this\\ is /a \\ group/\\with weird\\characters/</name>
    <revision>1</revision>
    <lastModified>
      <time>1633619775310</time>
      <timezone>America/New_York</timezone>
    </lastModified>
    <description/>
    <channels>
      <channel version=\"3.11.0\">
        <id>de5a1a40-3f1e-44e1-b3cb-60d6772234ae</id>
        <revision>0</revision>
      </channel>
    </channels>
  </channelGroup>
  <channelGroup version=\"3.11.0\">
    <id>2b8b91c1-0340-4e44-af3b-333aae9c7262</id>
    <name>This is a group</name>
    <revision>1</revision>
    <lastModified>
      <time>1632175112613</time>
      <timezone>America/New_York</timezone>
    </lastModified>
    <description>This is the group's description</description>
    <channels>
      <channel version=\"3.11.0\">
        <id>2521ed7e-156d-47dd-b701-0705583b99ec</id>
        <revision>0</revision>
      </channel>
      <channel version=\"3.11.0\">
        <id>fab4b87b-71a7-42dd-b00e-049d28adae64</id>
        <revision>0</revision>
      </channel>
    </channels>
  </channelGroup>
</set>"))

(def channel-group-loc (mx/to-zip (slurp "dev-resources/mirth-11-baseline/Channels/This is a group/index.xml")))

(def channel-in-group-loc
  (mx/to-zip (slurp "dev-resources/mirth-11-baseline/Channels/This is a group/Http Hello2 3081.xml")))

(def channel-without-group-loc
  (mx/to-zip (slurp "dev-resources/mirth-11-baseline/Channels/Http 3080.xml")))

(def updated-channel-group-loc
  (update-id channel-group-loc))

(def codetemplate-libraries-loc (mirthsync.xml/to-zip "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<list>
  <codeTemplateLibrary version=\"3.11.0\">
    <id>3e5488e0-2c95-456e-bce3-0178d365d198</id>
    <name>Library 1</name>
    <revision>1</revision>
    <lastModified>
      <time>1633625072821</time>
      <timezone>America/New_York</timezone>
    </lastModified>
    <description/>
    <includeNewChannels>false</includeNewChannels>
    <enabledChannelIds/>
    <disabledChannelIds>
      <string>2521ed7e-156d-47dd-b701-0705583b99ec</string>
      <string>51a75675-33cc-4d87-87c6-2a337dedff67</string>
    </disabledChannelIds>
    <codeTemplates>
      <codeTemplate version=\"3.11.0\">
        <id>66d76711-0274-49e4-b92a-73c4690bcbe7</id>
      </codeTemplate>
      <codeTemplate version=\"3.11.0\">
        <id>62288726-4e95-478f-9fd7-5d2d709e805e</id>
      </codeTemplate>
    </codeTemplates>
  </codeTemplateLibrary>
  <codeTemplateLibrary version=\"3.11.0\">
    <id>506d3ed2-a38e-46f4-a970-1c99f317a78b</id>
    <name>Library 2</name>
    <revision>1</revision>
    <lastModified>
      <time>1632175113387</time>
      <timezone>America/New_York</timezone>
    </lastModified>
    <description/>
    <includeNewChannels>false</includeNewChannels>
    <enabledChannelIds/>
    <disabledChannelIds>
      <string>2521ed7e-156d-47dd-b701-0705583b99ec</string>
      <string>51a75675-33cc-4d87-87c6-2a337dedff67</string>
    </disabledChannelIds>
    <codeTemplates>
      <codeTemplate version=\"3.11.0\">
        <id>b64edb54-5358-4f6c-b8e1-c66f0b517eab</id>
      </codeTemplate>
    </codeTemplates>
  </codeTemplateLibrary>
</list>"))

(def codetemplate-library-loc
  (mx/to-zip (slurp "dev-resources/mirth-11-baseline/CodeTemplates/Library 2/index.xml")))

(def updated-codetemplate-library-loc
  (update-id codetemplate-library-loc))

(def codetemplate-loc
  (mx/to-zip (slurp "dev-resources/mirth-11-baseline/CodeTemplates/Library 2/Template 2.xml")))

;;;; keeping this here for now as an alternate specter based implementation of
;;;; our add/update function in api.clj.
;; (defn add-update-child-specter
;;   [root child]
;;   (let [id-path [:content ALL (fn [t] (= (:tag t) :id)) :content FIRST]
;;         id (select-one id-path child)
;;         [new-root replaced] (replace-in
;;                              [:content
;;                               ALL
;;                               (fn [t] (and (= (:tag t) (:tag child))
;;                                           (= id (select-one id-path t))))]
;;                              (fn [old] [child old])
;;                              root)]
;;     (if (nil? replaced)
;;       (setval [:content AFTER-ELEM] child root)
;;       new-root)))

(ct/deftest test-nested-file-path
  (ct/testing "Nested channel path is valid"
    (ct/is (= "target/Channels/This is a group/Http Hello2 3081.xml"
           (ma/nested-file-path channel-groups-loc
                             [:channelGroup :channels :channel]
                             "target"
                             channel-in-group-loc
                             (nth ma/apis 6)))))

  (ct/testing "Top level channel path is valid"
    (ct/is (= "target/Channels/Http 3080.xml"
           (ma/nested-file-path channel-groups-loc
                             [:channelGroup :channels :channel]
                             "target"
                             channel-without-group-loc
                             (nth ma/apis 6)))))

  (ct/testing "Nested code template path is valid"
    (ct/is (= "target/CodeTemplates/Library 2/Template 2.xml"
           (ma/nested-file-path codetemplate-libraries-loc
                             [:codeTemplateLibrary :codeTemplates :codeTemplate]
                             "target"
                             codetemplate-loc
                             (nth ma/apis 4))))))

(ct/deftest test-add-update-child
  (ct/testing "Update results in identical codetemplate library xml"
    (let [[a b] (clojure.data/diff
                 (cz/node codetemplate-libraries-loc)
                 (cz/node (ma/add-update-child codetemplate-libraries-loc codetemplate-library-loc)))]
      (ct/is (= [nil nil] [a b]))))

  (ct/testing "Update results in identical channel group xml"
    (let [[a b] (clojure.data/diff
                 (cz/node channel-groups-loc)
                 (cz/node (ma/add-update-child channel-groups-loc channel-group-loc)))]
      (ct/is (= [nil nil] [a b]))))

  (ct/testing "Add results in addition to right side of diff and nothing on left"
    (let [[a b] (clojure.data/diff
                 (cz/node channel-groups-loc)
                 (cz/node (ma/add-update-child channel-groups-loc updated-channel-group-loc)))]
      (ct/is (= nil a))
      (ct/is (not= nil b)))))

(comment
  (ct/deftest iterate-apis
    (ct/is (= "target/foo/blah.xm" (local-path-str "foo/blah.xml" "target")))))
