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

public class OpenShiftRegionJobProperty extends
        JobProperty<AbstractProject<?, ?>> {

    public final String region;

    @DataBoundConstructor
    public OpenShiftRegionJobProperty(String region) {
        this.region = region;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {
        @Override
        public String getDisplayName() {
            return "Region";
        }

        @Override
        public boolean isApplicable(java.lang.Class<? extends Job> jobType) {
            return OpenShiftCloud.get() != null
                    && AbstractProject.class.isAssignableFrom(jobType);
        }
    }
}