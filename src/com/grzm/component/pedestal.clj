(ns com.grzm.component.pedestal
  "Connection between the Component framework and the Pedestal web
  application server."
  (:require [com.stuartsierra.component :as component]
            [io.pedestal.http :as http]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :refer [interceptor]]))

(def pedestal-component-key ::pedestal-component)
(def component-key ::component)

(defn insert-context-interceptor
  "Returns an interceptor which associates key with value in the
   Pedestal context map."
  [key value]
  (interceptor {:name  ::insert-context
                :enter (fn [context] (assoc context key value))}))

(defn add-component-interceptor
  "Adds an interceptor to the pedestal-config map which associates the
   pedestal-component into the Pedestal context map. Must be called before
   io.pedestal.http/create-server."
  [pedestal-config pedestal-component]
  (update pedestal-config
          ::http/interceptors
          conj (insert-context-interceptor
                 pedestal-component-key pedestal-component)))

(defrecord Pedestal [config-fn server]
  component/Lifecycle
  (start [this]
    (if server
      this
      (assoc this :server
             (-> (config-fn)
                 (add-component-interceptor this)
                 http/create-server
                 http/start))))
  (stop [this]
    (when server
      (http/stop server)
      (assoc this :server nil))))

(defn pedestal [config-fn]
  "Returns a new instance of the Pedestal server component.
   pedestal-config-fn is a no-argument function which returns the
   Pedestal server configuration map, which will be passed to
   io.pedestal.http/create-server. If you want the default
   interceptors, you must call io.pedestal.http/default-interceptors
   in pedestal-config-fn.

   The Pedestal component should have dependencies (as by
   com.stuartsierra.component/using or system-using) on all components
   needed by your web application. These dependencies will be available
   in the Pedestal context map via 'context-component'."
  (->Pedestal config-fn nil))

(defrecord PedestalServlet [config-fn server]
  component/Lifecycle
  (start [this]
    (if server
      this
      (assoc this :server
             (-> (config-fn)
                 (add-component-interceptor this)
                 http/create-servlet))))
  (stop [this]
    (when server
      (assoc this :server nil))))

(defn pedestal-servlet [config-fn]
  (map->PedestalServlet {:config-fn config-fn}))

(defn- get-pedestal
  [context]
  (let [pedestal (get context pedestal-component-key ::not-found)]
    (when (nil? pedestal)
      (throw (ex-info (str "Pedestal component was nil in the context map; "
                           "component.pedestal is not configured correctly")
                      {:reason  ::nil-pedestal
                       :context context})))
    (when (= ::not-found pedestal)
      (throw (ex-info (str "Pedestal component was missing from context map; "
                           "component.pedestal is not configured correctly")
                      {:reason  ::missing-pedestal
                       :context context})))
    pedestal))

(defn get-component
  "Returns the component at key from the Pedestal context map. key
  must have been a declared dependency of the Pedestal server component."
  [context key]
  (let [component (-> context get-pedestal (get key ::not-found))]
    (when (nil? component)
      (throw (ex-info (str "Component " key " was nil in the Pedestal dependencies; "
                           "maybe it returned nil from start or stop")
                      {:reason         ::nil-component
                       :dependency-key key
                       :context        context})))
    component))

(defn using-component
  "Returns an interceptor which associates the component named key
   into the Ring-style request map as :component. The key must have been
   declared a dependency of the Pedestal server component.

   You can add this interceptor to your Pedestal routes to make the
   component available to your Ring-style handler functions, which can
   get :component from the request map."
  [key]
  (interceptor
    {:name  ::using-component
     :enter (fn [context]
              (assoc-in context [:request component-key key]
                        (get-component context key)))}))

(defn use-component
  "Returns the component added to the request map by 'using-component"
  [request key]
  (let [component (get-in request [component-key key])]
    (if (nil? component)
      (throw (ex-info (str "Component " key " was nil in the request map; "
                           "key must be declared as a depedency via `using-component`")
                      {:reason         ::nil-component
                       :dependency-key key
                       :request        request}))
      component)))

(def strip-component
  "Interceptor to remove the pedestal component"
  (interceptor
    {:leave (fn [context]
              (-> context
                  (update-in [:request] #(dissoc % component-key))
                  (dissoc pedestal-component-key)))}))
