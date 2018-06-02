# Pedestal Service wrapper for Component Systems

The Pedestal Component library provides a convenient way to use Pedestal
in [Component][component] systems. Besides Pedestal participating as a normal
component in the system, other components can be made available to interceptors
through the Pedestal context.

Pedestal Component also provides test helpers for starting and stopping the
system.

## Releases and Dependency Information

Releases are on [Clojars](https://clojars.org/com.grzm/component.pedestal).

### Clojure [CLI/deps.edn][deps] coordinates:

```clojure
{com.grzm/component.pedestal {:mvn/version "0.1.7"}}
```

### [Leiningen][]/[Boot][] dependency information:

```clojure
[com.grzm/component.pedestal "0.1.7"]
```

### [Maven] dependency information:

```xml
<dependency>
  <groupId>com.grzm</groupId>
  <artifactId>component.pedestal</artifactId>
  <version>0.1.7</version>
</dependency>
```

[deps]: https://clojure.org/reference/deps_and_cli
[Leiningen]: http://leiningen.org/
[Boot]: http://boot-clj.com
[Maven]: http://maven.apache.org/

Please see [breaking changes](/changes.markdown#0.1.7) in 0.1.7.

## Usage

```clojure
(ns com.example.myapp
  (:require [com.grzm.component.pedestal :as cp]))
```

 * Define all the components which you intend to use from Pedestal into a fixed vector: `(def components-to-inject [:db :sms :worker])`

Pedestal Component provides a constructor that takes a single
0-arity function which returns the Pedestal configuration service map.
The service map is passed to `io.pedestal.http/create-server`.

 * Use said constructor, along with `components-to-inject` as dependencies:

```clojure
(component/using (cp/pedestal config-fn) components-to-inject) ;; => cp/Pedestal record
```

For injecting components from your system into the Pedestal request map:

 * Build and define an interceptor chain which will inject those components: `(def component-interceptors (cp/component-interceptors components-to-inject))`

 * Then, you can prepend said chain into your routes: ``["/api" :get (into component-interceptors [http/json-body `my-handler])]``

 * Now, you can get your components from the request map directly: `(defn my-handler [{:keys [db sms] :as request}] ...)`

 * Optionally, you can use namespace-qualified keys instead of plain `:db`, etc.

## Legacy API

 * `(cp/using-component :component-key)` returns an interceptor which adds a
   component to the Pedestal context, making it available via the request map.
 * `(cp/use-component request :component-key)` returns the component from the
   request map.

## Complete example

```clojure
(ns com.example.myapp.server
  (:gen-class)
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.body-params :refer [body-params]]
            [io.pedestal.http.route :as route]
            [ring.util.response :as ring-resp]
            [com.stuartsierra.component :as component]
            [com.grzm.component.pedestal :as cp]
            [com.example.myapp.component.application :as app]
            [com.example.myapp.component.database :as db]))

(defn home-page []
  (ring-resp/response "Welcome!"))

(defn login-page
  [{:keys [form-params app db] :as request}]
  ;; now you can do something with `app` or `db`...
  (let [{:keys [username password]} form-params]
    (if-let [user (app/user-by-creds {:username username :password password})]
      (ring-resp/response (str "Welcome, " (:user/first-name user) "!"))
      (ring-resp/redirect (route/url-for :home-page)))))

(def components-to-inject [:db])

(def component-interceptors (cp/component-interceptors components-to-inject))

(def routes #{["/" :get (into component-interceptors [(body-params) `home-page])]
              ["/login" :post (into into component-interceptors [(body-params) `login-page])]})

(defn pedestal-config-fn "Return Pedestal service map" []
 ;; routes and other interesting bits
)

(def config {:pedestal {:config-fn pedestal-config-fn}
             :db {:conn-uri "datomic:dev://localhost:4334/myapp"}})

(defn system [{:keys [pedestal db]}]
  (component/system-map
    :db (db/database (:conn-uri db))
    :app (component/using (app/application) components-to-inject)
    :pedestal (component/using (cp/pedestal (:config-fn pedestal))
                 (conj components-to-inject :app))))

(defn start-system [sys]
  (component/start sys)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. #(component/stop sys))))

(defn -main [& args]
  (start-system (system config)))

```

## Testing

```clojure
(ns com.example.yourapp.test
  (:require [com.grzm.component.pedestal.test.alpha :as cpt]))
```

There are two functions and a macro that are useful for testing:

 * The `cpt/with-system` macro wraps a test body.
   It handles starting the system, catching any exception that might
   be thrown during the running of the tests, and stopping the system.
   You provide it with the system variable and an system initialization
   function: `(cpt/with-system #'system init-fn ...)`
 * The `cpt/service` function takes the system and
   returns its service function, suitable for passing to the Pedestal
   `response-for` testing function.
 * The `cpt/ring-handler` function takes the system
   and returns a Ring handler suitable for using with Nelson Morris'
   [Peridot][peridot] library. This is very useful for making multiple
   requests where you need to maintain state between requests.

There's also a `PedestalServlet` component to use for testing in lieu of the
Pedestal component used in production.

```clojure
(ns com.example.myapp.handler-test
   (:require [clojure.test :refer :all]
             [io.pedestal.test :refer :all]
             [com.grzm.component.pedestal.test.alpha :as cpt]
             [com.grzm.component.pedestal :as cp]
             [com.example.myapp.component.application :as app]
             [com.example.myapp.component.database :as db]             
             [peridot.core :as p]))

(def system nil)
(def test-config
  {:pedestal {:config-fn pedestal-config-fn}
   :db {:conn-uri "datomic:dev://localhost:4334/myapp-test"}})

(defn test-system
  "Set up using PedestalServlet component instead of Pedestal"
  [{:keys [pedestal db]}]
  (component/system-map
    :db (db/database (:conn-uri db))
    :app (component/using (app/application) [:db]
    :pedestal (component/using (cp/pedestal-servlet (:config-fn pedestal))
                 [:app])))

(defn init-fn []
  (constantly (test-system test-config)))

(deftest test-home-page
  "Use the Pedestal `response-for` function for testing simple requests"
  (cpt/with-system #'system init-fn
    (let [response (response-for (cpt/service system) :get "/")
          {:keys [body status headers]} response]
       (is (= status 200))
       (is (= "text/html" (get headers "Content-Type")))
       (is (.contains body "Welcome!")))))

(deftest test-failed-login
  "Use Nelson Morris' Peridot library for maintaining state between requests."
  (cpt/with-system #'system init-fn
    (-> (p/session (cpt/ring-handler system))
        (p/request "/login" :request-method :post
                   :params {:username "no-such-user@example.com"
                            :password "none-shall-pass!"})
        (doto ((fn [{:keys [response]}]
                 (let [{:keys [body status headers]} response]
                    (is (= 302 status))
                    (is (= "/" (get headers "Location")))))))
        (p/follow-redirect)
        ;; ...
        )))
```     

## Thanks!

Thanks to [Stuart Sierra][stuart-github] for [Component][component] and the first
draft of [component.pedestal][], and to [Gijs Stuurman][gijs-github] for his use
of Component with Pedestal in his [w3a][] library and allowing me to include his
code to wrap Pedestal service-fn in a Ring handler. Great stuff!

[stuart-github]: https://github.com/stuartsierra
[component]: https://github.com/stuartsierra/component
[component.pedestal]: https://github.com/stuartsierra/component.pedestal
[gijs-github]: https://github.com/thegeez
[w3a]: https://github.com/thegeez/w3a
[peridot]: https://github.com/xeqi/peridot

## Copyright and License

The MIT License (MIT)

© 2016–2018 Michael Glaesemann

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
