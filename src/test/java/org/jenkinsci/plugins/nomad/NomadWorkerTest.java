package org.jenkinsci.plugins.nomad;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class NomadWorkerTest {

    @Test
    void testRetentionStrategyReusable(JenkinsRule j) throws Exception {
        NomadWorker worker = new NomadWorker("name", "cloudName", "label", 1, 10, true, "/tmp");
        assertInstanceOf(NomadRetentionStrategy.class, worker.getRetentionStrategy());
    }

    @Test
    void testRetentionStrategyOnce(JenkinsRule j) throws Exception {
        NomadWorker worker = new NomadWorker("name", "cloudName", "label", 1, 10, false, "/tmp");
        assertInstanceOf(NomadOnceRetentionStrategy.class, worker.getRetentionStrategy());
    }
}
