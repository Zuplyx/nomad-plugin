package org.jenkinsci.plugins.nomad;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.EphemeralNode;
import hudson.slaves.JNLPLauncher;
import jenkins.model.Jenkins;

public class NomadWorker extends AbstractCloudSlave implements EphemeralNode {

    private static final Logger LOGGER = Logger.getLogger(NomadWorker.class.getName());
    private final boolean reusable;
    private final String cloudName;
    private final int idleTerminationInMinutes;
    private String namespace;
    private String region;

    @DataBoundConstructor
    public NomadWorker(String name, String cloudName, String labelString, int numExecutors, int idleTerminationInMinutes,
            boolean reusable, String remoteFS) throws FormException, IOException {
        super(name, remoteFS, new JNLPLauncher(false));

        setLabelString(labelString);
        setMode(labelString.isEmpty() ? Mode.NORMAL : Mode.EXCLUSIVE);
        setNumExecutors(numExecutors);
        if (reusable) {
            setRetentionStrategy(new NomadRetentionStrategy(idleTerminationInMinutes));
        } else {
            setRetentionStrategy(new NomadOnceRetentionStrategy(idleTerminationInMinutes));
        }

        this.cloudName = cloudName;
        this.reusable = reusable;
        this.idleTerminationInMinutes = idleTerminationInMinutes;
        this.namespace = null;
        this.region = null;
    }

    @Override
    public Node asNode() {
        return this;
    }

    @Override
    public AbstractCloudComputer<NomadWorker> createComputer() {
        return new NomadComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) {
        NomadCloud cloud = getCloud();
        if (cloud == null) {
            // Jenkins removes the node regardless of what happens here, so the Nomad job would be
            // left running with nothing left to deregister it.
            LOGGER.log(Level.WARNING, "Cloud '" + cloudName + "' no longer exists, so worker '" + getNodeName()
                    + "' cannot be deregistered. Stop it manually: nomad job stop -purge " + getNodeName());
            return;
        }
        LOGGER.log(Level.INFO, "Asking Nomad to deregister worker '" + getNodeName() + "' in namespace '" + getNamespace() +
                "' in region '" + getRegion() + "'");
        cloud.nomad().stopWorker(getNodeName(), getNamespace(), getRegion());
    }

    /**
     * @return the cloud that provisioned this worker, or null if it has been renamed or deleted
     */
    @CheckForNull
    public NomadCloud getCloud() {
        Cloud cloud = Jenkins.get().getCloud(cloudName);
        return cloud instanceof NomadCloud ? (NomadCloud) cloud : null;
    }

    public String getCloudName() {
        return cloudName;
    }

    public boolean isReusable() {
        return reusable;
    }

    public int getIdleTerminationInMinutes() {
        return this.idleTerminationInMinutes;
    }

    @Extension
    public static class DescriptorImpl extends SlaveDescriptor {
        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "Nomad Worker";
        }

        /**
         * We only create these kinds of nodes programatically.
         */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    public String getNamespace() {
        return this.namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getRegion() {
        return this.region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

}
