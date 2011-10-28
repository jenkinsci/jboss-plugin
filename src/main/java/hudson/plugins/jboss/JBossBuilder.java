package hudson.plugins.jboss;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletException;

import hudson.CopyOnWrite;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * This plugin is for simple management of JBoss instances.
 *
 * @author Juliusz Brzostek
 */
public class JBossBuilder extends Builder {

    private final String serverName;
    private final Operation operation;
    
    /**
     * Currently hard-coded localhost address.
     * In future it can be more flexible.
     */
    
    @DataBoundConstructor
    public JBossBuilder(Operation operation, String serverName) {
    	this.operation = operation;
    	this.serverName = serverName;
    }

    public String getServerName() {
        return this.serverName;
    }

    public Operation getOperation() {
    	return this.operation;
    }
    
	@Override
	public boolean perform(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {    	
    	
		ServerBean server = getDescriptor().findServer(serverName);
		
    	if (server == null || operation == null) {
    		listener.fatalError("Wrong configuration of the plugin. Step error.");
    		return false;
    	}

    	ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader(); 
    	Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
    	try {
    		
	    	switch (operation.getType()) {
	    	
	    		case START_AND_WAIT:
		    		listener.getLogger().println("START_AND_WAIT: Checking if server is already running (max 20 seconds)...");
	    			if (JMXUtils.checkServerStatus(server.getAddress(), server.getJndiPort(), listener, 20, false)) {
	    				listener.getLogger().println("START_AND_WAIT: JBoss AS already started.");
	    				return true;
	    			}
	    			listener.getLogger().println("START_AND_WAIT: Going to start server with timeout " + server.getTimeout() + " seconds...");
	    			long startJbossServerTime;
	    			startJbossServerTime = System.currentTimeMillis();
	    			boolean ret = CommandsUtils.start(server,
						operation.getProperties(), build, launcher, listener)
						&& JMXUtils.checkServerStatus(server.getAddress(), server.getJndiPort(),
								listener, server.getTimeout(), false);
	    			startJbossServerTime = System.currentTimeMillis() - startJbossServerTime;
	    			if (ret) {
	        			listener.getLogger().println("START_AND_WAIT: JBoss AS started for " + startJbossServerTime/1000.0 + " sec !");
	    			} else {
	        			listener.getLogger().println(
	        					String.format("START_AND_WAIT: JBoss AS is not started before timeout (%d sec) has expired!",
	        								server.getTimeout()));
	    			}
	    			return ret;
	    			
	    		case START:
		    		listener.getLogger().println("START: Checking if server is already running (max 20 seconds)...");
				if (JMXUtils.checkServerStatus(server.getAddress(), server.getJndiPort(), listener, 20, false)) {
		    			listener.getLogger().println("START: JBoss AS already started.");
		    			return true;
		    		}
	    			listener.getLogger().println("START: Going to trigger start server...");
	    			return CommandsUtils.start(server, operation.getProperties(), build, launcher, listener);

	    		case SHUTDOWN:
		    		listener.getLogger().println("SHUTDOWN: Checking if server is running (max 20 seconds)...");
	    			if (!JMXUtils.checkServerStatus(server.getAddress(), server.getJndiPort(), listener, 20, false)) {
	    				listener.getLogger().println("SHUTDOWN: JBoss AS is not working.");
	    				return true;
	    			}
	    			return CommandsUtils.stop(server, launcher, listener);

	    		case CHECK_DEPLOY:
		    		listener.getLogger().println("CHECK_DEPLOY: Checking if server is running (max 20 seconds)...");
	    			if(!JMXUtils.checkServerStatus(server.getAddress(), server.getJndiPort(), listener, 20, false)){
	    				listener.getLogger().println("CHECK_DEPLOY: JBoss AS is not working.");
	    				return false;
	    			}
	    			boolean result = false;
	    			if (Util.fixEmpty(operation.getProperties()) != null) {
	    				String[] modules = Util.tokenize(operation.getProperties());
	    				result = JMXUtils.checkDeploy(server.getAddress(), server.getJndiPort(), listener, 20, modules);
	    			} else {
	    				listener.getLogger().println("CHECK_DEPLOY: No modules provided.");
	    				result = true;
	    			}
	    			if (!result && operation.isStopOnFailure()) {
	    				listener.getLogger().println("CHECK_DEPLOY: StopOnFailure flag is set, going to down server...");
	    				CommandsUtils.stop(server, launcher, listener);
	    			}
	    			return result;
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
				@QueryParameter final String value)
					throws IOException,	ServletException {
        	
            if(value == null || value.length() == 0) { 
                return FormValidation.error("Please set path to JBoss home.");
            }
        
            File jbossHomeFile = new File(value);
            
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
    
		public FormValidation doCheckAddress(
				@QueryParameter final String value)
					throws IOException,	ServletException {
        	
            if(value == null || value.length() == 0) { 
                return FormValidation.error("Please set IP address of JBoss server.");
            }
            return FormValidation.ok();
        }
            
		public FormValidation doCheckCmdToStart(
				@QueryParameter final String value)
					throws IOException,	ServletException {
        	
            if(value == null || value.length() == 0) { 
                return FormValidation.error("Please set the command to start JBoss server.");
            }
            return FormValidation.ok();
        }
            
		public FormValidation doCheckCmdToShutdown(
				@QueryParameter final String value)
					throws IOException,	ServletException {
        	
            if(value == null || value.length() == 0) { 
                return FormValidation.error("Please set the command to shutdown JBoss server.");
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
            	return FormValidation.error("JNDI port is not valid number.");
            }
            
            if (jndiPortNr <= 1024) {
            	return FormValidation.error("JNDI port can not be lowest then 1024.");
            }
            
            return FormValidation.ok();
        }

        public FormValidation doCheckTimeout(
        		@QueryParameter final String value)
        			throws IOException, ServletException {

            if(value == null || value.length() == 0) { 
                return FormValidation.error("Timeout is mandatory.");
            }
            
            int timeout = 0;
            try {
            	timeout = Integer.parseInt(value);
            } catch (NumberFormatException e) {
            	return FormValidation.error("Timeout is not valid number.");
            }
            
            if (timeout < 5) {
            	return FormValidation.warning("Timeout is very low, are you sure JBoss will start in this time?");
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
        	
        	servers.clear();
        	
            JSONObject optServersObject = parameters.optJSONObject("servers");
            if (optServersObject != null) {
            	JSONObject serverObject = (JSONObject) (optServersObject.optJSONObject("CurrentServer"));
            	if(serverObject.getString("value").equals("option_1") == true){//remote case
            		servers.add(new ServerBean(
            			serverObject.getString("cmdToStart"),
            			serverObject.getString("cmdToShutdown"),
            			serverObject.getString("address"),
            			serverObject.getString("serverName"),
            			serverObject.getInt("jndiPort"),
            			serverObject.getInt("timeout"),
            			1));
            	}
            	else{//local case
            		servers.add(new ServerBean(
                			serverObject.getString("serverName"),
                			serverObject.getString("homeDir"),
                			serverObject.getInt("jndiPort"),
							serverObject.getString("address"),
                			serverObject.getInt("timeout"),
                			0));
            	}
            } else {
            	JSONArray optServersArray = parameters.optJSONArray("servers");
            	if (optServersArray != null) {
            		for (int i=0; i < optServersArray.size(); i++) {
            			JSONObject serverObject = (JSONObject) ((JSONObject)optServersArray.get(i)).optJSONObject("CurrentServer");
            			if(serverObject.getString("value").equals("option_1") == true){//remote case
            				servers.add(new ServerBean(
        						serverObject.getString("cmdToStart"),
                    			serverObject.getString("cmdToShutdown"),
                    			serverObject.getString("address"),
    							serverObject.getString("serverName"),
    							serverObject.getInt("jndiPort"),
    							serverObject.getInt("timeout"),
    							1));
            			}
            			else{//local case
            				servers.add(new ServerBean(
                        			serverObject.getString("serverName"),
                        			serverObject.getString("homeDir"),
                        			serverObject.getInt("jndiPort"),
									serverObject.getString("address"),
                        			serverObject.getInt("timeout"),
                        			0));
            			}
            		}
            	}
            }
            
            save();
            return super.configure(req, parameters);
        }
        
        public List<ServerBean> getServers() {
        	return this.servers;
        }

        public OperationEnum[] getOperations() {
        	return OperationEnum.all;
        }
        
        protected ServerBean findServer(String serverProfileName) {
        	for (ServerBean server : this.servers) {
        		if (serverProfileName.equals(server.getServerName())) {
        			return server;
        		}
        	}
        	return null;
        }
        
    }
    
    public static class ServerBean {
    	private final String cmdToStart;
		private final String cmdToShutdown;
    	private final String address;
    	private final String serverName;
    	private final String homeDir;
    	private final int jndiPort;
    	private final int timeout;
    	private final int kind;
    	
    	/**
    	 * Constructor for ServerBean in remote case
    	 * @param cmdToStart
    	 * @param cmdToShutdown
    	 * @param address
    	 * @param serverName
    	 * @param jndiPort
    	 * @param timeout
    	 * @param kind
    	 */
		public ServerBean(final String cmdToStart,
						final String cmdToShutdown,
						final String address,
						final String serverName,
						final int jndiPort, final int timeout, final int kind) {
    		this.cmdToStart = cmdToStart;
    		this.cmdToShutdown = cmdToShutdown;
    		this.address = address;
    		this.serverName = serverName;
    		this.jndiPort =jndiPort;
    		this.timeout = timeout;
    		this.kind = kind;
    		//empty initialization
    		this.homeDir = "";
    	}
		
		/**
		 * Constructor for ServerBean in local case
		 * @param serverName
		 * @param homeDir
		 * @param jndiPort
		 * @param address
		 * @param timeout
		 * @param kind
		 */
		public ServerBean(final String serverName,
						final String homeDir,
						final int jndiPort,final String address, final int timeout, final int kind) {
			this.serverName = serverName;
			this.homeDir = homeDir;
			this.jndiPort =jndiPort;
			this.timeout = timeout;
			this.kind = kind;
			if (address == null || address.length() == 0){
				this.address = "127.0.0.1";
			} else {
				this.address = address;
			}
			//empty initialization
			this.cmdToStart = "";
			this.cmdToShutdown = "";
		}
		
    	public String getCmdToStart() {
			return cmdToStart;
		}

		public String getCmdToShutdown() {
			return cmdToShutdown;
		}

		public String getAddress() {
			return address;
		}

    	public String getServerName() {
    		return this.serverName;
    	}
    	
    	public String getHomeDir() {
    		return this.homeDir;
    	}
    	
    	public int getJndiPort() {
    		return this.jndiPort;
    	}
    	
    	public int getTimeout() {
    		return this.timeout;
    	}
    	
    	public int getKind() {
			return kind;
		}
    	
    	@Override
    	public String toString() {
    		return new StringBuilder()
    				.append("ServerBean=")
    				.append(address)
    				.append(":")
    				.append(jndiPort)
    				.append(" timeout=")
    				.append(timeout)
    				.append(" kind=")
    				.append(kind).toString();
    	}
    }
}
