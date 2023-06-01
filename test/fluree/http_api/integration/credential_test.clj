(ns fluree.http-api.integration.credential-test
  (:require [clojure.edn :as edn]
            [clojure.core.async :as async]
            [clojure.test :as test :refer [deftest testing is]]
            [fluree.db.json-ld.credential :as cred]
            [fluree.http-api.integration.test-system :refer :all :as test-utils]
            [jsonista.core :as json]))

(test/use-fixtures :once test-utils/run-test-server)

(def default-context
  {"id" "@id"
   "type" "@type"
   "xsd" "http://www.w3.org/2001/XMLSchema#"
   "rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   "rdfs" "http://www.w3.org/2000/01/rdf-schema#"
   "sh" "http://www.w3.org/ns/shacl#"
   "schema" "http://schema.org/"
   "skos" "http://www.w3.org/2008/05/skos#"
   "wiki" "https://www.wikidata.org/wiki/"
   "f" "https://ns.flur.ee/ledger#"
   "ex" "http://example.com/ns/"})

(deftest ^:integration  credential-test
  (let [ledger-name "credential-test"]
    (testing "create"
      ;; cannot transact without roles already defined
      (let [create-req  {"ledger" ledger-name
                         "defaultContext" default-context
                         "txn"    [{"id"      (:id test-utils/auth)
                                    "f:role"  {"id" "role:root"}
                                    "type"    "schema:Person"
                                    "ex:name" "Goose"}
                                   {"id" "ex:rootPolicy"
                                    "type" "f:Policy"
                                    "f:targetNode" {"id" "f:allNodes"}
                                    "f:allow" [{"f:targetRole" {"id" "role:root"}
                                                "f:action" [{"id" "f:view"} {"id" "f:modify"}]}]}]}
            create-res  (test-utils/post :create {:body    (json/write-value-as-string create-req)
                                                  :headers test-utils/json-headers})]
        (is (= 201 (:status create-res)))
        (is (= {"address" "fluree:memory://credential-test/main/head",
                "t"       1,
                "alias"   "credential-test"}
               (-> create-res :body json/read-value)))))
    (testing "transact"
      (let [txn-req (async/<!! (cred/generate
                                 {"ledger" ledger-name
                                  "txn"    [{"id"      "ex:cred-test"
                                             "type"    "schema:Test"
                                             "ex:name" "cred test"
                                             "ex:foo"  1}]}
                                 (:private test-utils/auth)))
            txn-res (test-utils/post :transact {:body (json/write-value-as-string txn-req)
                                                :headers test-utils/json-headers})]
        (is (= 200 (:status txn-res)))
        (is (= {"address" "fluree:memory://credential-test/main/head",
                "t"       2,
                "alias"   "credential-test"}
               (-> txn-res :body json/read-value)))))
    (testing "query"
      (let [query-req (async/<!! (cred/generate {"ledger" ledger-name
                                                 "query"  {"select" {"?t" ["*"]}
                                                           "where"  [["?t" "type" "schema:Test"]]}}
                                                (:private test-utils/auth)))
            query-res (test-utils/post :query {:body    (json/write-value-as-string query-req)
                                               :headers test-utils/json-headers})]
        (is (= 200 (:status query-res)))
        (is (= [{"ex:name"  "cred test",
                 "ex:foo"   1,
                 "id"       "ex:cred-test"
                 "rdf:type" ["schema:Test"]}]
               (-> query-res :body json/read-value)))))
    (testing "multi-query"
      (let [multi-query-req (async/<!! (cred/generate {"ledger" ledger-name
                                                       "query"  {"test" {"select" {"?t" ["*"]}
                                                                         "where"  [["?t" "type" "schema:Test"]]}
                                                                 "subj" {"select" {"?s" ["*"]}
                                                                         "where"  [["?s" "@id" "ex:cred-test"]]}}}
                                                      (:private test-utils/auth)))
            multi-query-res (test-utils/post :multi-query {:body    (json/write-value-as-string multi-query-req)
                                                           :headers test-utils/json-headers})]
        (is (= 200 (:status multi-query-res)))
        (is (= {"subj"
                [{"ex:name"  "cred test",
                  "ex:foo"   1,
                  "id"       "ex:cred-test",
                  "rdf:type" ["schema:Test"]}],
                "test"
                [{"ex:name"  "cred test",
                  "ex:foo"   1,
                  "id"       "ex:cred-test",
                  "rdf:type" ["schema:Test"]}]}
               (-> multi-query-res :body json/read-value)))))
    (testing "history"
      (let [history-req (async/<!! (cred/generate {"ledger" ledger-name
                                                   "query"  {"history" "ex:cred-test"
                                                             "t"       {"from" 1}}}
                                                  (:private test-utils/auth)))

            history-res (test-utils/post :history {:body    (json/write-value-as-string history-req)
                                                   :headers test-utils/json-headers})]
        (is (= 200 (:status history-res)))
        (is (= [{"f:retract" [],
                 "f:assert"
                 [{"ex:name" "cred test",
                   "ex:foo" 1,
                   "id" "ex:cred-test",
                   "rdf:type" ["schema:Test"]}],
                 "f:t" 2}]
               (-> history-res :body json/read-value)))))

    (testing "invalid credential"
      (let [invalid-tx  (-> (async/<!! (cred/generate
                                         {"@context" {"ledger" "http://flur.ee/ns/ledger"
                                                      "txn" "http://flur.ee/ns/txn"}
                                          "ledger" "credential-test"
                                          "txn"    {"@id" "ex:cred-test"
                                                    "ex:KEY" "VALUE"}}
                                         (:private test-utils/auth)))
                            (assoc-in ["credentialSubject" "txn" "ex:KEY"] "ALTEREDVALUE"))

            invalid-res (test-utils/post :transact {:body    (json/write-value-as-string invalid-tx)
                                                    :headers test-utils/json-headers})]
        (is (= 400 (:status invalid-res)))
        (is (= {"error" "Invalid credential"}
               (-> invalid-res :body json/read-value)))))))