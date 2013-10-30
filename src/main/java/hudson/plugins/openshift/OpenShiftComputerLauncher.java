package hudson.plugins.openshift;

import hudson.model.TaskListener;
import hudson.model.Descriptor;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.openshift.client.OpenShiftException;

public class OpenShiftComputerLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger
            .getLogger(OpenShiftComputerLauncher.class.getName());

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener taskListener)
            throws IOException, InterruptedException {
        LOGGER.info("Launching slave...");

        OpenShiftComputer computer = (OpenShiftComputer) slaveComputer;

        // If the slave doesn't have a uuid, connect it
        if (computer.getNode().getUuid() == null) {
            // Don't delay DNS lookup since in this case, Jenkins has probably
            // just been restarted and the slave is still running
            computer.getNode().connect(true);
        }

        LOGGER.info("Checking availability of computer " + computer.getNode());
        String hostName = computer.getNode().getHostName();
        LOGGER.info("Checking SSH access to application " + hostName);
        try {
            final JSch jsch = new JSch();
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");

            // Add the private key location
            jsch.addIdentity(OpenShiftCloud.get().getPrivateKey()
                    .getAbsolutePath());

            // The user for the SSH connection is the application uuid
            String username = computer.getNode().getUuid();
            LOGGER.info("Connecting via SSH '" + username + "' '" + hostName + "' '" + OpenShiftCloud.get().getPrivateKey()
                    .getAbsolutePath() + "'");
            final Session sess = jsch.getSession(username, hostName, 22);
            sess.setConfig(config);
            sess.connect();
            LOGGER.info("Connected via SSH.");

            PrintStream logger = taskListener.getLogger();
            logger.println("Attempting to connect slave...");
            logger.println("Transferring slave.jar file...");

            // A command for the initial slave setup
            StringBuilder execCommand = new StringBuilder();
            execCommand.append("mkdir -p $OPENSHIFT_DATA_DIR/jenkins")
                    .append(" && cd $OPENSHIFT_DATA_DIR/jenkins")
                    .append(" && rm -f slave.jar").append(" && wget -q --no-check-certificate https://")
                    .append(getGearDNS(hostName))
                    .append("/jnlpJars/slave.jar");

            String command = execCommand.toString();
            LOGGER.info("Exec " + command);

            // Open an execution channel that supports SSH agent forwarding
            Channel channel = sess.openChannel("exec");
            ((ChannelExec) channel).setCommand(command);
            channel.connect();

            // Wait for the channel to close
            while (true) {
                if (channel.isClosed()) {
                    break;
                }
                try {
                    Thread.sleep(1000);
                } catch (Exception ee) {
                }
            }
            int result = channel.getExitStatus();
            if (result != 0) {
                LOGGER.warning("Download of slave.jar failed.  Return code = " + result);
                throw new IOException(
                        "Download of slave.jar failed.  Return code = "
                                + result);
            }
            channel.disconnect();

            // Execute the slave.jar to establish a connection
            // Make sure to enable SSH agent forwarding
            logger.println("Executing slave jar to make connection...");
            final Channel slaveChannel = sess.openChannel("exec");
            String sshWrapperPath = "/usr/libexec/openshift/cartridges/jenkins/bin/git_ssh_wrapper.sh";
            ((ChannelExec) slaveChannel).setEnv("GIT_SSH", sshWrapperPath);
            ((ChannelExec) slaveChannel).setAgentForwarding(true);
            ((ChannelExec) slaveChannel)
                    .setCommand("java -jar $OPENSHIFT_DATA_DIR/jenkins/slave.jar");
            InputStream serverOutput = slaveChannel.getInputStream();
            OutputStream clientInput = slaveChannel.getOutputStream();
            slaveChannel.connect();
            if (slaveChannel.isClosed()) {
                LOGGER.severe("Slave connection terminated early with exit = "
                        + channel.getExitStatus());
            }

            computer.setChannel(serverOutput, clientInput, taskListener,
                    new Listener() {

                        public void onClosed(hudson.remoting.Channel channel,
                                             IOException cause) {
                            slaveChannel.disconnect();
                            sess.disconnect();
                        }
                    });

            LOGGER.info("Slave connected.");
            logger.flush();
        } catch (JSchException e) {
            e.printStackTrace();
            throw new IOException(e);
        }
    }

    protected String getGearDNS(String hostname) throws IOException {
    	StringTokenizer tokenizer = new StringTokenizer(hostname, "-");
    	tokenizer.nextToken();
    	String currentDns = tokenizer.nextToken();
    	while (tokenizer.hasMoreTokens()) {
    	    currentDns = currentDns + "-" + tokenizer.nextToken();
    	}
    	String gearDns = System.getenv("OPENSHIFT_GEAR_DNS");
    	tokenizer = new StringTokenizer(gearDns, "-");
    	currentDns = tokenizer.nextToken() + "-" + currentDns;
    	return currentDns;
    }

    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
