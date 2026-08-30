package org.jenkinsci.plugins.nomad;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.net.URISyntaxException;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import hudson.util.FormValidation;
import hudson.util.Secret;

/**
 * Checks that the TLS support is working as expected.
 */
@WithJenkins
@ExtendWith(MockitoExtension.class)
class NomadApiTLSTest {

    /**
     * Keystore: contains the public and private keys (e.g. KEYSTORE_CLIENT_A contains key pair of client a)
     */
    private static final String KEYSTORE_CLIENT_A = loadResource("/tls/client_a.p12");
    private static final String KEYSTORE_SERVER_A = loadResource("/tls/server_a.p12");
    private static final String KEYSTORE_SERVER_B = loadResource("/tls/server_b.p12");

    /**
     * Truststore: contains the public keys (e.g. TRUSTSTORE_CLIENT_A trusts client a but not client b)
     */
    private static final String TRUSTSTORE_CLIENT_A = loadResource("/tls/truststore_client_a.p12");
    private static final String TRUSTSTORE_SERVER_A = loadResource("/tls/truststore_server_a.p12");
    private static final String TRUSTSTORE_CLIENT_B = loadResource("/tls/truststore_client_b.p12");
    private static final String TRUSTSTORE_SERVER_B = loadResource("/tls/truststore_server_b.p12");

    private static Secret PASSWORD;

    @Mock
    private NomadCloud nomadCloud;

    @InjectMocks
    private NomadApi nomadApi;

    private WireMockServer wireMockServer;

    /**
     * Since we are using self-signed certificates, we have to emulate that our server certificate (server_a.p12) is issued by one of the
     * default CA's. {@link Secret#fromString} needs a running Jenkins, hence the {@link JenkinsRule} parameter.
     */
    @BeforeEach
    void setDefaultTrustStore(JenkinsRule j) {
        PASSWORD = Secret.fromString("changeit");
        System.setProperty("javax.net.ssl.trustStore", TRUSTSTORE_SERVER_A);
        System.setProperty("javax.net.ssl.trustStorePassword", PASSWORD.getPlainText());
    }

    @AfterEach
    void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testServerIsTrustworthyWithDefaultCA() {
        // GIVEN
        startWiremock(KEYSTORE_SERVER_A, null, false);
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockServer.baseUrl());
        when(nomadCloud.isTlsEnabled()).thenReturn(true);

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.OK));
        assertThat(response.getMessage(), is("Nomad API request succeeded."));
    }

    @Test
    void testServerIsNotTrustworthyWithDefaultCA() {
        // GIVEN
        startWiremock(KEYSTORE_SERVER_B, null, false);
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockServer.baseUrl());
        when(nomadCloud.isTlsEnabled()).thenReturn(true);

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.ERROR));
        assertThat(response.getMessage(), not(emptyString()));
    }

    @Test
    void testServerIsTrustworthyWithCustomCA() {
        // GIVEN
        startWiremock(KEYSTORE_SERVER_B, null, false);
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockServer.baseUrl());
        when(nomadCloud.isTlsEnabled()).thenReturn(true);
        when(nomadCloud.getServerCertificate()).thenReturn(TRUSTSTORE_SERVER_B);
        when(nomadCloud.getServerPassword()).thenReturn(PASSWORD);

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.OK));
        assertThat(response.getMessage(), is("Nomad API request succeeded."));
    }

    @Test
    void testClientIsTrustworthyWithDefaultCA() {
        // GIVEN
        startWiremock(KEYSTORE_SERVER_A, TRUSTSTORE_CLIENT_A, true);
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockServer.baseUrl());
        when(nomadCloud.isTlsEnabled()).thenReturn(true);
        when(nomadCloud.getClientCertificate()).thenReturn(KEYSTORE_CLIENT_A);
        when(nomadCloud.getClientPassword()).thenReturn(PASSWORD);

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.OK));
        assertThat(response.getMessage(), is("Nomad API request succeeded."));
    }

    @Test
    void testClientIsNotTrustworthyWithDefaultCA() {
        // GIVEN
        startWiremock(KEYSTORE_SERVER_A, TRUSTSTORE_CLIENT_B, true);
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockServer.baseUrl());
        when(nomadCloud.isTlsEnabled()).thenReturn(true);
        when(nomadCloud.getClientCertificate()).thenReturn(KEYSTORE_CLIENT_A);
        when(nomadCloud.getClientPassword()).thenReturn(PASSWORD);

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.ERROR));
        assertThat(response.getMessage(), not(emptyString()));
    }

    @Test
    void testClientIsTrustworthyWithCustomCA() {
        // GIVEN
        startWiremock(KEYSTORE_SERVER_B, TRUSTSTORE_CLIENT_A, true);
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockServer.baseUrl());
        when(nomadCloud.isTlsEnabled()).thenReturn(true);
        when(nomadCloud.getClientCertificate()).thenReturn(KEYSTORE_CLIENT_A);
        when(nomadCloud.getClientPassword()).thenReturn(PASSWORD);
        when(nomadCloud.getServerCertificate()).thenReturn(TRUSTSTORE_SERVER_B);
        when(nomadCloud.getServerPassword()).thenReturn(PASSWORD);

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.OK));
        assertThat(response.getMessage(), is("Nomad API request succeeded."));
    }

    @Test
    void testClientIsNotTrustworthyWithCustomCA() {
        // GIVEN
        startWiremock(KEYSTORE_SERVER_B, TRUSTSTORE_CLIENT_B, true);
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockServer.baseUrl());
        when(nomadCloud.isTlsEnabled()).thenReturn(true);
        when(nomadCloud.getClientCertificate()).thenReturn(KEYSTORE_CLIENT_A);
        when(nomadCloud.getClientPassword()).thenReturn(PASSWORD);
        when(nomadCloud.getServerCertificate()).thenReturn(TRUSTSTORE_SERVER_B);
        when(nomadCloud.getServerPassword()).thenReturn(PASSWORD);

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.ERROR));
        assertThat(response.getMessage(), not(emptyString()));
    }

    private void startWiremock(String keystore, String truststore, boolean clientAuth) {
        WireMockConfiguration config = wireMockConfig()
                .port(0)
                .httpsPort(0)
                .needClientAuth(clientAuth);

        if (keystore != null) {
            config.keystorePath(keystore)
                    .keystoreType("PKCS12")
                    .keystorePassword(PASSWORD.getPlainText())
                    .keyManagerPassword(PASSWORD.getPlainText());
        }

        if (truststore != null) {
            config.trustStorePath(truststore)
                    .trustStoreType("PKCS12")
                    .trustStorePassword(PASSWORD.getPlainText());
        }

        wireMockServer = new WireMockServer(config);
        wireMockServer.start();
        wireMockServer.stubFor(get(anyUrl()).willReturn(ok()));
    }

    private static String loadResource(String resource) {
        try {
            return Paths.get(NomadApiTLSTest.class.getResource(resource).toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }


}