(ns fluree.http-api.integration.policy-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [fluree.http-api.integration.test-system :refer :all]
            [jsonista.core :as json]))

(use-fixtures :once run-test-server)

(deftest ^:integration ^:json policy-opts-json-test
  (testing "policy-enforcing opts are correctly handled"
    (let [ledger-name  (create-rand-ledger "policy-opts-test")
          json-headers {"Content-Type" "application/json"
                        "Accept"       "application/json"}
          alice-did    "did:fluree:Tf6i5oh2ssYNRpxxUM2zea1Yo7x4uRqyTeU"
          txn-req      {:body
                        (json/write-value-as-string
                         {:ledger ledger-name
                          :txn    [{"id"        "ex:alice"
                                    "type"      "ex:User"
                                    "ex:secret" "alice's secret"}
                                   {"id"        "ex:bob"
                                    "type"      "ex:User"
                                    "ex:secret" "bob's secret"}
                                   {"id"            "ex:UserPolicy"
                                    "type"          ["f:Policy"]
                                    "f:targetClass" {"id" "ex:User"}
                                    "f:allow"
                                    [{"id"           "ex:globalViewAllow"
                                      "f:targetRole" {"id" "ex:userRole"}
                                      "f:action"     [{"id" "f:view"}]}]
                                    "f:property"
                                    [{"f:path" {"id" "ex:secret"}
                                      "f:allow"
                                      [{"id"           "ex:secretsRule"
                                        "f:targetRole" {"id" "ex:userRole"}
                                        "f:action"     [{"id" "f:view"} {"id" "f:modify"}]
                                        "f:equals"     {"@list" [{"id" "f:$identity"} {"id" "ex:User"}]}}]}]}
                                   {"id"      alice-did
                                    "ex:User" {"id" "ex:alice"}
                                    "f:role"  {"id" "ex:userRole"}}]})
                        :headers json-headers}
          txn-res      (post :transact txn-req)
          _            (assert (= 200 (:status txn-res)))
          secret-query {"select" {"?s" ["*"]}
                        "where"  [["?s" "rdf:type" "ex:User"]]}

          query-req    {:body
                        (json/write-value-as-string
                         {:ledger ledger-name
                          :query  (assoc secret-query
                                    :opts {"role" "ex:userRole"
                                           "did"  alice-did})})
                        :headers json-headers}
          query-res    (post :query query-req)]
      (is (= 200 (:status query-res))
          (str "policy-enforced query response was: " (pr-str query-res)))
      (is (= [{"id" "ex:bob", "rdf:type" ["ex:User"]}
              {"id"        "ex:alice",
               "rdf:type"  ["ex:User"],
               "ex:secret" "alice's secret"}]
             (-> query-res :body json/read-value))
          "query policy opts should prevent seeing bob's secret")
      (let [txn-req   {:body
                       (json/write-value-as-string
                        {:ledger ledger-name
                         :txn    [{"id"        "ex:alice"
                                   "ex:secret" "alice's NEW secret"}]
                         :opts   {"role" "ex:userRole"
                                  "did"  alice-did}})
                       :headers json-headers}
            txn-res   (post :transact txn-req)
            _         (assert (= 200 (:status txn-res)))
            query-req {:body
                       (json/write-value-as-string
                        {:ledger ledger-name
                         :query  secret-query})
                       :headers json-headers}
            query-res (post :query query-req)
            _         (assert (= 200 (:status query-res)))]
        (is (= [{"id"        "ex:bob",
                 "rdf:type"  ["ex:User"],
                 "ex:secret" "bob's secret"}
                {"id"        "ex:alice",
                 "rdf:type"  ["ex:User"],
                 "ex:secret" "alice's NEW secret"}]
               (-> query-res :body json/read-value))
            "alice's secret should be modified")
        (let [txn-req {:body
                       (json/write-value-as-string
                        {"ledger" ledger-name
                         "txn"    [{"id"        "ex:bob"
                                    "ex:secret" "bob's new secret"}]
                         "opts"   {"role" "ex:userRole"
                                   "did"  alice-did}})
                       :headers json-headers}
              txn-res (post :transact txn-req)]
          (is (not= 200 (:status txn-res))
              (str "transaction policy opts should have prevented modification, instead response was: " (pr-str txn-res)))
          (let [query-req {:body
                           (json/write-value-as-string
                            {"ledger" ledger-name
                             "query"  {"history" "ex:bob"
                                       "t"       {"from" 1}
                                       "opts"    {"role" "ex:userRole"
                                                  "did"  alice-did}}})
                           :headers json-headers}
                query-res (post :history query-req)]
            (is (= 200 (:status query-res))
                (str "History query response was: " (pr-str query-res)))
            (is (= [{"id" "ex:bob", "rdf:type" ["ex:User"]}]
                   (-> query-res :body json/read-value first (get "f:assert")))
                "policy opts should have prevented seeing bob's secret")))))))

(deftest ^:integration ^:edn policy-opts-edn-test
  (testing "policy-enforcing opts are correctly handled"
    (let [ledger-name  (create-rand-ledger "policy-opts-test")
          edn-headers  {"Content-Type" "application/edn"
                        "Accept"       "application/edn"}
          alice-did    "did:fluree:Tf6i5oh2ssYNRpxxUM2zea1Yo7x4uRqyTeU"
          txn-req      {:body
                        (pr-str
                         {:ledger ledger-name
                          :txn    [{:id        :ex/alice,
                                    :type      :ex/User,
                                    :ex/secret "alice's secret"}
                                   {:id        :ex/bob,
                                    :type      :ex/User,
                                    :ex/secret "bob's secret"}
                                   {:id            :ex/UserPolicy,
                                    :type          [:f/Policy],
                                    :f/targetClass :ex/User
                                    :f/allow       [{:id           :ex/globalViewAllow
                                                     :f/targetRole :ex/userRole
                                                     :f/action     [:f/view]}]
                                    :f/property    [{:f/path  :ex/secret
                                                     :f/allow [{:id           :ex/secretsRule
                                                                :f/targetRole :ex/userRole
                                                                :f/action     [:f/view :f/modify]
                                                                :f/equals     {:list [:f/$identity :ex/User]}}]}]}
                                   {:id      alice-did
                                    :ex/User :ex/alice
                                    :f/role  :ex/userRole}]})
                        :headers edn-headers}
          txn-res      (post :transact txn-req)
          _            (assert (= 200 (:status txn-res)))
          secret-query '{:select {?s [:*]}
                         :where  [[?s :rdf/type :ex/User]]}

          query-req    {:body
                        (pr-str
                         {:ledger ledger-name
                          :query  (assoc secret-query
                                    :opts {:role :ex/userRole
                                           :did  alice-did})})
                        :headers edn-headers}
          query-res    (post :query query-req)]
      (is (= 200 (:status query-res))
          (str "policy-enforced query response was: " (pr-str query-res)))
      (is (= [{:id       :ex/bob
               :rdf/type [:ex/User]}
              {:id        :ex/alice
               :rdf/type  [:ex/User]
               :ex/secret "alice's secret"}]
             (-> query-res :body edn/read-string))
          "query policy opts should prevent seeing bob's secret")
      (let [txn-req   {:body
                       (pr-str
                        {:ledger ledger-name
                         :txn    [{:id        :ex/alice
                                   :ex/secret "alice's NEW secret"}]
                         :opts   {:role :ex/userRole
                                  :did  alice-did}})
                       :headers edn-headers}
            txn-res   (post :transact txn-req)
            _         (assert (= 200 (:status txn-res)))
            query-req {:body
                       (pr-str
                        {:ledger ledger-name
                         :query  secret-query})
                       :headers edn-headers}
            query-res (post :query query-req)
            _         (assert (= 200 (:status query-res)))]
        (is (= [{:id        :ex/bob
                 :rdf/type  [:ex/User]
                 :ex/secret "bob's secret"}
                {:id        :ex/alice
                 :rdf/type  [:ex/User]
                 :ex/secret "alice's NEW secret"}]
               (-> query-res :body edn/read-string))
            "alice's secret should be modified")
        (let [txn-req {:body
                       (pr-str
                        {:ledger ledger-name
                         :txn    [{:id        :ex/bob
                                   :ex/secret "bob's NEW secret"}]
                         :opts   {:role :ex/userRole
                                  :did  alice-did}})
                       :headers edn-headers}
              txn-res (post :transact txn-req)]
          (is (not= 200 (:status txn-res))
              (str "transaction policy opts should have prevented modification, instead response was:" (pr-str txn-res)))
          (let [query-req {:body
                           (pr-str
                            {:ledger ledger-name
                             :query  {:history :ex/bob
                                      :t       {:from 1}
                                      :opts    {:role :ex/userRole
                                                :did  alice-did}}})
                           :headers edn-headers}
                query-res (post :history query-req)]
            (is (= 200 (:status query-res))
                (str "History query response was: " (pr-str query-res)))
            (is (= [{:id :ex/bob :rdf/type [:ex/User]}]
                   (-> query-res :body edn/read-string first (get :f/assert)))
                "policy opts should have prevented seeing bob's secret")))))))
