package hudson.plugins.openshift;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;

import org.kohsuke.stapler.DataBoundConstructor;

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
    }
}