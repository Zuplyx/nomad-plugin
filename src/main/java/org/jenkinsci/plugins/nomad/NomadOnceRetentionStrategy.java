package org.jenkinsci.plugins.nomad;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.OneOffExecutor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NomadOnceRetentionStrategy extends CloudRetentionStrategy implements ExecutorListener {
    private static final Logger LOGGER = Logger.getLogger(NomadOnceRetentionStrategy.class.getName());
    private transient boolean terminating;

    public NomadOnceRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
    }

    @Override
    public long check(AbstractCloudComputer c) {
        // Fallback: If the node is idle AND it has already executed a build, kill it.
        if (c.isIdle() && !c.getBuilds().isEmpty()) {
            LOGGER.log(Level.INFO, "Fallback: Single-use Nomad node {0} finished its build. Terminating.", c.getName());
            terminate(c);
            return 1;
        }

        // Otherwise, it's either still running a job, OR it's a brand new node booting up.
        // Let the standard idle timeout handle the boot grace period.
        return super.check(c);
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        // Do nothing when task starts
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        done(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        done(executor);
    }

    private void done(Executor executor) {
        final AbstractCloudComputer<?> c = (AbstractCloudComputer<?>) executor.getOwner();

        // Ignore flyweight tasks (lightweight background tasks that don't use real executors)
        if (executor instanceof OneOffExecutor) {
            return;
        }

        LOGGER.log(Level.INFO, "Event: Task completed on single-use node {0}. Terminating immediately.", c.getName());
        terminate(c);
    }

    private void terminate(final AbstractCloudComputer<?> c) {
        c.setAcceptingTasks(false); // Stop Jenkins from sending new jobs here

        synchronized (this) {
            if (terminating) {
                return;
            }
            terminating = true;
        }

        // Run termination in a background thread to prevent locking up the Jenkins executor
        Computer.threadPoolForRemoting.submit(() -> {
            try {
                AbstractCloudSlave node = (AbstractCloudSlave) c.getNode();
                if (node != null) {
                    node.terminate();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to terminate Nomad node", e);
                synchronized (NomadOnceRetentionStrategy.this) {
                    terminating = false; // Reset so the fallback check() can try again later
                }
            }
        });
    }
}
