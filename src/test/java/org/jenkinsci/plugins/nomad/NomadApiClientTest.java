package org.jenkinsci.plugins.nomad;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import hudson.util.FormValidation;

/**
 * Checks that the NomadApi is working as expected especially how the client behaves when the Nomad is not available or the configuration
 * is correct.
 */
@ExtendWith(MockitoExtension.class)
public class NomadApiClientTest {

    @RegisterExtension
    static final WireMockExtension wireMockRule = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .configureStaticDsl(true)
            .build();

    @Mock
    NomadCloud nomadCloud;

    @InjectMocks
    NomadApi nomadApi;

    private static Object getFieldValue(NomadApi nomadApi, String name) {
        try {
            Field field = NomadApi.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(nomadApi);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object invoke(NomadApi nomadApi, String name) {
        {
            try {
                Method method = NomadApi.class.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(nomadApi);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Checks that only one client instance exists at a time even when multiple threads requesting the client in parallel.
     */
    @Test
    void testClientIsThreadSafe() {

        // WHEN
        int count = IntStream.range(0, 100)
                .parallel()
                .mapToObj(i -> invoke(nomadApi, "client"))
                .collect(Collectors.toSet()).size();

        // THEN
        assertThat(count, is(1));
        assertThat(getFieldValue(nomadApi, "client"), notNullValue());
    }

    @Test
    void testCheckConnectionSuccessful() {
        // GIVEN
        stubFor(get(urlEqualTo("/v1/agent/self"))
                .willReturn(ok("{}")));
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockRule.baseUrl());

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.OK));
        assertThat(response.getMessage(), is("Nomad API request succeeded."));
        assertThat(getFieldValue(nomadApi, "client"), notNullValue());
    }

    @Test
    void testCheckConnectionUnauthorized() {
        // GIVEN
        stubFor(get(urlEqualTo("/v1/agent/self"))
                .willReturn(aResponse()
                        .withStatus(401)));
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockRule.baseUrl());

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.ERROR));
        assertThat(response.getMessage(), startsWith("Response{protocol=http/1.1, code=401, message=Unauthorized"));
        assertThat(getFieldValue(nomadApi, "client"), nullValue());
    }

    @Test
    void testCheckConnectionForbidden() {
        // GIVEN
        stubFor(get(urlEqualTo("/v1/agent/self"))
                .willReturn(aResponse()
                        .withStatus(403)));
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockRule.baseUrl());

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.ERROR));
        assertThat(response.getMessage(), startsWith("Response{protocol=http/1.1, code=403, message=Forbidden"));
        assertThat(getFieldValue(nomadApi, "client"), nullValue());
    }

    @Test
    void testCheckConnectionNotFound() {
        // GIVEN
        stubFor(get(urlEqualTo("/v1/agent/self"))
                .willReturn(aResponse()
                        .withHeader("Content-Length", "0")
                        .withStatus(404)));
        when(nomadCloud.getNomadUrl()).thenReturn(wireMockRule.baseUrl());

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.ERROR));
        assertThat(response.getMessage(), startsWith("Response{protocol=http/1.1, code=404, message=Not Found"));
        assertThat(getFieldValue(nomadApi, "client"), notNullValue());
    }

    @Test
    void testCheckConnectionUnknownHost() {
        // GIVEN
        when(nomadCloud.getNomadUrl()).thenReturn("http://" + UUID.randomUUID());

        // WHEN
        FormValidation response = nomadApi.checkConnection();

        // THEN
        assertThat(response.kind, is(FormValidation.Kind.ERROR));
        assertThat(response.getMessage(), not(emptyString()));
        assertThat(getFieldValue(nomadApi, "client"), nullValue());
    }

}