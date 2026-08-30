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
