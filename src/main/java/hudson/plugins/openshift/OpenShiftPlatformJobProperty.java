package hudson.plugins.openshift;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class OpenShiftPlatformJobProperty extends
        JobProperty<AbstractProject<?, ?>> {

    public final String platform;

    @DataBoundConstructor
    public OpenShiftPlatformJobProperty(String platform) {
        this.platform = platform;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Platform";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return OpenShiftCloud.get() != null
                    && AbstractProject.class.isAssignableFrom(jobType);
        }
    }
}
