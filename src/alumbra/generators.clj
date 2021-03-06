(ns alumbra.generators
  (:require [alumbra.generators
             [directive :refer [directive-generators]]
             [selection-set :refer [selection-set-generators]]
             [value :refer [value-generators]]]
            [alumbra.generators.raw
             [common :refer [maybe -name]]
             [document :refer [-document]]
             [schema :refer [-schema]]]
            [clojure.test.check
             [generators :as gen]]
            [stateful.core :as stateful]
            [clojure.string :as string]))

;; ## TODO
;;
;; - Variables

;; ## Helpers

(defn- make-operation-gen
  [{:keys [schema selection-set-gen]} k]
  (when-let [t (get-in schema [:schema-root :schema-root-types (name k)])]
    (stateful/generator
      (selection-set-gen t))))

;; ## Public Functions

;; ### Valid Data

(defmacro ^:private prepare->
  [opts & pairs]
  (if (seq pairs)
    (let [[k [f & args] & rst] pairs]
      `(let [opts#   ~opts
             result# (~f opts# ~@args)]
         (prepare->
           (assoc opts# ~k result#)
           ~@rst)))
    opts))

(defn operation
  "Create a function that can be called with an operation type (e.g.
   :query, :mutation, :subscription) and, optionally, the desired name
   of the operation, and will produce an operation matching the given
   analyzed schema.

   ```clojure
   (def schema
     (alumbra.analyzer/analyze-schema
       \"type Person { ... } ... \"
       alumbra.parser/parse-schema))

   (def gen-operation
     (operation schema))

   (gen/sample (gen-operation :query))
   (gen/sample (gen-operation :query \"MyQueryName\"))
   ```

   `schema` must conform to `:alumbra/analyzed-schema` (see alumbra.spec)."
  [schema & [opts]]
  (let [opts (prepare->
               (assoc opts :schema schema)
               :value-gen         (value-generators)
               :directive-gen     (directive-generators)
               :selection-set-gen (selection-set-generators))
        type->gen {:query        (make-operation-gen opts :query)
                   :mutation     (make-operation-gen opts :mutation)
                   :subscription (make-operation-gen opts :subscription)}]
    (fn [k & [operation-name]]
      (if-let [gen (type->gen k)]
        (if operation-name
          (gen/fmap
            #(str (name k) " " (name operation-name) " " %)
            gen)
          (gen/let [t (if (= k :query)
                        (maybe (gen/return (name k)))
                        (gen/return (name k)))
                    n (maybe -name)
                    [s state] gen]
            (->> (vector
                   (if t
                     (str t " " (some-> n (str " ")) s)
                     s)
                   (some->> (:fragments state)
                            (string/join " ")))
                 (filter identity)
                 (string/join " "))))
        (throw
          (IllegalArgumentException.
            (str "no generator for operation type: " k)))))))

;; ### Random Data

(defn raw-document
  "Create a generator for a random (i.e. not semantically sound) GraphQL
   query document."
  []
  -document)

(defn raw-schema
  "Create a generator for a random (i.e. not semantically sound) GraphQL
   schema document."
  []
  -schema)

;; ## Example

(comment
  (require '[alumbra [parser :as parser] [analyzer :as analyzer]])
  (def schema
    (-> "type Person { name: String!, pets: [Pet!] }
         type Pet { name: String!, meows: Boolean }
         union PersonOrPet = Person | Pet
         enum PositionKind { LONG, LAT }
         input Position { x: Int, y: Int, k: PositionKind! }
         type QueryRoot { person(name: String!): Person, random(seed: Position!): PersonOrPet }
         type MutationRoot { createPerson(name: String!): Person! }
         schema { query: QueryRoot, mutation: MutationRoot }"
        (analyzer/analyze-schema parser/parse-schema)))

  (def gen (operation schema))

  (gen/generate (gen :query) 1))
