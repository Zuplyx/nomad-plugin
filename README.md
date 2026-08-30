Jenkins Nomad Cloud Plugin
==========================

This plugin uses HashiCorp's [Nomad scheduler](https://www.nomadproject.io/) to 
provision new build workers based on workload.

Documentation is available on the [plugin site](https://plugins.jenkins.io/nomad/).

**Community contributions are very welcome!**

## Requirements

* Jenkins 2.541.3 or newer.
* Java 17 or 21.
* A Nomad cluster reachable from the Jenkins controller.

## TLS Support

To connect to a TLS-enabled Nomad cluster:

* Configure the *Nomad URL* field with a HTTPS URL, for example: `https://nomad.service.consul:4646`
* Tick the *Enable TLS* checkbox
  - If the Nomad cluster authenticates clients, configure the path to the PKCS12
    certificate and, if needed, the password to access the PKCS12 certificate.

  - Specify a custom PKCS12 certificate to authenticate the Nomad cluster, if
    it can't be verified by the default truststore used by the Jenkins
    controller.

Note that, in each case, the certificates:

* Must be files reachable by the Jenkins controller.
* Must be in the [PKCS12 format](https://en.wikipedia.org/wiki/PKCS_12).

## About this fork

This is a fork of [jenkinsci/nomad-plugin](https://github.com/jenkinsci/nomad-plugin), which
has had no release since v0.10.0 (February 2023). It carries pull requests that are still
open upstream, with fixes applied on top, plus a modernised build.

It is not published to the Jenkins update centre. Build and install it yourself:

```
mvn clean verify
```

then upload `target/nomad.hpi` via *Manage Jenkins* > *Plugins* > *Advanced settings* >
*Deploy Plugin*.

### Upstream contributions included

| PR                                                       | Author | Change |
|----------------------------------------------------------| --- | --- |
| 170                                                      | Loïc Yavercovski | Fix orphaned-worker pruning: `SubmitTime` is nanoseconds, and the timeout was discarded |
| 196                                                      | Loïc Yavercovski | Maximum concurrent jobs per worker template  |
| Issue 185                                                | Loïc Yavercovski | Check Nomad capacity before provisioning, so other clouds get a turn |
| 213                                                      | Ryan Huddleston | Terminate single-use agents as soon as their build finishes |
| 214 | Tim Jacomb | Replace Commons Lang 2 with the Java Platform equivalents |

Defects found while integrating #196 and #185 were fixed here; see the commit history. If
this work is merged upstream, this section becomes redundant.

### Other changes in this fork

* Jenkins baseline raised to 2.541.3 (Java 17), current plugin parent POM and dependency BOM.
* Security fixes: okio (CVE-2023-3635) via OkHttp 4.12.0, and `org.json`
  (CVE-2023-5072, CVE-2026-59171).
* Tests migrated to JUnit 5 and WireMock 3.
