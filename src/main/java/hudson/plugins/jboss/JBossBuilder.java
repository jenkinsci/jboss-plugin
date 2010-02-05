package hudson.plugins.jboss;
import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.VariableResolver;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.io.output.NullOutputStream;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * This plugin is for simple management of JBoss instances.
 *
 * @author Juliusz Brzostek
 */
public class JBossBuilder extends Builder {

    private final ServerBean server;

    private final DescriptorImpl.Operation operation;

    private final String properties;

    /**
     * Currently hard-coded localhost address.
     * In future it can be more flexible.
     */
    private final String hostName = "127.0.0.1";
    
    @DataBoundConstructor
    public JBossBuilder(DescriptorImpl.Operation operation, String serverName, String properties) {
    	this.operation = operation;
    	this.properties = Util.fixEmptyAndTrim(properties);;
    	
    	ServerBean localServerBean = null;
    	
    	for (ServerBean bean : getDescriptor().getServers()) {
    		if (bean.getServerName().equals(serverName)){
    			localServerBean = bean;
    			break;
    		}
    	}
    	
    	this.server = localServerBean;
    }

    public ServerBean getServer() {
        return this.server;
    }

    public DescriptorImpl.Operation getOperation() {
    	return this.operation;
    }
    
    public String getProperties() {
    	return this.properties;
    }
    
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException{
    	
    	if (server == null || operation == null) {
    		listener.fatalError("Wrong configuration of plugin. Step error.");
    		return false;
    	}

    	ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader(); 
    	Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    	try {
    		
	    	switch (operation) {
	    	
	    		case START_AND_WAIT:
	    			if (JMXUtils.checkStatus(hostName, server.getJndiPort(), listener, 3, true)) {
	    				listener.getLogger().println("JBoss AS already started.");
	    				return true;
	    			}
	    			boolean ret = CommandsUtils.start(server,
						this.properties, build, launcher, listener)
						&& JMXUtils.checkStatus(hostName, server.getJndiPort(),
								listener, 15, false);
	    			if (ret) {
	        			listener.getLogger().println("JBoss AS started!");
	    			} else {
	        			listener.getLogger().println("JBoss AS is not stared before timeout has expired!");
	    			}
	    			return ret;
	    			
	    		case START:
					if (JMXUtils.checkStatus(hostName, server.getJndiPort(), listener, 3, true)) {
		    				listener.getLogger().println("JBoss AS already started.");
		    				return true;
		    		}
	    			return CommandsUtils.start(server, this.properties, build, launcher, listener);

	    		case SHUTDOWN:
	    			if (!JMXUtils.checkStatus(hostName, server.getJndiPort(), listener, 3, true)) {
	    				listener.getLogger().println("JBoss AS is not working.");
	    				return true;
	    			}
	    			return CommandsUtils.stop(server, launcher, listener);

	    		default:
	    			listener.fatalError("Uexpected type of operation.");
	    			return false;
	    	}
    	} finally {
        	Thread.currentThread().setContextClassLoader(contextClassLoader);
    	}
    }




    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link JBossBuilder}. Used as a singleton.
      */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

    	/**
         * Home directory of JBoss.
         */
        private String homeDir;

        /**
         * List of defined servers.
         */
        @CopyOnWrite
        private List<ServerBean> servers = new ArrayList<ServerBean>();

        public DescriptorImpl() {
            load();
        }

        /**
         * Performs validation of JBoss home directory
         *
         * @value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         */
		public FormValidation doCheckHomeDir(
				@QueryParameter final String jbossHomeString)
					throws IOException,	ServletException {
        	
            if(jbossHomeString == null || jbossHomeString.length() == 0) { 
                return FormValidation.error("Please set path to JBoss home.");
            }
        
            File jbossHomeFile = new File(jbossHomeString);
            
            if (!jbossHomeFile.exists()) {
            	return FormValidation.error("Path doesn't exist.");
            }

            if (!jbossHomeFile.isDirectory()) {
            	return FormValidation.error("Path is not valid directory.");
            }

            for (String subDir : new String[]{"bin", "server"}) {
            	File subFile = new File(jbossHomeFile, subDir);
            	if (!subFile.exists() || !subFile.isDirectory()) {
            		return FormValidation.error("It's not look like correct JBoss home directory.");
            	}
            }
            
            return FormValidation.ok();
        }

        public FormValidation doCheckServerName(
        		@QueryParameter final String value)
        			throws IOException, ServletException {

            if(value == null || value.length() == 0) { 
                return FormValidation.error("Server name can not be empty.");
            }
            
            return FormValidation.ok();
        }

        public FormValidation doCheckJndiPort(
        		@QueryParameter final String value)
        			throws IOException, ServletException {

            if(value == null || value.length() == 0) { 
                return FormValidation.error("JNDI port is mandatory.");
            }
            
            int jndiPortNr = 0;
            try {
            	jndiPortNr = Integer.parseInt(value);
            } catch (NumberFormatException e) {
            	return FormValidation.error("JNDI port is valid number.");
            }
            
            if (jndiPortNr <= 1024) {
            	return FormValidation.error("JNDI port can not be lowest then 1024.");
            }
            
            return FormValidation.ok();
        }
		
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * Builder name.
         */
        public String getDisplayName() {
            return "JBoss Management";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject parameters) throws FormException {

        	homeDir = parameters.getString("homeDir");

        	servers.clear();
        	
            JSONObject optServersObject = parameters.optJSONObject("servers");
            if (optServersObject != null) {
            	servers.add(new ServerBean(
            							homeDir,
            							optServersObject.getString("serverName"),
            							optServersObject.getInt("jndiPort")));
            } else {
            	JSONArray optServersArray = parameters.optJSONArray("servers");
            	if (optServersArray != null) {
            		for (int i=0; i < optServersArray.size(); i++) {
            			JSONObject serverObject = (JSONObject) optServersArray.get(i);
                    	servers.add(new ServerBean(
                    			homeDir,
    							serverObject.getString("serverName"),
    							serverObject.getInt("jndiPort")));
            		}
            	}
            }
            
            save();
            return super.configure(req, parameters);
        }

        public String getHomeDir() {
            return homeDir;
        }
        
        public List<ServerBean> getServers() {
        	return this.servers;
        }

        public Operation[] getOperations() {
        	return Operation.all;
        }

        enum Operation {
        	
            START_AND_WAIT,
            START,
            SHUTDOWN;
            
            private static Operation[] all =
            	new Operation[]{START_AND_WAIT, START, SHUTDOWN}; 
        }
}
    
    public static class ServerBean {
    	private final String homeDir;
    	private final String serverName;
    	private final int jndiPort;
    	
		public ServerBean(final String homeDir,
				final String serverName, final int jndiPort) {
    		this.homeDir = homeDir;
    		this.serverName = serverName;
    		this.jndiPort =jndiPort;
    	}
    	
		public String getHomeDir() {
			return this.homeDir;
		}
		
    	public String getServerName() {
    		return this.serverName;
    	}
    	
    	public int getJndiPort() {
    		return this.jndiPort;
    	}
    	
    	@Override
    	public String toString() {
    		return new StringBuilder()
    				.append("ServerBean=")
    				.append(serverName)
    				.append(":")
    				.append(jndiPort).toString();
    	}
    }
}
