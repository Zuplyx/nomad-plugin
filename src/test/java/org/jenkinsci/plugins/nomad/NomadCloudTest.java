package org.jenkinsci.plugins.nomad;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProvisioner;
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

    private LabelAtom createLabel() {
        return new LabelAtom(UUID.randomUUID().toString());
    }

}
