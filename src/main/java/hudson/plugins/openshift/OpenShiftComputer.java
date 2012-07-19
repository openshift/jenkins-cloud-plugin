package hudson.plugins.openshift;

import hudson.slaves.AbstractCloudComputer;

import java.io.IOException;
import java.util.logging.Logger;

public class OpenShiftComputer extends AbstractCloudComputer {
    private static final Logger LOGGER = Logger
            .getLogger(OpenShiftComputer.class.getName());

    @SuppressWarnings("unchecked")
    public OpenShiftComputer(OpenShiftSlave slave) {
        super(slave);
        LOGGER.info("Creating Computer");
    }

    @Override
    public OpenShiftSlave getNode() {
        return (OpenShiftSlave) super.getNode();
    }

    @Override
    public String getHostName() throws IOException {
        return getNode().getHostName();
    }
}
