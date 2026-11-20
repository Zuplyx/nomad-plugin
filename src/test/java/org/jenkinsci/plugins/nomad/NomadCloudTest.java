package org.jenkinsci.plugins.nomad;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.nomad.Api.JobInfo;
import org.jenkinsci.plugins.nomad.Api.JobSummary;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@WithJenkins
class NomadCloudTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void testCanProvision() {
        // GIVEN
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(label.getName());
        NomadCloud cloud = createCloud(template);

        // WHEN
        boolean result = cloud.canProvision(label);

        // THEN
        assertThat(result, is(true));
    }

    @Test
    void testProvision() {
        // GIVEN
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(label.getName());
        NomadCloud cloud = createCloud(template);

        // WHEN
        Collection<NodeProvisioner.PlannedNode> result = cloud.provision(label, 3);

        // THEN
        assertThat(result.size(), is(3));
    }

    @Test
    void testGetTemplateWithLabels() {
        // GIVEN
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(label.getName());
        NomadCloud cloud = createCloud(template);
        
        // WHEN
        NomadWorkerTemplate result = cloud.getTemplate(label);

        // THEN
        assertThat(result, is(template));
    }

    @Test
    void testGetTemplateWithLabelsNull() {
        // GIVEN
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(null);
        NomadCloud cloud = createCloud(template);
        
        // WHEN
        NomadWorkerTemplate result = cloud.getTemplate(label);

        // THEN
        assertThat(result, nullValue());
    }

    @Test
    void testGetTemplateWithLabelsEmpty() {
        // GIVEN
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate("");
        NomadCloud cloud = createCloud(template);

        // WHEN
        NomadWorkerTemplate result = cloud.getTemplate(label);

        // THEN
        assertThat(result, nullValue());
    }

    @Test
    void testGetTemplateWithLabelNull() {
        // GIVEN
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(null);
        NomadCloud cloud = createCloud(template);

        // WHEN
        NomadWorkerTemplate result = cloud.getTemplate(null);

        // THEN
        assertThat(result, is(result));
    }

    /**
     * Regression test for the concurrency cap arithmetic. The original predicate subtracted
     * {@code created} from the allowance and then compared against {@code created} again, so a
     * limit of 4 stopped after 2. See PR #196.
     */
    @Test
    void testProvisionStopsExactlyAtConcurrencyLimit() {
        // GIVEN
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(label.getName());
        template.setMaxConcurrentJobs(4);
        NomadCloud cloud = createCloud(template);
        cloud.setNomad(createNomadApi(new JobInfo[0]));

        // WHEN
        Collection<NodeProvisioner.PlannedNode> result = cloud.provision(label, 10);

        // THEN
        assertThat(result.size(), is(4));
    }

    /**
     * Nomad keeps completed batch jobs until its own GC runs, so counting every job returned for
     * the prefix would let dead jobs fill the limit and stall provisioning entirely.
     */
    @Test
    void testDeadJobsDoNotCountTowardsConcurrencyLimit() {
        // GIVEN
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(label.getName());
        template.setMaxConcurrentJobs(3);
        NomadCloud cloud = createCloud(template);
        cloud.setNomad(createNomadApi(new JobInfo[]{
                createJobInfo("dead"), createJobInfo("dead"), createJobInfo("running")}));

        // WHEN
        Collection<NodeProvisioner.PlannedNode> result = cloud.provision(label, 10);

        // THEN - only the running job counts, so 2 of the 3 slots are still free
        assertThat(result.size(), is(2));
    }

    /**
     * A template loaded from a config.xml written before maxConcurrentJobs existed has no value for
     * it. That must mean "unlimited"; a primitive int would have deserialized to 0 and blocked all
     * provisioning on upgrade. See PR #196.
     */
    @Test
    void testTemplateWithoutConcurrencyLimitIsUnlimited() {
        // GIVEN
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(label.getName());
        NomadCloud cloud = createCloud(template);
        NomadApi nomadApi = createNomadApi(new JobInfo[0]);
        cloud.setNomad(nomadApi);

        // WHEN
        Collection<NodeProvisioner.PlannedNode> result = cloud.provision(label, 5);

        // THEN - and Nomad is never asked to count jobs, since there is no limit to enforce
        assertThat(result.size(), is(5));
        verify(nomadApi, never()).getRunningWorkers(anyString());
    }

    /**
     * The capacity pre-check is opt-in: upgrading must not start issuing a /plan request per
     * provisioned worker. See issue #185.
     */
    @Test
    void testCapacityPreCheckIsDisabledByDefault() {
        // GIVEN
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(label.getName());
        NomadCloud cloud = createCloud(template);
        NomadApi nomadApi = mock(NomadApi.class);
        cloud.setNomad(nomadApi);

        // THEN
        assertThat(cloud.isCheckCapacityBeforeProvisioning(), is(false));

        // WHEN
        cloud.provision(label, 2);

        // THEN
        verify(nomadApi, never()).checkAllocAvailability(template);
    }

    /**
     * The upgrade path that matters: XStream does not call the constructor, so a template whose
     * config.xml predates maxConcurrentJobs simply has no value for it. As a primitive int that
     * deserialized to 0, meaning "no workers allowed", and bricked provisioning. See PR #196.
     */
    @Test
    void testTemplateDeserializedWithoutTheFieldIsUnlimited() {
        // GIVEN a config.xml written before maxConcurrentJobs existed
        String xml = "<org.jenkinsci.plugins.nomad.NomadWorkerTemplate>"
                + "<prefix>jenkins</prefix>"
                + "<labels>linux</labels>"
                + "<idleTerminationInMinutes>10</idleTerminationInMinutes>"
                + "<reusable>true</reusable>"
                + "<numExecutors>1</numExecutors>"
                + "<remoteFs></remoteFs>"
                + "<jobTemplate>{}</jobTemplate>"
                + "</org.jenkinsci.plugins.nomad.NomadWorkerTemplate>";

        // WHEN
        NomadWorkerTemplate template = (NomadWorkerTemplate) Jenkins.XSTREAM2.fromXML(xml);

        // THEN
        assertThat(template.getMaxConcurrentJobs(), is(nullValue()));
    }

    /**
     * Provisioning waits for the agent to connect. If the node is removed in the meantime (a
     * retention strategy, an admin), that wait must end immediately: the planned node stays counted
     * as capacity until its future completes, so a wait that runs on to the full worker timeout
     * stops the NodeProvisioner from replacing a worker that is already gone. Observed in
     * production as "Provisioning completed" seven seconds after the worker was deregistered.
     */
    @Test
    void testPlannedNodeFailsFastWhenWorkerIsRemovedWhileBooting() throws Exception {
        // GIVEN a cloud whose worker timeout (1 minute) is far longer than this test may wait
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(label.getName());
        NomadCloud cloud = createCloud(template);
        NomadApi nomadApi = createNomadApi(new JobInfo[0]);
        when(nomadApi.startWorker(anyString(), anyString(), any()))
                .thenReturn("{\"Job\": {\"Region\": \"global\"}}");
        cloud.setNomad(nomadApi);

        // WHEN a worker is provisioned and then removed before its agent connects
        NodeProvisioner.PlannedNode planned = cloud.provision(label, 1).iterator().next();
        Node node = waitForNode(planned.displayName);
        r.jenkins.removeNode(node);

        // THEN the planned node fails within seconds, not after the worker timeout
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> planned.future.get(15, TimeUnit.SECONDS));
        assertThat(failure.getCause().getMessage(), containsString("removed"));
        // and the worker is not deregistered a second time
        verify(nomadApi, never()).stopWorker(anyString(), any(), any());
    }

    /**
     * The plugin adds the worker node itself, before the agent connects, because an inbound agent
     * can only connect to a node that already exists. NodeProvisioner does not know that: when the
     * planned node's future completes it adds whatever node the future returned. That is a no-op
     * while the same instance is still registered, but a single-use worker whose build finished
     * within the provisioner's polling interval has already been terminated and removed by then,
     * and the re-add resurrects it as a node with no Nomad job behind it. Observed in production as
     * workers that stayed listed as offline forever after their job was deregistered.
     */
    @Test
    void testCompletedPlannedNodeDoesNotResurrectATerminatedWorker() throws Exception {
        // GIVEN a worker whose agent has connected
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(label.getName());
        NomadCloud cloud = createCloud(template);
        NomadApi nomadApi = createNomadApi(new JobInfo[0]);
        when(nomadApi.startWorker(anyString(), anyString(), any()))
                .thenReturn("{\"Job\": {\"Region\": \"global\"}}");
        cloud.setNomad(nomadApi);
        r.jenkins.clouds.add(cloud);
        NodeProvisioner.PlannedNode planned = cloud.provision(label, 1).iterator().next();
        NomadWorker worker = (NomadWorker) waitForNode(planned.displayName);
        // Swap the inbound launcher for one the test can drive, and make the computer pick it up
        worker.setLauncher(r.createComputerLauncher(null));
        r.jenkins.setNodes(r.jenkins.getNodes());
        r.waitOnline(worker);
        Node provisioned = planned.future.get(15, TimeUnit.SECONDS);
        assertThat(r.jenkins.getNode(worker.getNodeName()), is(notNullValue()));

        // WHEN the worker is terminated before NodeProvisioner gets round to the completed future
        worker.terminate();
        assertThat(r.jenkins.getNode(worker.getNodeName()), is(nullValue()));
        // and NodeProvisioner.update() then does what it does with a completed future
        if (provisioned != null) {
            r.jenkins.addNode(provisioned);
        }

        // THEN the worker stays gone
        assertThat(r.jenkins.getNode(worker.getNodeName()), is(nullValue()));
    }

    /**
     * The node is registered before the agent connects, because an inbound agent can only connect
     * to a node that already exists. Nothing else reaps a worker that has never been online -- both
     * retention strategies skip one while it is still booting -- so a failure between registering
     * the node and waiting for the agent used to leave an offline node that no code path could
     * remove, once per provisioning round. A malformed Nomad URL is enough to trigger it.
     */
    @Test
    void testProvisioningFailureDoesNotLeaveAnUnreapableNode() throws Exception {
        // GIVEN registering the Nomad job fails
        LabelAtom label = createLabel();
        NomadWorkerTemplate template = createTemplate(label.getName());
        NomadCloud cloud = createCloud(template, false);
        NomadApi nomadApi = createNomadApi(new JobInfo[0]);
        when(nomadApi.startWorker(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("Unable to parse Nomad url"));
        cloud.setNomad(nomadApi);
        r.jenkins.clouds.add(cloud);

        // WHEN
        NodeProvisioner.PlannedNode planned = cloud.provision(label, 1).iterator().next();

        // THEN the planned node fails and the worker is torn down, not left behind
        assertThrows(ExecutionException.class, () -> planned.future.get(15, TimeUnit.SECONDS));
        assertThat(r.jenkins.getNode(planned.displayName), is(nullValue()));
        verify(nomadApi).stopWorker(eq(planned.displayName), any(), any());
    }

    @Test
    void testPruneDeregistersAnExpiredOrphanedWorker() {
        // GIVEN a running Nomad job with no Jenkins node, submitted longer ago than the timeout
        LabelAtom label = createLabel();
        NomadApi nomadApi = pruningApi(orphan("jenkins-dead1"), 5);
        NomadCloud cloud = pruningCloud(nomadApi, label);

        // WHEN only pruning runs: an excess workload of 0 provisions nothing
        cloud.provision(label, 0);

        // THEN
        verify(nomadApi).stopWorker("jenkins-dead1", "ns1", "global");
    }

    @Test
    void testPruneLeavesAWorkerThatIsStillStartingUp() {
        // GIVEN the same job, submitted just now: it may still be booting
        LabelAtom label = createLabel();
        NomadApi nomadApi = pruningApi(orphan("jenkins-booting1"), 0);
        NomadCloud cloud = pruningCloud(nomadApi, label);

        // WHEN
        cloud.provision(label, 0);

        // THEN
        verify(nomadApi, never()).stopWorker(anyString(), any(), any());
    }

    /**
     * Jobs are listed by name prefix across every namespace, so the listing also returns jobs this
     * controller never created: another controller's workers, or any job whose ID merely starts
     * with the prefix. Deregistering one of those interrupts someone else's build, and an ID is
     * also allowed to contain characters that would rewrite the request path.
     */
    @Test
    void testPruneIgnoresJobsItDidNotCreate() {
        // GIVEN jobs that share the prefix but cannot have come from this template
        LabelAtom label = createLabel();
        NomadApi nomadApi = pruningApi(orphan("jenkins"), 5);
        when(nomadApi.getRunningWorkers(anyString())).thenReturn(new JobInfo[]{
                orphan("jenkins"),                        // the job that runs Jenkins itself
                orphan("jenkins-worker-of-other-ci"),     // another controller's naming scheme
                orphan("jenkins-a/../../v1/nodes"),       // would rewrite the request path
        });
        NomadCloud cloud = pruningCloud(nomadApi, label);

        // WHEN
        cloud.provision(label, 0);

        // THEN
        verify(nomadApi, never()).stopWorker(anyString(), any(), any());
    }

    /**
     * Jenkins removes the node whatever _terminate does, so a cloud that has been renamed or
     * deleted must not throw: the Nomad job would be left running with nothing left to stop it, and
     * the operator would see no reason why.
     */
    @Test
    void testTerminatingAWorkerWhoseCloudIsGoneRemovesTheNode() throws Exception {
        // GIVEN a worker whose cloud does not exist
        NomadWorker worker = new NomadWorker("orphan-worker", "renamed-away", "label", 1, 1, true, "/tmp");
        r.jenkins.addNode(worker);

        // WHEN
        worker.terminate();

        // THEN
        assertThat(r.jenkins.getNode("orphan-worker"), is(nullValue()));
    }

    private JobInfo orphan(String jobId) {
        return new JobInfo(jobId, jobId, "batch", "running", 50, new JobSummary(jobId, "ns1"));
    }

    private NomadApi pruningApi(JobInfo orphan, int submittedMinutesAgo) {
        NomadApi nomadApi = createNomadApi(new JobInfo[]{orphan});
        long submitTimeNanos = Instant.now().minus(submittedMinutesAgo, ChronoUnit.MINUTES)
                .toEpochMilli() * 1_000_000L;
        when(nomadApi.getRunningWorker(anyString(), any())).thenReturn(
                new JSONObject().put("Region", "global").put("SubmitTime", submitTimeNanos));
        return nomadApi;
    }

    private NomadCloud pruningCloud(NomadApi nomadApi, LabelAtom label) {
        // workerTimeout is 1 minute, so a job submitted 5 minutes ago is past its startup window
        NomadCloud cloud = createCloud(createTemplate(label.getName()), true);
        cloud.setNomad(nomadApi);
        return cloud;
    }

    private Node waitForNode(String name) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Node node = r.jenkins.getNode(name);
            if (node != null) {
                return node;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("worker " + name + " was never added to Jenkins");
    }

    private NomadApi createNomadApi(JobInfo[] runningWorkers) {
        NomadApi nomadApi = mock(NomadApi.class);
        when(nomadApi.getRunningWorkers(anyString())).thenReturn(runningWorkers);
        return nomadApi;
    }

    private JobInfo createJobInfo(String status) {
        return new JobInfo(UUID.randomUUID().toString(), "jenkins", "batch", status, 50, null);
    }

    private NomadCloud createCloud(NomadWorkerTemplate template) {
        return createCloud(template, false);
    }

    private NomadCloud createCloud(NomadWorkerTemplate template, boolean prune) {
        return new NomadCloud(
                "nomad",
                "nomadUrl",
                false,
                null,
                null,
                null,
                null,
                1,
                "",
                prune,
                Collections.singletonList(template));
    }

    private NomadWorkerTemplate createTemplate(String labels) {
        return new NomadWorkerTemplate(
                "jenkins",
                labels,
                1,
                true,
                1,
                null,
                NomadWorkerTemplate.DescriptorImpl.defaultJobTemplate);
    }

    private LabelAtom createLabel() {
        return new LabelAtom(UUID.randomUUID().toString());
    }

}
