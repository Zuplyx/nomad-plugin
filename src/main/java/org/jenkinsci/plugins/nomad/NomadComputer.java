package org.jenkinsci.plugins.nomad;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.ComputerListener;

import java.util.logging.Level;
import java.util.logging.Logger;

public class NomadComputer extends AbstractCloudComputer<NomadWorker> {

    private static final Logger LOGGER = Logger.getLogger(NomadComputer.class.getName());

    /**
     * Whether the agent has connected at least once. Until it has, the worker is still booting
     * (pulling its image, starting the JVM, dialling in) and must not be reaped as "idle":
     * {@link Computer#isIdle()} is true for a computer that has never connected, so an idle
     * timeout would otherwise race the boot. That phase is bounded by the cloud's worker timeout
     * instead, enforced by {@code NomadCloud.ProvisioningCallback}.
     */
    private volatile boolean everOnline;

    public NomadComputer(NomadWorker worker) {
        super(worker);
    }

    public boolean hasEverBeenOnline() {
        return everOnline;
    }

    void markOnline() {
        everOnline = true;
    }

    @Extension
    public static final class OnlineTracker extends ComputerListener {
        @Override
        public void onOnline(Computer c, TaskListener listener) {
            if (c instanceof NomadComputer) {
                ((NomadComputer) c).markOnline();
            }
        }
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);
        if (!isReusable()) {
            setAcceptingTasks(false);
        }
        LOGGER.log(Level.INFO, " Computer " + this + ": task accepted");
    }

    private boolean isReusable() {
        NomadWorker node = getNode();
        return node != null && node.isReusable();
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        super.taskCompleted(executor, task, durationMS);
        LOGGER.log(Level.INFO, " Computer " + this + ": task completed");
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        LOGGER.log(Level.WARNING, " Computer " + this + " task completed with problems");
    }

    @Override
    public String toString() {
        return String.format("%s (worker: %s)", getName(), getNode());
    }

}
