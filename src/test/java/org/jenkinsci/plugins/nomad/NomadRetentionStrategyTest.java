package org.jenkinsci.plugins.nomad;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * A freshly provisioned worker is offline until its agent dials in, and an offline computer counts
 * as idle. With a short idle timeout the retention check could therefore terminate a worker while
 * it was still booting: observed in production as a deregistration three seconds after scheduling.
 * The boot phase is bounded by the cloud's worker timeout instead, so the retention strategies must
 * not act on a worker that has never been online.
 */
@WithJenkins
class NomadRetentionStrategyTest {

    private static final String CLOUD = "nomad";

    private JenkinsRule j;
    private NomadApi nomadApi;

    @BeforeEach
    void registerCloudWithMockedApi(JenkinsRule rule) {
        j = rule;
        NomadCloud cloud = new NomadCloud(CLOUD, "http://nomad:4646", false, null, null, null, null,
                5, "", false, Collections.emptyList());
        nomadApi = mock(NomadApi.class);
        cloud.setNomad(nomadApi);
        j.jenkins.clouds.add(cloud);
    }

    @Test
    void bootingSingleUseWorkerIsNotTerminatedByZeroIdleTimeout() throws Exception {
        assertBootingWorkerSurvivesCheck(false);
    }

    @Test
    void bootingReusableWorkerIsNotTerminatedByZeroIdleTimeout() throws Exception {
        assertBootingWorkerSurvivesCheck(true);
    }

    @Test
    void connectedIdleWorkerIsStillTerminated() throws Exception {
        // GIVEN a worker that HAS connected once and is now idle past a zero timeout
        NomadWorker worker = createWorker("connected-once", true);
        NomadComputer computer = (NomadComputer) worker.toComputer();
        computer.markOnline();
        // Core's check is `idleMilliseconds > 0`, and idle-start has millisecond granularity, so
        // at least one millisecond must pass or the comparison is 0 > 0 and nothing happens.
        Thread.sleep(10);

        // WHEN
        worker.getRetentionStrategy().check(computer);

        // THEN the guard does not get in the way of normal reaping
        verify(nomadApi).stopWorker(anyString(), any(), any());
        assertThat(j.jenkins.getNode("connected-once"), is(nullValue()));
    }

    @SuppressWarnings("unchecked")
    private void assertBootingWorkerSurvivesCheck(boolean reusable) throws Exception {
        // GIVEN a just-provisioned worker whose agent has not connected yet
        String name = "booting-" + (reusable ? "reusable" : "single-use");
        NomadWorker worker = createWorker(name, reusable);
        NomadComputer computer = (NomadComputer) worker.toComputer();
        assertThat(computer.isOnline(), is(false));
        assertThat(computer.hasEverBeenOnline(), is(false));

        // WHEN the retention check runs, as it would on the next ComputerRetentionWork tick
        worker.getRetentionStrategy().check(computer);

        // THEN it is left alone: nothing was deregistered and the node still exists
        verify(nomadApi, never()).stopWorker(anyString(), any(), any());
        assertThat(j.jenkins.getNode(name), is(notNullValue()));
    }

    private NomadWorker createWorker(String name, boolean reusable) throws Exception {
        // idleTerminationInMinutes = 0: the configuration that exposes the race
        NomadWorker worker = new NomadWorker(name, CLOUD, "label", 1, 0, reusable, "/tmp");
        j.jenkins.addNode(worker);
        return worker;
    }
}
