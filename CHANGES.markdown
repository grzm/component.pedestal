# Changes

## 0.1.7

### Breaking changes

* Domain-qualify artifact and namespaces with com.grzm.
  This is in keeping with normal Java practice.
* Move the test API into `c.g.c.p.test.alpha`.  Recognized the API is not
  stable, in particular the `with-system` helper macro and how to pass
  init and teardown functions. Once this is stable, it'll move to
  `c.g.c.p.test`.

### Other changes

* Fix extraction of CSRF token.
* Add `c.g.c.p/strip-component` to remove pedestal component from context.

## 0.0.1-SNAPSHOT

Initial release.
