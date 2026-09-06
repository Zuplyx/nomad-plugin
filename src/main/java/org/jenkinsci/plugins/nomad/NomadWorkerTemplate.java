package org.jenkinsci.plugins.nomad;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import org.jenkinsci.Symbol;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import hudson.Extension;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.Jenkins;

public class NomadWorkerTemplate implements Describable<NomadWorkerTemplate> {

    private static final String SLAVE_PREFIX = "jenkins";

    // persistent fields
    private final String prefix;
    // Nullable on purpose: absent from an existing config.xml means "unlimited".
    // A primitive here would deserialize to 0 and block all provisioning on upgrade.
    private Integer maxConcurrentJobs;
    private final int idleTerminationInMinutes;
    private final boolean reusable;
    private final int numExecutors;
    private final String labels;
    private final String jobTemplate;
    private final String remoteFs;

    @DataBoundConstructor
    public NomadWorkerTemplate(
            String prefix,
            String labels,
            int idleTerminationInMinutes,
            boolean reusable,
            int numExecutors,
            String remoteFs,
            String jobTemplate
    ) {
        this.prefix = prefix.isEmpty() ? SLAVE_PREFIX : prefix;
        this.idleTerminationInMinutes = idleTerminationInMinutes;
        this.reusable = reusable;
        this.numExecutors = numExecutors;
        this.labels = Util.fixNull(labels);
        this.remoteFs = Util.fixNull(remoteFs);
        this.jobTemplate = jobTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Descriptor<NomadWorkerTemplate> getDescriptor() {
        return Jenkins.get().getDescriptor(getClass());
    }

    public String createWorkerName() {
        return prefix + "-" + Long.toHexString(System.nanoTime());
    }

    public String getPrefix() {
        return prefix;
    }

    /**
     * Maximum number of concurrent Nomad jobs for this template.
     * @return the configured limit, or {@code null} when unlimited
     */
    @CheckForNull
    public Integer getMaxConcurrentJobs() {
        return maxConcurrentJobs;
    }

    @DataBoundSetter
    public void setMaxConcurrentJobs(Integer maxConcurrentJobs) {
        this.maxConcurrentJobs = maxConcurrentJobs;
    }

    /**
     * @return true when this template caps how many jobs may run concurrently.
     *         A null or negative value means unlimited.
     */
    public boolean hasConcurrencyLimit() {
        return maxConcurrentJobs != null && maxConcurrentJobs >= 0;
    }

    public int getIdleTerminationInMinutes() {
        return idleTerminationInMinutes;
    }

    public boolean isReusable() {
        return reusable;
    }

    public int getNumExecutors() {
        return numExecutors;
    }

    public String getLabels() {
        return labels;
    }

    public String getRemoteFs() {
        return remoteFs;
    }

    public String getJobTemplate() {
        return jobTemplate;
    }

    @Extension
    @Symbol("nomadWorkerTemplate")
    public static final class DescriptorImpl extends Descriptor<NomadWorkerTemplate> {
        public static final String defaultJobTemplate = loadDefaultJobTemplate();

        private static String loadDefaultJobTemplate() {
            try (InputStream in = DescriptorImpl.class.getResourceAsStream(
                    "/org/jenkinsci/plugins/nomad/jobTemplate.json")) {
                return new String(Objects.requireNonNull(in, "jobTemplate.json is missing").readAllBytes(), UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        public DescriptorImpl() {
            load();
        }

        @Override
        public @NonNull String getDisplayName() {
            return "";
        }

        @POST
        public FormValidation doValidation(
                @QueryParameter String nomadUrl,
                @QueryParameter boolean tlsEnabled,
                @QueryParameter String clientCertificate,
                @QueryParameter String clientPassword,
                @QueryParameter String serverCertificate,
                @QueryParameter String serverPassword,
                @QueryParameter String nomadACLCredentialsId,
                @QueryParameter String jobTemplate) {
            Objects.requireNonNull(Jenkins.get()).checkPermission(Jenkins.ADMINISTER);

            NomadCloud cloud = new NomadCloud(
                    "validate-template-" + UUID.randomUUID(),
                    nomadUrl,
                    tlsEnabled,
                    clientCertificate,
                    Secret.fromString(clientPassword),
                    serverCertificate,
                    Secret.fromString(serverPassword),
                    1,
                    nomadACLCredentialsId,
                    false,
                    null
            );

            NomadWorkerTemplate template = new NomadWorkerTemplate(
                    "validate-template",
                    null,
                    0,
                    false,
                    1,
                    null,
                    jobTemplate
            );

            NomadApi api = new NomadApi(cloud);
            return api.validateTemplate(template);
        }
    }
}
