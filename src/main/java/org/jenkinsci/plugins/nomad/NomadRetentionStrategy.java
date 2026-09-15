package org.jenkinsci.plugins.nomad;

import hudson.model.Descriptor;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.RetentionStrategy;

public class NomadRetentionStrategy extends CloudRetentionStrategy {

    public NomadRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
    }

    public NomadRetentionStrategy(String idleMinutes) {
        super(Integer.parseInt(idleMinutes));
    }

    @Override
    public long check(AbstractCloudComputer c) {
        // See NomadOnceRetentionStrategy: a never-connected worker is booting, not idle.
        if (NomadOnceRetentionStrategy.isStillBooting(c)) {
            return 1;
        }
        return super.check(c);
    }

    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "Nomad Retention Strategy";
        }
    }

}
