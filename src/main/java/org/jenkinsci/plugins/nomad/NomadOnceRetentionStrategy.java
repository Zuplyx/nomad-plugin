package org.jenkinsci.plugins.nomad;

import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.CloudRetentionStrategy;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NomadOnceRetentionStrategy extends CloudRetentionStrategy {
    private static final Logger LOGGER = Logger.getLogger(NomadOnceRetentionStrategy.class.getName());

    public NomadOnceRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
    }

    @Override
    public long check(AbstractCloudComputer c) {
        // If the node is idle AND it has already executed a build, kill it immediately.
        if (c.isIdle() && !c.getBuilds().isEmpty()) {
            LOGGER.log(Level.INFO, "Single-use Nomad node {0} finished its build. Terminating immediately.", c.getName());
            try {
                if (c.getNode() != null) {
                    c.getNode().terminate();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to terminate Nomad node", e);
            }
            return 1;
        }
        
        // Otherwise, it's either still running a job, OR it's a brand new node booting up.
        // Let the standard idle timeout handle the boot grace period.
        return super.check(c);
    }
}
