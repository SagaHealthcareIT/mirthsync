(ns mirthsync.mirth-3-12-test
  (:require [clojure.test :refer :all]
            [mirthsync.core :refer :all]
            [mirthsync.fixture-tools :refer :all]
            [mirthsync.common-tests :refer :all]))

(use-fixtures :once mirth-3-12-fixture)

(def version "3-12")
(def baseline-dir (str "dev-resources/mirth-" version "-baseline"))
(def repo-dir (str "target/tmp-" version))

(deftest integration-with-3-12
  (test-integration repo-dir baseline-dir version))
