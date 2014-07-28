package hudson.plugins.openshift;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class OpenShiftBuilderTimeoutJobProperty extends
        JobProperty<AbstractProject<?, ?>> {

    public final long builderTimeout;

    @DataBoundConstructor
    public OpenShiftBuilderTimeoutJobProperty(String builderTimeout) {
        this.builderTimeout = Long.parseLong(builderTimeout);
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Builder Timeout";
        }

        @Override
        public boolean isApplicable(java.lang.Class<? extends Job> jobType) {
            return OpenShiftCloud.get() != null
                    && AbstractProject.class.isAssignableFrom(jobType);
        }

        @Restricted(NoExternalUse.class)
        public FormValidation doCheckBuilderTimeout(
                @QueryParameter String builderTimeout, @AncestorInPath AbstractProject<?, ?> job
        ) {
            if (!job.getFullName().equals(job.getAssignedLabelString())) return FormValidation.ok();

            return FormValidation.validatePositiveInteger(builderTimeout);
        }
    }
}