package hudson.plugins.openshift;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;

import org.kohsuke.stapler.DataBoundConstructor;

public class OpenShiftBuilderTypeJobProperty extends
        JobProperty<AbstractProject<?, ?>> {

    public final String builderType;

    @DataBoundConstructor
    public OpenShiftBuilderTypeJobProperty(String builderType) {
        this.builderType = builderType;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Builder Type";
        }

        @Override
        public boolean isApplicable(java.lang.Class<? extends Job> jobType) {
            return OpenShiftCloud.get() != null
                    && AbstractProject.class.isAssignableFrom(jobType);
        }
    }
}