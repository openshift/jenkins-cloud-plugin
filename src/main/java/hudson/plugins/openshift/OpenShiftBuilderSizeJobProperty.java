package hudson.plugins.openshift;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;

import org.kohsuke.stapler.DataBoundConstructor;

public class OpenShiftBuilderSizeJobProperty extends
        JobProperty<AbstractProject<?, ?>> {

    public final String builderSize;

    @DataBoundConstructor
    public OpenShiftBuilderSizeJobProperty(String builderSize) {
        this.builderSize = builderSize;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Builder Size";
        }

        @Override
        public boolean isApplicable(java.lang.Class<? extends Job> jobType) {
            return OpenShiftCloud.get() != null
                    && AbstractProject.class.isAssignableFrom(jobType);
        }
    }
}