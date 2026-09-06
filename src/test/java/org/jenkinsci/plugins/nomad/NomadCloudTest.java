package org.jenkinsci.plugins.nomad;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.nomad.Api.JobInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

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
        boolean result = cloud.canProvision(cloudState(label));

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
        Collection<NodeProvisioner.PlannedNode> result = cloud.provision(cloudState(label), 3);

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
        Collection<NodeProvisioner.PlannedNode> result = cloud.provision(cloudState(label), 10);

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
        Collection<NodeProvisioner.PlannedNode> result = cloud.provision(cloudState(label), 10);

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
        Collection<NodeProvisioner.PlannedNode> result = cloud.provision(cloudState(label), 5);

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
        cloud.provision(cloudState(label), 2);

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
     * 0.12.0 deletes MigrationHelper and the legacy template fields. A config.xml written by an
     * older plugin still references those elements, so Jenkins must tolerate them rather than
     * fail to load the cloud. XStream2's RobustReflectionConverter reports the unknown fields to
     * the Old Data monitor and carries on; this pins that, because the alternative -- a
     * controller that will not start -- is the one outcome this release must not produce.
     */
    @Test
    void testCloudFromOlderPluginStillLoads() {
        // GIVEN a cloud saved by an older plugin: legacy cloud fields, and a template carrying
        // fields that no longer exist plus one referencing a class that has been deleted
        String xml = """
                <org.jenkinsci.plugins.nomad.NomadCloud>
                  <name>nomad</name>
                  <nomadUrl>http://nomad:4646</nomadUrl>
                  <jenkinsUrl>http://jenkins:8080</jenkinsUrl>
                  <jenkinsTunnel>jenkins:50000</jenkinsTunnel>
                  <workerUrl>http://jenkins:8080/jnlpJars/slave.jar</workerUrl>
                  <workerTimeout>5</workerTimeout>
                  <templates>
                    <org.jenkinsci.plugins.nomad.NomadWorkerTemplate>
                      <prefix>jenkins</prefix>
                      <labels>linux</labels>
                      <idleTerminationInMinutes>10</idleTerminationInMinutes>
                      <reusable>true</reusable>
                      <numExecutors>1</numExecutors>
                      <remoteFs></remoteFs>
                      <jobTemplate>{}</jobTemplate>
                      <cpu>500</cpu>
                      <memory>256</memory>
                      <image>jenkins/inbound-agent</image>
                      <driver>docker</driver>
                      <ports>
                        <org.jenkinsci.plugins.nomad.NomadPortTemplate>
                          <label>http</label><value>8080</value>
                        </org.jenkinsci.plugins.nomad.NomadPortTemplate>
                      </ports>
                    </org.jenkinsci.plugins.nomad.NomadWorkerTemplate>
                  </templates>
                </org.jenkinsci.plugins.nomad.NomadCloud>
                """;

        // WHEN
        NomadCloud cloud = (NomadCloud) Jenkins.XSTREAM2.fromXML(xml);

        // THEN - it loads, and everything still supported survives
        assertThat(cloud.getNomadUrl(), is("http://nomad:4646"));
        assertThat(cloud.getTemplates().size(), is(1));
        NomadWorkerTemplate template = cloud.getTemplates().get(0);
        assertThat(template.getPrefix(), is("jenkins"));
        assertThat(template.getLabels(), is("linux"));
        assertThat(template.getJobTemplate(), is("{}"));
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
                false,
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

    private Cloud.CloudState cloudState(LabelAtom label) {
        return new Cloud.CloudState(label, 0);
    }

    private LabelAtom createLabel() {
        return new LabelAtom(UUID.randomUUID().toString());
    }

}
