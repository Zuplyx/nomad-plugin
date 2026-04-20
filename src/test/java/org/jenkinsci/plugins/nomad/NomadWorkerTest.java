package org.jenkinsci.plugins.nomad;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import static org.junit.Assert.assertTrue;

public class NomadWorkerTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testRetentionStrategyReusable() throws Exception {
        NomadWorker worker = new NomadWorker("name", "cloudName", "label", 1, 10, true, "/tmp");
        assertTrue(worker.getRetentionStrategy() instanceof NomadRetentionStrategy);
    }

    @Test
    public void testRetentionStrategyOnce() throws Exception {
        NomadWorker worker = new NomadWorker("name", "cloudName", "label", 1, 10, false, "/tmp");
        assertTrue(worker.getRetentionStrategy() instanceof NomadOnceRetentionStrategy);
    }
}
